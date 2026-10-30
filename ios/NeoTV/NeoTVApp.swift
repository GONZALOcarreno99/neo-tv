import SwiftUI

@main
struct NeoTVApp: App {
    var body: some Scene {
        WindowGroup { OptimizerView() }
    }
}

/// Una app de optimizar que el script optimizar.sh puede desactivar.
struct TvApp: Identifiable {
    let id: String          // paquete
    let what: String
    let recommended: Bool
    let state: String       // activa · desactivada · desactivada-por-neo
    var selected: Bool
}

@MainActor
final class Optimizer: ObservableObject {
    @AppStorage("host") var host = ""
    @Published var apps: [TvApp] = []
    @Published var status = "Escribe la IP de tu TV"
    @Published var busy = false
    private var adb: AdbClient?

    private let remoteScript = "/data/local/tmp/neo-optimizar.sh"

    func connect() async {
        await run("Conectando…") {
            let client = AdbClient(host: self.host, key: try AdbKey())
            try await client.connect {
                Task { @MainActor in self.status = "En la TV elige «Permitir siempre»" }
            }
            self.adb = client
            try await self.pushScript()
            try await self.refresh()
        }
    }

    func apply() async {
        let pkgs = apps.filter { $0.selected && $0.state == "activa" }.map(\.id)
        await run("Optimizando…") {
            let out = try await self.script("aplicar " + (pkgs.isEmpty ? "ninguna" : pkgs.joined(separator: " ")))
            let ok = out.filter { $0.first == "OK" }.count
            let cache = out.first { $0.first == "CACHE" }.map { " · caché +\($0[1]) MB" } ?? ""
            try await self.refresh()
            self.status = "Listo: \(ok) apps desactivadas\(cache)"
        }
    }

    func revert() async {
        await run("Deshaciendo…") {
            let ok = try await self.script("revertir").filter { $0.first == "OK" }.count
            try await self.refresh()
            self.status = "Listo: reactivé \(ok) apps"
        }
    }

    private func refresh() async throws {
        apps = try await script("lista").compactMap { p in
            guard p.count >= 5 else { return nil }
            return TvApp(id: p[0], what: p[4], recommended: p[2] == "1", state: p[3], selected: p[3] == "activa" && p[2] == "1")
        }
        let ram = try await script("estado").first { $0.first == "RAM" }?[1] ?? "?"
        status = "Conectado · \(ram) MB de RAM libres"
    }

    /// Sube optimizar.sh (va dentro de la app) a la TV, en base64 por partes.
    private func pushScript() async throws {
        guard let url = Bundle.main.url(forResource: "optimizar", withExtension: "sh"),
              let b64 = try? Data(contentsOf: url).base64EncodedString() else { return }
        _ = try await adb!.shell("rm -f \(remoteScript).b64")
        var i = b64.startIndex
        while i < b64.endIndex {
            let j = b64.index(i, offsetBy: 3000, limitedBy: b64.endIndex) ?? b64.endIndex
            _ = try await adb!.shell("echo -n '\(b64[i..<j])' >> \(remoteScript).b64")
            i = j
        }
        _ = try await adb!.shell("base64 -d \(remoteScript).b64 > \(remoteScript) && rm \(remoteScript).b64")
    }

    private func script(_ args: String) async throws -> [[String]] {
        let out = try await adb!.shell("sh \(remoteScript) \(args) 2>/dev/null", timeout: 90)
        return out.split(separator: "\n").filter { $0.contains("|") }
            .map { $0.trimmingCharacters(in: .whitespaces).components(separatedBy: "|") }
    }

    private func run(_ label: String, _ work: @escaping () async throws -> Void) async {
        busy = true; status = label
        do { try await work() } catch { status = error.localizedDescription }
        busy = false
    }
}

struct OptimizerView: View {
    @StateObject private var opt = Optimizer()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("IP de la TV (ej. 192.168.1.50)", text: $opt.host)
                        .keyboardType(.decimalPad)
                    Button("Conectar") { Task { await opt.connect() } }
                        .disabled(opt.host.isEmpty || opt.busy)
                } footer: { Text(opt.status) }

                if !opt.apps.isEmpty {
                    Section("Apps que tu TV no necesita") {
                        ForEach($opt.apps) { $app in
                            Toggle(isOn: $app.selected) {
                                VStack(alignment: .leading) {
                                    Text(app.what)
                                    Text(app.state == "activa" ? (app.recommended ? "Recomendado" : "Opcional") : "Ya desactivada")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .disabled(app.state != "activa")
                        }
                    }
                    Section {
                        Button("Optimizar") { Task { await opt.apply() } }.disabled(opt.busy)
                        Button("Deshacer todo", role: .destructive) { Task { await opt.revert() } }.disabled(opt.busy)
                    }
                }
            }
            .navigationTitle("NEO TV")
        }
    }
}
