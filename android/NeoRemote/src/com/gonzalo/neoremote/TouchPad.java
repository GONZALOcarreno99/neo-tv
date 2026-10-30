package com.gonzalo.neoremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Touchpad para mover el puntero en la TV:
 * un dedo mueve (con aceleración), tocar = clic, mantener = clic largo,
 * dos dedos = mover la pantalla (manda flechas, que es como avanzan las apps de TV).
 */
final class TouchPad extends View {

    interface Listener {
        void onMove(float dx, float dy);
        void onClick();
        void onLongClick();
        void onArrow(int keyCode);
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int accent;
    private final float density, slop, radius;
    private Listener listener;
    private float lastX, lastY, gx, gy, travel, twoX, twoY, accX, accY;
    private long lastT, downAt;
    private boolean touching, moved, longFired, twoFingers;

    private final Runnable longClick = new Runnable() {
        @Override public void run() {
            if (touching && !moved && !twoFingers && listener != null) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onLongClick();
            }
        }
    };

    TouchPad(Context c, int accent) {
        super(c);
        this.accent = accent;
        density = c.getResources().getDisplayMetrics().density;
        slop = 8 * density;
        radius = 28 * density;
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(density);
        border.setColor(0x22FFFFFF);
        dots.setColor(0x14FFFFFF);
        hint.setColor(0x66FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(14 * c.getResources().getDisplayMetrics().scaledDensity);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setListener(Listener l) { listener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF1D2331, 0xFF10131A, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        c.drawRoundRect(box, radius, radius, bg);
        c.drawRoundRect(box, radius, radius, border);
        float gap = 22 * density;
        for (float y = gap; y < h; y += gap)
            for (float x = gap; x < w; x += gap) c.drawCircle(x, y, 1.6f, dots);
        if (touching) {
            float r = 90 * density;
            glow.setShader(new RadialGradient(gx, gy, r, (accent & 0x00FFFFFF) | 0x55000000, 0, Shader.TileMode.CLAMP));
            c.drawCircle(gx, gy, r, glow);
        } else {
            float s = hint.getTextSize();
            c.drawText("Desliza para mover el puntero", w / 2f, h / 2f - s * 1.6f, hint);
            c.drawText("Toca: clic   ·   Mantén: clic largo", w / 2f, h / 2f, hint);
            c.drawText("Empuja contra el borde para seguir avanzando", w / 2f, h / 2f + s * 1.6f, hint);
            c.drawText("Dos dedos: mover la pantalla", w / 2f, h / 2f + s * 3.2f, hint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                moved = longFired = twoFingers = false;
                travel = accX = accY = 0;
                lastX = gx = e.getX();
                lastY = gy = e.getY();
                lastT = downAt = e.getEventTime();
                handler.postDelayed(longClick, 550);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                twoFingers = true;
                handler.removeCallbacks(longClick);
                twoX = avg(e, true);
                twoY = avg(e, false);
                accX = accY = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (twoFingers && e.getPointerCount() >= 2) {
                    // como deslizar la pantalla del celular: los dedos van hacia arriba -> la TV baja
                    float x = avg(e, true), y = avg(e, false);
                    accX += twoX - x;
                    accY += twoY - y;
                    twoX = x;
                    twoY = y;
                    float step = 40 * density;
                    while (Math.abs(accY) >= step && Math.abs(accY) >= Math.abs(accX)) {
                        arrow(accY > 0 ? 20 : 19);
                        accY -= Math.signum(accY) * step;
                    }
                    while (Math.abs(accX) >= step && Math.abs(accX) > Math.abs(accY)) {
                        arrow(accX > 0 ? 22 : 21);
                        accX -= Math.signum(accX) * step;
                    }
                    return true;
                }
                float x = e.getX(), y = e.getY();
                float dx = x - lastX, dy = y - lastY;
                long dt = Math.max(1, e.getEventTime() - lastT);
                lastX = gx = x;
                lastY = gy = y;
                lastT = e.getEventTime();
                travel += Math.abs(dx) + Math.abs(dy);
                if (!moved && travel > slop) {
                    moved = true;
                    handler.removeCallbacks(longClick);
                }
                if (moved && listener != null) {
                    // aceleración: dedo lento = preciso, dedo rápido = cruza la pantalla
                    float speed = (float) Math.hypot(dx, dy) / density / dt; // dp por ms
                    float gain = 2.0f + Math.min(4.0f, speed * 2.6f);
                    listener.onMove(dx / density * gain * 1.5f, dy / density * gain * 1.5f);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved && !longFired && !twoFingers && e.getEventTime() - downAt < 350) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onClick();
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longClick);
                touching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void arrow(int keyCode) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.onArrow(keyCode);
    }

    private static float avg(MotionEvent e, boolean x) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += x ? e.getX(i) : e.getY(i);
        return s / e.getPointerCount();
    }
}
