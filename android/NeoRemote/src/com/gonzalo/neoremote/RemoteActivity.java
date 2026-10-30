package com.gonzalo.neoremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Control remoto de la TV por Wi-Fi: cruceta, encendido, volumen, apps de la TV, teclado y voz. */
public class RemoteActivity extends Activity {

    private static final int BG1 = 0xFF0B0E15, BG2 = 0xFF04060A, CARD = 0xFF131720, ACCENT = 0xFF22D3EE;
    private static final int WHITE = 0xFFFFFFFF, DIM = 0x99FFFFFF, FAINT = 0x59FFFFFF;
    private static final int RED = 0xFFFF5A5F, GREEN = 0xFF34D399, AMBER = 0xFFFBBF24;
    private static final String HELPER = "/data/local/tmp/neokeys.dex";
    private static final String EXPORT = "/sdcard/Android/data/com.gonzalo.neolauncher/files";
    private static final String OPTIMIZER = "/data/local/tmp/neo-optimizar.sh";
    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int REQ_MIC = 7;

    private static final class TvApp {
        String pkg, label, file;
        Bitmap banner;
    }

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread ioThread, pollThread;
    private Handler io, poll;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Adb adb;
    private volatile Adb.Stream keys;
    private volatile boolean resumed;
    private volatile Boolean tvAwake;
    private volatile boolean appsDirty = true;
    private String appsSig = "";
    private WifiManager.WifiLock wifiLock;
    private int pollCount, failStreak;
    private long lastFind;

    private boolean land;
    private TextView statusText, powerLabel, cleanLabel, kbStatus, micLabel;
    private View statusDot, micBtn;
    private GradientDrawable micBg;
    private EditText input;
    private GradientDrawable kbBg;
    private LinearLayout appsBox;
    private boolean imeShown, silent, listening;
    private String sent = "";
    private SpeechRecognizer recognizer;
    private DPad dpad;
    private TouchPad touchPad;
    private TextView segPad, segTouch;

