package com.gonzalo.neolauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * NEO Remote para celulares sin app (iPhone): una página web servida desde la TV en el puerto 8686.
 * El celular la abre en Safari y la agrega a inicio. Los comandos llegan por WebSocket y se pasan
 * al mismo ayudante de teclas que usa la tablet (KeyServer), que NEO arranca conectándose por ADB
 * a la propia TV. La primera vez, la TV pide «Permitir depuración» para la llave de NEO.
 * Cada celular se empareja una vez con un código de 4 números que aparece en la pantalla.
 */
public class WebRemoteService extends Service {

    static final int PORT = 8686;
    private static final String HELPER = "/data/local/tmp/neokeys.dex";
    private static final String OPTIMIZER = "/data/local/tmp/neo-optimizar.sh";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String TOKEN = "tok:"; // prefs: "tok:<token>" -> último uso
    private static final String ACTION_FORGET = "com.gonzalo.neolauncher.FORGET_PHONES";
    private static final long TOKEN_TTL_MS = 90L * 24 * 3600 * 1000;
    private static final int MAX_PAIR_FAILS = 5, MAX_PHONES = 6;
    /** Ninguna otra página puede meter el control en un marco ni cargar cosas de fuera. */
    private static final String SECURITY_HEADERS = "X-Frame-Options: DENY\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'self' ws:; frame-ancestors 'none'\r\n";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Client> clients = new CopyOnWriteArrayList<Client>();
    private SharedPreferences prefs;
    private ServerSocket server;
    private volatile boolean running = true;
    private volatile Adb adb;
    private volatile Adb.Stream keys;
    private volatile boolean pointerReady;
    private volatile String status = "";
    private String pairCode;
    private long pairUntil;
    private int pairFails;
    private volatile NetWatch net;
    private android.view.View codeView;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, WebRemoteService.class));
        } catch (Exception ignored) { /* se intenta en el próximo inicio */ }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("web_remote", MODE_PRIVATE);
        prefs.edit().remove("tokens").apply(); // lista vieja sin vencimiento (antes del 2026-10-29)
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("web", "Control desde el celular", NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, "web")
                .setContentTitle("NEO Remote web")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build());
        net = new NetWatch(this, this::execOrThrow, d -> ui.post(() -> showNewDevice(d)));
        new Thread(this::serve, "web-server").start();
        new Thread(this::statusLoop, "web-status").start();
        new Thread(this::netLoop, "net-watch").start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_FORGET.equals(i.getAction())) {
            for (Client c : clients) {
                try { c.sock.close(); } catch (IOException ignored) { /* ya cerrado */ }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { /* ya cerrado */ }
        Adb a = adb;
        if (a != null) a.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    // ---------- HTTP ----------

    private void serve() {
        try {
            server = new ServerSocket(PORT);
            // hilos limitados: muchas conexiones a la vez no pueden tumbar NEO
            ExecutorService pool = Executors.newFixedThreadPool(16);
            while (running) {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            android.util.Log.w("NeoWeb", "servidor detenido", e);
        }
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String first = readLine(in);
            if (first == null) { s.close(); return; }
            Map<String, String> headers = new HashMap<String, String>();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int c = l.indexOf(':');
                if (c > 0) headers.put(l.substring(0, c).trim().toLowerCase(), l.substring(c + 1).trim());
            }
            String[] parts = first.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> args = parseQuery(query);

            if (path.equals("/ws") && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", "sin emparejar".getBytes()); s.close(); return; }
                if (clients.size() >= MAX_PHONES) { reply(out, 503, "text/plain", "demasiados celulares".getBytes()); s.close(); return; }
                upgrade(s, in, out, headers.get("sec-websocket-key"));
                return;
            }
            switch (path) {
                case "/": asset(out, "web_index.html", "text/html; charset=utf-8"); break;
                case "/manifest.json": asset(out, "web_manifest.json", "application/manifest+json"); break;
                case "/icon.png": asset(out, "web_icon.png", "image/png"); break;
                case "/pair/start": startPairing(); reply(out, 200, "application/json", "{\"ok\":true}".getBytes()); break;
                case "/pair": pair(out, args.get("code")); break;
                case "/api":
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain; charset=utf-8", "NEO no reconoce este atajo. Vuelve a copiar el enlace desde NEO Remote.".getBytes(StandardCharsets.UTF_8)); break; }
                    String said;
                    try { said = api(args); } catch (Exception e) { dropAdb(); said = "La TV no respondió, prueba otra vez"; }
                    reply(out, 200, "text/plain; charset=utf-8", said.getBytes(StandardCharsets.UTF_8));
                    break;
                case "/siri/new":
                    // un token aparte para los atajos: si el iPhone se vuelve a emparejar, los atajos siguen sirviendo
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", new byte[0]); break; }
                    reply(out, 200, "application/json", ("{\"token\":\"" + newToken() + "\"}").getBytes(StandardCharsets.UTF_8));
                    break;
                case "/net":
                case "/net/scan":
                case "/net/name":
                case "/net/forget": {
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", new byte[0]); break; }
                    if (path.equals("/net/scan")) net.scan();
                    if (path.equals("/net/name")) net.name(args.get("mac"), args.get("name"));
                    if (path.equals("/net/forget")) net.forget(args.get("mac"));
                    reply(out, 200, "application/json; charset=utf-8", net.json().toString().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/opt": {
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", new byte[0]); break; }
                    String act = args.get("do"), pkgs = args.get("pkgs") == null ? "" : args.get("pkgs");
                    // lo que llega va a la consola de la TV: solo acciones conocidas y nombres de paquete
                    if (act == null || !act.matches("lista|estado|aplicar|revertir|cache") || !pkgs.matches("[A-Za-z0-9_.,]*")) {
                        reply(out, 400, "text/plain", new byte[0]);
                        break;
                    }
                    try {
                        String o = optimizer(act + " " + pkgs.replace(',', ' '));
                        reply(out, 200, "text/plain; charset=utf-8", o.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        dropAdb();
                        reply(out, 503, "text/plain", new byte[0]);
                    }
                    break;
                }
                case "/apps":
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", new byte[0]); break; }
                    reply(out, 200, "application/json; charset=utf-8", appsJson().getBytes(StandardCharsets.UTF_8));
                    break;
                case "/banner":
                    if (!validToken(args.get("t"))) { reply(out, 403, "text/plain", new byte[0]); break; }
                    banner(out, args.get("f"));
                    break;
                default: reply(out, 404, "text/plain", "no existe".getBytes());
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { /* ya cerrado */ }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("línea muy larga");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try { m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); } catch (Exception ignored) { /* mal formado */ }
        }
        return m;
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + SECURITY_HEADERS
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void asset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            reply(out, 200, type, readAll(in));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ---------- emparejar ----------

    /**
     * Muestra en la TV un código de 6 números por un minuto. Si ya hay uno vigente se vuelve a
     * mostrar el mismo: pedir códigos nuevos sin parar no sirve para adivinarlo.
     */
    private void startPairing() {
        final String code;
        synchronized (this) {
            if (pairCode == null || System.currentTimeMillis() >= pairUntil) {
                pairCode = String.format(java.util.Locale.US, "%06d", random.nextInt(1000000));
                pairUntil = System.currentTimeMillis() + 60000;
                pairFails = 0;
            }
            code = pairCode;
        }
        ui.post(() -> showCode(code));
        ui.removeCallbacks(hideCode);
        ui.postDelayed(hideCode, Math.max(1000, pairUntil - System.currentTimeMillis()));
    }

    private void pair(OutputStream out, String code) throws IOException {
        String token = null;
        boolean burned = false;
        synchronized (this) {
            if (pairCode != null && System.currentTimeMillis() < pairUntil) {
                if (pairCode.equals(code)) {
                    token = newToken();
                    pairCode = null;
                } else if (++pairFails >= MAX_PAIR_FAILS) {
                    // 5 intentos fallidos: el código deja de servir y hay que pedir otro
                    android.util.Log.w("NeoWeb", "código anulado tras " + pairFails + " intentos fallidos");
                    pairCode = null;
                    burned = true;
                }
            }
        }
        if (token != null || burned) ui.post(hideCode);
        reply(out, token != null ? 200 : 403, "application/json",
                (token != null ? "{\"token\":\"" + token + "\"}" : "{\"error\":\"código incorrecto\"}").getBytes(StandardCharsets.UTF_8));
    }

    private String newToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String token = sb.toString();
        prefs.edit().putLong(TOKEN + token, System.currentTimeMillis()).apply();
        return token;
    }

    /** Un celular emparejado. Si no se usa en 90 días, deja de valer y hay que emparejarlo otra vez. */
    private boolean validToken(String t) {
        if (t == null || !t.matches("[0-9a-f]{32}")) return false;
        long last = prefs.getLong(TOKEN + t, 0), now = System.currentTimeMillis();
        if (last == 0) return false;
        if (now - last > TOKEN_TTL_MS) {
            prefs.edit().remove(TOKEN + t).apply();
            return false;
        }
        if (now - last > 3600000) prefs.edit().putLong(TOKEN + t, now).apply();
        return true;
    }

    /** Opciones de NEO → «Olvidar celulares»: todos tendrán que emparejarse de nuevo. */
    static void forgetPhones(Context c) {
        SharedPreferences p = c.getSharedPreferences("web_remote", MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith(TOKEN) || k.equals("tokens")) e.remove(k);
        e.commit();
        // y cortar a los que estén conectados ahora mismo
        try {
            c.startService(new Intent(c, WebRemoteService.class).setAction(ACTION_FORGET));
        } catch (Exception ignored) { /* el servicio no corre: no hay nadie conectado */ }
    }

    private void showCode(String code) {
        hideCode.run();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding((int) (40 * d), (int) (24 * d), (int) (40 * d), (int) (24 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141418);
        bg.setCornerRadius(24 * d);
        bg.setStroke((int) (2 * d), 0x6622D3EE);
        box.setBackground(bg);
        TextView t1 = new TextView(this);
        t1.setText("Código para el celular");
        t1.setTextColor(0xB3FFFFFF);
        t1.setTextSize(18);
        box.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(code.substring(0, 3) + "  " + code.substring(3));
        t2.setTextColor(0xFFFFFFFF);
        t2.setTextSize(64);
        t2.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        t2.setGravity(Gravity.CENTER);
        box.addView(t2);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(box, lp);
            codeView = box;
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Código para el celular: " + code, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable hideCode = () -> {
        if (codeView == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(codeView); } catch (Exception ignored) { /* ya no estaba */ }
        codeView = null;
    };

    // ---------- apps ----------

    /** Las mismas apps y banners que NEO deja para la tablet (apps.tsv en su carpeta externa). */
    private String appsJson() throws IOException {
        File dir = getExternalFilesDir(null);
        File f = dir != null ? new File(dir, "apps.tsv") : null;
        StringBuilder sb = new StringBuilder("[");
        if (f != null && f.exists()) {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"pkg\":").append(json(p[0])).append(",\"label\":").append(json(p[1]))
                        .append(",\"banner\":").append(json(p[2])).append('}');
            }
        }
        return sb.append(']').toString();
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void banner(OutputStream out, String name) throws IOException {
        File dir = getExternalFilesDir(null);
        if (dir == null || name == null || name.contains("/") || name.contains("..")) { reply(out, 404, "text/plain", new byte[0]); return; }
        File f = new File(new File(dir, "banners"), name);
        if (!f.exists()) { reply(out, 404, "text/plain", new byte[0]); return; }
        byte[] body;
        try (InputStream in = new FileInputStream(f)) { body = readAll(in); }
        String head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + body.length
                + "\r\nCache-Control: private, max-age=31536000\r\n" + SECURITY_HEADERS + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void launchApp(String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return; // un nombre de paquete nunca trae espacios ni símbolos de consola
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        final Intent launch = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ui.post(() -> {
            try {
                startActivity(launch);
            } catch (Exception e) {
                new Thread(() -> exec("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1")).start();
            }
        });
    }

    // ---------- WebSocket ----------

    private final class Client {
        final Socket sock;
        final OutputStream out;
        Client(Socket s, OutputStream o) { sock = s; out = o; }

        synchronized void send(String text) {
            try {
                byte[] p = text.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x81);
                if (p.length < 126) {
                    f.write(p.length);
                } else {
                    f.write(126);
                    f.write(p.length >> 8);
                    f.write(p.length & 0xFF);
                }
                f.write(p);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException e) {
                try { sock.close(); } catch (IOException ignored) { /* ya cerrado */ }
            }
        }
    }

    private void upgrade(Socket s, InputStream in, OutputStream out, String key) throws Exception {
        String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        s.setSoTimeout(0);
        Client c = new Client(s, out);
        clients.add(c);
        android.util.Log.i("NeoWeb", "celular conectado " + s.getInetAddress().getHostAddress());
        if (!status.isEmpty()) c.send(status);
        String why = "cerrado por el celular";
        try {
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                if (len > 65536) break;
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) readFully(in, mask);
                byte[] data = new byte[(int) len];
                readFully(in, data);
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if (op == 8) break;
                if (op == 9) { synchronized (c) { out.write(0x8A); out.write(0); out.flush(); } continue; }
                if (op != 1) continue;
                String cmd = new String(data, StandardCharsets.UTF_8);
                // el celular comprueba cada pocos segundos que la conexión sigue viva
                if (cmd.equals("ping")) c.send(status.isEmpty() ? "{}" : status);
                else command(cmd);
            }
        } catch (IOException e) {
            why = e.getMessage();
            throw e;
        } finally {
            android.util.Log.i("NeoWeb", "celular desconectado: " + why);
            clients.remove(c);
            try { s.close(); } catch (IOException ignored) { /* ya cerrado */ }
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("conexión cerrada");
            off += n;
        }
    }

    /** Un comando del celular. Solo se aceptan los que entiende el ayudante, más "app" y "power". */
    private void command(String cmd) {
        if (cmd.startsWith("app ")) { launchApp(cmd.substring(4).trim()); return; }
        if (cmd.equals("power")) { power(); return; }
        if (cmd.equals("ping")) return;
        boolean ok = cmd.startsWith("k ") || cmd.startsWith("l ") || cmd.startsWith("d ") || cmd.startsWith("t ")
                || cmd.startsWith("p ") || cmd.equals("c") || cmd.equals("cl") || cmd.equals("h");
        if (!ok || cmd.indexOf('\n') >= 0) return;
        boolean pointer = cmd.startsWith("p ") || cmd.equals("c") || cmd.equals("cl");
        try {
            ensureKeys();
            if (pointer) ensurePointer();
            keys.write(cmd + "\n");
        } catch (Exception e) {
            dropAdb();
        }
    }

    private void power() {
        setPower(!awakeNow());
    }

    /** Pantalla encendida, consultado en el momento (el estado guardado solo se actualiza con celulares conectados). */
    private boolean awakeNow() {
        String o = exec("dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'");
        return o.isEmpty() ? status.contains("\"awake\":true") : o.contains("Awake");
    }

    private void setPower(boolean on) {
        try {
            if (!on) {
                ensureKeys();
                keys.write("k 223\n");
            } else {
                ui.post(() -> {
                    try {
                        startActivity(new Intent(this, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { /* se prueba con la tecla */ }
                });
                ensureKeys();
                keys.write("k 224\n");
            }
        } catch (Exception e) {
            dropAdb();
        }
    }

    // ---------- Siri (app Atajos del iPhone) ----------

    /**
     * Una orden por enlace, para la acción «Obtener contenido de URL» de Atajos:
     * /api?t=TOKEN&do=on|off|power|pause|mute|vol_up|vol_down|home|back|open&app=NOMBRE&n=VECES
     * Responde una frase corta, que Siri lee en voz alta si el atajo la muestra.
     */
    private String api(Map<String, String> a) throws Exception {
        String what = a.get("do") == null ? "" : a.get("do");
        int n = 1;
        try { n = Math.max(1, Math.min(15, Integer.parseInt(a.get("n")))); } catch (Exception ignored) { /* una vez */ }
        boolean awake = awakeNow();
        switch (what) {
            case "on":
                if (awake) return "La TV ya está encendida";
                setPower(true);
                return "Listo, TV encendida";
            case "off":
                if (!awake) return "La TV ya está apagada";
                setPower(false);
                return "Listo, TV apagada";
            case "power":
                setPower(!awake);
                return awake ? "Listo, TV apagada" : "Listo, TV encendida";
            case "open": {
                String[] app = findApp(a.get("app"));
                if (app == null) return "No encontré esa app en la TV";
                if (!awake) { setPower(true); Thread.sleep(1500); }
                launchApp(app[0]);
                return "Abriendo " + app[1];
            }
            case "pause": return keyTimes(85, 1, "Listo");
            case "mute": return keyTimes(164, 1, "Listo");
            case "vol_up": return keyTimes(24, n, "Volumen arriba");
            case "vol_down": return keyTimes(25, n, "Volumen abajo");
            case "home": return keyTimes(3, 1, "Listo");
            case "back": return keyTimes(4, 1, "Listo");
            case "net": return netSummary();
            default: return "No entendí la orden";
        }
    }

    private String keyTimes(int code, int times, String done) throws Exception {
        ensureKeys();
        for (int i = 0; i < times; i++) keys.write("k " + code + "\n");
        return done;
    }

    /** Busca una app de NEO por nombre, sin importar mayúsculas, tildes ni símbolos ("disney" → Disney+). */
    private String[] findApp(String name) throws IOException {
        String want = simple(name == null ? "" : name);
        if (want.isEmpty()) return null;
        File dir = getExternalFilesDir(null);
        File f = dir != null ? new File(dir, "apps.tsv") : null;
        if (f == null || !f.exists()) return null;
        String[] partial = null;
        for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            String label = simple(p[1]);
            if (label.equals(want)) return new String[] { p[0], p[1] };
            if (partial == null && (label.startsWith(want) || want.startsWith(label))) partial = new String[] { p[0], p[1] };
        }
        return partial;
    }

    private static String simple(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---------- ADB a la propia TV + ayudante de teclas ----------

    private KeyPair keyPair() throws Exception {
        File priv = new File(getFilesDir(), "adbkey"), pub = new File(getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private void ensureAdb() throws Exception {
        Adb a = adb;
        if (a != null && a.alive()) return;
        adb = Adb.connect("127.0.0.1", 5555, 2000, keyPair(), new Adb.Listener() {
            @Override public void onNeedsApproval() {
                ui.post(() -> android.widget.Toast.makeText(WebRemoteService.this,
                        "Acepta «Permitir siempre» para usar el control desde el celular", android.widget.Toast.LENGTH_LONG).show());
            }
            @Override public void onDisconnected() { dropAdb(); }
        });
        keys = null;
    }

    private void ensureKeys() throws Exception {
        lock.lock();
        try {
            ensureAdb();
            Adb.Stream k = keys;
            if (k != null && !k.closed) return;
            byte[] dex;
            try (InputStream in = getAssets().open("neokeys.dex")) { dex = readAll(in); }
            String size = adb.exec("stat -c %s " + HELPER + " 2>/dev/null", 3000).trim();
            if (!size.equals(String.valueOf(dex.length))) push(HELPER, dex);
            Adb.Stream s = adb.open("shell:CLASSPATH=" + HELPER + " app_process / com.gonzalo.neoremote.KeyServer");
            StringBuilder out = new StringBuilder();
            long end = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < end && out.indexOf("ready") < 0) {
                byte[] b = s.read(500);
                if (b == null) continue;
                if (b.length == 0) throw new IOException("el ayudante se cerró");
                out.append(new String(b, StandardCharsets.UTF_8));
            }
            if (out.indexOf("ready") < 0) throw new IOException("el ayudante no respondió");
            keys = s;
            pointerReady = false;
        } finally {
            lock.unlock();
        }
    }

    /** Copia un archivo a la TV en base64 por partes (los comandos de shell tienen largo máximo). */
    private void push(String path, byte[] data) throws Exception {
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        adb.exec("rm -f " + path + ".b64", 3000);
        for (int i = 0; i < b64.length(); i += 3000)
            adb.exec("echo -n '" + b64.substring(i, Math.min(b64.length(), i + 3000)) + "' >> " + path + ".b64", 3000);
        adb.exec("base64 -d " + path + ".b64 > " + path + " && rm " + path + ".b64", 5000);
    }

    /** Corre optimizer/optimizar.sh en la TV (lo sube si cambió) y devuelve lo que imprime. */
    private String optimizer(String args) throws Exception {
        byte[] sh;
        try (InputStream in = getAssets().open("optimizar.sh")) { sh = readAll(in); }
        lock.lock();
        try {
            ensureAdb();
            String size = adb.exec("stat -c %s " + OPTIMIZER + " 2>/dev/null", 3000).trim();
            if (!size.equals(String.valueOf(sh.length))) push(OPTIMIZER, sh);
        } finally {
            lock.unlock();
        }
        return adb.exec("sh " + OPTIMIZER + " " + args + " 2>/dev/null", 90000);
    }

    /** La flecha del puntero: la dibuja CursorService (también de NEO) y el ayudante necesita el tamaño de pantalla. */
    private void ensurePointer() throws Exception {
        if (pointerReady) return;
        adb.exec("appops set com.gonzalo.neolauncher SYSTEM_ALERT_WINDOW allow", 3000);
        startService(new Intent(this, CursorService.class));
        Point p = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        keys.write("P " + Math.max(p.x, p.y) + " " + Math.min(p.x, p.y) + "\n");
        pointerReady = true;
    }

    private void dropAdb() {
        Adb a = adb;
        adb = null;
        keys = null;
        if (a != null) a.close();
    }

    private String exec(String cmd) {
        try {
            return execOrThrow(cmd, 5000);
        } catch (Exception e) {
            dropAdb();
            return "";
        }
    }

    private String execOrThrow(String cmd, long timeoutMs) throws Exception {
        lock.lock();
        try { ensureAdb(); } finally { lock.unlock(); }
        return adb.exec(cmd, timeoutMs);
    }

    // ---------- ¿quién está en mi Wi-Fi? ----------

    /** Revisa la red al rato de arrancar y después cada 5 minutos. */
    private void netLoop() {
        try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
        while (running) {
            try {
                int n = net.scan();
                android.util.Log.i("NeoNet", "red revisada: " + n + " conectados, " + net.unknownOnline().size() + " sin identificar");
            } catch (Exception e) {
                android.util.Log.w("NeoNet", "no se pudo revisar la red", e);
            }
            try { Thread.sleep(5 * 60000); } catch (InterruptedException e) { return; }
        }
    }

    /** Aviso en la esquina de la TV durante 12 s cuando entra a la red un equipo que nunca se vio. */
    private void showNewDevice(NetWatch.Device d) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * dp), (int) (16 * dp), (int) (24 * dp), (int) (16 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141418);
        bg.setCornerRadius(20 * dp);
        bg.setStroke((int) (2 * dp), 0x99FBBF24);
        box.setBackground(bg);
        TextView t1 = new TextView(this);
        t1.setText("Nuevo equipo en tu Wi-Fi");
        t1.setTextColor(0xFFFBBF24);
        t1.setTextSize(16);
        box.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(NetWatch.label(d));
        t2.setTextColor(0xFFFFFFFF);
        t2.setTextSize(22);
        box.addView(t2);
        TextView t3 = new TextView(this);
        t3.setText(d.ip + "  ·  míralo en NEO Remote → Red");
        t3.setTextColor(0x99FFFFFF);
        t3.setTextSize(14);
        box.addView(t3);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = lp.y = (int) (32 * dp);
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            wm.addView(box, lp);
            ui.postDelayed(() -> { try { wm.removeView(box); } catch (Exception ignored) { /* ya no estaba */ } }, 12000);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Nuevo equipo en tu Wi-Fi: " + NetWatch.label(d), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Frase para Siri: cuántos equipos hay y cuáles no se reconocen. */
    private String netSummary() throws Exception {
        net.scan();
        int n = net.onlineCount();
        List<NetWatch.Device> unk = net.unknownOnline();
        StringBuilder sb = new StringBuilder("Hay " + n + (n == 1 ? " equipo conectado" : " equipos conectados"));
        if (unk.isEmpty()) return sb.append(", todos conocidos").toString();
        sb.append(unk.size() == 1 ? ". Uno sin identificar: " : ". " + unk.size() + " sin identificar: ");
        for (int i = 0; i < unk.size() && i < 3; i++) sb.append(i > 0 ? ", " : "").append(NetWatch.label(unk.get(i)));
        return sb.toString();
    }

    /** Mientras haya celulares conectados: estado de la TV (pantalla encendida, teclado abierto) cada 0,8 s. */
    private void statusLoop() {
        while (running) {
            try {
                Thread.sleep(800);
                if (clients.isEmpty()) continue;
                String o = exec("dumpsys input_method | grep -m1 -o 'mInputShown=[a-z]*'; "
                        + "dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'");
                if (o.isEmpty()) continue;
                String now = "{\"ime\":" + o.contains("mInputShown=true") + ",\"awake\":" + o.contains("Awake")
                        + ",\"nd\":" + (net == null ? 0 : net.unknownOnline().size()) + "}";
                boolean changed = !now.equals(status);
                status = now;
                if (changed) for (Client c : clients) c.send(now);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
