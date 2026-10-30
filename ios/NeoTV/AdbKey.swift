import Foundation
import Security

/// Llave RSA de este iPhone para ADB. Se crea una vez y queda en el llavero.
/// La TV la guarda cuando eliges «Permitir siempre».
final class AdbKey {
    private static let tag = "tv.neo.adbkey".data(using: .utf8)!
    let privateKey: SecKey

    init() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: AdbKey.tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let k = item {
            privateKey = k as! SecKey
            return
        }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: AdbKey.tag,
            ],
        ]
        var err: Unmanaged<CFError>?
        guard let k = SecKeyCreateRandomKey(attrs as CFDictionary, &err) else { throw err!.takeRetainedValue() }
        privateKey = k
    }

    /// Firma el token de 20 bytes que manda la TV (ADB lo trata como un hash SHA-1 ya calculado).
    func sign(token: Data) throws -> Data {
        var err: Unmanaged<CFError>?
        guard let sig = SecKeyCreateSignature(privateKey, .rsaSignatureDigestPKCS1v15SHA1, token as CFData, &err) else {
            throw err!.takeRetainedValue()
        }
        return sig as Data
    }

    /// Llave pública en el formato propio de Android (el mismo de ~/.android/adbkey.pub) + nombre.
    func androidPublicKey(name: String) throws -> Data {
        guard let pub = SecKeyCopyPublicKey(privateKey),
              let der = SecKeyCopyExternalRepresentation(pub, nil) as Data? else { throw AdbError.key }
        let (modulus, exponent) = try AdbKey.parseRSAPublicKey(der)
        let words = 64 // 2048 bits
        // n como 64 palabras de 32 bits, la menos significativa primero
        var n = [UInt32](repeating: 0, count: words)
        let bytes = [UInt8](modulus.suffix(256))
        for i in 0..<words {
            let o = bytes.count - 4 * (i + 1)
            n[i] = UInt32(bytes[o]) << 24 | UInt32(bytes[o + 1]) << 16 | UInt32(bytes[o + 2]) << 8 | UInt32(bytes[o + 3])
        }
        // n0inv = -1 / n[0] mod 2^32 (Newton)
        var inv: UInt32 = 1
        for _ in 0..<5 { inv = inv &* (2 &- n[0] &* inv) }
        let n0inv = 0 &- inv
        // rr = 2^4096 mod n, duplicando 4096 veces
        var r = [UInt32](repeating: 0, count: words + 1)
        r[0] = 1
        for _ in 0..<(2 * 2048) {
            var carry: UInt32 = 0
            for i in 0...words { let v = r[i]; r[i] = v << 1 | carry; carry = v >> 31 }
            if AdbKey.geq(r, n) { AdbKey.sub(&r, n) }
        }
        var out = Data()
        func put(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        put(UInt32(words)); put(n0inv)
        n.forEach(put)
        r.prefix(words).forEach(put)
        put(exponent)
        var line = out.base64EncodedData()
        line.append(contentsOf: Array(" \(name)\0".utf8))
        return line
    }

    private static func geq(_ a: [UInt32], _ n: [UInt32]) -> Bool {
        if a[n.count] != 0 { return true }
        for i in stride(from: n.count - 1, through: 0, by: -1) where a[i] != n[i] { return a[i] > n[i] }
        return true
    }

    private static func sub(_ a: inout [UInt32], _ n: [UInt32]) {
        var borrow: Int64 = 0
        for i in 0..<a.count {
            let d = Int64(a[i]) - Int64(i < n.count ? n[i] : 0) - borrow
            a[i] = UInt32(truncatingIfNeeded: d)
            borrow = d < 0 ? 1 : 0
        }
    }

    /// DER de RSAPublicKey: SEQUENCE { INTEGER n, INTEGER e }.
    private static func parseRSAPublicKey(_ der: Data) throws -> (Data, UInt32) {
        var p = [UInt8](der)[...]
        func length() throws -> Int {
            guard let b = p.popFirst() else { throw AdbError.key }
            if b < 0x80 { return Int(b) }
            var l = 0
            for _ in 0..<Int(b & 0x7F) { guard let x = p.popFirst() else { throw AdbError.key }; l = l << 8 | Int(x) }
            return l
        }
        func integer() throws -> [UInt8] {
            guard p.popFirst() == 0x02 else { throw AdbError.key }
            let l = try length()
            let v = Array(p.prefix(l)); p = p.dropFirst(l)
            return Array(v.drop(while: { $0 == 0 }))
        }
        guard p.popFirst() == 0x30 else { throw AdbError.key }
        _ = try length()
        let n = try integer(), e = try integer()
        return (Data(n), e.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }
}
