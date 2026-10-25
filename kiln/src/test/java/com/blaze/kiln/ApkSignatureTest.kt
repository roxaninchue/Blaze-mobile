package com.blaze.kiln

import com.blaze.kiln.apk.ApkInspector
import com.blaze.kiln.apk.ApkSignatureVerifier
import com.blaze.kiln.apk.DeviceProfile
import com.blaze.kiln.apk.GuestPin
import com.blaze.kiln.apk.GuestTrust
import com.blaze.kiln.apk.SigVerdict
import com.blaze.kiln.apk.TrustedGuests
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pinned signer + integrity check (ApkSignature.kt) against the real F-Droid guest bundled in kilnTest
 * (kiln-guests/fdroid, path passed by kiln/build.gradle.kts), a copy re-signed with a throwaway key
 * (tools/kiln/make_resigned_fixture.py), tampered and unsigned copies made here, and the project's own test guest.
 */
class ApkSignatureTest {
    private val pkg = "io.github.buildsbyben.shoppinglistcalc"
    private val signer = "01d815e4f55f81f61ace0b29164d957fefc5293cf862c1637f2f215c751cedd3"
    private val fileHash = "d5a1e434bd54270dadee55217758568c05c3f89f93693d9a9c2e8a065b9690ee"

    private val fdroid: File by lazy {
        val p = System.getProperty("kiln.fdroidGuestApk") ?: error("kiln.fdroidGuestApk not set (kiln/build.gradle.kts)")
        File(p).also { check(it.isFile) { "F-Droid guest APK missing: $p" } }
    }
    private val wrongSigner: File by lazy {
        File(javaClass.classLoader.getResource("fixtures/shoppinglistcalc-wrong-signer.apk")!!.toURI())
    }
    private val testGuest: File? by lazy { System.getProperty("kiln.testGuestApk")?.let(::File)?.takeIf { it.isFile } }

    private fun tmp(name: String, bytes: ByteArray): File =
        Files.createTempDirectory("kiln-sig").toFile().let { File(it, name) }.also { it.writeBytes(bytes) }

