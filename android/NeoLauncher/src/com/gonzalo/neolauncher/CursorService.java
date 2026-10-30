package com.gonzalo.neolauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Dibuja la flecha del puntero de NEO Remote encima de todo.
 * El ayudante de teclas (KeyServer) le manda "x y" por 127.0.0.1:47800 cada vez que el puntero se mueve.
 * La ventana es chiquita y solo se mueve: no agrega una capa a pantalla completa sobre el video.
 */
public class CursorService extends Service {

    private static final int PORT = 47800;
    private static final long HIDE_AFTER_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View arrow;
    private WindowManager.LayoutParams lp;
    private int half;
    private float tx, ty, cx, cy;
    private boolean framePending;
    private ServerSocket server;
    private volatile boolean running = true;

    private final Runnable hide = new Runnable() {
        @Override public void run() { arrow.setVisibility(View.GONE); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("cursor", "Puntero", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "cursor")
                .setContentTitle("Puntero de NEO Remote")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build());

        float d = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        arrow = new PointerView(this);
        half = Math.round(23 * d);
        lp = new WindowManager.LayoutParams(half * 2, half * 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        arrow.setVisibility(View.GONE);
        try {
            wm.addView(arrow, lp);
        } catch (Exception e) {
            // sin permiso de superposición no hay flecha
            stopSelf();
            return;
        }
        new Thread(this::serve, "cursor-server").start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    private void serve() {
        try {
            server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            while (running) {
                final Socket s = server.accept();
                new Thread(() -> read(s), "cursor-client").start();
            }
        } catch (Exception e) {
            android.util.Log.w("NeoCursor", "servidor del puntero detenido", e);
        }
    }

    private void read(Socket s) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                final String line = l;
                handler.post(() -> apply(line));
            }
        } catch (Exception ignored) { /* el ayudante se desconectó */ }
    }

    private void apply(String line) {
        handler.removeCallbacks(hide);
        if (line.equals("h")) {
            arrow.setVisibility(View.GONE);
            return;
        }
        if (line.equals("c")) {
            // clic: el punto se encoge y vuelve
            arrow.animate().cancel();
            arrow.setScaleX(0.6f);
            arrow.setScaleY(0.6f);
            arrow.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
            handler.postDelayed(hide, HIDE_AFTER_MS);
            return;
        }
        String[] p = line.split(" ");
        if (p.length < 2) return;
        tx = Integer.parseInt(p[0]) - half; // el centro del punto es la posición
        ty = Integer.parseInt(p[1]) - half;
        if (arrow.getVisibility() != View.VISIBLE) {
            cx = tx; // aparece directo donde está, sin deslizarse desde la posición vieja
            cy = ty;
            arrow.setVisibility(View.VISIBLE);
            place();
        } else if (!framePending) {
            framePending = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
        handler.postDelayed(hide, HIDE_AFTER_MS);
    }

    /**
     * Las posiciones llegan por Wi-Fi a saltos (varias juntas y luego ninguna). En vez de mover la
     * ventana con cada una, se mueve una vez por cuadro y acercándose al destino, así se ve fluido.
     */
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nanos) {
            framePending = false;
            cx += (tx - cx) * 0.65f;
            cy += (ty - cy) * 0.65f;
            if (Math.abs(tx - cx) < 0.5f && Math.abs(ty - cy) < 0.5f) {
                cx = tx;
                cy = ty;
            } else {
                framePending = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
            place();
        }
    };

    private void place() {
        int x = Math.round(cx), y = Math.round(cy);
        if (x == lp.x && y == lp.y && arrow.isAttachedToWindow()) return;
        lp.x = x;
        lp.y = y;
        wm.updateViewLayout(arrow, lp);
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { /* ya cerrado */ }
        try { wm.removeView(arrow); } catch (Exception ignored) { /* no estaba */ }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    /** Puntero estilo TV: punto blanco con halo suave y anillo, legible sobre fondos claros y oscuros. */
    private static final class PointerView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;

        PointerView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            shade.setColor(0x59000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * d);
            ring.setColor(0xF2FFFFFF);
            dot.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            glow.setShader(new RadialGradient(w / 2f, h / 2f, w / 2f,
                    new int[] { 0x66FFFFFF, 0x2622D3EE, 0x00000000 }, new float[] { 0.35f, 0.7f, 1f },
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            c.drawCircle(cx, cy, cx, glow);
            c.drawCircle(cx, cy, 12 * d, shade);
            c.drawCircle(cx, cy, 11 * d, ring);
            c.drawCircle(cx, cy, 5.5f * d, dot);
        }
    }
}
