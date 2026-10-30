package com.gonzalo.neolauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pantalla de inicio estilo Apple TV: reloj y botones arriba, el nombre de la app enfocada en grande,
 * un dock de 4 favoritas en una banda y el resto en una cuadrícula que sube al bajar.
 */
public class HomeActivity extends Activity {

    private static final int WHITE = 0xFFFFFFFF, GRAY = 0x99FFFFFF, FAINT = 0x4DFFFFFF;
    private static final int DOCK_MAX = 4, COLS = 4;
    // medidas en dp (pantalla de 960x540 dp)
    private static final int TILE_W = 180, TILE_H = 100, GAP = 32, PAD = 14;
    private static final int STAGE_TOP = 150, TITLE_TOP = 92, DOCK_TOP = 199, GRID_TOP = 371;
    private static final int ROW_H = 142, SHIFT0 = 153;
    private static final long SAVER_DELAY_MS = 3 * 60 * 1000L;
    private static final String[] WALL_NAMES = { "Negro", "Portal", "Rejilla", "Dorado", "Radar", "Orbe", "Circuitos" };
    private static final String[] DEFAULT_DOCK = { "youtube", "netflix", "disney", "twitch" };
    private static final Locale ES = new Locale("es");

    private static final class App {
        String pkg, label;
        ComponentName cn;
        ResolveInfo ri;
        int uses;
    }

