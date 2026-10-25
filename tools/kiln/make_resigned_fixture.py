#!/usr/bin/env python3
"""Kiln test fixture generator (not shipped, not part of :kiln).

Re-signs an APK with a fresh, throwaway ECDSA P-256 key using APK Signature Scheme v2 (written from the public spec,
source.android.com/docs/security/features/apksigning/v2). The private key only lives in memory and is discarded, so the
output is a validly signed APK whose signer is NOT the pinned one: the "wrong signer" case of ApkSignatureTest.
Any existing v2/v3 signing block is replaced; the ZIP entries (and any v1 JAR files) are kept byte for byte.

usage: make_resigned_fixture.py <in.apk> <out.apk>        (needs the 'cryptography' package, Apache-2.0/BSD)
"""
import datetime, hashlib, struct, sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

CHUNK = 1 << 20

def lp(b):
    return struct.pack('<I', len(b)) + b

def split(data):
    eocd = data.rfind(b'PK\x05\x06')
    cd = struct.unpack_from('<I', data, eocd + 16)[0]
    start = cd
    if data[cd - 16:cd] == b'APK Sig Block 42':
        size = struct.unpack_from('<Q', data, cd - 24)[0]
        start = cd - size - 8
    return data[:start], data[cd:eocd], bytearray(data[eocd:])

def content_digest(entries, cdir, eocd, sig_start):
    e = bytearray(eocd)
    struct.pack_into('<I', e, 16, sig_start)
    chunks = []
    for sec in (entries, cdir, bytes(e)):
        for i in range(0, len(sec), CHUNK):
            c = sec[i:i + CHUNK]
            chunks.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(c)) + c).digest())
    return hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()

def main(src, dst):
    entries, cdir, eocd = split(open(src, 'rb').read())
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Kiln wrong-signer test fixture (throwaway key)')])
    now = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1).not_valid_before(now).not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256()))
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    ALGO = 0x0201  # ECDSA with SHA2-256
    digest = content_digest(entries, cdir, eocd, len(entries))
    signed = lp(lp(struct.pack('<I', ALGO) + lp(digest))) + lp(lp(cert_der)) + lp(b'')
    sig = key.sign(signed, ec.ECDSA(hashes.SHA256()))
    signer = lp(signed) + lp(lp(struct.pack('<I', ALGO) + lp(sig))) + lp(pub)
    v2 = lp(lp(signer))
    pair = struct.pack('<Q', 4 + len(v2)) + struct.pack('<I', 0x7109871a) + v2
    size = len(pair) + 8 + 16
    block = struct.pack('<Q', size) + pair + struct.pack('<Q', size) + b'APK Sig Block 42'
    struct.pack_into('<I', eocd, 16, len(entries) + len(block))
    open(dst, 'wb').write(entries + block + cdir + bytes(eocd))
    print('signer sha256', hashlib.sha256(cert_der).hexdigest())

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