    // ---------- ciclo de vida ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG1);
        getWindow().setNavigationBarColor(BG2);
        ioThread = new HandlerThread("tv-keys");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        pollThread = new HandlerThread("tv-poll");
        pollThread.start();
        poll = new Handler(pollThread.getLooper());
        setContentView(buildUi());
        showCachedApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        appsDirty = true;
        // sin esto el Wi-Fi ahorra energía juntando paquetes y el puntero avanza a tirones
        if (wifiLock == null) {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = w.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "neo-remote");
            wifiLock.setReferenceCounted(false);
        }
        wifiLock.acquire();
        poll.removeCallbacks(pollTask);
        poll.post(pollTask);
        if (host().isEmpty() && !scanning) scan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (wifiLock != null) wifiLock.release();
        poll.removeCallbacks(pollTask);
        if (listening && recognizer != null) recognizer.cancel();
        setListening(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        final Adb a = adb;
        io.post(() -> { if (a != null) a.close(); });
        ioThread.quitSafely();
        pollThread.quitSafely();
    }

    /** Los botones de volumen de la tablet controlan el volumen de la TV. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            key(code);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------- utilidades de UI ----------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams lin(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h), weight);
    }

    private LinearLayout.LayoutParams lin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private GradientDrawable rect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void round(View v, float radiusDp) {
        final int r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF171C27, CARD });
        g.setCornerRadius(dp(28));
        g.setStroke(dp(1), 0x14FFFFFF);
        c.setBackground(g);
        return c;
    }

    private void tap(View v, Runnable r) {
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            r.run();
        });
    }

    /** Botón redondo con ícono y texto debajo. */
    private View roundBtn(int icon, String label, int size, int c1, int c2, Runnable action) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { c1, c2 });
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(WHITE);
        circle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, mask));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Ico(icon, WHITE));
        circle.addView(ic, new FrameLayout.LayoutParams(dp(size * 0.38f), dp(size * 0.38f), Gravity.CENTER));
        tap(circle, action);
        col.addView(circle, lin(size, size, 0, 0, 0, 0));
        if (label != null) {
            TextView t = tv(label, 13, DIM, "sans-serif-medium");
            t.setGravity(Gravity.CENTER);
            col.addView(t, lin(-2, -2, 0, 8, 0, 0));
            if (icon == Ico.POWER) powerLabel = t;
            if (icon == Ico.CLEAN) cleanLabel = t;
        }
        return col;
    }

    private View chipBtn(String text, boolean primary, Runnable action) {
        TextView t = tv(text, 15, primary ? 0xFF001018 : WHITE, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = rect(primary ? ACCENT : 0x1AFFFFFF, 16);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null));
        tap(t, action);
        return t;
    }

    // ---------- interfaz ----------

    private View buildUi() {
        land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { BG1, BG2 }));
        root.setPadding(dp(28), dp(20), dp(28), dp(20));

        // cabecera
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View logo = new View(this);
        logo.setBackground(rect(ACCENT, 3));
        logo.setRotation(45);
        header.addView(logo, lin(13, 13, 4, 0, 14, 0));
        TextView brand = tv("NEO", 22, WHITE, "sans-serif-medium");
        brand.setLetterSpacing(0.25f);
        header.addView(brand);
        header.addView(tv("  Remote", 22, DIM, "sans-serif-light"));
        header.addView(new View(this), lin(0, 1, 1f));
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), dp(8), dp(16), dp(8));
        chip.setBackground(rect(0x14FFFFFF, 20));
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(DIM);
        statusDot.setBackground(dot);
        chip.addView(statusDot, lin(10, 10, 0, 0, 10, 0));
        statusText = tv("Buscando la TV…", 14, WHITE, "sans-serif-medium");
        chip.addView(statusText);
        header.addView(chip);
        header.addView(roundBtn(Ico.GEAR, null, 44, 0xFF232834, 0xFF181B23, this::showSettings), lin(-2, -2, 14, 0, 0, 0));
        root.addView(header, lin(-1, -2, 0, 0, 0, 18));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        // cruceta
        LinearLayout pad = card();
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        DPad.Listener padKeys = new DPad.Listener() {
            @Override public void onKey(int code) { key(code); }
            @Override public void onLongOk() { line("l 23"); }
        };
        dpad = new DPad(this, ACCENT);
        dpad.setListener(padKeys);
        touchPad = new TouchPad(this, ACCENT);
        touchPad.setListener(new TouchPad.Listener() {
            @Override public void onMove(float dx, float dy) { pointerMove(dx, dy); }
            @Override public void onClick() { pointerCmd("c"); }
            @Override public void onLongClick() { pointerCmd("cl"); }
            @Override public void onArrow(int keyCode) { key(keyCode); }
        });

        // selector Cruceta / Touchpad
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(rect(0xFF0D1016, 20));
        segPad = segBtn("Cruceta", false);
        segTouch = segBtn("Puntero", true);
        seg.addView(segPad, lin(0, 36, 1f));
        seg.addView(segTouch, lin(0, 36, 1f));
        pad.addView(seg, lin(280, -2, 0, 0, 0, 16));

        FrameLayout surface = new FrameLayout(this);
        surface.addView(dpad, new FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER));
        surface.addView(touchPad, new FrameLayout.LayoutParams(-1, -1));
        pad.addView(surface, lin(-1, 300, 0, 0, 0, 18));
        applyPadMode(false);

        LinearLayout seek = new LinearLayout(this);
        seek.addView(chipBtn("«  10 s", false, () -> key(89)), lin(0, 44, 1f));
        seek.addView(new View(this), lin(12, 1, 0f));
        seek.addView(chipBtn("10 s  »", false, () -> key(90)), lin(0, 44, 1f));
        pad.addView(seek, lin(300, -2, 0, 0, 0, 16));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_HORIZONTAL);
        nav.addView(roundBtn(Ico.BACK, "Atrás", 64, 0xFF262B36, 0xFF171A21, () -> key(4)), lin(-2, -2, 20, 0, 20, 0));
        nav.addView(roundBtn(Ico.HOME, "Inicio", 64, 0xFF262B36, 0xFF171A21, () -> key(3)), lin(-2, -2, 20, 0, 20, 0));
        nav.addView(roundBtn(Ico.PLAY, "Pausa", 64, 0xFF262B36, 0xFF171A21, () -> key(85)), lin(-2, -2, 20, 0, 20, 0));
        pad.addView(nav);

        // encendido y volumen
        LinearLayout mid = card();
        mid.addView(roundBtn(Ico.POWER, "Encender", 92, 0xFFFF6B6B, 0xFFC0283A, this::power), lin(-2, -2, 0, 4, 0, 26));
        LinearLayout vol = new LinearLayout(this);
        vol.setOrientation(LinearLayout.VERTICAL);
        vol.setGravity(Gravity.CENTER_HORIZONTAL);
        vol.setPadding(0, dp(10), 0, dp(10));
        vol.setBackground(rect(0xFF0D1016, 40));
        vol.addView(roundBtn(Ico.VOL_UP, null, 64, 0xFF262B36, 0xFF171A21, () -> key(24)));
        TextView vl = tv("VOL", 12, FAINT, "sans-serif-medium");
        vl.setLetterSpacing(0.2f);
        vol.addView(vl, lin(-2, -2, 0, 12, 0, 12));
        vol.addView(roundBtn(Ico.VOL_DOWN, null, 64, 0xFF262B36, 0xFF171A21, () -> key(25)));
        mid.addView(vol, lin(84, -2, 0, 0, 0, 22));
        mid.addView(roundBtn(Ico.MUTE, "Silencio", 56, 0xFF262B36, 0xFF171A21, () -> key(164)), lin(-2, -2, 0, 0, 0, 22));
        mid.addView(roundBtn(Ico.CLEAN, "Limpiar", 56, 0xFF262B36, 0xFF171A21, this::cleanCache));

        // teclado, voz y apps
        LinearLayout right = card();
        right.setGravity(Gravity.START);
        LinearLayout kbHead = new LinearLayout(this);
        kbHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView kbIcon = new ImageView(this);
        kbIcon.setImageDrawable(new Ico(Ico.KEYBOARD, ACCENT));
        kbHead.addView(kbIcon, lin(26, 26, 0, 0, 12, 0));
        kbHead.addView(tv("Escribir o hablar a la TV", 18, WHITE, "sans-serif-medium"));
        right.addView(kbHead);
        kbStatus = tv("", 13, DIM, "sans-serif");
        right.addView(kbStatus, lin(-2, -2, 0, 6, 0, 12));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(22);
        input.setTextColor(WHITE);
        input.setHintTextColor(FAINT);
        input.setHint("Escribe aquí…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(dp(18), dp(14), dp(18), dp(14));
        kbBg = rect(0xFF0D1016, 18);
        kbBg.setStroke(dp(2), 0x22FFFFFF);
        input.setBackground(kbBg);
        right.addView(input, lin(-1, -2, 0, 0, 0, 12));
        LinearLayout kbBtns = new LinearLayout(this);
        kbBtns.addView(chipBtn("Borrar", false, () -> { line("d 60"); clearSilently(); }), lin(0, 52, 0.8f));
        kbBtns.addView(new View(this), lin(12, 1, 0f));
        kbBtns.addView(buildMicBtn(), lin(0, 52, 1.2f));
        kbBtns.addView(new View(this), lin(12, 1, 0f));
        kbBtns.addView(chipBtn("Buscar  ⏎", true, this::enter), lin(0, 52, 1f));
        right.addView(kbBtns, lin(-1, -2, 0, 0, 0, 20));
        setKeyboardIdle();

        TextView appsLabel = tv("APPS DE LA TV", 12, FAINT, "sans-serif-medium");
        appsLabel.setLetterSpacing(0.25f);
        right.addView(appsLabel, lin(-2, -2, 0, 0, 0, 10));
        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        appsBox.addView(tv("Cargando apps de la TV…", 14, FAINT, "sans-serif"));
        if (land) {
            ScrollView appsScroll = new ScrollView(this);
            appsScroll.setVerticalScrollBarEnabled(false);
            appsScroll.addView(appsBox);
            right.addView(appsScroll, lin(-1, 0, 1f));
        } else {
            right.addView(appsBox, lin(-1, -2, 0, 0, 0, 0));
        }

        if (land) {
            body.addView(pad, lin(0, -1, 1.05f));
            body.addView(new View(this), lin(18, 1, 0f));
            body.addView(mid, lin(170, -1, 0f));
            body.addView(new View(this), lin(18, 1, 0f));
            body.addView(right, lin(0, -1, 1.35f));
            root.addView(body, lin(-1, 0, 1f));
        } else {
            body.addView(pad, lin(-1, -2, 0, 0, 0, 16));
            mid.setOrientation(LinearLayout.HORIZONTAL);
            mid.setGravity(Gravity.CENTER);
            body.addView(mid, lin(-1, -2, 0, 0, 0, 16));
            body.addView(right, lin(-1, -2, 0, 0, 0, 0));
            ScrollView sv = new ScrollView(this);
            sv.addView(body);
            root.addView(sv, lin(-1, 0, 1f));
        }
        hookInput();
        return root;
    }

    private TextView segBtn(String text, final boolean touch) {
        TextView t = tv(text, 14, DIM, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        t.setBackground(rect(0, 16));
        tap(t, () -> {
            prefs.edit().putBoolean("touchpad", touch).apply();
            applyPadMode(true);
        });
        return t;
    }

    /** Muestra la cruceta o el puntero según lo último que eligió el usuario. */
    private void applyPadMode(boolean fromUser) {
        boolean touch = prefs.getBoolean("touchpad", false);
        if (fromUser) {
            if (touch) pointerReady = false; // re-arrancar la flecha en la TV por si NEO se reinició
            pointerCmd(touch ? "p 0 0" : "h"); // mostrar la flecha / esconderla
        }
        dpad.setVisibility(touch ? View.GONE : View.VISIBLE);
        touchPad.setVisibility(touch ? View.VISIBLE : View.GONE);
        ((GradientDrawable) segPad.getBackground()).setColor(touch ? 0 : ACCENT);
        ((GradientDrawable) segTouch.getBackground()).setColor(touch ? ACCENT : 0);
        segPad.setTextColor(touch ? DIM : 0xFF001018);
        segTouch.setTextColor(touch ? 0xFF001018 : DIM);
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
    }

    // ---------- apps de la TV ----------

    /** Muestra al instante las apps de la última vez (guardadas en la tablet) mientras conecta. */
    private void showCachedApps() {
        String tsv = prefs.getString("apps", null);
        if (tsv == null) return;
        List<TvApp> list = parseApps(tsv);
        for (TvApp a : list) {
            File f = a.file != null ? new File(getCacheDir(), a.file) : null;
            if (f != null && f.exists()) a.banner = BitmapFactory.decodeFile(f.getPath());
        }
        renderApps(list);
    }

    private static List<TvApp> parseApps(String tsv) {
        List<TvApp> list = new ArrayList<TvApp>();
        for (String l : tsv.split("\n")) {
            String[] p = l.split("\t");
            if (p.length < 2 || p[0].isEmpty()) continue;
            TvApp a = new TvApp();
            a.pkg = p[0].trim();
            a.label = p[1].trim();
            a.file = p.length > 2 ? p[2].trim() : null;
            list.add(a);
        }
        return list;
    }

    /** Lee la lista que publica NEO en la TV y baja los banners que la tablet aún no tiene. */
    private void loadApps() throws Exception {
        String tsv = adb.exec("cat " + EXPORT + "/apps.tsv 2>/dev/null", 4000);
        if (tsv.trim().isEmpty()) {
            // sin NEO en la TV: lista básica con los nombres de paquete
            String o = adb.exec("cmd package query-activities --brief -a android.intent.action.MAIN "
                    + "-c android.intent.category.LEANBACK_LAUNCHER", 5000);
            StringBuilder b = new StringBuilder();
            for (String l : o.split("\n")) {
                l = l.trim();
                int s = l.indexOf('/');
                if (s > 0 && !l.contains(" ")) b.append(l, 0, s).append('\t').append(l, 0, s).append('\n');
            }
            tsv = b.toString();
        }
        final List<TvApp> list = parseApps(tsv);
        for (TvApp a : list) {
            if (a.file == null) continue;
            File f = new File(getCacheDir(), a.file);
            if (!f.exists()) {
                String b64 = adb.exec("base64 " + EXPORT + "/banners/" + a.file + " 2>/dev/null", 8000);
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                if (bytes.length > 0) try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
            }
            if (f.exists()) a.banner = BitmapFactory.decodeFile(f.getPath());
        }
        appsDirty = false;
        try { syncNeoBackup(); } catch (Exception ignored) { /* se reintenta en el próximo ciclo */ }
        if (tsv.equals(appsSig)) return;
        appsSig = tsv;
        prefs.edit().putString("apps", tsv).apply();
        ui.post(() -> renderApps(list));
    }

    private void renderApps(List<TvApp> list) {
        appsBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                appsBox.addView(row, lin(-1, -2, 0, 0, 0, 12));
            } else {
                row.addView(new View(this), lin(12, 1, 0f));
            }
            row.addView(appTile(list.get(i)), lin(0, -2, 1f));
        }
        if (list.size() % 3 != 0) {
            for (int k = list.size() % 3; k < 3; k++) {
                row.addView(new View(this), lin(12, 1, 0f));
                row.addView(new View(this), lin(0, 1, 1f));
            }
        }
    }

    private View appTile(final TvApp a) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(rect(0xFF1C2029, 14));
        round(frame, 14);
        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(a.banner);
            frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView t = tv(a.label, 15, WHITE, "sans-serif-medium");
            t.setGravity(Gravity.CENTER);
            frame.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        View ripple = new View(this);
        ripple.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, rect(WHITE, 14)));
        frame.addView(ripple, new FrameLayout.LayoutParams(-1, -1));
        tap(ripple, () -> openApp(a.pkg, a.label));
        col.addView(frame, lin(-1, 76, 0f));
        TextView name = tv(a.label, 12, DIM, "sans-serif-medium");
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name, lin(-1, -2, 2, 6, 0, 0));
        return col;
    }

    // ---------- teclado ----------

    private void hookInput() {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (silent) return;
                ui.removeCallbacks(sync);
                ui.postDelayed(sync, 120);
            }
        });
        input.setOnEditorActionListener((v, id, ev) -> {
            enter();
            return true;
        });
    }

    /** Manda a la TV solo la diferencia con lo ya enviado: borra lo que sobra y escribe lo nuevo. */
    private final Runnable sync = () -> {
        String now = clean(input.getText().toString(), imeShown);
        int p = 0;
        while (p < now.length() && p < sent.length() && now.charAt(p) == sent.charAt(p)) p++;
        int dels = sent.length() - p;
        String add = now.substring(p);
        if (dels > 0) line("d " + dels);
        if (!add.isEmpty()) line("t " + add);
        sent = now;
    };

    /**
     * Con el teclado de Android abierto en la TV, tildes y ñ pasan tal cual (el ayudante las escribe
     * como texto). Si no está abierto, es un buscador con teclado propio (YouTube, Netflix) que
     * descarta esas letras: ahí "canción" se manda como "cancion", que encuentra lo mismo.
     */
    private static String clean(String s, boolean systemKeyboard) {
        String n = systemKeyboard ? Normalizer.normalize(s, Normalizer.Form.NFC)
                : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) {
            boolean ok = systemKeyboard
                    ? c >= 0x20 && c != 0x7F && !Character.isSurrogate(c) && Character.getType(c) != Character.NON_SPACING_MARK
                    : c >= 0x20 && c < 0x7F;
            if (ok) b.append(c);
        }
        return b.toString();
    }

    private void enter() {
        ui.removeCallbacks(sync);
        sync.run();
        key(66);
        clearSilently();
    }

    private void clearSilently() {
        silent = true;
        input.setText("");
        silent = false;
        sent = "";
    }

    private void setKeyboardIdle() {
        kbStatus.setText("Se abre solo cuando la TV pide texto. En YouTube toca el campo o usa la voz.");
        kbStatus.setTextColor(DIM);
        kbBg.setStroke(dp(2), 0x22FFFFFF);
    }

    private void onTvState(boolean ime, boolean awake) {
        tvAwake = awake;
        powerLabel.setText(awake ? "Apagar" : "Encender");
        setStatus(awake ? "TV encendida" : "TV en reposo", awake ? GREEN : AMBER);
        if (ime == imeShown) return;
        imeShown = ime;
        if (listening) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        clearSilently();
        if (ime) {
            kbStatus.setText("La TV está esperando texto · escribe aquí");
            kbStatus.setTextColor(ACCENT);
            kbBg.setStroke(dp(2), ACCENT);
            input.requestFocus();
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } else {
            setKeyboardIdle();
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        }
    }

    // ---------- voz: la tablet escucha porque la TV no tiene micrófono ----------

    private View buildMicBtn() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        micBg = rect(0x1AFFFFFF, 16);
        micBg.setStroke(dp(2), ACCENT);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), micBg, null));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Ico(Ico.MIC, ACCENT));
        b.addView(ic, lin(22, 22, 0, 0, 8, 0));
        micLabel = tv("Hablar", 15, WHITE, "sans-serif-medium");
        b.addView(micLabel);
        tap(b, this::toggleVoice);
        micBtn = b;
        return b;
    }

    private void toggleVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQ_MIC);
            return;
        }
        if (listening) {
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        startVoice();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        else if (req == REQ_MIC) Toast.makeText(this, "Sin permiso de micrófono no puedo escucharte", Toast.LENGTH_LONG).show();
    }

    private void startVoice() {
        if (recognizer == null) {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            } else {
                Toast.makeText(this, "Esta tablet no tiene reconocimiento de voz disponible", Toast.LENGTH_LONG).show();
                return;
            }
            recognizer.setRecognitionListener(voiceListener);
        }
        // si la TV está en YouTube y su teclado no está abierto, abre la búsqueda
        io.post(() -> {
            try {
                ensureConnected();
                String f = adb.exec("dumpsys window | grep -m1 mCurrentFocus", 3000);
                if (f.contains("youtube")) keys.write("k 84\n");
            } catch (Exception ignored) { /* se escribe igual donde esté el foco */ }
        });
        clearSilently();
        setListening(true);
        String lang = prefs.getString("voiceLang", null);
        if (lang != null || Build.VERSION.SDK_INT < 34) {
            recognizer.startListening(voiceIntent(lang != null ? lang : "es-ES"));
            return;
        }
        // primera vez: ver qué español tiene la tablet y, si no hay, descargarlo
        recognizer.checkRecognitionSupport(voiceIntent("es-ES"), getMainExecutor(),
                new android.speech.RecognitionSupportCallback() {
                    @Override public void onSupportResult(android.speech.RecognitionSupport s) {
                        android.util.Log.d("NeoRemote", "voz instalados=" + s.getInstalledOnDeviceLanguages()
                                + " soportados=" + s.getSupportedOnDeviceLanguages()
                                + " pendientes=" + s.getPendingOnDeviceLanguages());
                        String installed = pickSpanish(s.getInstalledOnDeviceLanguages());
                        if (installed != null) {
                            prefs.edit().putString("voiceLang", installed).apply();
                            recognizer.startListening(voiceIntent(installed));
                            return;
                        }
                        String dl = pickSpanish(s.getSupportedOnDeviceLanguages());
                        setListening(false);
                        if (dl == null) {
                            Toast.makeText(RemoteActivity.this, "El reconocimiento de voz de esta tablet no trae español",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        downloadSpanish(dl);
                    }
                    @Override public void onError(int error) {
                        recognizer.startListening(voiceIntent("es-ES"));
                    }
                });
    }

    private Intent voiceIntent(String lang) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        return i;
    }

    /** Prefiere español latino, luego de EE. UU., luego de España, luego cualquier "es". */
    private static String pickSpanish(List<String> langs) {
        if (langs == null) return null;
        for (String want : new String[] { "es-419", "es-US", "es-MX", "es-ES" })
            for (String l : langs) if (l.equalsIgnoreCase(want)) return l;
        for (String l : langs) if (l.toLowerCase().startsWith("es")) return l;
        return null;
    }

    /** Descarga una sola vez el modelo de voz en español (después funciona sin internet). */
    private void downloadSpanish(final String lang) {
        if (Build.VERSION.SDK_INT < 34) return;
        micLabel.setText("Descargando…");
        kbStatus.setText("Descargando el español para la voz (solo esta vez)…");
        kbStatus.setTextColor(ACCENT);
        recognizer.triggerModelDownload(voiceIntent(lang), getMainExecutor(), new android.speech.ModelDownloadListener() {
            @Override public void onProgress(int percent) {
                micLabel.setText("Descargando " + percent + "%");
            }
            @Override public void onSuccess() {
                prefs.edit().putString("voiceLang", lang).apply();
                setListening(false);
                setKeyboardIdle();
                Toast.makeText(RemoteActivity.this, "Listo: ya puedes hablar", Toast.LENGTH_LONG).show();
            }
            @Override public void onScheduled() {
                setListening(false);
                setKeyboardIdle();
                Toast.makeText(RemoteActivity.this, "La descarga del español quedó programada; prueba en unos minutos",
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onError(int error) {
                setListening(false);
                setKeyboardIdle();
                Toast.makeText(RemoteActivity.this, "No se pudo descargar el español (" + error + ")",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setListening(boolean on) {
        listening = on;
        if (micLabel == null) return;
        micLabel.setText(on ? "Escuchando…" : "Hablar");
        micBg.setColor(on ? 0x5522D3EE : 0x1AFFFFFF);
        if (!on) micBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {
            if (!listening) return;
            kbStatus.setText("Te escucho… di lo que quieres buscar");
            kbStatus.setTextColor(ACCENT);
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) {
            float s = 1f + Math.max(0f, Math.min(rms, 10f)) / 60f;
            micBtn.setScaleX(s);
            micBtn.setScaleY(s);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { micLabel.setText("Procesando…"); }
        @Override public void onError(int error) {
            setListening(false);
            setKeyboardIdle();
            String msg;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                msg = "No te escuché, intenta de nuevo";
            } else if (error == 12 || error == 13) { // idioma no soportado / no descargado
                prefs.edit().remove("voiceLang").apply();
                msg = "Falta el español para la voz: toca Hablar otra vez para descargarlo";
            } else {
                msg = "Error de voz (" + error + ")";
            }
            Toast.makeText(RemoteActivity.this, msg, Toast.LENGTH_LONG).show();
        }
        @Override public void onResults(Bundle b) {
            setListening(false);
            setKeyboardIdle();
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) return;
            input.setText(r.get(0));
            enter();
        }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) {
                silent = true;
                input.setText(r.get(0));
                silent = false;
            }
        }
        @Override public void onEvent(int type, Bundle b) { }
    };

    // ---------- conexión con la TV ----------

    private String host() {
        return Tv.host(this);
    }

    private final Adb.Listener adbListener = new Adb.Listener() {
        @Override public void onNeedsApproval() {
            ui.post(() -> setStatus("En la TV elige «Permitir siempre»", ACCENT));
        }
        @Override public void onDisconnected() {
            keys = null;
            ui.post(() -> setStatus("Reconectando…", DIM));
        }
    };

    private void ensureConnected() throws Exception {
        lock.lock();
        try {
            Adb a = adb;
            if (a != null && a.alive()) {
                Adb.Stream k = keys;
                if (k == null || k.closed) startKeys();
                return;
            }
            adb = null;
            keys = null;
            adb = Adb.connect(host(), 5555, CONNECT_TIMEOUT_MS, Tv.keyPair(this), adbListener);
            rememberMac();
            ensureHelper();
            startKeys();
            appsDirty = true;
            ui.post(() -> setStatus("Conectada", GREEN));
        } finally {
            lock.unlock();
        }
    }

    private void fail() {
        Adb a = adb;
        adb = null;
        keys = null;
        if (a != null) a.close();
        ui.post(() -> setStatus("Sin conexión con la TV", RED));
    }

    /** Sube a la TV el ayudante de teclas (una sola vez; queda en /data/local/tmp). */
    private void ensureHelper() throws Exception {
        byte[] dex;
        try (InputStream in = getAssets().open("neokeys.dex")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            dex = out.toByteArray();
        }
        String size = adb.exec("stat -c %s " + HELPER + " 2>/dev/null", 3000).trim();
        if (!size.equals(String.valueOf(dex.length))) push(HELPER, dex);
    }

    /** Escribe un archivo en la TV pasándolo en base64 por partes (los comandos de shell tienen largo máximo). */
    private void push(String path, byte[] data) throws Exception {
        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        adb.exec("rm -f " + path + ".b64", 3000);
        for (int i = 0; i < b64.length(); i += 3000)
            adb.exec("echo -n '" + b64.substring(i, Math.min(b64.length(), i + 3000)) + "' >> " + path + ".b64", 3000);
        adb.exec("base64 -d " + path + ".b64 > " + path + " && rm " + path + ".b64", 3000);
    }

    /**
     * Respaldo de NEO: guarda en la tablet la configuración que publica NEO en la TV.
     * Si NEO se reinstaló (todavía sin configurar), le deja el respaldo para que lo aplique.
     */
    private void syncNeoBackup() throws Exception {
        String bk = adb.exec("cat " + EXPORT + "/backup.txt 2>/dev/null", 4000);
        if (bk.trim().isEmpty()) return;
        if (bk.contains("configured\tb\ttrue")) {
            if (!bk.equals(prefs.getString("neoBackup", ""))) prefs.edit().putString("neoBackup", bk).apply();
            return;
        }
        String saved = prefs.getString("neoBackup", null);
        if (saved == null) return;
        if (!adb.exec("ls " + EXPORT + "/restore.txt 2>/dev/null", 3000).trim().isEmpty()) return;
        push(EXPORT + "/restore.txt", saved.getBytes("UTF-8"));
        ui.post(() -> Toast.makeText(this, "Le devolví a NEO tu configuración; se aplica al volver al inicio de la TV",
                Toast.LENGTH_LONG).show());
    }

    private void startKeys() throws Exception {
        Adb.Stream s = adb.open("shell:CLASSPATH=" + HELPER + " app_process / com.gonzalo.neoremote.KeyServer");
        StringBuilder out = new StringBuilder();
        long end = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < end && out.indexOf("ready") < 0) {
            byte[] b = s.read(500);
            if (b == null) continue;
            if (b.length == 0) throw new IOException("El ayudante de la TV se cerró");
            out.append(new String(b, "UTF-8"));
        }
        if (out.indexOf("ready") < 0) throw new IOException("El ayudante de la TV no respondió");
        keys = s;
        pointerReady = false; // ayudante nuevo: hay que volver a decirle el tamaño de pantalla
    }

    // ---------- puntero ----------

    private final Object pointerLock = new Object();
    private float pendDx, pendDy;
    private boolean flushQueued;
    private volatile boolean pointerReady;

    /** Junta los movimientos mientras el anterior viaja a la TV, así nunca se acumula retraso. */
    private void pointerMove(float dx, float dy) {
        synchronized (pointerLock) {
            pendDx += dx;
            pendDy += dy;
            if (flushQueued) return;
            flushQueued = true;
        }
        io.post(flushPointer);
    }

    private final Runnable flushPointer = () -> {
        float dx, dy;
        synchronized (pointerLock) {
            dx = pendDx;
            dy = pendDy;
            pendDx = pendDy = 0;
            flushQueued = false;
        }
        try {
            ensureConnected();
            ensurePointer();
            keys.write(String.format(java.util.Locale.US, "p %.1f %.1f\n", dx, dy));
        } catch (Exception e) {
            fail();
        }
    };

    private void pointerCmd(final String cmd) {
        io.post(() -> {
            try {
                ensureConnected();
                if (!cmd.equals("h")) ensurePointer();
                keys.write(cmd + "\n");
            } catch (Exception e) {
                fail();
            }
        });
    }

    /** Prepara la TV: permiso de superposición para NEO, servicio que dibuja la flecha y tamaño de pantalla. */
    private void ensurePointer() throws Exception {
        if (pointerReady) return;
        String o = adb.exec("appops set com.gonzalo.neolauncher SYSTEM_ALERT_WINDOW allow; "
                + "cmd activity start-foreground-service -n com.gonzalo.neolauncher/.CursorService >/dev/null 2>&1; "
                + "wm size", 5000);
        int w = 1920, h = 1080;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(o);
        while (m.find()) {
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        }
        keys.write("P " + w + " " + h + "\n");
        pointerReady = true;
    }

    private void rememberMac() {
        if (!prefs.contains("serial")) {
            try {
                prefs.edit().putString("serial", adb.exec("getprop ro.serialno", 3000).trim()).apply();
            } catch (Exception ignored) { /* se intenta en la próxima conexión */ }
        }
        if (prefs.contains("mac")) return;
        try {
            String o = adb.exec("ip link show wlan0 2>/dev/null; ip link show eth0 2>/dev/null", 3000);
            int i = o.indexOf("link/ether ");
            if (i >= 0) prefs.edit().putString("mac", o.substring(i + 11, i + 28)).apply();
        } catch (Exception ignored) { /* sin MAC no hay Wake-on-LAN */ }
    }

    private void line(final String l) {
        io.post(() -> {
            try {
                ensureConnected();
                keys.write(l + "\n");
            } catch (Exception e) {
                fail();
            }
        });
    }

    private void key(int code) {
        line("k " + code);
    }

    /**
     * Encender/apagar sin esperas: usa el estado que ya conoce (no consulta a la TV),
     * y si la conexión se cortó manda Wake-on-LAN al instante y reintenta cada 0,3 s.
     */
    private void power() {
        final boolean turnOff = Boolean.TRUE.equals(tvAwake);
        powerLabel.setText(turnOff ? "Apagando…" : "Encendiendo…");
        io.post(() -> {
            try {
                Adb a = adb;
                if (a == null || !a.alive()) {
                    if (!turnOff) Tv.wakeOnLan(this);
                    long end = System.currentTimeMillis() + 20000;
                    int tries = 0;
                    while (true) {
                        try {
                            ensureConnected();
                            break;
                        } catch (Exception e) {
                            if (System.currentTimeMillis() > end) throw e;
                            if (!turnOff && ++tries % 3 == 0) Tv.wakeOnLan(this);
                            Thread.sleep(300);
                        }
                    }
                } else {
                    ensureConnected();
                }
                keys.write(turnOff ? "k 223\n" : "k 224\n");
                ui.post(() -> onTvState(imeShown, !turnOff));
                poll.removeCallbacks(pollTask);
                poll.postDelayed(pollTask, 800);
            } catch (Exception e) {
                fail();
                ui.post(() -> powerLabel.setText("No responde"));
            }
        });
    }

    private void openApp(final String pkg, final String name) {
        // el nombre viene de un archivo de la TV y va a la consola: solo letras, números, punto y guion bajo
        if (!pkg.matches("[A-Za-z0-9_.]+")) return;
        Toast.makeText(this, "Abriendo " + name + " en la TV", Toast.LENGTH_SHORT).show();
        io.post(() -> {
            try {
                ensureConnected();
                adb.exec("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1", 8000);
            } catch (Exception e) {
                fail();
            }
        });
    }

    /**
     * Borra la caché de todas las apps de la TV (Android la vuelve a crear sola; no toca datos ni sesiones).
     * Va en su propio hilo para no frenar las teclas mientras la TV trabaja.
     */
    private void cleanCache() {
        if (!cleanLabel.isEnabled()) return; // ya está limpiando
        cleanLabel.setEnabled(false);
        cleanLabel.setText("Limpiando…");
        new Thread(() -> {
            String msg;
            try {
                ensureConnected();
                String o = adb.exec("df /data | tail -1; pm trim-caches 999G; df /data | tail -1", 60000);
                String[] l = o.trim().split("\n");
                long before = freeKb(l[0]), after = freeKb(l[l.length - 1]);
                long mb = Math.max(0, after - before) / 1024;
                String free = String.format(java.util.Locale.US, "la TV tiene %.1f GB libres", after / 1048576f);
                msg = mb > 0 ? "Listo: liberé " + mb + " MB · " + free : "La caché ya estaba limpia · " + free;
            } catch (Exception e) {
                android.util.Log.w("NeoRemote", "limpiar caché", e);
                msg = "No pude limpiar: la TV no responde";
            }
            final String m = msg;
            android.util.Log.i("NeoRemote", m);
            ui.post(() -> {
                cleanLabel.setText("Limpiar");
                cleanLabel.setEnabled(true);
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        }, "clean").start();
    }

    /** Columna "Available" (KB) de una línea de df. */
    private static long freeKb(String dfLine) {
        String[] p = dfLine.trim().split("\\s+");
        return p.length >= 4 ? Long.parseLong(p[3]) : 0;
    }

    /** Estado de la TV: cada 0,8 s si está encendida, más lento si duerme o no conecta. */
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long next = 800;
            if (lock.isLocked()) {
                next = 400; // el botón de encender está conectando: no competir con él
            } else if (host().isEmpty()) {
                next = 1500; // todavía no se sabe dónde está la TV: la está buscando scan()
            } else {
                try {
                    ensureConnected();
                    String o = adb.exec("dumpsys input_method | grep -m1 -o 'mInputShown=[a-z]*'; "
                            + "dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'", 4000);
                    final boolean ime = o.contains("mInputShown=true");
                    final boolean awake = o.contains("Awake");
                    ui.post(() -> onTvState(ime, awake));
                    if (!awake) next = 2000;
                    if (appsDirty || ++pollCount % 75 == 0) loadApps();
                    failStreak = 0;
                } catch (Exception e) {
                    fail();
                    next = 1500;
                    // ~6 s sin respuesta: puede que el router le haya dado otra IP a la TV
                    long now = System.currentTimeMillis();
                    if (++failStreak >= 4 && now - lastFind > 120000) {
                        lastFind = now;
                        ui.post(() -> setStatus("Buscando la TV en la red…", DIM));
                        final String ip = Tv.find(RemoteActivity.this, Tv.openPorts());
                        if (ip != null) {
                            ui.post(() -> setStatus("TV encontrada en " + ip, GREEN));
                            next = 100;
                        } else {
                            ui.post(() -> setStatus("Sin conexión con la TV", RED));
                        }
                    }
                }
            }
            if (resumed) poll.postDelayed(this, next);
        }
    };

    // ---------- ajustes ----------

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText ip = new EditText(this);
        ip.setText(host());
        ip.setSingleLine(true);
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(ip);
        TextView info = tv("La TV necesita «Depuración por red» activada en Opciones de desarrollador. "
                + "La primera vez, acepta el aviso que aparece en la TV.", 13, DIM, "sans-serif");
        box.addView(info, lin(-1, -2, 0, 8, 0, 0));
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("IP de la TV")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    prefs.edit().putString("host", ip.getText().toString().trim()).remove("mac").remove("serial").apply();
                    io.post(this::fail);
                })
                .setNeutralButton("Buscar TV", (d, w) -> scan())
                .setNegativeButton("Cancelar", null)
                .create();
        box.addView(chipBtn("Optimizar TV", true, () -> { dlg.dismiss(); optimize(); }), lin(-1, 48, 0, 16, 0, 4));
        dlg.show();
    }

    /**
     * Busca en la red local la TV ya autorizada; si no hay ninguna, toma el primer equipo
     * con el puerto 5555 (ADB) abierto (una TV nueva, que pedirá permiso en pantalla).
     */
    private volatile boolean scanning;

    private void scan() {
        scanning = true;
        setStatus("Buscando la TV en la red…", DIM);
        new Thread(() -> {
            List<String> found = Tv.openPorts();
            String known = Tv.find(this, found);
            final String ip = known != null ? known : found.isEmpty() ? null : found.get(0);
            if (known == null && ip != null)
                prefs.edit().putString("host", ip).remove("mac").remove("serial").apply();
            scanning = false;
            ui.post(() -> {
                if (ip == null) {
                    Toast.makeText(this, "No encontré la TV. Revisa que esté en la misma red.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "TV encontrada: " + ip, Toast.LENGTH_LONG).show();
                    io.post(this::fail);
                }
            });
        }, "scan").start();
    }

    // ---------- optimizar la TV (el trabajo lo hace optimizer/optimizar.sh dentro de la TV) ----------

    private static final class Opt {
        String pkg, group, state, what;
        boolean recommended;
    }

    /** Corre el optimizador en la TV (lo sube si cambió) y devuelve solo las líneas con datos. */
    private String[] optimizer(String args, long timeoutMs) throws Exception {
        ensureConnected();
        byte[] sh;
        try (InputStream in = getAssets().open("optimizar.sh")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            sh = out.toByteArray();
        }
        String size = adb.exec("stat -c %s " + OPTIMIZER + " 2>/dev/null", 3000).trim();
        if (!size.equals(String.valueOf(sh.length))) push(OPTIMIZER, sh);
        List<String> rows = new ArrayList<String>();
        for (String l : adb.exec("sh " + OPTIMIZER + " " + args + " 2>/dev/null", timeoutMs).split("\n"))
            if (l.contains("|")) rows.add(l.trim());
        return rows.toArray(new String[0]);
    }

    private void optimize() {
        Toast.makeText(this, "Revisando la TV…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final List<Opt> list = new ArrayList<Opt>();
                for (String l : optimizer("lista", 15000)) {
                    String[] p = l.split("\\|", 5);
                    if (p.length < 5) continue;
                    Opt o = new Opt();
                    o.pkg = p[0];
                    o.group = p[1];
                    o.recommended = p[2].equals("1");
                    o.state = p[3];
                    o.what = p[4];
                    list.add(o);
                }
                ui.post(() -> showOptimizer(list));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "No pude hablar con la TV", Toast.LENGTH_LONG).show());
            }
        }, "optimize").start();
    }

    private void showOptimizer(List<Opt> all) {
        final List<Opt> active = new ArrayList<Opt>();
        int done = 0;
        for (Opt o : all) {
            if (o.state.equals("activa")) active.add(o);
            else if (o.state.equals("desactivada-por-neo")) done++;
        }
        String[] labels = new String[active.size()];
        final boolean[] checked = new boolean[active.size()];
        for (int i = 0; i < active.size(); i++) {
            Opt o = active.get(i);
            labels[i] = o.what;
            checked[i] = o.recommended;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(active.isEmpty() ? "Tu TV ya está optimizada" : "Apps que tu TV no necesita");
        if (active.isEmpty()) {
            b.setMessage("No queda nada por desactivar. Igual puedo limpiar la caché y acelerar las animaciones.");
        } else {
            b.setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on);
        }
        b.setPositiveButton(active.isEmpty() ? "Limpiar" : "Optimizar", (d, w) -> {
            StringBuilder pkgs = new StringBuilder();
            for (int i = 0; i < active.size(); i++) if (checked[i]) pkgs.append(' ').append(active.get(i).pkg);
            // "ninguna" no está en el catálogo: así "aplicar" no toma las recomendadas por defecto
            runOptimizer("aplicar" + (pkgs.length() == 0 ? " ninguna" : pkgs.toString()));
        });
        if (done > 0) b.setNeutralButton("Deshacer todo", (d, w) -> confirmRevert());
        b.setNegativeButton("Cancelar", null).show();
    }

    private void confirmRevert() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("¿Deshacer la optimización?")
                .setMessage("Vuelvo a activar las apps que desactivó NEO y dejo las animaciones como estaban.")
                .setPositiveButton("Deshacer", (d, w) -> runOptimizer("revertir"))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void runOptimizer(final String args) {
        Toast.makeText(this, "Trabajando en la TV…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int ok = 0, failed = 0;
                String cache = null;
                for (String l : optimizer(args, 90000)) {
                    String[] p = l.split("\\|");
                    if (p[0].equals("OK")) ok++;
                    else if (p[0].equals("FALLO")) failed++;
                    else if (p[0].equals("CACHE") && p.length > 2) cache = "caché +" + p[1] + " MB (" + p[2] + " MB libres)";
                }
                if (args.equals("revertir")) {
                    msg = "Listo: reactivé " + ok + " apps y devolví las animaciones.";
                } else {
                    msg = "Listo: " + ok + " apps desactivadas, animaciones más rápidas"
                            + (cache != null ? ", " + cache : "") + "."
                            + (failed > 0 ? " " + failed + " no se dejaron desactivar." : "");
                }
            } catch (Exception e) {
                android.util.Log.w("NeoRemote", "optimizar", e);
                msg = "No pude terminar: la TV dejó de responder";
            }
            final String m = msg;
            ui.post(() -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Optimizar TV").setMessage(m).setPositiveButton("OK", null).show());
        }, "optimize").start();
    }
}