    private interface Pick { void on(int i); }

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private FrameLayout root, page, content, panel, saver;
    private GlowView glow;
    private TextView greeting, clock, ampm, date, stats, title, hint, netLabel, saverClock, saverDate;
    private LinearLayout dockBand, gridBox, saverBox;
    private Glyph netGlyph;
    private View panelPrevFocus;
    private final List<App> dock = new ArrayList<App>();
    private final List<App> grid = new ArrayList<App>();
    private final List<App> hiddenApps = new ArrayList<App>();
    private final Map<String, View> tiles = new HashMap<String, View>();
    private long hoverFocusAt;
    private final Map<String, Integer> glowCache = new HashMap<String, Integer>();
    // concurrentes: también los usa el hilo que exporta las apps para NEO Remote
    private final Map<String, Drawable.ConstantState> bannerCache = new ConcurrentHashMap<String, Drawable.ConstantState>();
    private final Map<String, Drawable.ConstantState> iconCache = new ConcurrentHashMap<String, Drawable.ConstantState>();
    private String lastSig = "", restorePkg, movingPkg;
    private boolean saverOn, started;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 20000);
        }
    };

    // ---------- ciclo de vida ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("neo_home", MODE_PRIVATE);
        // instalaciones anteriores al respaldo: lo que ya tenían cuenta como configurado
        if (prefs.contains("dock") && !prefs.contains("configured")) prefs.edit().putBoolean("configured", true).apply();
        buildUi();
        applyWallpaper();
        WebRemoteService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean restored = maybeRestore();
        if (reload() || restored) rebuildTiles();
        updateNet();
        handler.removeCallbacks(tick);
        tick.run();
        armSaver();
        View f = getCurrentFocus();
        if (!started || f == null || !f.isShown()) restoreFocus();
        started = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        View f = getCurrentFocus();
        restorePkg = f != null && f.getTag() instanceof String ? (String) f.getTag() : restorePkg;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(saverStart);
        hideSaver();
        closePanel(false);
        stopMove();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closePanel(false);
        stopMove();
        if (maybeRestore() && reload()) rebuildTiles();
        restorePkg = null;
        restoreFocus();
    }

    /**
     * Si NEO Remote dejó un respaldo (porque NEO se reinstaló y perdió su configuración),
     * lo aplica una sola vez y lo borra.
     */
    private boolean maybeRestore() {
        File dir = getExternalFilesDir(null);
        File f = dir != null ? new File(dir, "restore.txt") : null;
        if (f == null || !f.exists()) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            SharedPreferences.Editor e = prefs.edit();
            String l;
            while ((l = r.readLine()) != null) {
                String[] p = l.split("\t", 3);
                if (p.length < 3) continue;
                switch (p[1]) {
                    case "i": e.putInt(p[0], Integer.parseInt(p[2])); break;
                    case "b": e.putBoolean(p[0], Boolean.parseBoolean(p[2])); break;
                    case "S": e.putStringSet(p[0], new HashSet<String>(
                            p[2].isEmpty() ? new ArrayList<String>() : Arrays.asList(p[2].split(",")))); break;
                    default: e.putString(p[0], p[2]); break;
                }
            }
            e.putBoolean("configured", true).apply();
        } catch (Exception ignored) { /* respaldo dañado: se ignora */ }
        f.delete();
        lastSig = "";
        applyWallpaper();
        stats.setVisibility(prefs.getBoolean("stats", false) ? View.VISIBLE : View.GONE);
        Toast.makeText(this, "Configuración de NEO restaurada", Toast.LENGTH_LONG).show();
        return true;
    }

    /** Toda la configuración de NEO como texto: clave, tipo y valor por línea. */
    private String backupText() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
            Object v = en.getValue();
            String type;
            if (v instanceof Integer) type = "i";
            else if (v instanceof Boolean) type = "b";
            else if (v instanceof Set) { type = "S"; v = TextUtils.join(",", (Set<?>) v); }
            else if (v instanceof String) type = "s";
            else continue;
            b.append(en.getKey()).append('\t').append(type).append('\t').append(v).append('\n');
        }
        return b.toString();
    }

    /** Marca que el usuario ya personalizó NEO: desde ahí la tablet guarda su respaldo. */
    private void markConfigured() {
        if (!prefs.getBoolean("configured", false)) prefs.edit().putBoolean("configured", true).apply();
    }

    @Override
    public void onBackPressed() {
        if (panel != null) { closePanel(true); return; }
        if (content.getTranslationY() != 0) { restorePkg = null; restoreFocus(); }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { hideSaver(); armSaver(); }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) armSaver();
        if (movingPkg != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) moveBy(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) moveBy(1);
                else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_BACK) stopMove();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- utilidades ----------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static Typeface font(String family) {
        return Typeface.create(family, Typeface.NORMAL);
    }

    private TextView tv(String s, int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
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

    private LinearLayout.LayoutParams lin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private FrameLayout.LayoutParams flp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h), gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout box(int orientation) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    // ---------- interfaz ----------

    private void buildUi() {
        root = new FrameLayout(this);
        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // reloj, saludo y fecha
        LinearLayout left = box(LinearLayout.VERTICAL);
        greeting = tv("", 11, GRAY, font("sans-serif-medium"));
        greeting.setLetterSpacing(0.22f);
        left.addView(greeting);
        LinearLayout timeRow = box(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        clock = tv("", 54, WHITE, font("sans-serif-light"));
        ampm = tv("", 15, GRAY, font("sans-serif-medium"));
        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        date = tv("", 13, GRAY, font("sans-serif-medium"));
        date.setLetterSpacing(0.18f);
        timeRow.addView(clock);
        timeRow.addView(ampm, lin(-2, -2, 6, 14, 0, 0));
        timeRow.addView(divider, lin(1, 30, 18, 6, 18, 0));
        timeRow.addView(date, lin(-2, -2, 0, 6, 0, 0));
        left.addView(timeRow, lin(-2, -2, 0, 4, 0, 0));
        stats = tv("", 12, FAINT, font("sans-serif"));
        stats.setVisibility(prefs.getBoolean("stats", false) ? View.VISIBLE : View.GONE);
        left.addView(stats, lin(-2, -2, 2, 8, 0, 0));
        page.addView(left, flp(-2, -2, Gravity.TOP | Gravity.START, 56, 26, 0, 0));

        // botones
        LinearLayout btns = box(LinearLayout.HORIZONTAL);
        btns.addView(pill(new Glyph(Glyph.INPUT), "Entradas", v -> showInputs(), null), lin(-2, -2, 0, 0, 10, 0));
        netGlyph = new Glyph(Glyph.WIFI);
        View net = pill(netGlyph, "Wi-Fi", v -> openSettings(Settings.ACTION_WIFI_SETTINGS), null);
        netLabel = (TextView) net.getTag();
        btns.addView(net, lin(-2, -2, 0, 0, 10, 0));
        btns.addView(pill(new Glyph(Glyph.GEAR), "Ajustes", v -> openSettings(Settings.ACTION_SETTINGS),
                v -> { launcherMenu(0); return true; }));
        page.addView(btns, flp(-2, -2, Gravity.TOP | Gravity.END, 0, 58, 56, 0));

        // escenario que se desplaza: nombre de la app, dock y cuadrícula
        FrameLayout stage = new FrameLayout(this);
        // recorta lo que sube: sin esto, al bajar a la 3.ª fila el dock se monta sobre el reloj
        stage.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        stage.setClipToOutline(true);
        page.addView(stage, flp(-1, -1, Gravity.TOP, 0, STAGE_TOP, 0, 0));
        content = new FrameLayout(this);
        content.setClipChildren(false);
        stage.addView(content, new FrameLayout.LayoutParams(-1, dp(1200)));

        title = tv("", 44, WHITE, font("sans-serif-light"));
        content.addView(title, flp(-2, -2, Gravity.TOP | Gravity.START, 66, TITLE_TOP, 0, 0));

        dockBand = box(LinearLayout.HORIZONTAL);
        dockBand.setGravity(Gravity.CENTER_VERTICAL);
        dockBand.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));
        GradientDrawable band = rect(0xE6151517, 28);
        band.setStroke(dp(1), 0x1FFFFFFF);
        dockBand.setBackground(band);
        content.addView(dockBand, flp(PAD * 2 + TILE_W * DOCK_MAX + GAP * (DOCK_MAX - 1), TILE_H + PAD * 2,
                Gravity.TOP | Gravity.START, 58, DOCK_TOP, 0, 0));

        gridBox = box(LinearLayout.VERTICAL);
        content.addView(gridBox, flp(-2, -2, Gravity.TOP | Gravity.START, 58 + PAD, GRID_TOP, 0, 0));

        hint = tv("", 12, FAINT, font("sans-serif"));
        resetHint();
        page.addView(hint, flp(-2, -2, Gravity.TOP | Gravity.END, 0, 114, 60, 0));

        buildSaver();
        setContentView(root);
    }

    private void resetHint() {
        hint.setText("Mantén OK sobre una app: opciones   ·   sobre Ajustes: opciones de NEO");
    }

    /** Botón tipo píldora con ícono. El TextView queda en el tag para poder cambiar el texto. */
    private View pill(final Glyph glyph, String text, View.OnClickListener click, View.OnLongClickListener longClick) {
        final LinearLayout p = box(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(16), dp(10), dp(20), dp(10));
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF2E2E31, 0xFF19191B });
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setFocusable(true);
        p.setClickable(true);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(glyph);
        p.addView(ic, lin(18, 18, 0, 0, 10, 0));
        final TextView t = tv(text, 15, WHITE, font("sans-serif-medium"));
        p.addView(t);
        p.setTag(t);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColors(has ? new int[] { WHITE, 0xFFE8E8EA } : new int[] { 0xFF2E2E31, 0xFF19191B });
            t.setTextColor(has ? 0xFF111111 : WHITE);
            glyph.setColor(has ? 0xFF111111 : WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        p.setOnClickListener(click);
        if (longClick != null) p.setOnLongClickListener(longClick);
        hoverFocus(p);
        return p;
    }

    /**
     * Puntero de NEO Remote: lo que queda debajo se marca igual que al elegirlo con el control
     * (borde, zoom, nombre). La pausa evita un vaivén: marcar una app de la cuadrícula sube el
     * escenario y deja otra cosa bajo el puntero.
     */
    private void hoverFocus(View v) {
        v.setOnHoverListener((view, e) -> {
            int act = e.getActionMasked();
            if ((act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE)
                    && !view.isFocused() && SystemClock.uptimeMillis() - hoverFocusAt > 350) {
                hoverFocusAt = SystemClock.uptimeMillis();
                view.requestFocusFromTouch();
            }
            return false;
        });
    }

    // ---------- datos ----------

    private Set<String> hiddenSet() {
        return new HashSet<String>(prefs.getStringSet("hidden", new HashSet<String>()));
    }

    private List<String> dockOrder() {
        String s = prefs.getString("dock", null);
        if (s == null) return null;
        List<String> out = new ArrayList<String>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void saveDock(List<String> pkgs) {
        prefs.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    /** Relee apps, dock y ocultas. Devuelve true solo si algo cambió. */
    private boolean reload() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<String, App>();
        List<App> all = new ArrayList<App>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.ri = ri;
            a.cn = new ComponentName(pkg, ri.activityInfo.name);
            a.label = String.valueOf(ri.loadLabel(pm));
            a.uses = prefs.getInt("n:" + pkg, 0);
            byPkg.put(pkg, a);
            all.add(a);
        }
        Collections.sort(all, (x, y) -> x.uses != y.uses ? y.uses - x.uses : x.label.compareToIgnoreCase(y.label));
        Set<String> hidden = hiddenSet();

        List<String> order = dockOrder();
        if (order == null) {
            order = new ArrayList<String>();
            for (String key : DEFAULT_DOCK)
                for (App a : all) if (a.pkg.contains(key) && !order.contains(a.pkg)) { order.add(a.pkg); break; }
            for (App a : all) if (order.size() < DOCK_MAX && !order.contains(a.pkg)) order.add(a.pkg);
            saveDock(order);
        }

        List<App> d = new ArrayList<App>(), g = new ArrayList<App>(), h = new ArrayList<App>();
        for (String p : order) {
            App a = byPkg.get(p);
            if (a != null && !hidden.contains(p) && d.size() < DOCK_MAX) d.add(a);
        }
        for (App a : all) {
            if (hidden.contains(a.pkg)) h.add(a);
            else if (!d.contains(a)) g.add(a);
        }

        StringBuilder sig = new StringBuilder();
        for (App a : d) sig.append(a.pkg).append(':').append(a.uses).append(',');
        sig.append('|');
        for (App a : g) sig.append(a.pkg).append(':').append(a.uses).append(',');
        sig.append('|');
        for (App a : h) sig.append(a.pkg).append(',');
        if (sig.toString().equals(lastSig)) return false;
        lastSig = sig.toString();
        dock.clear(); dock.addAll(d);
        grid.clear(); grid.addAll(g);
        hiddenApps.clear(); hiddenApps.addAll(h);
        return true;
    }

    // ---------- tarjetas ----------

    private void rebuildTiles() {
        tiles.clear();
        dockBand.removeAllViews();
        gridBox.removeAllViews();
        for (int i = 0; i < dock.size(); i++)
            dockBand.addView(tile(dock.get(i), true, 0), lin(TILE_W, TILE_H, 0, 0, i < DOCK_MAX - 1 ? GAP : 0, 0));
        if (dock.isEmpty()) {
            TextView t = tv("Mantén OK sobre una app y elige \"Poner en el dock\"", 15, GRAY, font("sans-serif"));
            dockBand.addView(t, lin(-2, -2, 12, 0, 0, 0));
        }
        LinearLayout row = null;
        for (int i = 0; i < grid.size(); i++) {
            if (i % COLS == 0) {
                row = box(LinearLayout.HORIZONTAL);
                gridBox.addView(row);
            }
            row.addView(tile(grid.get(i), false, i / COLS),
                    lin(TILE_W, ROW_H, 0, 0, i % COLS < COLS - 1 ? GAP : 0, 0));
        }
        int rows = (grid.size() + COLS - 1) / COLS;
        content.getLayoutParams().height = dp(GRID_TOP + rows * ROW_H + 200);
        content.requestLayout();
        exportForRemote();
    }

    /**
     * Deja la lista de apps (en el orden de NEO) y sus banners en la carpeta externa de la app,
     * donde NEO Remote los lee por ADB para mostrar en la tablet las mismas apps que hay en la TV.
     */
    private void exportForRemote() {
        final List<App> list = new ArrayList<App>(dock);
        list.addAll(grid);
        final File dir = getExternalFilesDir(null);
        if (dir == null) return;
        final String backup = backupText();
        new Thread(() -> {
            try {
                try (FileOutputStream o = new FileOutputStream(new File(dir, "backup.txt"))) {
                    o.write(backup.getBytes("UTF-8"));
                }
                File banners = new File(dir, "banners");
                banners.mkdirs();
                PackageManager pm = getPackageManager();
                StringBuilder sb = new StringBuilder();
                for (App a : list) {
                    long stamp = 0;
                    try { stamp = pm.getPackageInfo(a.pkg, 0).lastUpdateTime; } catch (Exception ignored) { /* sin fecha */ }
                    String name = a.pkg + "_" + stamp + ".jpg";
                    File f = new File(banners, name);
                    if (!f.exists()) writeBanner(a, f);
                    sb.append(a.pkg).append('\t').append(a.label.replace('\t', ' ')).append('\t').append(name).append('\n');
                }
                File tmp = new File(dir, "apps.tsv.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes("UTF-8")); }
                tmp.renameTo(new File(dir, "apps.tsv"));
            } catch (Exception ignored) { /* la tablet usa la lista básica */ }
        }, "neo-export").start();
    }

    private void writeBanner(App a, File f) throws IOException {
        Bitmap bm = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFF1C1C22);
        Drawable d = bannerOf(a);
        if (d != null) {
            d.setBounds(0, 0, 320, 180);
            d.draw(c);
        } else {
            Drawable ic = iconOf(a);
            if (ic != null) {
                ic.setBounds(112, 42, 208, 138);
                ic.draw(c);
            }
        }
        try (FileOutputStream o = new FileOutputStream(f)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 88, o);
        }
        bm.recycle();
    }

    private View tile(final App a, final boolean inDock, final int row) {
        LinearLayout wrap = box(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        final FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        holder.setFocusable(true);
        holder.setClickable(true);
        holder.setTag(a.pkg);

        final View halo = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0);
        hg.setCornerRadius(dp(17));
        hg.setStroke(dp(4), 0x40FFFFFF);
        halo.setBackground(hg);
        halo.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -1);
        hp.setMargins(-dp(5), -dp(5), -dp(5), -dp(5));
        holder.addView(halo, hp);

        FrameLayout card = new FrameLayout(this);
        Drawable banner = bannerOf(a);
        if (banner != null) {
            card.setBackgroundColor(0xFF1C1C1E);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] { 0xFF2C2C30, 0xFF151518 }));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(iconOf(a));
            card.addView(iv, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        }
        round(card, 12);
        final GradientDrawable border = new GradientDrawable();
        border.setColor(0);
        border.setCornerRadius(dp(12));
        border.setStroke(dp(2), 0xE6FFFFFF);
        border.setAlpha(0);
        card.setForeground(border);
        holder.addView(card, new FrameLayout.LayoutParams(-1, -1));
        wrap.addView(holder, lin(TILE_W, TILE_H, 0, 0, 0, 0));

        final TextView name = tv(a.label, 14, WHITE, font("sans-serif-medium"));
        name.setGravity(Gravity.CENTER);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setVisibility(View.INVISIBLE);
        if (!inDock) wrap.addView(name, lin(TILE_W, -2, 0, 14, 0, 0));

        holder.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                    .setDuration(has ? 280 : 160).start();
            halo.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            border.setAlpha(has ? 255 : 0);
            if (!inDock) name.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (has) onTileFocus(a, inDock, row);
        });
        holder.setOnClickListener(v -> launch(a));
        holder.setOnLongClickListener(v -> { tileMenu(a, inDock); return true; });
        hoverFocus(holder);
        tiles.put(a.pkg, holder);
        return wrap;
    }

    /** Dock: muestra el nombre grande y el brillo. Cuadrícula: sube el escenario y apaga el brillo. */
    private void onTileFocus(App a, boolean inDock, int row) {
        float ty = inDock ? 0 : -dp(SHIFT0 + row * ROW_H);
        if (content.getTranslationY() != ty)
            content.animate().translationY(ty).setInterpolator(new DecelerateInterpolator(2.2f)).setDuration(340).start();
        if (inDock) {
            if (movingPkg == null) title.setText(a.label);
            title.animate().alpha(1f).setDuration(200).start();
            glow.glowTo(glowOf(a));
        } else {
            title.animate().alpha(0f).setDuration(150).start();
            glow.glowTo(0);
        }
    }

    private void restoreFocus() {
        View v = restorePkg != null ? tiles.get(restorePkg) : null;
        if (v == null && !dock.isEmpty()) v = tiles.get(dock.get(0).pkg);
        if (v == null && !grid.isEmpty()) v = tiles.get(grid.get(0).pkg);
        if (v == null) return;
        final View target = v;
        target.requestFocus();
        target.post(() -> target.requestFocus());
    }

    private Drawable bannerOf(App a) {
        Drawable.ConstantState cs = bannerCache.get(a.pkg);
        if (cs != null) return cs.newDrawable(getResources());
        Drawable d = a.ri.activityInfo.loadBanner(getPackageManager());
        if (d != null && d.getConstantState() != null) bannerCache.put(a.pkg, d.getConstantState());
        return d;
    }

    private Drawable iconOf(App a) {
        Drawable.ConstantState cs = iconCache.get(a.pkg);
        if (cs != null) return cs.newDrawable(getResources());
        Drawable d = a.ri.loadIcon(getPackageManager());
        if (d != null && d.getConstantState() != null) iconCache.put(a.pkg, d.getConstantState());
        return d;
    }

    /** Color del brillo: promedio de los píxeles más saturados del banner, oscurecido. */
    private int glowOf(App a) {
        Integer cached = glowCache.get(a.pkg);
        if (cached != null) return cached;
        int col = 0xFF3A3A44;
        try {
            Drawable d = bannerOf(a);
            if (d == null) d = iconOf(a);
            Bitmap bm = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 18);
            d.draw(new Canvas(bm));
            float r = 0, g = 0, b = 0, w = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float wt = hsv[1] * hsv[2];
                    wt *= wt;
                    r += Color.red(p) * wt;
                    g += Color.green(p) * wt;
                    b += Color.blue(p) * wt;
                    w += wt;
                }
            }
            bm.recycle();
            if (w > 0.5f) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                hsv[1] = Math.max(hsv[1], 0.75f);
                hsv[2] = 0.6f;
                col = Color.HSVToColor(hsv);
            }
        } catch (Exception ignored) { /* se usa el gris neutro */ }
        glowCache.put(a.pkg, col);
        return col;
    }

    private void launch(App a) {
        try {
            prefs.edit().putInt("n:" + a.pkg, a.uses + 1).putBoolean("configured", true).apply();
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { /* sin ajustes */ }
        }
    }

    /** Aplica un cambio de dock/ocultas y deja el foco sobre la app indicada. */
    private void apply(String focusPkg) {
        markConfigured();
        if (reload()) rebuildTiles();
        restorePkg = focusPkg;
        restoreFocus();
    }

    // ---------- menús ----------

    private void tileMenu(final App a, final boolean inDock) {
        List<String[]> rows = new ArrayList<String[]>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        final List<String> order = dockOrder() != null ? dockOrder() : new ArrayList<String>();
        if (inDock) {
            rows.add(new String[] { "Mover", "Izquierda / derecha para cambiar de lugar" });
            acts.add(() -> startMove(a));
            rows.add(new String[] { "Quitar del dock", "Pasa a la cuadrícula de abajo" });
            acts.add(() -> { order.remove(a.pkg); saveDock(order); apply(a.pkg); });
        } else {
            String sub = dock.size() >= DOCK_MAX ? "Reemplaza a " + dock.get(DOCK_MAX - 1).label : "Queda arriba, en la banda";
            rows.add(new String[] { "Poner en el dock", sub });
            acts.add(() -> {
                List<String> o = new ArrayList<String>();
                for (App d : dock) o.add(d.pkg);
                if (o.size() >= DOCK_MAX) o.remove(DOCK_MAX - 1);
                o.add(a.pkg);
                saveDock(o);
                apply(a.pkg);
            });
            rows.add(new String[] { "Ocultar del inicio", "Se recupera manteniendo OK en Ajustes" });
            acts.add(() -> {
                Set<String> h = hiddenSet();
                h.add(a.pkg);
                prefs.edit().putStringSet("hidden", h).apply();
                apply(null);
            });
        }
        rows.add(new String[] { "Información de la app", "Permisos, almacenamiento, forzar cierre" });
        acts.add(() -> openSettingsUri(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:" + a.pkg));
        showMenu(a.label, rows, 0, i -> acts.get(i).run());
    }

    private void openSettingsUri(String action, String uri) {
        try {
            startActivity(new Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "No disponible en esta TV", Toast.LENGTH_SHORT).show();
        }
    }

    private void launcherMenu(int focusIdx) {
        List<String[]> rows = new ArrayList<String[]>();
        int wall = prefs.getInt("wall", 1);
        boolean st = prefs.getBoolean("stats", false);
        rows.add(new String[] { "Fondo", WALL_NAMES[wall] + "   ·   OK para cambiar" });
        rows.add(new String[] { "Datos bajo el reloj", st ? "Activados" : "Desactivados" });
        rows.add(new String[] { "Apps ocultas", hiddenApps.isEmpty() ? "Ninguna" : hiddenApps.size() + " oculta(s)" });
        rows.add(new String[] { "Abrir NEO clásico", "La versión con NEXUS, MÓDULOS y SISTEMA" });
        rows.add(new String[] { "Cambiar launcher", "Elegir la pantalla de inicio de la TV" });
        rows.add(new String[] { "Olvidar celulares", "Los celulares con NEO Remote web tendrán que emparejarse otra vez" });
        showMenu("Opciones de NEO", rows, focusIdx, i -> {
            if (i < 2) markConfigured();
            if (i == 0) {
                prefs.edit().putInt("wall", (wall + 1) % WALL_NAMES.length).apply();
                applyWallpaper();
                exportForRemote();
                launcherMenu(0);
            } else if (i == 1) {
                prefs.edit().putBoolean("stats", !st).apply();
                stats.setVisibility(!st ? View.VISIBLE : View.GONE);
                updateClock();
                exportForRemote();
                launcherMenu(1);
            } else if (i == 2) {
                hiddenMenu();
            } else if (i == 3) {
                startActivity(new Intent(this, MainActivity.class));
            } else if (i == 4) {
                openSettings(Settings.ACTION_HOME_SETTINGS);
            } else {
                WebRemoteService.forgetPhones(this);
                Toast.makeText(this, "Listo: ningún celular puede controlar la TV hasta que se empareje de nuevo",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void hiddenMenu() {
        if (hiddenApps.isEmpty()) {
            Toast.makeText(this, "No hay apps ocultas", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<App> list = new ArrayList<App>(hiddenApps);
        List<String[]> rows = new ArrayList<String[]>();
        for (App a : list) rows.add(new String[] { a.label, "OK para mostrarla otra vez" });
        showMenu("Apps ocultas", rows, 0, i -> {
            Set<String> h = hiddenSet();
            h.remove(list.get(i).pkg);
            prefs.edit().putStringSet("hidden", h).apply();
            apply(list.get(i).pkg);
        });
    }

    private void showInputs() {
        final TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        final List<TvInputInfo> list = new ArrayList<TvInputInfo>();
        try {
            if (tim != null) for (TvInputInfo i : tim.getTvInputList()) if (i.isPassthroughInput()) list.add(i);
        } catch (Exception ignored) { /* sin acceso a entradas */ }
        if (list.isEmpty()) {
            Toast.makeText(this, "No se encontraron entradas", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (TvInputInfo info : list) {
            int s = tim.getInputState(info.getId());
            rows.add(new String[] { String.valueOf(info.loadLabel(this)),
                    s == TvInputManager.INPUT_STATE_CONNECTED ? "Conectada"
                            : s == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "Conectada (en espera)" : "Sin cable" });
        }
        showMenu("Entradas", rows, 0, i -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(list.get(i).getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo abrir la entrada", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Menú flotante centrado. Mientras está abierto, el foco no sale de él. */
    private void showMenu(String head, List<String[]> rows, int focusIdx, final Pick pick) {
        View prev = panel != null ? panelPrevFocus : getCurrentFocus();
        closePanel(false);
        panelPrevFocus = prev;
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xB3000000);
        panel.setClickable(true);
        LinearLayout box = box(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(12));
        GradientDrawable bg = rect(0xF51C1C1E, 22);
        bg.setStroke(dp(1), 0x22FFFFFF);
        box.setBackground(bg);
        box.addView(tv(head, 20, WHITE, font("sans-serif-medium")), lin(-2, -2, 6, 0, 0, 14));
        View focus = null;
        for (int i = 0; i < rows.size(); i++) {
            final int k = i;
            View r = menuRow(rows.get(i)[0], rows.get(i)[1], v -> {
                closePanel(true);
                pick.on(k);
            });
            box.addView(r, lin(-1, -2, 0, 0, 0, 8));
            if (i == 0 || i == focusIdx) focus = r;
        }
        panel.addView(box, new FrameLayout.LayoutParams(dp(440), -2, Gravity.CENTER));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        page.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (focus != null) focus.requestFocus();
    }

    private View menuRow(String title, String sub, View.OnClickListener click) {
        LinearLayout r = box(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        final GradientDrawable bg = rect(0x14FFFFFF, 14);
        r.setBackground(bg);
        r.setFocusable(true);
        r.setClickable(true);
        final TextView t = tv(title, 16, WHITE, font("sans-serif-medium"));
        final TextView s = tv(sub, 12, GRAY, font("sans-serif"));
        r.addView(t);
        if (!sub.isEmpty()) r.addView(s, lin(-2, -2, 0, 4, 0, 0));
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? WHITE : 0x14FFFFFF);
            t.setTextColor(has ? 0xFF111111 : WHITE);
            s.setTextColor(has ? 0x99111111 : GRAY);
        });
        r.setOnClickListener(click);
        hoverFocus(r);
        return r;
    }

    private void closePanel(boolean restore) {
        if (panel == null) return;
        root.removeView(panel);
        panel = null;
        page.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        if (restore && panelPrevFocus != null && panelPrevFocus.isAttachedToWindow()) panelPrevFocus.requestFocus();
    }

    // ---------- mover dentro del dock ----------

    private void startMove(App a) {
        movingPkg = a.pkg;
        title.setText("Moviendo " + a.label);
        hint.setText("Izquierda / derecha para mover     ·     OK para soltar");
        restorePkg = a.pkg;
        restoreFocus();
    }

    private void moveBy(int delta) {
        List<String> o = new ArrayList<String>();
        for (App d : dock) o.add(d.pkg);
        int i = o.indexOf(movingPkg), j = i + delta;
        if (i < 0 || j < 0 || j >= o.size()) return;
        Collections.swap(o, i, j);
        saveDock(o);
        apply(movingPkg);
    }

    private void stopMove() {
        if (movingPkg == null) return;
        String pkg = movingPkg;
        movingPkg = null;
        resetHint();
        for (App d : dock) if (d.pkg.equals(pkg)) title.setText(d.label);
    }

    // ---------- reloj, red y fondo ----------

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        String hm = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now);
        clock.setText(hm);
        ampm.setText(h24 ? "" : new SimpleDateFormat("a", Locale.US).format(now).toUpperCase(Locale.US));
        ampm.setVisibility(h24 ? View.GONE : View.VISIBLE);
        int h = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(now));
        greeting.setText((h < 6 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 20 ? "Buenas tardes" : "Buenas noches")
                .toUpperCase(ES));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", ES).format(now).toUpperCase(ES));
        if (stats.getVisibility() == View.VISIBLE) stats.setText(statsLine());
        if (saverOn) saverClock.setText(hm);
    }

    private String statsLine() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return String.format(Locale.US, "Memoria libre %.1f GB     ·     Almacenamiento libre %.1f GB",
                mi.availMem / 1073741824.0, s.getAvailableBytes() / 1073741824.0);
    }

    private void updateNet() {
        String label = "Sin red";
        int g = Glyph.WIFI;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { label = "Ethernet"; g = Glyph.ETHERNET; }
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) label = "Wi-Fi";
                    else label = "Conectado";
                }
            }
        } catch (Exception ignored) { /* se muestra "Sin red" */ }
        netLabel.setText(label);
        netGlyph.setType(g);
    }

    private void applyWallpaper() {
        final int wall = prefs.getInt("wall", 1);
        if (wall == 0) { glow.setWallpaper(null); return; }
        new Thread(() -> {
            Bitmap b = null;
            try (InputStream in = getAssets().open("bg" + (wall - 1) + ".jpg")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                b = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { /* queda negro */ }
            final Bitmap fb = b;
            handler.post(() -> glow.setWallpaper(fb));
        }, "neo-wall").start();
    }

    // ---------- protector de pantalla ----------

    private final Runnable saverStart = new Runnable() {
        @Override public void run() { showSaver(); }
    };

    private final Runnable saverDrift = new Runnable() {
        @Override public void run() {
            saverBox.animate().translationX(random.nextInt(dp(520)) - dp(260))
                    .translationY(random.nextInt(dp(240)) - dp(120)).setDuration(2500).start();
            handler.postDelayed(this, 30000);
        }
    };

    private void buildSaver() {
        saver = new FrameLayout(this);
        saver.setBackgroundColor(0xE6000000);
        saver.setVisibility(View.GONE);
        saverBox = box(LinearLayout.VERTICAL);
        saverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        saverClock = tv("", 110, 0xDDFFFFFF, font("sans-serif-thin"));
        saverDate = tv("", 16, GRAY, font("sans-serif-medium"));
        saverDate.setLetterSpacing(0.2f);
        saverBox.addView(saverClock);
        saverBox.addView(saverDate, lin(-2, -2, 0, 10, 0, 0));
        saver.addView(saverBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
    }

    private void armSaver() {
        handler.removeCallbacks(saverStart);
        handler.postDelayed(saverStart, SAVER_DELAY_MS);
    }

    private void showSaver() {
        if (saverOn || panel != null) return;
        saverOn = true;
        saverClock.setText(clock.getText());
        saverDate.setText(date.getText());
        saverBox.setTranslationX(0);
        saverBox.setTranslationY(0);
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(700).start();
        handler.postDelayed(saverDrift, 30000);
    }

    private void hideSaver() {
        if (!saverOn) return;
        saverOn = false;
        handler.removeCallbacks(saverDrift);
        saverBox.animate().cancel();
        saver.animate().cancel();
        saver.setVisibility(View.GONE);
    }
}
