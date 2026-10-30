package com.gonzalo.neoremote;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Corre en la TV como usuario "shell" (app_process), igual que scrcpy, y queda escuchando por stdin.
 * Inyecta teclas al instante, sin el segundo de arranque que tarda "input keyevent".
 *
 * Comandos (una línea cada uno):
 *   k CODIGO   tecla      l CODIGO   tecla mantenida      d N   borrar N caracteres
 *   t TEXTO    escribir texto (con tildes y ñ)                q     salir
 * Puntero (como un mouse):
 *   P ANCHO ALTO   activar y centrar     p DX DY   mover        c   clic        cl  clic largo
 *   h              ocultar el puntero
 * Si el puntero ya está en un borde y se sigue empujando, se manda la flecha de esa dirección:
 * las apps de TV (YouTube, NEO) avanzan por foco, no con rueda de mouse.
 * El puntero lo dibuja NEO (CursorService), que escucha en 127.0.0.1:47800.
 */
public final class KeyServer {

    private static Object inputManager;
    private static Method inject;
    private static final float EDGE_PUSH = 150; // px de "empuje" contra el borde por cada paso
    private static float px = 960, py = 540, pushX, pushY;
    private static int screenW = 1920, screenH = 1080;
    private static Socket overlay;
    private static OutputStream overlayOut;
    private static long overlayRetryAt;
    private static volatile long lastHover;
    private static volatile boolean hoverPending;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        inputManager = c.getDeclaredMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        // el último hover que quedó guardado se manda cuando el puntero se detiene
        Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(45);
                    long t = SystemClock.uptimeMillis();
                    if (hoverPending && t - lastHover >= 40) hover(t);
                } catch (Exception ignored) { /* siguiente vuelta */ }
            }
        }, "hover");
        flusher.setDaemon(true);
        flusher.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        System.out.println("ready");
        System.out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            try {
                if (line.startsWith("k ")) {
                    key(Integer.parseInt(line.substring(2).trim()), false);
                } else if (line.startsWith("l ")) {
                    key(Integer.parseInt(line.substring(2).trim()), true);
                } else if (line.startsWith("d ")) {
                    int n = Integer.parseInt(line.substring(2).trim());
                    for (int i = 0; i < n; i++) key(KeyEvent.KEYCODE_DEL, false);
                } else if (line.startsWith("t ")) {
                    type(kcm, line.substring(2));
                } else if (line.startsWith("P ")) {
                    String[] p = line.split(" ");
                    screenW = Integer.parseInt(p[1]);
                    screenH = Integer.parseInt(p[2]);
                    px = screenW / 2f;
                    py = screenH / 2f;
                    pointer();
                } else if (line.startsWith("p ")) {
                    String[] p = line.split(" ");
                    move(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                } else if (line.equals("c")) {
                    overlay("c");
                    touch(0);
                } else if (line.equals("cl")) {
                    overlay("c");
                    touch(700);
                } else if (line.equals("h")) {
                    overlay("h");
                } else if (line.equals("q")) {
                    break;
                }
            } catch (Exception e) {
                System.out.println("err " + e);
                System.out.flush();
            }
        }
    }

    /**
     * Escribe texto. Lo que tiene tecla (ASCII) va como teclas normales; lo que no (á, ñ, ¿)
     * va como evento de texto ACTION_MULTIPLE, que los campos de texto de Android insertan tal cual.
     */
    private static void type(KeyCharacterMap kcm, String text) throws Exception {
        for (char ch : text.toCharArray()) {
            KeyEvent[] events = kcm.getEvents(new char[] { ch });
            if (events != null) {
                for (KeyEvent e : events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
            } else {
                send(new KeyEvent(SystemClock.uptimeMillis(), String.valueOf(ch),
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
            }
        }
    }

    private static void send(InputEvent e) throws Exception {
        inject.invoke(inputManager, e, 0);
    }

    /**
     * Mueve la flecha y avisa a las apps que el "mouse" pasa por ahí (algunas resaltan lo que está debajo).
     * La flecha recibe todas las posiciones; a la app se le avisa como mucho cada 40 ms, porque
     * en esta TV procesar cada hover le quita cuadros a la flecha.
     */
    private static void pointer() throws Exception {
        overlay((int) px + " " + (int) py);
        long t = SystemClock.uptimeMillis();
        if (t - lastHover < 40) {
            hoverPending = true;
            return;
        }
        hover(t);
    }

    private static synchronized void hover(long t) throws Exception {
        lastHover = t;
        hoverPending = false;
        MotionEvent e = mouse(t, MotionEvent.ACTION_HOVER_MOVE);
        send(e);
        e.recycle();
    }

    /** Mueve el puntero; lo que sobra al chocar con un borde se junta y se convierte en flechas. */
    private static void move(float dx, float dy) throws Exception {
        float nx = px + dx, ny = py + dy;
        float maxX = screenW - 1, maxY = screenH - 1;
        if (nx > maxX) { pushX += nx - maxX; nx = maxX; }
        else if (nx < 0) { pushX += nx; nx = 0; }
        else if (dx != 0) pushX = 0;
        if (ny > maxY) { pushY += ny - maxY; ny = maxY; }
        else if (ny < 0) { pushY += ny; ny = 0; }
        else if (dy != 0) pushY = 0;
        px = nx;
        py = ny;
        pointer();
        while (pushX >= EDGE_PUSH) { key(KeyEvent.KEYCODE_DPAD_RIGHT, false); pushX -= EDGE_PUSH; }
        while (pushX <= -EDGE_PUSH) { key(KeyEvent.KEYCODE_DPAD_LEFT, false); pushX += EDGE_PUSH; }
        while (pushY >= EDGE_PUSH) { key(KeyEvent.KEYCODE_DPAD_DOWN, false); pushY -= EDGE_PUSH; }
        while (pushY <= -EDGE_PUSH) { key(KeyEvent.KEYCODE_DPAD_UP, false); pushY += EDGE_PUSH; }
    }

    private static MotionEvent mouse(long t, int action) {
        MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = px;
        coords[0].y = py;
        return MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
    }

    /** Clic en la posición de la flecha: un toque de pantalla, que casi todas las apps entienden. */
    private static void touch(long holdMs) throws Exception {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(down);
        down.recycle();
        Thread.sleep(holdMs > 0 ? holdMs : 40);
        MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(up);
        up.recycle();
    }

    /** Le pasa la posición (o "h" para ocultar) a la flecha que dibuja NEO. Si NEO no responde, reintenta cada 2 s. */
    private static void overlay(String msg) {
        try {
            if (overlayOut == null) {
                if (SystemClock.uptimeMillis() < overlayRetryAt) return;
                overlay = new Socket();
                overlay.connect(new InetSocketAddress("127.0.0.1", 47800), 150);
                overlay.setTcpNoDelay(true);
                overlayOut = overlay.getOutputStream();
            }
            overlayOut.write((msg + "\n").getBytes("UTF-8"));
            overlayOut.flush();
        } catch (Exception e) {
            overlayOut = null;
            overlayRetryAt = SystemClock.uptimeMillis() + 2000;
            try { if (overlay != null) overlay.close(); } catch (Exception ignored) { /* ya cerrado */ }
        }
    }

    private static void key(int code, boolean hold) throws Exception {
        long t = SystemClock.uptimeMillis();
        send(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        if (hold) {
            Thread.sleep(650);
            send(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD));
        }
        send(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }
}
