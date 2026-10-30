package com.gonzalo.neolauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends Activity {

    private static final String[] TABS = {
            "NEXUS", "MÓDULOS", "SELECCIÓN PRIME", "ESCÁNER", "IDENTIDAD", "SISTEMA" };
    private static final String[] HINTS = {
            "OK: abrir     Mantén OK: fijar o quitar de SELECCIÓN PRIME",
            "OK: abrir     Mantén OK: fijar o quitar de SELECCIÓN PRIME     Lo más usado aparece primero",
            "Tus módulos fijados     Mantén OK: quitar",
            "Izquierda / derecha: elegir letra     Abajo: ver resultados",
            "Tu actividad en este equipo",
            "Estado del equipo y accesos rápidos" };
    private static final int NEXUS = 0, MODULOS = 1, PRIME = 2, ESCANER = 3, IDENTIDAD = 4, SISTEMA = 5;
    private static final int[] THEME_COLORS = { 0xFF22D3EE, 0xFFA78BFA, 0xFFFBBF24, 0xFF34D399, 0xFFF472B6 };
    private static final String[] THEME_NAMES = { "Cian", "Violeta", "Ámbar", "Verde", "Rosa" };
    private static final long SAVER_DELAY_MS = 3 * 60 * 1000L;
    private int accent = THEME_COLORS[0];
    private int themeIdx;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xB3FFFFFF;

    /** Una pantalla ya armada: su vista, el primer elemento enfocable y un índice paquete→tarjeta. */
    private static final class Built {
        View view;
        View first;
        final Map<String, View> byPkg = new HashMap<String, View>();
    }

    private static final class Mod {
        String pkg, label;
        ComponentName cn;
        ResolveInfo ri;
        int uses, color;
        boolean pinned;
    }

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BackdropView backdrop;
    private FrameLayout content;
    private TextView footer, miniClock;
    private FrameLayout root, saver, inputsPanel;
    private LinearLayout page, saverBox;
    private View logo, themeBtn, panelPrevFocus;
    private TextView saverClock, saverDate;
    private boolean saverOn;
    private final Random random = new Random();
    private final LinearLayout[] tabBox = new LinearLayout[TABS.length];
    private final TextView[] tabText = new TextView[TABS.length];
    private final View[] tabBar = new View[TABS.length];
    private final int[] tabId = new int[TABS.length];
    private final Built[] cache = new Built[TABS.length];
    private final List<Mod> mods = new ArrayList<Mod>();
    private int current = -1;
    private TextView nexusGreeting, nexusClock, nexusDate;
    private FrameLayout scanHolder;
    private LinearLayout scanChips;
    private boolean rebuilding;
    private String lastSig = "";
    private String restorePkg;
    private boolean restoreInContent;
    private String scanKey;
    private Runnable scanFill;
    private final Map<String, Drawable.ConstantState> bannerCache = new HashMap<String, Drawable.ConstantState>();
    private final Map<String, Drawable.ConstantState> iconCache = new HashMap<String, Drawable.ConstantState>();

    /** Arma en segundo plano, de a una, las secciones que aún no se abrieron. */
    private final Runnable prebuild = new Runnable() {
        @Override public void run() {
            for (int i = 0; i < TABS.length; i++) {
                if (cache[i] == null) {
                    ensure(i);
                    handler.postDelayed(this, 120);
                    return;
                }
            }
        }
    };

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
        prefs = getSharedPreferences("neo", MODE_PRIVATE);
        themeIdx = prefs.getInt("theme", 0) % THEME_COLORS.length;
        accent = THEME_COLORS[themeIdx];
        buildShell();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean first = current < 0;
        boolean changed = reload();
        show(first ? NEXUS : current);
        if (first) focusFirst();
        else if (changed && restoreInContent) restoreFocus();
        handler.removeCallbacks(tick);
        tick.run();
        handler.removeCallbacks(prebuild);
        handler.postDelayed(prebuild, 500);
        armSaver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        View f = getCurrentFocus();
        restoreInContent = f != null && !isTab(f) && inputsPanel == null;
        restorePkg = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(prebuild);
        handler.removeCallbacks(saverStart);
        hideSaver();
        if (inputsPanel != null) closePanel(false);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        restoreInContent = false;
        restorePkg = null;
        show(NEXUS);
        focusFirst();
    }

    private boolean isTab(View v) {
        for (View t : tabBox) if (t == v) return true;
        return false;
    }

    @Override
    public void onBackPressed() {
        if (inputsPanel != null) { closePanel(true); return; }
        View f = getCurrentFocus();
        boolean onTab = false;
        for (View t : tabBox) if (t == f) onTab = true;
        if (!onTab && current >= 0) tabBox[current].requestFocus();
        else if (current != NEXUS) tabBox[NEXUS].requestFocus();
        else finish(); // ya no es la pantalla de inicio: desde NEXUS, atrás vuelve al inicio nuevo
    }

    // ---------- utilidades de UI ----------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setSingleLine(true);
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

    /** Medidas en dp; los valores negativos (-1, -2) se dejan tal cual. */
    private LinearLayout.LayoutParams lin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout rowOf() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private TextView label(String s) {
        TextView t = tv(s, 13, accent, true);
        t.setLetterSpacing(0.25f);
        return t;
    }

    // ---------- estructura: cabecera, contenido, pie ----------

    private void buildShell() {
        root = new FrameLayout(this);
        backdrop = new BackdropView(this, TABS.length);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        page = column();
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = rowOf();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(40), dp(16), dp(48), dp(4));

        logo = new View(this);
        logo.setBackground(rect(accent, 3));
        logo.setRotation(45);
        header.addView(logo, lin(11, 11, 6, 0, 10, 0));
        TextView brand = tv("NEO", 16, WHITE, true);
        brand.setLetterSpacing(0.3f);
        header.addView(brand, lin(-2, -2, 0, 0, 22, 0));

        for (int i = 0; i < TABS.length; i++) header.addView(makeTab(i), lin(-2, -2, 0, 0, 2, 0));

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        miniClock = tv("", 16, DIM, true);
        miniClock.setVisibility(View.INVISIBLE);
        header.addView(miniClock);
        page.addView(header, lin(-1, -2, 0, 0, 0, 0));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        footer = tv("", 12, 0x80FFFFFF, false);
        footer.setPadding(dp(48), 0, dp(48), dp(14));
        page.addView(footer, lin(-1, -2, 0, 0, 0, 0));

        buildSaver();
        setContentView(root);
    }

    private View makeTab(final int i) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setPadding(dp(10), dp(6), dp(10), dp(4));
        box.setBackground(rect(0, 10));
        tabId[i] = View.generateViewId();
        box.setId(tabId[i]);

        TextView t = tv(TABS[i], 13, 0x8CFFFFFF, true);
        t.setLetterSpacing(0.12f);
        View bar = new View(this);
        bar.setBackground(rect(accent, 2));
        bar.setVisibility(View.INVISIBLE);
        box.addView(t, lin(-2, -2, 0, 0, 0, 0));
        box.addView(bar, lin(22, 3, 0, 4, 0, 0));

        box.setOnFocusChangeListener((v, has) -> {
            ((GradientDrawable) v.getBackground()).setColor(has ? 0x33FFFFFF : 0);
            if (has && !rebuilding) show(i);
        });
        box.setOnClickListener(v -> focusFirst());
        tabBox[i] = box;
        tabText[i] = t;
        tabBar[i] = bar;
        return box;
    }

    private void show(int i) {
        ensure(i);
        Built b = cache[i];
        boolean already = i == current && b.view.getVisibility() == View.VISIBLE;
        current = i;
        for (int k = 0; k < TABS.length; k++) {
            boolean on = k == i;
            tabText[k].setTextColor(on ? WHITE : 0x8CFFFFFF);
            tabBar[k].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
        if (!already) {
            for (int k = 0; k < TABS.length; k++) {
                if (k != i && cache[k] != null) {
                    cache[k].view.animate().cancel();
                    cache[k].view.setVisibility(View.INVISIBLE);
                }
            }
            b.view.setAlpha(0f);
            b.view.setVisibility(View.VISIBLE);
            b.view.animate().alpha(1f).setDuration(160).start();
        }
        backdrop.setScene(i);
        if (b.first != null) for (LinearLayout t : tabBox) t.setNextFocusDownId(b.first.getId());
        footer.setText(HINTS[i]);
        miniClock.setVisibility(i == NEXUS ? View.INVISIBLE : View.VISIBLE);
    }

    /** Arma la sección una sola vez; queda dentro de content, oculta, hasta que se muestre. */
    private void ensure(int i) {
        if (cache[i] != null) return;
        Built b = new Built();
        switch (i) {
            case NEXUS: buildNexus(b); break;
            case MODULOS: buildGrid(b, mods, MODULOS, "MÓDULOS", mods.size() + " instalados", ""); break;
            case PRIME: buildGrid(b, primeList(false), PRIME, "SELECCIÓN PRIME", "Tus favoritos fijados",
                    "Aún no fijaste ninguno.  Mantén OK sobre un módulo para traerlo aquí."); break;
            case ESCANER: buildScan(b); break;
            case IDENTIDAD: buildIdentity(b); break;
            default: buildSystem(b); break;
        }
        b.view.setVisibility(View.INVISIBLE);
        content.addView(b.view, new FrameLayout.LayoutParams(-1, -1));
        cache[i] = b;
    }

    private void invalidateAll() {
        // Al quitar la vista enfocada Android pasa el foco a la primera pestaña; eso no debe cambiar de sección.
        rebuilding = true;
        for (int i = 0; i < cache.length; i++) {
            if (cache[i] != null) content.removeView(cache[i].view);
            cache[i] = null;
        }
        rebuilding = false;
        scanHolder = null;
        scanChips = null;
        scanKey = null;
        nexusGreeting = nexusClock = nexusDate = null;
    }

    private void focusFirst() {
        if (current < 0 || cache[current] == null || cache[current].first == null) return;
        final View f = cache[current].first;
        f.requestFocus();
        f.post(() -> f.requestFocus());
    }

    /** Devuelve el foco al módulo que tenías al salir (o al primero de la sección). */
    private void restoreFocus() {
        Built b = cache[current];
        if (b == null) return;
        View k = restorePkg != null ? b.byPkg.get(restorePkg) : null;
        final View target = k != null ? k : b.first;
        if (target == null) return;
        target.requestFocus();
        target.post(() -> target.requestFocus());
    }

    // ---------- datos ----------

    private Set<String> pins() {
        return new HashSet<String>(prefs.getStringSet("pins", new HashSet<String>()));
    }

    /** Relee las apps. Devuelve true (y descarta las pantallas armadas) solo si algo cambió. */
    private boolean reload() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> pinned = pins();
        Set<String> seen = new HashSet<String>();
        List<Mod> fresh = new ArrayList<Mod>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            Mod m = new Mod();
            m.pkg = pkg;
            m.ri = ri;
            m.cn = new ComponentName(pkg, ri.activityInfo.name);
            m.label = String.valueOf(ri.loadLabel(pm));
            m.uses = prefs.getInt("n:" + pkg, 0);
            m.pinned = pinned.contains(pkg);
            m.color = colorFor(pkg);
            fresh.add(m);
        }
        Collections.sort(fresh, (a, b) -> a.uses != b.uses
                ? b.uses - a.uses : a.label.compareToIgnoreCase(b.label));
        StringBuilder sig = new StringBuilder();
        for (Mod m : fresh) sig.append(m.pkg).append(':').append(m.uses).append(m.pinned ? "*" : "").append(';');
        sig.append(prefs.getString("name", "")).append('#').append(themeIdx);
        if (sig.toString().equals(lastSig)) return false;
        lastSig = sig.toString();
        mods.clear();
        mods.addAll(fresh);
        invalidateAll();
        return true;
    }

    private List<Mod> primeList(boolean fallback) {
        List<Mod> out = new ArrayList<Mod>();
        for (Mod m : mods) if (m.pinned) out.add(m);
        if (out.isEmpty() && fallback) {
            for (Mod m : mods) { out.add(m); if (out.size() == 6) break; }
        }
        return out;
    }

    private int colorFor(String key) {
        if (key.contains("netflix")) return 0xFFE50914;
        if (key.contains("youtube")) return 0xFFE62117;
        if (key.contains("disney")) return 0xFF113CCF;
        if (key.contains("twitch")) return 0xFF9146FF;
        if (key.contains("kick")) return 0xFF2BB515;
        float[] hsv = { Math.abs(key.hashCode()) % 360, 0.65f, 0.78f };
        return Color.HSVToColor(hsv);
    }

    private void updateClock() {
        Date now = new Date();
        String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        miniClock.setText(hm);
        if (saverOn) saverClock.setText(hm);
        if (nexusClock != null) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            nexusGreeting.setText((h < 6 ? "Buenas noches" : h < 12 ? "Buenos días"
                    : h < 20 ? "Buenas tardes" : "Buenas noches")
                    + (prefs.getString("name", "").isEmpty() ? "" : ", " + prefs.getString("name", "")));
            nexusClock.setText(hm);
            String d = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(now);
            nexusDate.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
        }
    }

    // ---------- accesos rápidos, tema, entradas ----------

    private TextView quickBtn(String text, View.OnClickListener click) {
        final TextView b = tv(text, 13, WHITE, true);
        b.setLetterSpacing(0.12f);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setBackground(rect(0x2EFFFFFF, 12));
        b.setFocusable(true);
        b.setClickable(true);
        b.setId(View.generateViewId());
        b.setOnFocusChangeListener((v, has) -> {
            ((GradientDrawable) v.getBackground()).setColor(has ? accent : 0x2EFFFFFF);
            b.setTextColor(has ? 0xFF001018 : WHITE);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(click);
        return b;
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { /* sin ajustes disponibles */ }
        }
    }

    private void nextTheme() {
        themeIdx = (themeIdx + 1) % THEME_COLORS.length;
        accent = THEME_COLORS[themeIdx];
        prefs.edit().putInt("theme", themeIdx).apply();
        logo.setBackground(rect(accent, 3));
        for (View bar : tabBar) bar.setBackground(rect(accent, 2));
        int tab = current;
        reload();
        show(tab);
        if (themeBtn != null) themeBtn.requestFocus();
    }

    private View inputRow(String title, String sub, View.OnClickListener click) {
        LinearLayout r = column();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(8), dp(16), dp(8));
        r.setBackground(rect(0x26FFFFFF, 12));
        r.setFocusable(true);
        r.setClickable(true);
        r.addView(tv(title, 16, WHITE, true));
        if (!sub.isEmpty()) r.addView(tv(sub, 12, DIM, false));
        r.setOnFocusChangeListener((v, has) ->
                ((GradientDrawable) v.getBackground()).setColor(has ? 0x66FFFFFF : 0x26FFFFFF));
        r.setOnClickListener(click);
        r.setLayoutParams(lin(-1, -2, 0, 0, 0, 8));
        return r;
    }

    /** Lista las entradas de la TV (HDMI, AV) y abre la que elijas. */
    private void showInputs() {
        if (inputsPanel != null) return;
        panelPrevFocus = getCurrentFocus();
        final TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> list = new ArrayList<TvInputInfo>();
        try {
            if (tim != null) for (TvInputInfo i : tim.getTvInputList()) if (i.isPassthroughInput()) list.add(i);
        } catch (Exception ignored) { /* sin acceso a las entradas */ }

        inputsPanel = new FrameLayout(this);
        inputsPanel.setBackgroundColor(0xCC000000);
        inputsPanel.setClickable(true);
        LinearLayout box = column();
        box.setPadding(dp(28), dp(22), dp(28), dp(14));
        box.setBackground(rect(0xF0121826, 18));
        box.addView(label("ENTRADAS"), lin(-2, -2, 0, 0, 0, 12));

        View first = null;
        for (final TvInputInfo info : list) {
            int st = tim.getInputState(info.getId());
            String state = st == TvInputManager.INPUT_STATE_CONNECTED ? "Conectada"
                    : st == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "Conectada (en espera)" : "Sin cable";
            View row = inputRow(String.valueOf(info.loadLabel(this)), state, v -> {
                closePanel(false);
                openInput(info);
            });
            if (first == null) first = row;
            box.addView(row);
        }
        if (list.isEmpty()) box.addView(tv("No se encontraron entradas.", 14, DIM, false), lin(-2, -2, 0, 0, 0, 10));
        View close = inputRow("Cerrar", "", v -> closePanel(true));
        if (first == null) first = close;
        box.addView(close);

        inputsPanel.addView(box, new FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER));
        root.addView(inputsPanel, new FrameLayout.LayoutParams(-1, -1));
        page.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        first.requestFocus();
    }

    private void closePanel(boolean restore) {
        if (inputsPanel == null) return;
        root.removeView(inputsPanel);
        inputsPanel = null;
        page.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        if (restore && panelPrevFocus != null) panelPrevFocus.requestFocus();
    }

    private void openInput(TvInputInfo info) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la entrada", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- protector de pantalla con reloj ----------

    private final Runnable saverStart = new Runnable() {
        @Override public void run() { showSaver(); }
    };

    /** Mueve el reloj de lugar cada tanto para no marcar la pantalla. */
    private final Runnable saverDrift = new Runnable() {
        @Override public void run() {
            saverBox.animate().translationX(random.nextInt(dp(520)) - dp(260))
                    .translationY(random.nextInt(dp(240)) - dp(120)).setDuration(2500).start();
            handler.postDelayed(this, 30000);
        }
    };

    private void buildSaver() {
        saver = new FrameLayout(this);
        saver.setBackgroundColor(0xFF000000);
        saver.setVisibility(View.GONE);
        saver.setClickable(true);
        saverBox = column();
        saverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        saverClock = tv("", 96, 0xCCFFFFFF, true);
        saverDate = tv("", 22, 0x99FFFFFF, false);
        saverBox.addView(saverClock);
        saverBox.addView(saverDate);
        saver.addView(saverBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
    }

    private void armSaver() {
        handler.removeCallbacks(saverStart);
        handler.postDelayed(saverStart, SAVER_DELAY_MS);
    }

    private void showSaver() {
        if (saverOn || inputsPanel != null) return;
        saverOn = true;
        Date now = new Date();
        saverClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        String d = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(now);
        saverDate.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
        saverBox.setTranslationX(0);
        saverBox.setTranslationY(0);
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(600).start();
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverOn) {
            // la primera tecla solo despierta la pantalla
            if (e.getAction() == KeyEvent.ACTION_DOWN) { hideSaver(); armSaver(); }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) armSaver();
        return super.dispatchKeyEvent(e);
    }

    // ---------- tarjetas ----------

    private View card(final Mod m, int w, int h, int upId, final Built b) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        f.setId(View.generateViewId());
        f.setTag(m.pkg);
        if (upId != View.NO_ID) f.setNextFocusUpId(upId);

        Drawable banner = bannerOf(m);
        ImageView iv = new ImageView(this);
        if (banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            f.setBackgroundColor(0xFF111827);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] { m.color, 0xFF0B0F1A });
            f.setBackground(g);
            iv.setImageDrawable(iconOf(m));
            int s = dp(Math.min(w, h) * 0.5f);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL);
            ip.topMargin = dp(h * 0.12f);
            f.addView(iv, ip);
        }

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x99000000, 0xE6000000 }));
        f.addView(shade, new FrameLayout.LayoutParams(-1, dp(34), Gravity.BOTTOM));

        TextView name = tv(m.label, 13, WHITE, true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(10), 0, dp(10), dp(8));
        f.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (m.pinned) {
            TextView pin = tv("PRIME", 9, 0xFF001018, true);
            pin.setLetterSpacing(0.1f);
            pin.setBackground(rect(accent, 6));
            pin.setPadding(dp(6), dp(1), dp(6), dp(1));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            pp.setMargins(0, dp(8), dp(8), 0);
            f.addView(pin, pp);
        }

        round(f, 14);
        final GradientDrawable border = new GradientDrawable();
        border.setColor(0);
        border.setCornerRadius(dp(14));
        border.setStroke(dp(3), WHITE);
        border.setAlpha(0);
        f.setForeground(border);

        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(120).start();
            border.setAlpha(has ? 255 : 0);
        });
        f.setOnClickListener(v -> launch(m));
        f.setOnLongClickListener(v -> { togglePin(m); return true; });

        if (b.first == null) b.first = f;
        b.byPkg.put(m.pkg, f);
        f.setLayoutParams(lin(w, h, 0, 0, 14, 14));
        return f;
    }

    /** Las imágenes de cada app se leen una sola vez; después se clonan del caché. */
    private Drawable bannerOf(Mod m) {
        Drawable.ConstantState cs = bannerCache.get(m.pkg);
        if (cs != null) return cs.newDrawable(getResources());
        Drawable d = m.ri.activityInfo.loadBanner(getPackageManager());
        if (d != null && d.getConstantState() != null) bannerCache.put(m.pkg, d.getConstantState());
        return d;
    }

    private Drawable iconOf(Mod m) {
        Drawable.ConstantState cs = iconCache.get(m.pkg);
        if (cs != null) return cs.newDrawable(getResources());
        Drawable d = m.ri.loadIcon(getPackageManager());
        if (d != null && d.getConstantState() != null) iconCache.put(m.pkg, d.getConstantState());
        return d;
    }

    private void launch(Mod m) {
        try {
            prefs.edit().putInt("n:" + m.pkg, m.uses + 1).apply();
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(m.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ex) {
            Toast.makeText(this, "No se pudo abrir " + m.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePin(Mod m) {
        Set<String> p = pins();
        boolean add = p.add(m.pkg);
        if (!add) p.remove(m.pkg);
        prefs.edit().putStringSet("pins", p).apply();
        Toast.makeText(this, add ? m.label + " fijado en SELECCIÓN PRIME"
                : m.label + " quitado de SELECCIÓN PRIME", Toast.LENGTH_SHORT).show();
        final int tab = current;
        reload();
        show(tab);
        restorePkg = m.pkg;
        restoreFocus();
    }

    private LinearLayout grid(List<Mod> list, int cols, int cw, int ch, int upId, Built b) {
        LinearLayout g = column();
        g.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = rowOf();
                g.addView(row);
            }
            row.addView(card(list.get(i), cw, ch, i < cols ? upId : View.NO_ID, b));
        }
        return g;
    }

    private View titleBlock(String title, String sub) {
        LinearLayout t = column();
        TextView a = tv(title, 26, WHITE, true);
        a.setLetterSpacing(0.15f);
        t.addView(a);
        t.addView(tv(sub, 14, DIM, false));
        return t;
    }

    // ---------- NEXUS ----------

    private void buildNexus(Built b) {
        LinearLayout col = column();
        col.setPadding(dp(48), dp(10), dp(48), 0);

        LinearLayout top = rowOf();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = column();
        nexusGreeting = tv("", 20, DIM, false);
        nexusClock = tv("", 64, WHITE, true);
        nexusDate = tv("", 18, DIM, false);
        left.addView(nexusGreeting);
        left.addView(nexusClock);
        left.addView(nexusDate);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(statusPanel(), lin(300, -2, 0, 0, 0, 0));
        col.addView(top, lin(-1, -2, 0, 0, 0, 0));

        col.addView(label("SELECCIÓN PRIME"), lin(-2, -2, 0, 22, 0, 8));

        List<Mod> list = primeList(true);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(10), 0, dp(10));
        LinearLayout r = rowOf();
        for (Mod m : list) r.addView(card(m, 210, 118, tabId[NEXUS], b));
        hs.addView(r);
        col.addView(hs, lin(-1, -2, 0, 0, 0, 0));

        LinearLayout quick = rowOf();
        quick.addView(quickBtn("ENTRADAS", v -> showInputs()), lin(-2, -2, 0, 0, 12, 0));
        quick.addView(quickBtn("RED", v -> openSettings(Settings.ACTION_WIFI_SETTINGS)), lin(-2, -2, 0, 0, 12, 0));
        quick.addView(quickBtn("AJUSTES", v -> openSettings(Settings.ACTION_SETTINGS)), lin(-2, -2, 0, 0, 12, 0));
        themeBtn = quickBtn("TEMA: " + THEME_NAMES[themeIdx].toUpperCase(), v -> nextTheme());
        quick.addView(themeBtn, lin(-2, -2, 0, 0, 0, 0));
        col.addView(quick, lin(-1, -2, 0, 6, 0, 0));

        b.view = col;
        updateClock();
    }

    private View statusPanel() {
        LinearLayout p = column();
        p.setPadding(dp(16), dp(14), dp(16), dp(14));
        p.setBackground(rect(0x24FFFFFF, 14));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        double usedRam = mi.totalMem - mi.availMem;
        p.addView(meter("MEMORIA", gb(usedRam) + " / " + gb(mi.totalMem), usedRam / mi.totalMem));
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        double usedDisk = s.getTotalBytes() - s.getAvailableBytes();
        p.addView(meter("ALMACENAMIENTO", gb(usedDisk) + " / " + gb(s.getTotalBytes()), usedDisk / s.getTotalBytes()),
                lin(-1, -2, 0, 12, 0, 0));
        return p;
    }

    private String gb(double bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
    }

    private View meter(String name, String value, double frac) {
        LinearLayout box = column();
        LinearLayout head = rowOf();
        head.addView(tv(name, 11, DIM, true), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(tv(value, 11, WHITE, true));
        box.addView(head);
        LinearLayout track = rowOf();
        track.setBackground(rect(0x33FFFFFF, 4));
        int fill = (int) Math.max(2, Math.min(100, Math.round(frac * 100)));
        View on = new View(this);
        on.setBackground(rect(frac > 0.85 ? 0xFFF59E0B : accent, 4));
        track.addView(on, new LinearLayout.LayoutParams(0, -1, fill));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 100 - fill));
        box.addView(track, lin(-1, 6, 0, 6, 0, 0));
        return box;
    }

    // ---------- MÓDULOS / SELECCIÓN PRIME ----------

    private void buildGrid(Built b, List<Mod> list, int tab, String title, String sub, String empty) {
        LinearLayout col = column();
        col.setPadding(dp(48), dp(8), dp(48), 0);
        col.addView(titleBlock(title, sub), lin(-1, -2, 0, 0, 0, 12));
        if (list.isEmpty()) {
            TextView t = tv(empty, 16, DIM, false);
            t.setSingleLine(false);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setNextFocusUpId(tabId[tab]);
            col.addView(t, lin(-2, -2, 0, 20, 0, 0));
            b.first = t;
        } else {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(grid(list, 4, 196, 110, tabId[tab], b));
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        b.view = col;
    }

    // ---------- ESCÁNER ----------

    private String letterOf(Mod m) {
        String s = Normalizer.normalize(m.label, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (s.isEmpty()) return "#";
        char c = Character.toUpperCase(s.charAt(0));
        return Character.isLetter(c) ? String.valueOf(c) : "#";
    }

    private void buildScan(final Built b) {
        LinearLayout col = column();
        col.setPadding(dp(48), dp(8), dp(48), 0);
        col.addView(titleBlock("ESCÁNER", "Filtra tus módulos por letra"), lin(-1, -2, 0, 0, 0, 12));

        TreeSet<String> letters = new TreeSet<String>();
        for (Mod m : mods) letters.add(letterOf(m));
        final List<String> keys = new ArrayList<String>();
        keys.add("TODO");
        keys.addAll(letters);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(4), dp(4), dp(4), dp(4));
        scanChips = rowOf();
        for (final String k : keys) {
            final TextView chip = tv(k, 15, WHITE, true);
            chip.setLetterSpacing(0.1f);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setId(View.generateViewId());
            chip.setBackground(rect(0x26FFFFFF, 10));
            chip.setNextFocusUpId(tabId[ESCANER]);
            chip.setOnFocusChangeListener((v, has) -> {
                ((GradientDrawable) v.getBackground()).setColor(has ? accent : 0x26FFFFFF);
                chip.setTextColor(has ? 0xFF001018 : WHITE);
                if (has) scheduleScan(k, b);
            });
            if (b.first == null) b.first = chip;
            scanChips.addView(chip, lin(k.length() > 1 ? 70 : 44, 38, 0, 0, 8, 0));
        }
        hs.addView(scanChips);
        col.addView(hs, lin(-1, -2, 0, 0, 0, 8));

        scanHolder = new FrameLayout(this);
        col.addView(scanHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        b.view = col;
        fillScan("TODO", b);
    }

    /** Espera un instante antes de armar los resultados, para no trabarse al pasar rápido por las letras. */
    private void scheduleScan(final String key, final Built b) {
        handler.removeCallbacks(scanFill);
        scanFill = () -> fillScan(key, b);
        handler.postDelayed(scanFill, 90);
    }

    private void fillScan(String key, Built b) {
        if (scanHolder == null || key.equals(scanKey)) return;
        scanKey = key;
        List<Mod> sel = new ArrayList<Mod>();
        for (Mod m : mods) if (key.equals("TODO") || letterOf(m).equals(key)) sel.add(m);
        scanHolder.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        Built tmp = new Built();
        sv.addView(grid(sel, 4, 196, 110, scanChips.getChildAt(0).getId(), tmp));
        scanHolder.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        b.byPkg.putAll(tmp.byPkg);
        if (tmp.first != null) {
            for (int i = 0; i < scanChips.getChildCount(); i++)
                scanChips.getChildAt(i).setNextFocusDownId(tmp.first.getId());
        }
    }

    // ---------- IDENTIDAD ----------

    private void buildIdentity(final Built b) {
        LinearLayout col = column();
        col.setPadding(dp(48), dp(8), dp(48), 0);
        col.addView(titleBlock("IDENTIDAD", "Tu actividad en este equipo"), lin(-1, -2, 0, 0, 0, 14));

        int total = 0;
        for (Mod m : mods) total += m.uses;
        int level = 1 + total / 10;
        String name = prefs.getString("name", "");

        LinearLayout head = rowOf();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView avatar = tv(name.substring(0, 1).toUpperCase(), 40, WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x33FFFFFF);
        ring.setStroke(dp(3), accent);
        avatar.setBackground(ring);
        head.addView(avatar, lin(84, 84, 0, 0, 20, 0));
        LinearLayout info = column();
        TextView nm = tv(name.toUpperCase(), 30, WHITE, true);
        nm.setLetterSpacing(0.15f);
        info.addView(nm);
        info.addView(tv("OPERADOR  ·  NIVEL " + level + "  ·  " + total + " aperturas", 14, DIM, false));
        info.addView(meter("PROGRESO AL NIVEL " + (level + 1), (total % 10) + " / 10", (total % 10) / 10.0),
                lin(320, -2, 0, 10, 0, 0));
        head.addView(info);
        col.addView(head);

        col.addView(label("MÓDULOS MÁS USADOS"), lin(-2, -2, 0, 20, 0, 8));
        int max = 1;
        for (Mod m : mods) max = Math.max(max, m.uses);
        int shown = 0;
        for (Mod m : mods) {
            if (m.uses == 0 || shown == 4) break;
            LinearLayout r = rowOf();
            r.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(m.ri.loadIcon(getPackageManager()));
            r.addView(ic, lin(26, 26, 0, 0, 12, 0));
            r.addView(tv(m.label, 14, WHITE, true), lin(150, -2, 0, 0, 0, 0));
            LinearLayout track = rowOf();
            track.setBackground(rect(0x22FFFFFF, 4));
            View on = new View(this);
            on.setBackground(rect(m.color, 4));
            track.addView(on, new LinearLayout.LayoutParams(0, -1, m.uses * 100f / max));
            track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 100 - m.uses * 100f / max));
            r.addView(track, lin(260, 8, 0, 0, 12, 0));
            r.addView(tv(String.valueOf(m.uses), 13, DIM, false));
            col.addView(r, lin(-2, 30, 0, 0, 0, 0));
            shown++;
        }
        if (shown == 0) col.addView(tv("Todavía no abriste ningún módulo desde NEO.", 14, DIM, false));

        TextView reset = tv("REINICIAR ESTADÍSTICAS", 12, WHITE, true);
        reset.setLetterSpacing(0.12f);
        reset.setPadding(dp(16), dp(8), dp(16), dp(8));
        reset.setBackground(rect(0x26FFFFFF, 10));
        reset.setFocusable(true);
        reset.setId(View.generateViewId());
        reset.setNextFocusUpId(tabId[IDENTIDAD]);
        reset.setOnFocusChangeListener((v, has) ->
                ((GradientDrawable) v.getBackground()).setColor(has ? 0x66FFFFFF : 0x26FFFFFF));
        reset.setOnClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (Mod m : mods) e.remove("n:" + m.pkg);
            e.apply();
            int tab = current;
            reload();
            show(tab);
            focusFirst();
        });
        col.addView(reset, lin(-2, -2, 0, 12, 0, 0));
        b.first = reset;
        b.view = col;
    }

    // ---------- SISTEMA ----------

    private void buildSystem(Built b) {
        LinearLayout col = column();
        col.setPadding(dp(48), dp(8), dp(48), 0);
        col.addView(titleBlock("SISTEMA", Build.MANUFACTURER + " " + Build.MODEL + "  ·  Android "
                + Build.VERSION.RELEASE), lin(-1, -2, 0, 0, 0, 14));

        LinearLayout body = rowOf();
        LinearLayout stats = column();
        stats.setPadding(dp(16), dp(14), dp(16), dp(14));
        stats.setBackground(rect(0x24FFFFFF, 14));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        double usedRam = mi.totalMem - mi.availMem;
        stats.addView(meter("MEMORIA", gb(usedRam) + " / " + gb(mi.totalMem), usedRam / mi.totalMem));
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        double usedDisk = s.getTotalBytes() - s.getAvailableBytes();
        stats.addView(meter("ALMACENAMIENTO", gb(usedDisk) + " / " + gb(s.getTotalBytes()),
                usedDisk / s.getTotalBytes()), lin(-1, -2, 0, 14, 0, 0));
        long up = SystemClock.elapsedRealtime() / 60000;
        stats.addView(tv("ENCENDIDA HACE", 11, DIM, true), lin(-2, -2, 0, 14, 0, 0));
        stats.addView(tv((up / 60) + " h " + (up % 60) + " min", 20, WHITE, true));
        body.addView(stats, lin(280, -2, 0, 0, 24, 0));

        String[][] tiles = {
                { "Ajustes de Android", "Panel completo", Settings.ACTION_SETTINGS },
                { "Red y Wi-Fi", "Conexión", Settings.ACTION_WIFI_SETTINGS },
                { "Pantalla", "Imagen y resolución", Settings.ACTION_DISPLAY_SETTINGS },
                { "Sonido", "Volumen y salida", Settings.ACTION_SOUND_SETTINGS },
                { "Aplicaciones", "Administrar todas", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS },
                { "Cambiar launcher", "Elegir pantalla de inicio", Settings.ACTION_HOME_SETTINGS } };
        LinearLayout tg = column();
        LinearLayout r = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % 2 == 0) {
                r = rowOf();
                tg.addView(r);
            }
            r.addView(tile(tiles[i][0], tiles[i][1], tiles[i][2], i < 2 ? tabId[SISTEMA] : View.NO_ID, b),
                    lin(236, 68, 0, 0, 14, 14));
        }
        body.addView(tg);
        col.addView(body);
        b.view = col;
    }

    private View tile(String title, String sub, final String action, int upId, Built b) {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(12), 0);
        t.setBackground(rect(0x2EFFFFFF, 14));
        t.setFocusable(true);
        t.setClickable(true);
        t.setId(View.generateViewId());
        if (upId != View.NO_ID) t.setNextFocusUpId(upId);
        t.addView(tv(title, 15, WHITE, true));
        t.addView(tv(sub, 12, DIM, false));
        t.setOnFocusChangeListener((v, has) -> {
            ((GradientDrawable) v.getBackground()).setColor(has ? 0x66FFFFFF : 0x2EFFFFFF);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start();
        });
        t.setOnClickListener(v -> openSettings(action));
        if (b.first == null) b.first = t;
        return t;
    }
}

