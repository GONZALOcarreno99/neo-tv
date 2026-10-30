package com.gonzalo.neolauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * «¿Quién está en mi Wi-Fi?»: cada pocos minutos la TV hace ping a toda la red y lee la tabla de
 * vecinos (ip neigh) por ADB, así aparecen hasta los equipos que no contestan ping. Cada equipo se
 * recuerda por su MAC, con el fabricante y el nombre que le ponga el usuario. Si aparece uno que
 * nunca se vio, avisa (en la TV y en NEO Remote).
 */
final class NetWatch {

    interface Shell { String run(String cmd, long timeoutMs) throws Exception; }
    interface Listener { void onNewDevice(Device d); }

    static final class Device {
        String mac, ip, name = "", vendor = "";
        long first, last;
        boolean known;
    }

    private final Context ctx;
    private final Shell shell;
    private final Listener listener;
    private final File file;
    private final Map<String, Device> devices = new LinkedHashMap<String, Device>();
    private String selfMac = "", routerIp = "";
    private long lastScan;
    private boolean baseline; // primera búsqueda: lo que ya estaba no cuenta como «nuevo» para el aviso

    NetWatch(Context c, Shell shell, Listener listener) {
        this.ctx = c;
        this.shell = shell;
        this.listener = listener;
        file = new File(c.getFilesDir(), "devices.json");
        load();
    }

    // ---------- búsqueda ----------