    /** Offset of the APK Signing Block and of the central directory, read the same way the spec describes. */
    private fun offsets(b: ByteArray): Pair<Int, Int> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = b.size - 22
        while (bb.getInt(eocd) != 0x06054b50) eocd--
        val cd = bb.getInt(eocd + 16)
        val size = bb.getLong(cd - 24)
        return (cd - size - 8).toInt() to cd
    }

    @Test
    fun pinnedFdroidGuestIsAccepted() {
        val r = GuestTrust.check(fdroid, pkg, 34)
        assertEquals(r.verdict, SigVerdict.OK, r.describe())
        assertEquals(r.scheme, 2)
        assertEquals(r.signerSha256, signer)
        assertEquals(r.apkSha256, fileHash)
        // Same result on the oldest Android Kiln supports and on Android 15.
        assertTrue(GuestTrust.check(fdroid, pkg, 26).ok)
        assertTrue(GuestTrust.check(fdroid, pkg, 35).ok)
    }

    @Test
    fun pinsMatchTheBundledGuest() {
        val pin = TrustedGuests.pinFor(pkg)
        assertNotNull(pin)
        assertEquals(pin!!.signerSha256, setOf(signer))
        assertEquals(pin.apkSha256, setOf(fileHash))
        val m = ApkInspector.inspect(fdroid, DeviceProfile(34, listOf("x86_64"))).manifest!!
        assertEquals(m.packageName, pkg)
        assertEquals(m.launcherActivity, "$pkg.MainActivity")
    }

    @Test
    fun wrongSignerIsRejected() {
        // Valid v2 signature over identical content, but by a different key.
        val sig = ApkSignatureVerifier.verify(wrongSigner, 34)
        assertEquals(sig.verdict, SigVerdict.OK, sig.describe())
        val r = GuestTrust.check(wrongSigner, pkg, 34)
        assertEquals(r.verdict, SigVerdict.WRONG_SIGNER, r.describe())
        assertFalse(r.signerSha256 == signer)
        // Even with the APK-hash pin removed, the signer pin alone rejects it.
        val signerOnly = listOf(GuestPin(pkg, setOf(signer)))
        assertEquals(GuestTrust.check(wrongSigner, pkg, 34, signerOnly).verdict, SigVerdict.WRONG_SIGNER)
    }

    @Test
    fun tamperedContentIsRejected() {
        val b = fdroid.readBytes()
        val (sigStart, _) = offsets(b)
        b[sigStart / 2] = (b[sigStart / 2].toInt() xor 0x01).toByte() // one bit inside the ZIP entries
        val r = GuestTrust.check(tmp("tampered.apk", b), pkg, 34)
        assertEquals(r.verdict, SigVerdict.DIGEST_MISMATCH, r.describe())
    }

    @Test
    fun tamperedCentralDirectoryIsRejected() {
        val b = fdroid.readBytes()
        val (_, cd) = offsets(b)
        b[cd + 30] = (b[cd + 30].toInt() xor 0x01).toByte()
        assertEquals(GuestTrust.check(tmp("tampered-cd.apk", b), pkg, 34).verdict, SigVerdict.DIGEST_MISMATCH)
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val b = fdroid.readBytes()
        val (sigStart, cd) = offsets(b)
        // Flip a bit near the end of the v2 signer (signature bytes / public key) inside the block.
        val pos = sigStart + (cd - sigStart) / 2
        b[pos] = (b[pos].toInt() xor 0x10).toByte()
        val r = GuestTrust.check(tmp("tampered-sig.apk", b), pkg, 34)
        assertFalse(r.ok, r.describe())
    }

    @Test
    fun wrongApkHashIsRejected() {
        val pins = listOf(GuestPin(pkg, setOf(signer), setOf("00".repeat(32))))
        assertEquals(GuestTrust.check(fdroid, pkg, 34, pins).verdict, SigVerdict.WRONG_APK_HASH)
    }

    @Test
    fun unsignedCopyIsRejected() {
        // Same ZIP with the APK Signing Block cut out (only the v1 JAR files remain).
        val b = fdroid.readBytes()
        val (sigStart, cd) = offsets(b)
        val out = b.copyOfRange(0, sigStart) + b.copyOfRange(cd, b.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = out.size - 22
        while (bb.getInt(eocd) != 0x06054b50) eocd--
        bb.putInt(eocd + 16, sigStart)
        val f = tmp("unsigned.apk", out)
        java.util.zip.ZipFile(f).use { assertNotNull(it.getEntry("AndroidManifest.xml")) }
        assertEquals(GuestTrust.check(f, pkg, 34).verdict, SigVerdict.UNSIGNED)
    }

    @Test
    fun plainZipIsRejected() {
        val f = Files.createTempFile("kiln-plain", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(8)); z.closeEntry() }
        assertEquals(GuestTrust.check(f, pkg, 34).verdict, SigVerdict.UNSIGNED)
        assertEquals(GuestTrust.check(tmp("junk.apk", ByteArray(100) { it.toByte() }), pkg, 34).verdict, SigVerdict.MALFORMED)
    }

    @Test
    fun unknownPackageIsNotPinned() {
        assertEquals(GuestTrust.check(fdroid, "com.example.other", 34).verdict, SigVerdict.NOT_PINNED)
    }

    @Test
    fun v3SignedApkUsesV3OnApi24PlusAndV2Below() {
        // Bouncy 0.0.1 from F-Droid (Apache-2.0): v2 + v3 blocks, v3 signer for SDK 24+ (fixtures/README.md).
        val f = File(javaClass.classLoader.getResource("fixtures/bouncy-v2v3.apk")!!.toURI())
        val bouncy = "4e015e12bc0e1ae6806edf57befab90e2dcca477dd909665c87d4f29d9be29b0"
        val v3 = ApkSignatureVerifier.verify(f, 34)
        assertEquals(v3.verdict, SigVerdict.OK, v3.describe())
        assertEquals(v3.scheme, 3)
        assertEquals(v3.signerSha256, bouncy)
        val v2 = ApkSignatureVerifier.verify(f, 23)
        assertEquals(v2.scheme, 2)
        assertEquals(v2.signerSha256, bouncy)
        // Not on Kiln's list, and never accepted under another app's pin.
        assertEquals(GuestTrust.check(f, "io.github.ebraminio.bouncy", 34).verdict, SigVerdict.NOT_PINNED)
        assertEquals(GuestTrust.check(f, "io.github.ebraminio.bouncy", 34, listOf(GuestPin("io.github.ebraminio.bouncy", setOf(signer)))).verdict,
            SigVerdict.WRONG_SIGNER)
    }

    @Test
    fun agpSignedTestGuestVerifiesButIsNotTrusted() {
        val g = testGuest ?: return // built by :kiln-testguest:assembleDebug in the same run (kiln/build.gradle.kts)
        val sig = ApkSignatureVerifier.verify(g, 34)
        assertEquals(sig.verdict, SigVerdict.OK, sig.describe())
        assertEquals(GuestTrust.check(g, "com.blaze.kiln.testguest", 34).verdict, SigVerdict.NOT_PINNED)
        // Pinned to the F-Droid signer it is a wrong signer.
        val pins = listOf(GuestPin("com.blaze.kiln.testguest", setOf(signer)))
        assertEquals(GuestTrust.check(g, "com.blaze.kiln.testguest", 34, pins).verdict, SigVerdict.WRONG_SIGNER)
    }
}
