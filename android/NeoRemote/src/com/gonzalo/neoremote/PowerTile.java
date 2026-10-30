package com.gonzalo.neoremote;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Botón en el panel de notificaciones de la tablet para prender o apagar la TV sin abrir la app. */
public class PowerTile extends TileService {

    @Override
    public void onStartListening() {
        show(Tile.STATE_UNAVAILABLE, "Buscando…");
        new Thread(() -> {
            Boolean on = Tv.state(this);
            show(on == null ? Tile.STATE_UNAVAILABLE : on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE,
                    on == null ? "Sin conexión" : on ? "Encendida" : "En reposo");
        }, "tile-state").start();
    }

    @Override
    public void onClick() {
        Tile t = getQsTile();
        boolean wasOn = t != null && t.getState() == Tile.STATE_ACTIVE;
        show(Tile.STATE_UNAVAILABLE, wasOn ? "Apagando…" : "Encendiendo…");
        new Thread(() -> {
            Boolean on = Tv.toggle(this);
            show(on == null ? Tile.STATE_UNAVAILABLE : on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE,
                    on == null ? "No responde" : on ? "Encendida" : "En reposo");
        }, "tile-toggle").start();
    }

    private void show(int state, String sub) {
        Tile t = getQsTile();
        if (t == null) return;
        int id = getResources().getIdentifier("ic_tile_power", "drawable", getPackageName());
        t.setIcon(Icon.createWithResource(this, id));
        t.setLabel("TV");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(sub);
        t.setState(state);
        t.updateTile();
    }
}
