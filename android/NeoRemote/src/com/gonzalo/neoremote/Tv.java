package com.gonzalo.neoremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Lo común para hablar con la TV: IP, llave ADB, Wake-on-LAN y un encendido rápido sin abrir la app. */
final class Tv {

    static final String DEFAULT_HOST = ""; // la primera vez la app busca la TV en la red
    private static KeyPair keyPair;

    private Tv() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    static String host(Context c) {
        return prefs(c).getString("host", DEFAULT_HOST);
    }

    static synchronized KeyPair keyPair(Context c) throws Exception {
        if (keyPair != null) return keyPair;
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath())));
            PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath())));
            return keyPair = new KeyPair(pu, pk);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) { o.write(keyPair.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(keyPair.getPublic().getEncoded()); }
        return keyPair;
    }

    /** Paquete mágico Wake-on-LAN (la TV tiene WoL y WoW activados). */
    static void wakeOnLan(Context c) {
        String mac = prefs(c).getString("mac", null);
        if (mac == null) return;
        try {
            String[] parts = mac.split(":");
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
            byte[] pkt = new byte[102];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
            for (int i = 6; i < pkt.length; i++) pkt[i] = m[(i - 6) % 6];
            String h = host(c);
            if (h.isEmpty()) return;
            String bcast = h.substring(0, h.lastIndexOf('.')) + ".255";
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (String target : new String[] { "255.255.255.255", bcast, h })
                    s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(target), 9));
            }
        } catch (Exception ignored) { /* best effort */ }
    }

    /** Equipos de la red local con el puerto 5555 (ADB) abierto. */
    static List<String> openPorts() {
        String prefix = null;
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                for (Enumeration<InetAddress> a = e.nextElement().getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (ad instanceof Inet4Address && !ad.isLoopbackAddress()) {
                        String s = ad.getHostAddress();
                        prefix = s.substring(0, s.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) { /* sin red */ }
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        if (prefix == null) return found;
        ExecutorService ex = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            ex.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, 5555), 350);
                    found.add(ip);
                } catch (IOException ignored) { /* no es la TV */ }
            });
        }
        ex.shutdown();
        try { ex.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { /* listo */ }
        return found;
    }

    /**
     * Busca la TV si cambió de IP (el router le dio otra). Solo acepta un equipo que ya autorizó
     * esta llave y, si se conoce, con el mismo número de serie. Guarda la IP nueva y la devuelve.
     */
    static String find(Context c, List<String> candidates) {
        String serial = prefs(c).getString("serial", null);
        for (String ip : candidates) {
            Adb a = null;
            try {
                a = Adb.connect(ip, 5555, 800, keyPair(c), null, false);
                String s = a.exec("getprop ro.serialno", 3000).trim();
                if (serial == null || serial.equals(s)) {
                    prefs(c).edit().putString("host", ip).putString("serial", s).apply();
                    return ip;
                }
            } catch (Exception ignored) { /* otro equipo o sin autorizar */
            } finally {
                if (a != null) a.close();
            }
        }
        return null;
    }

    /** Conexión corta; si la TV no contesta, la despierta por la red y reintenta unos segundos. */
    private static Adb connect(Context c, boolean wake) throws Exception {
        long end = System.currentTimeMillis() + (wake ? 12000 : 1500);
        int tries = 0;
        boolean searched = false;
        while (true) {
            try {
                return Adb.connect(host(c), 5555, 1200, keyPair(c), null);
            } catch (Exception e) {
                // a mitad de la espera, si sigue sin contestar, ver si la TV cambió de IP
                if (wake && !searched && System.currentTimeMillis() > end - 6000) {
                    searched = true;
                    if (find(c, openPorts()) != null) continue;
                }
                if (System.currentTimeMillis() > end) throw e;
                if (wake && tries++ % 3 == 0) wakeOnLan(c);
                Thread.sleep(300);
            }
        }
    }

    /** true = encendida, false = en reposo, null = no se pudo conectar. */
    static Boolean state(Context c) {
        Adb a = null;
        try {
            a = connect(c, false);
            return a.exec("dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'", 3000).contains("Awake");
        } catch (Exception e) {
            return null;
        } finally {
            if (a != null) a.close();
        }
    }

    /** Prende si está apagada y apaga si está prendida. Devuelve el estado final (null si no responde). */
    static Boolean toggle(Context c) {
        Adb a = null;
        try {
            wakeOnLan(c); // por si la TV apagó la red; a una TV prendida no le hace nada
            a = connect(c, true);
            // prender con la pantalla de NEO (~0,1 s); si NEO no está, con input keyevent
            String o = a.exec("if dumpsys power | grep -q 'mWakefulness=Awake'; then input keyevent 223; echo apagada; "
                    + "else cmd activity start-activity -n com.gonzalo.neolauncher/.WakeActivity >/dev/null 2>&1 "
                    + "|| input keyevent 224; echo encendida; fi", 6000);
            return o.contains("encendida");
        } catch (Exception e) {
            return null;
        } finally {
            if (a != null) a.close();
        }
    }
}
