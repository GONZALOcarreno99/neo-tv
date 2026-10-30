import Foundation
import Network

enum AdbError: LocalizedError {
    case key, closed, timeout, rejected
    var errorDescription: String? {
        switch self {
        case .key: return "No se pudo preparar la llave"
        case .closed: return "La TV cerró la conexión"
        case .timeout: return "La TV no respondió"
        case .rejected: return "La TV no aceptó la conexión"
        }
    }
}

/// Cliente mínimo del protocolo ADB por Wi-Fi (puerto 5555): conectar, autenticar y correr comandos de shell.
/// Es el mismo protocolo que habla Adb.java en la app Android.
actor AdbClient {
    private static let CNXN: UInt32 = 0x4E58_4E43, AUTH: UInt32 = 0x4854_5541, OPEN: UInt32 = 0x4E45_504F
    private static let OKAY: UInt32 = 0x5941_4B4F, WRTE: UInt32 = 0x4554_5257, CLSE: UInt32 = 0x4553_4C43

    private let conn: NWConnection
    private let key: AdbKey
    private var nextId: UInt32 = 1
    private var buffer = Data()

    init(host: String, key: AdbKey) {
        conn = NWConnection(host: NWEndpoint.Host(host), port: 5555, using: .tcp)
        self.key = key
    }

    /// Llama a `onApprove` si la TV muestra el aviso «Permitir depuración» (hay que aceptarlo en la TV).
    func connect(onApprove: @Sendable () -> Void) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            var done = false
            conn.stateUpdateHandler = { state in
                guard !done else { return }
                switch state {
                case .ready: done = true; c.resume()
                case .failed(let e), .waiting(let e): done = true; c.resume(throwing: e)
                default: break
                }
            }
            conn.start(queue: .global())
        }
        try await send(Self.CNXN, 0x0100_0001, 256 * 1024, Data("host::\0".utf8))
        var sentKey = false, signed = false
        while true {
            let (cmd, arg0, _, data) = try await receive(timeout: sentKey ? 60 : 10)
            if cmd == Self.CNXN { return }
            guard cmd == Self.AUTH, arg0 == 1 else { continue }
            if !signed {
                signed = true
                try await send(Self.AUTH, 2, 0, try key.sign(token: data))
            } else if !sentKey {
                sentKey = true
                onApprove()
                try await send(Self.AUTH, 3, 0, try key.androidPublicKey(name: "neo@iphone"))
            } else {
                throw AdbError.rejected
            }
        }
    }

    /// Corre un comando en la TV y devuelve lo que imprime.
    func shell(_ command: String, timeout: TimeInterval = 30) async throws -> String {
        let local = nextId; nextId += 1
        try await send(Self.OPEN, local, 0, Data("shell:\(command)\0".utf8))
        var out = Data()
        while true {
            let (cmd, arg0, arg1, data) = try await receive(timeout: timeout)
            guard arg1 == local else { continue }
            switch cmd {
            case Self.WRTE:
                out.append(data)
                try await send(Self.OKAY, local, arg0, Data())
            case Self.CLSE:
                try? await send(Self.CLSE, local, arg0, Data())
                return String(decoding: out, as: UTF8.self)
            default:
                break
            }
        }
    }

    func close() { conn.cancel() }

    // ---------- bajo nivel ----------

    private func send(_ cmd: UInt32, _ a0: UInt32, _ a1: UInt32, _ data: Data) async throws {
        var msg = Data()
        let sum = data.reduce(UInt32(0)) { $0 &+ UInt32($1) }
        for v in [cmd, a0, a1, UInt32(data.count), sum, cmd ^ 0xFFFF_FFFF] {
            withUnsafeBytes(of: v.littleEndian) { msg.append(contentsOf: $0) }
        }
        msg.append(data)
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            conn.send(content: msg, completion: .contentProcessed { e in
                if let e = e { c.resume(throwing: e) } else { c.resume() }
            })
        }
    }

    private func receive(timeout: TimeInterval) async throws -> (UInt32, UInt32, UInt32, Data) {
        let header = try await read(24, timeout: timeout)
        func word(_ i: Int) -> UInt32 {
            header.subdata(in: i * 4 ..< i * 4 + 4).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).littleEndian }
        }
        let body = word(3) > 0 ? try await read(Int(word(3)), timeout: timeout) : Data()
        return (word(0), word(1), word(2), body)
    }

    private func read(_ count: Int, timeout: TimeInterval) async throws -> Data {
        let deadline = Date().addingTimeInterval(timeout)
        while buffer.count < count {
            if Date() > deadline { throw AdbError.timeout }
            let chunk: Data = try await withCheckedThrowingContinuation { c in
                conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
                    if let error = error { c.resume(throwing: error) }
                    else if let data = data, !data.isEmpty { c.resume(returning: data) }
                    else if isComplete { c.resume(throwing: AdbError.closed) }
                    else { c.resume(returning: Data()) }
                }
            }
            buffer.append(chunk)
        }
        let out = buffer.prefix(count)
        buffer.removeFirst(count)
        return Data(out)
    }
}
