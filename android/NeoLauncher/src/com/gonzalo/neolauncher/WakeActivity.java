package com.gonzalo.neolauncher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Pantalla invisible que solo enciende la TV. NEO Remote la abre con "cmd activity start-activity",
 * que tarda ~0,1 s (contra ~1 s de "input keyevent", que arranca Java en cada llamada).
 */
public class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 400);
    }
}
