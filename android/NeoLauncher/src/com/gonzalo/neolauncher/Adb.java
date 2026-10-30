package com.gonzalo.neolauncher;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;

/**
 * Cliente mínimo del protocolo ADB por red (el mismo que usa "adb connect").
 * Varias "streams" comparten un solo socket; un hilo lector reparte los mensajes.
 */
final class Adb {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };
    private static final byte[] EOF = new byte[0];

    interface Listener {
        /** La TV muestra "¿Permitir depuración?" y hay que aceptarla allá. */
        void onNeedsApproval();
        void onDisconnected();
    }

    final class Stream {
        final int localId;
        volatile int remoteId;
        volatile boolean closed;
        final CountDownLatch opened = new CountDownLatch(1);
        final Semaphore canWrite = new Semaphore(0);
        final LinkedBlockingQueue<byte[]> in = new LinkedBlockingQueue<byte[]>();

        Stream(int id) { localId = id; }

        void write(String s) throws IOException, InterruptedException {
            if (closed) throw new IOException("stream cerrada");
            if (!canWrite.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("la TV no responde");
            send(WRTE, localId, remoteId, s.getBytes(StandardCharsets.UTF_8));
        }

        /** Devuelve datos, EOF (vacío) al cerrarse, o null si pasó el tiempo. */
        byte[] read(long ms) throws InterruptedException {
            return in.poll(ms, TimeUnit.MILLISECONDS);
        }

        void close() {
            if (!closed) {
                closed = true;
                try { send(CLSE, localId, remoteId, null); } catch (IOException ignored) { /* ya cerrada */ }
            }
            streams.remove(localId);
        }
    }

    private final Socket sock = new Socket();
    private DataInputStream input;
    private OutputStream output;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<Integer, Stream>();
    private int nextId = 1;
    private volatile boolean alive;
    private Listener listener;

    boolean alive() { return alive; }

    static Adb connect(String host, int port, int timeoutMs, KeyPair kp, Listener l) throws Exception {
        return connect(host, port, timeoutMs, kp, l, true);
    }

    /** mayAsk=false: si el equipo no conoce la llave, se rinde en vez de pedir permiso en pantalla. */
    static Adb connect(String host, int port, int timeoutMs, KeyPair kp, Listener l, boolean mayAsk) throws Exception {
        Adb a = new Adb();
        a.listener = l;
        a.sock.connect(new InetSocketAddress(host, port), timeoutMs);
        a.sock.setTcpNoDelay(true);
        a.sock.setKeepAlive(true);
        a.input = new DataInputStream(a.sock.getInputStream());
        a.output = a.sock.getOutputStream();
        a.send(CNXN, 0x01000000, 256 * 1024, "host::neo-remote\0".getBytes(StandardCharsets.US_ASCII));
        a.sock.setSoTimeout(6000);
        boolean sentSignature = false, sentKey = false;
        while (true) {
            int[] h = a.readHeader();
            byte[] data = a.readData(h[3]);
            if (h[0] == CNXN) break;
            if (h[0] != AUTH || h[1] != 1) continue;
            if (!sentSignature) {
                a.send(AUTH, 2, 0, sign(kp, data));
                sentSignature = true;
            } else if (!sentKey && !mayAsk) {
                a.sock.close();
                throw new IOException("Equipo sin autorizar");
            } else if (!sentKey) {
                a.send(AUTH, 3, 0, publicKey((RSAPublicKey) kp.getPublic()));
                sentKey = true;
                if (l != null) l.onNeedsApproval();
                a.sock.setSoTimeout(90000);
            } else {
                a.sock.close();
                throw new IOException("La TV rechazó la conexión");
            }
        }
        a.sock.setSoTimeout(0);
        a.alive = true;
        Thread reader = new Thread(a::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();
        return a;
    }

    Stream open(String destination) throws IOException, InterruptedException {
        Stream s;
        synchronized (this) { s = new Stream(nextId++); }
        streams.put(s.localId, s);
        send(OPEN, s.localId, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        if (!s.opened.await(5, TimeUnit.SECONDS) || s.closed) {
            streams.remove(s.localId);
            throw new IOException("No se pudo abrir: " + destination);
        }
        return s;
    }

    /** Corre un comando de shell en la TV y devuelve lo que imprime. */
    String exec(String cmd, long timeoutMs) throws IOException, InterruptedException {
        Stream s = open("shell:" + cmd);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = end - System.currentTimeMillis();
            byte[] b = left > 0 ? s.read(left) : null;
            if (b == null) { s.close(); break; }
            if (b.length == 0) break;
            out.write(b);
        }
        streams.remove(s.localId);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    void close() {
        alive = false;
        try { sock.close(); } catch (IOException ignored) { /* ya cerrado */ }
    }

    // ---------- bajo nivel ----------

    private synchronized void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) b.put(data);
        output.write(b.array());
        output.flush();
    }

    private int[] readHeader() throws IOException {
        byte[] h = new byte[24];
        input.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] { b.getInt(), b.getInt(), b.getInt(), b.getInt() };
    }

    private byte[] readData(int len) throws IOException {
        byte[] d = new byte[len];
        input.readFully(d);
        return d;
    }

    private void readLoop() {
        try {
            while (true) {
                int[] h = readHeader();
                byte[] data = readData(h[3]);
                Stream s = streams.get(h[2]);
                if (s == null) {
                    if (h[0] == WRTE) send(OKAY, h[2], h[1], null);
                    continue;
                }
                if (h[0] == OKAY) {
                    s.remoteId = h[1];
                    if (s.opened.getCount() > 0) s.opened.countDown();
                    s.canWrite.release();
                } else if (h[0] == WRTE) {
                    s.in.offer(data);
                    send(OKAY, s.localId, s.remoteId, null);
                } else if (h[0] == CLSE) {
                    boolean wasOpen = !s.closed;
                    s.closed = true;
                    s.opened.countDown();
                    s.in.offer(EOF);
                    if (wasOpen) send(CLSE, s.localId, h[1], null);
                    streams.remove(s.localId);
                }
            }
        } catch (IOException e) {
            alive = false;
            for (Stream s : streams.values()) { s.closed = true; s.opened.countDown(); s.in.offer(EOF); }
            streams.clear();
            try { sock.close(); } catch (IOException ignored) { /* ya cerrado */ }
            if (listener != null) listener.onDisconnected();
        }
    }

    // ---------- autenticación RSA (la misma clave que guarda "adb") ----------

    private static byte[] sign(KeyPair kp, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, kp.getPrivate());
        byte[] msg = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, msg, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, msg, SHA1_PREFIX.length, token.length);
        return c.doFinal(msg);
    }

    /** Clave pública en el formato de Android (estructura RSAPublicKey de mincrypt, en base64). */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        putLittleEndian(b, n);
        putLittleEndian(b, rr);
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " neo@tv\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void putLittleEndian(ByteBuffer b, BigInteger x) {
        byte[] be = x.toByteArray();
        for (int i = 0; i < 256; i++) b.put(i < be.length ? be[be.length - 1 - i] : 0);
    }
}
