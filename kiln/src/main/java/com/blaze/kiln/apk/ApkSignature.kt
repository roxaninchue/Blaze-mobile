package com.blaze.kiln.apk

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

// Guest APK integrity + signer check (CLEANROOM_RULES.md "Trust and safety"). Written from the public APK Signature
// Scheme v2 / v3 specification (source.android.com/docs/security/features/apksigning/v2 and /v3) and the ZIP format
// (PKWARE APPNOTE). Pure JVM (java.security only), so the same code runs in unit tests and on the device.
//
// What it proves before Kiln installs a guest:
//  1. the APK carries a v2/v3/v3.1 signature block, the signer's signature over its signed data verifies with the
//     signer's public key, and that key is the key of the signer's first certificate;
//  2. the APK's content digest (1 MiB chunked digest of ZIP entries, central directory and end-of-central-directory)
//     equals the digest the signer signed, so no byte of the APK changed after signing;
//  3. the signer certificate's SHA-256 is on Kiln's pinned allowlist for that package ([TrustedGuests]);
//  4. when the pin lists APK file hashes (from the source index, e.g. F-Droid's index-v2), the file's SHA-256 is one.

enum class SigVerdict(val why: String) {
    OK("signature verifies and the content digest matches"),
    UNSIGNED("no APK Signature Scheme v2/v3 block (unsigned or v1-only)"),
    MALFORMED("APK signing block or ZIP structure is malformed"),
    UNSUPPORTED("no signature algorithm Kiln supports"),
    MULTIPLE_SIGNERS("more than one signer (Kiln pins exactly one)"),
    BAD_SIGNATURE("signer's signature does not verify"),
    DIGEST_MISMATCH("APK content does not match the signed digest (tampered)"),
    STRIPPED("v2 block says a v3 signature was stripped"),
    NOT_PINNED("package is not on Kiln's trusted guest list"),
    WRONG_SIGNER("signer certificate is not the pinned one"),
    WRONG_APK_HASH("APK SHA-256 is not the one the source index lists"),
}

data class SignerCheck(
    val verdict: SigVerdict,
    /** 2 = v2, 3 = v3, 31 = v3.1; null when no block was verified. */
    val scheme: Int?,
    /** Lowercase hex SHA-256 of the signer's certificate (DER), when one was read. */
    val signerSha256: String?,
    /** Lowercase hex SHA-256 of the whole APK file. */
    val apkSha256: String,
    val detail: String = "",
) {
    val ok: Boolean get() = verdict == SigVerdict.OK
    fun describe(): String = (if (ok) "OK" else "REJECTED ${verdict.name}") + ": " + verdict.why +
        (scheme?.let { "; scheme v" + (if (it == 31) "3.1" else it.toString()) } ?: "") +
        (signerSha256?.let { "; signer ${it.take(16)}" } ?: "") + "; apk ${apkSha256.take(16)}" +
        (if (detail.isNotEmpty()) "; $detail" else "")
}

/** One trusted guest: its package, the signer certificate SHA-256 values allowed, and (optionally) exact APK hashes. */
data class GuestPin(
    val packageName: String,
    val signerSha256: Set<String>,
    /** Empty = any APK by the pinned signer; otherwise the file SHA-256 must be one of these. */
    val apkSha256: Set<String> = emptySet(),
    val source: String = "",
)

/** Kiln's pinned allowlist (GUEST_APPS.md records where each value came from). */
object TrustedGuests {
    val PINS: List<GuestPin> = listOf(
        // Shopping List Calculator 2.3 (versionCode 21), MIT, https://github.com/buildsbyben/shopping-list-calc.
        // Signer and APK hash from F-Droid's index-v2 (built and signed by the developer, reproducible), re-checked
        // with work/tools/apksig_certs.py on the APK downloaded from f-droid.org.
        GuestPin(
            packageName = "io.github.buildsbyben.shoppinglistcalc",
            signerSha256 = setOf("01d815e4f55f81f61ace0b29164d957fefc5293cf862c1637f2f215c751cedd3"),
            apkSha256 = setOf("d5a1e434bd54270dadee55217758568c05c3f89f93693d9a9c2e8a065b9690ee"),
            source = "https://f-droid.org/repo/io.github.buildsbyben.shoppinglistcalc_21.apk",
        ),
    )

    fun pinFor(pkg: String, pins: List<GuestPin> = PINS): GuestPin? = pins.firstOrNull { it.packageName == pkg }
}