    /** Barre la red (~3 s). Devuelve la cantidad de equipos conectados ahora. */
    int scan() throws Exception {
        String prefix = prefix();
        if (prefix == null) return 0;
        String out = shell.run("for i in $(seq 1 254); do ping -c1 -W1 " + prefix + "$i >/dev/null 2>&1 & done; wait; "
                + "ip neigh show; echo '#self'; ip link show wlan0; ip link show eth0; echo '#route'; ip route get 1.1.1.1", 20000);
        long now = System.currentTimeMillis();
        List<Device> fresh = new ArrayList<Device>();
        int online = 0;
        synchronized (this) {
            String section = "neigh";
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.startsWith("#")) { section = line.substring(1); continue; }
                String[] p = line.split("\\s+");
                if (section.equals("neigh")) {
                    // 192.168.1.21 dev wlan0 lladdr 02:00:00:00:00:01 [router] REACHABLE   (STALE/FAILED = no está ahora)
                    int ll = java.util.Arrays.asList(p).indexOf("lladdr");
                    if (ll < 0 || ll + 1 >= p.length || !p[0].startsWith(prefix)) continue;
                    String state = p[p.length - 1];
                    if (!(state.equals("REACHABLE") || state.equals("DELAY") || state.equals("PROBE"))) continue;
                    if (see(p[ll + 1].toLowerCase(Locale.ROOT), p[0], now, fresh)) online++;
                } else if (section.equals("self")) {
                    int i = line.indexOf("link/ether ");
                    if (i >= 0 && selfMac.isEmpty()) selfMac = line.substring(i + 11).split("\\s+")[0];
                } else if (section.equals("route")) {
                    int v = line.indexOf(" via ");
                    if (v >= 0) routerIp = line.substring(v + 5).split("\\s+")[0];
                }
            }
            String myIp = selfIp();
            if (!selfMac.isEmpty() && myIp != null && see(selfMac, myIp, now, fresh)) online++;
            boolean alert = baseline;
            if (online > 0) baseline = true; // una búsqueda vacía (sin red) no sirve como punto de partida
            lastScan = now;
            save();
            if (!alert) fresh.clear();
        }
        for (Device d : fresh) if (listener != null) listener.onNewDevice(d);
        return online;
    }

    /** Registra un equipo visto ahora. Devuelve true si cuenta como conectado. */
    private boolean see(String mac, String ip, long now, List<Device> fresh) {
        if (!mac.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}") || mac.equals("00:00:00:00:00:00")) return false;
        Device d = devices.get(mac);
        if (d == null) {
            d = new Device();
            d.mac = mac;
            d.first = now;
            d.vendor = vendor(mac);
            devices.put(mac, d);
            fresh.add(d);
        }
        d.ip = ip;
        d.last = now;
        if (mac.equals(selfMac) && d.name.isEmpty()) { d.name = "Esta TV"; d.known = true; }
        if (ip.equals(routerIp) && d.name.isEmpty()) { d.name = "Router"; d.known = true; }
        return true;
    }

    private static String prefix() {
        String ip = selfIp();
        return ip == null ? null : ip.substring(0, ip.lastIndexOf('.') + 1);
    }

    private static String selfIp() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) { /* sin red */ }
        return null;
    }

    /** Fabricante según los 3 primeros bytes de la MAC (lista de Wireshark en assets/oui.txt.gz). */
    private String vendor(String mac) {
        int b0 = Integer.parseInt(mac.substring(0, 2), 16);
        // bit «administrada localmente»: iPhone y Android modernos inventan una MAC por cada red
        if ((b0 & 0x02) != 0) return "MAC privada (celular, tablet o PC)";
        String want = mac.substring(0, 8).replace(":", "").toUpperCase(Locale.ROOT);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(ctx.getAssets().open("oui.txt.gz")), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith(want)) return l.substring(7);
            }
        } catch (Exception ignored) { /* sin lista */ }
        return "Fabricante desconocido";
    }

    // ---------- lo que pide NEO Remote ----------

    synchronized JSONObject json() throws Exception {
        List<Device> list = new ArrayList<Device>(devices.values());
        Collections.sort(list, (a, b) -> {
            boolean ao = a.last >= lastScan, bo = b.last >= lastScan;
            if (ao != bo) return ao ? -1 : 1;
            return Long.compare(b.last, a.last);
        });
        JSONArray arr = new JSONArray();
        for (Device d : list) {
            arr.put(new JSONObject().put("mac", d.mac).put("ip", d.ip).put("name", d.name).put("vendor", d.vendor)
                    .put("first", d.first).put("last", d.last).put("known", d.known)
                    .put("online", lastScan > 0 && d.last >= lastScan));
        }
        return new JSONObject().put("scan", lastScan).put("devices", arr);
    }

    /** Ponerle nombre a un equipo también lo marca como conocido. */
    synchronized boolean name(String mac, String name) {
        Device d = devices.get(mac == null ? "" : mac.toLowerCase(Locale.ROOT));
        if (d == null) return false;
        if (name != null) d.name = name.trim().length() > 40 ? name.trim().substring(0, 40) : name.trim();
        d.known = true;
        save();
        return true;
    }

    synchronized boolean forget(String mac) {
        boolean ok = devices.remove(mac == null ? "" : mac.toLowerCase(Locale.ROOT)) != null;
        if (ok) save();
        return ok;
    }

    /** Equipos conectados ahora que nadie marcó como conocidos. */
    synchronized List<Device> unknownOnline() {
        List<Device> l = new ArrayList<Device>();
        for (Device d : devices.values()) if (!d.known && lastScan > 0 && d.last >= lastScan) l.add(d);
        return l;
    }

    synchronized int onlineCount() {
        int n = 0;
        for (Device d : devices.values()) if (lastScan > 0 && d.last >= lastScan) n++;
        return n;
    }

    static String label(Device d) {
        return d.name.isEmpty() ? d.vendor : d.name;
    }

    // ---------- guardar ----------

    private void load() {
        if (!file.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = o.getJSONArray("devices");
            baseline = o.optBoolean("baseline") && arr.length() > 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                Device d = new Device();
                d.mac = j.getString("mac");
                d.ip = j.optString("ip");
                d.name = j.optString("name");
                d.vendor = j.optString("vendor");
                d.first = j.optLong("first");
                d.last = j.optLong("last");
                d.known = j.optBoolean("known");
                devices.put(d.mac, d);
            }
        } catch (Exception e) {
            android.util.Log.w("NeoNet", "no se pudo leer devices.json", e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Device d : devices.values()) {
                arr.put(new JSONObject().put("mac", d.mac).put("ip", d.ip).put("name", d.name).put("vendor", d.vendor)
                        .put("first", d.first).put("last", d.last).put("known", d.known));
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(new JSONObject().put("baseline", baseline).put("devices", arr).toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file);
        } catch (Exception e) {
            android.util.Log.w("NeoNet", "no se pudo guardar devices.json", e);
        }
    }
}
