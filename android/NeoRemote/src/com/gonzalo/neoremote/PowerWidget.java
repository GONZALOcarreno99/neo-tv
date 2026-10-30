package com.gonzalo.neoremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.security.SecureRandom;

/** Widget de la pantalla de inicio de la tablet: un toque prende o apaga la TV. */
public class PowerWidget extends AppWidgetProvider {

    private static final String TOGGLE = "com.gonzalo.neoremote.TOGGLE_TV";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, 0xFFFFFFFF, "TV");
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (!TOGGLE.equals(i.getAction())) return;
        // el receptor tiene que estar exportado (lo llama el inicio de la tablet), así que solo se
        // acepta el toque que trae la llave secreta del propio botón: otras apps no la conocen
        if (!secret(c).equals(i.getStringExtra("k"))) return;
        final Context app = c.getApplicationContext();
        final PendingResult pending = goAsync();
        render(app, 0xFF22D3EE, "…");
        new Thread(() -> {
            Boolean on = Tv.toggle(app);
            render(app, on == null ? 0xFFFF5A5F : on ? 0xFF34D399 : 0xFFFBBF24,
                    on == null ? "Sin conexión" : on ? "Encendida" : "En reposo");
            pending.finish();
        }, "widget-toggle").start();
    }

    private static synchronized String secret(Context c) {
        String k = Tv.prefs(c).getString("widget_key", null);
        if (k == null) {
            SecureRandom r = new SecureRandom();
            k = Long.toHexString(r.nextLong()) + Long.toHexString(r.nextLong());
            Tv.prefs(c).edit().putString("widget_key", k).apply();
        }
        return k;
    }

    private static void render(Context c, int color, String label) {
        String pkg = c.getPackageName();
        int layout = c.getResources().getIdentifier("widget_power", "layout", pkg);
        int root = c.getResources().getIdentifier("root", "id", pkg);
        int icon = c.getResources().getIdentifier("icon", "id", pkg);
        int text = c.getResources().getIdentifier("label", "id", pkg);
        RemoteViews v = new RemoteViews(pkg, layout);
        v.setInt(icon, "setColorFilter", color);
        v.setTextViewText(text, label);
        Intent i = new Intent(c, PowerWidget.class).setAction(TOGGLE).putExtra("k", secret(c));
        v.setOnClickPendingIntent(root, PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        m.updateAppWidget(new ComponentName(c, PowerWidget.class), v);
    }
}