object GuestTrust {
    /** Full pre-install check of [apk], which claims to be [packageName] (read from its manifest). */
    fun check(apk: File, packageName: String, sdkInt: Int, pins: List<GuestPin> = TrustedGuests.PINS): SignerCheck {
        val fileHash = ApkSignatureVerifier.sha256Hex(apk)
        val sig = ApkSignatureVerifier.verify(apk, sdkInt, fileHash)
        if (!sig.ok) return sig
        val pin = TrustedGuests.pinFor(packageName, pins)
            ?: return sig.copy(verdict = SigVerdict.NOT_PINNED, detail = packageName)
        if (sig.signerSha256 !in pin.signerSha256) return sig.copy(verdict = SigVerdict.WRONG_SIGNER)
        if (pin.apkSha256.isNotEmpty() && fileHash !in pin.apkSha256) return sig.copy(verdict = SigVerdict.WRONG_APK_HASH)
        return sig.copy(detail = "signer pinned" + if (pin.apkSha256.isNotEmpty()) ", APK hash pinned" else "")
    }
}

object ApkSignatureVerifier {
    const val ID_V2 = 0x7109871a
    val ID_V3 = 0xf05368c0L.toInt()
    const val ID_V31 = 0x1b93ad61
    private val ATTR_STRIPPING_PROTECTION = 0xbeeff00dL.toInt()
    private const val CHUNK = 1 shl 20
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** Signature algorithm IDs from the spec that use the chunked content digest. */
    private enum class Algo(val id: Int, val digest: String, val keyAlg: String, val jca: List<String>, val pss: PSSParameterSpec?, val strength: Int) {
        RSA_PSS_SHA256(0x0101, "SHA-256", "RSA", listOf("SHA256withRSA/PSS", "RSASSA-PSS"), PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1), 1),
        RSA_PSS_SHA512(0x0102, "SHA-512", "RSA", listOf("SHA512withRSA/PSS", "RSASSA-PSS"), PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1), 2),
        RSA_PKCS1_SHA256(0x0103, "SHA-256", "RSA", listOf("SHA256withRSA"), null, 1),
        RSA_PKCS1_SHA512(0x0104, "SHA-512", "RSA", listOf("SHA512withRSA"), null, 2),
        ECDSA_SHA256(0x0201, "SHA-256", "EC", listOf("SHA256withECDSA"), null, 1),
        ECDSA_SHA512(0x0202, "SHA-512", "EC", listOf("SHA512withECDSA"), null, 2),
        DSA_SHA256(0x0301, "SHA-256", "DSA", listOf("SHA256withDSA"), null, 1);

        companion object { fun of(id: Int): Algo? = entries.firstOrNull { it.id == id } }
    }

    private class Malformed(msg: String) : Exception(msg)

    /** ZIP layout pieces the digest needs. */
    private class Layout(val sigBlockStart: Long, val cdOffset: Long, val eocdOffset: Long, val eocd: ByteArray, val pairs: Map<Int, ByteBuffer>)

    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    /** Verifies the signature block and content digest only (no pin). [fileHash] is reused when already known. */
    fun verify(apk: File, sdkInt: Int, fileHash: String = sha256Hex(apk)): SignerCheck {
        RandomAccessFile(apk, "r").use { raf ->
            val layout = try { layout(raf) } catch (m: Malformed) {
                return SignerCheck(if (m.message == "no signing block") SigVerdict.UNSIGNED else SigVerdict.MALFORMED, null, null, fileHash, m.message ?: "")
            }
            // Newest scheme that covers this Android release wins, as on the platform.
            val v31 = layout.pairs[ID_V31]
            val v3 = layout.pairs[ID_V3]
            val v2 = layout.pairs[ID_V2]
            if (v31 == null && v3 == null && v2 == null) return SignerCheck(SigVerdict.UNSIGNED, null, null, fileHash, "signing block without v2/v3")
            return try {
                for ((scheme, block) in listOf(31 to v31, 3 to v3)) {
                    if (block == null) continue
                    val r = verifyScheme(raf, layout, block.duplicate().order(ByteOrder.LITTLE_ENDIAN), scheme, sdkInt, fileHash)
                    if (r != null) return r
                }
                if (v2 == null) return SignerCheck(SigVerdict.UNSIGNED, null, null, fileHash, "no v3 signer for API $sdkInt and no v2 block")
                verifyScheme(raf, layout, v2.duplicate().order(ByteOrder.LITTLE_ENDIAN), 2, sdkInt, fileHash, v3Present = v3 != null)
                    ?: SignerCheck(SigVerdict.MALFORMED, 2, null, fileHash, "empty v2 block")
            } catch (m: Malformed) {
                SignerCheck(SigVerdict.MALFORMED, null, null, fileHash, m.message ?: "")
            } catch (t: java.nio.BufferUnderflowException) {
                SignerCheck(SigVerdict.MALFORMED, null, null, fileHash, "truncated signing block")
            }
        }
    }

    /** Null when a v3/v3.1 block has no signer for [sdkInt] (the caller falls back to an older scheme). */
    private fun verifyScheme(raf: RandomAccessFile, layout: Layout, block: ByteBuffer, scheme: Int, sdkInt: Int, fileHash: String, v3Present: Boolean = false): SignerCheck? {
        val signers = lengthPrefixed(block)
        val all = ArrayList<ByteBuffer>()
        while (signers.hasRemaining()) all += lengthPrefixed(signers)
        if (all.isEmpty()) throw Malformed("no signers in v$scheme block")
        val chosen = if (scheme == 2) {
            if (all.size != 1) return SignerCheck(SigVerdict.MULTIPLE_SIGNERS, scheme, null, fileHash, "${all.size} signers")
            all.first()
        } else {
            // v3: one signer per SDK range; take the one covering this device.
            all.firstOrNull { s ->
                val d = s.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                lengthPrefixed(d)
                val min = d.int; val max = d.int
                sdkInt in min..max
            } ?: return null
        }
        return verifySigner(raf, layout, chosen, scheme, fileHash, v3Present)
    }

    private fun verifySigner(raf: RandomAccessFile, layout: Layout, signer: ByteBuffer, scheme: Int, fileHash: String, v3Present: Boolean): SignerCheck {
        val signedData = lengthPrefixed(signer)
        val signedBytes = ByteArray(signedData.remaining()).also { signedData.duplicate().get(it) }
        val signerSdk = if (scheme != 2) signer.int to signer.int else null
        val signatures = lengthPrefixed(signer)
        val publicKeyBytes = bytes(lengthPrefixed(signer))

        // Signed data: digests, certificates, [v3: min/max SDK], additional attributes.
        val digests = lengthPrefixed(signedData)
        val certs = lengthPrefixed(signedData)
        val signedSdk = if (scheme != 2) signedData.int to signedData.int else null
        if (signerSdk != signedSdk) throw Malformed("v$scheme signer SDK range differs from its signed data")
        val attrs = lengthPrefixed(signedData)

        val certList = ArrayList<ByteArray>()
        while (certs.hasRemaining()) certList += bytes(lengthPrefixed(certs))
        if (certList.isEmpty()) throw Malformed("signer has no certificate")
        val signerHash = hex(MessageDigest.getInstance("SHA-256").digest(certList.first()))

        fun reject(v: SigVerdict, d: String = "") = SignerCheck(v, scheme, signerHash, fileHash, d)

        if (scheme == 2 && !v3Present) {
            while (attrs.hasRemaining()) {
                val a = lengthPrefixed(attrs)
                if (a.int == ATTR_STRIPPING_PROTECTION && a.remaining() >= 4 && a.int == 3) return reject(SigVerdict.STRIPPED)
            }
        }

        // Strongest supported signature.
        var best: Pair<Algo, ByteArray>? = null
        val sigAlgos = ArrayList<Int>()
        while (signatures.hasRemaining()) {
            val s = lengthPrefixed(signatures)
            val id = s.int
            sigAlgos += id
            val sig = bytes(lengthPrefixed(s))
            val algo = Algo.of(id) ?: continue
            if (best == null || algo.strength > best.first.strength) best = algo to sig
        }
        val (algo, sigBytes) = best ?: return reject(SigVerdict.UNSUPPORTED, "algorithms " + sigAlgos.joinToString { "0x" + Integer.toHexString(it) })

        val publicKey = try {
            KeyFactory.getInstance(algo.keyAlg).generatePublic(X509EncodedKeySpec(publicKeyBytes))
        } catch (t: Exception) { return reject(SigVerdict.MALFORMED, "public key: ${t.javaClass.simpleName}") }
        if (!verifySignature(algo, publicKey, signedBytes, sigBytes)) return reject(SigVerdict.BAD_SIGNATURE, "algorithm 0x${Integer.toHexString(algo.id)}")

        // The signing key must be the first certificate's key.
        val cert = try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certList.first())) as X509Certificate
        } catch (t: Exception) { return reject(SigVerdict.MALFORMED, "certificate: ${t.javaClass.simpleName}") }
        if (!cert.publicKey.encoded.contentEquals(publicKeyBytes)) return reject(SigVerdict.BAD_SIGNATURE, "public key is not the certificate's key")

        // Digest list must name the same algorithms as the signature list.
        val digestByAlgo = LinkedHashMap<Int, ByteArray>()
        while (digests.hasRemaining()) {
            val d = lengthPrefixed(digests)
            val id = d.int
            digestByAlgo[id] = bytes(lengthPrefixed(d))
        }
        if (digestByAlgo.keys.toList().sorted() != sigAlgos.sorted()) return reject(SigVerdict.MALFORMED, "digest and signature algorithm lists differ")
        val expected = digestByAlgo[algo.id] ?: return reject(SigVerdict.MALFORMED, "no digest for the chosen algorithm")
        val actual = contentDigest(raf, layout, algo.digest)
        if (!MessageDigest.isEqual(expected, actual)) return reject(SigVerdict.DIGEST_MISMATCH, "algorithm 0x${Integer.toHexString(algo.id)}")
        return SignerCheck(SigVerdict.OK, scheme, signerHash, fileHash)
    }

    private fun verifySignature(algo: Algo, key: java.security.PublicKey, data: ByteArray, sig: ByteArray): Boolean {
        for (name in algo.jca) {
            val s = try { Signature.getInstance(name) } catch (_: Exception) { continue }
            return try {
                s.initVerify(key)
                if (algo.pss != null && name == "RSASSA-PSS") s.setParameter(algo.pss)
                s.update(data)
                s.verify(sig)
            } catch (_: Exception) { false }
        }
        return false
    }

    /** Chunked content digest over sections 1 (entries), 3 (central directory) and 4 (EOCD with patched CD offset). */
    private fun contentDigest(raf: RandomAccessFile, layout: Layout, alg: String): ByteArray {
        val chunkDigests = java.io.ByteArrayOutputStream()
        var count = 0
        val md = MessageDigest.getInstance(alg)
        val buf = ByteArray(CHUNK)
        fun chunk(bytes: ByteArray, len: Int) {
            md.reset()
            md.update(0xa5.toByte())
            md.update(le32(len))
            md.update(bytes, 0, len)
            chunkDigests.write(md.digest())
            count++
        }
        fun section(start: Long, end: Long) {
            var pos = start
            while (pos < end) {
                val n = minOf(CHUNK.toLong(), end - pos).toInt()
                raf.seek(pos)
                raf.readFully(buf, 0, n)
                chunk(buf, n)
                pos += n
            }
        }
        section(0, layout.sigBlockStart)
        section(layout.cdOffset, layout.eocdOffset)
        val eocd = layout.eocd.copyOf()
        ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN).putInt(16, layout.sigBlockStart.toInt())
        chunk(eocd, eocd.size)
        md.reset()
        md.update(0x5a.toByte())
        md.update(le32(count))
        md.update(chunkDigests.toByteArray())
        return md.digest()
    }

    private fun layout(raf: RandomAccessFile): Layout {
        val len = raf.length()
        if (len < 22) throw Malformed("too small for a ZIP")
        val tailLen = minOf(len, 22L + 0xffff).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen); raf.readFully(tail)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocdRel = -1
        var i = tailLen - 22
        while (i >= 0) {
            if (tb.getInt(i) == 0x06054b50 && (tb.getShort(i + 20).toInt() and 0xffff) == tailLen - 22 - i) { eocdRel = i; break }
            i--
        }
        if (eocdRel < 0) throw Malformed("no end of central directory")
        val eocdOffset = len - tailLen + eocdRel
        val eocd = tail.copyOfRange(eocdRel, tailLen)
        val cdSize = tb.getInt(eocdRel + 12).toLong() and 0xffffffffL
        val cdOffset = tb.getInt(eocdRel + 16).toLong() and 0xffffffffL
        if (cdOffset + cdSize != eocdOffset) throw Malformed("central directory does not end at EOCD")
        if (cdOffset < 32) throw Malformed("no signing block")
        val footer = ByteArray(24)
        raf.seek(cdOffset - 24); raf.readFully(footer)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) throw Malformed("no signing block")
        val size = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (size < 24 || size > Int.MAX_VALUE - 8) throw Malformed("bad signing block size")
        val start = cdOffset - size - 8
        if (start < 0) throw Malformed("signing block before file start")
        val blockBytes = ByteArray((size + 8).toInt())
        raf.seek(start); raf.readFully(blockBytes)
        val bb = ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getLong(0) != size) throw Malformed("signing block sizes differ")
        val pairs = HashMap<Int, ByteBuffer>()
        bb.position(8)
        val pairsEnd = blockBytes.size - 24
        while (bb.position() < pairsEnd) {
            if (pairsEnd - bb.position() < 8) throw Malformed("truncated ID-value pair")
            val n = bb.long
            if (n < 4 || n > pairsEnd - bb.position()) throw Malformed("bad ID-value pair length")
            val id = bb.int
            val v = bb.slice().order(ByteOrder.LITTLE_ENDIAN)
            v.limit((n - 4).toInt())
            if (!pairs.containsKey(id)) pairs[id] = v
            bb.position(bb.position() + (n - 4).toInt())
        }
        return Layout(start, cdOffset, eocdOffset, eocd, pairs)
    }

    private fun lengthPrefixed(b: ByteBuffer): ByteBuffer {
        if (b.remaining() < 4) throw Malformed("truncated length prefix")
        val n = b.int
        if (n < 0 || n > b.remaining()) throw Malformed("length prefix past end")
        val out = b.slice().order(ByteOrder.LITTLE_ENDIAN)
        out.limit(n)
        b.position(b.position() + n)
        return out
    }

    private fun bytes(b: ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.duplicate().get(it) }
    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
