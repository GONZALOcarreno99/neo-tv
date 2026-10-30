package com.gonzalo.neoremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Cruceta circular: 4 flechas y OK al centro. Mantener una flecha repite; mantener OK = pulsación larga. */
final class DPad extends View {

    interface Listener {
        void onKey(int keyCode);
        void onLongOk();
    }

    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23;
    private static final int NONE = -1, CENTER = 4;
    private static final int[] CODES = { UP, RIGHT, DOWN, LEFT };

    private final Paint disk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int accent;
    private Listener listener;
    private int pressed = NONE;
    private boolean longFired;

    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (pressed >= 0 && pressed < 4 && listener != null) {
                listener.onKey(CODES[pressed]);
                handler.postDelayed(this, 110);
            }
        }
    };

    private final Runnable longOk = new Runnable() {
        @Override public void run() {
            if (pressed == CENTER && listener != null) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onLongOk();
            }
        }
    };

    DPad(Context c, int accent) {
        super(c);
        this.accent = accent;
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x22FFFFFF);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setListener(Listener l) { listener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Math.min(w, h) / 2f;
        disk.setShader(new RadialGradient(w / 2f, h / 2f - r * 0.3f, r * 1.3f,
                0xFF2A2F3B, 0xFF12151C, Shader.TileMode.CLAMP));
        ring.setStrokeWidth(r * 0.012f);
        arrow.setStrokeWidth(r * 0.05f);
        label.setTextSize(r * 0.16f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) * 0.96f, rc = R * 0.40f;
        c.drawCircle(cx, cy, R, disk);
        c.drawCircle(cx, cy, R, ring);

        if (pressed >= 0 && pressed < 4) {
            glow.setShader(new RadialGradient(cx, cy, R, new int[] { 0x00000000, (accent & 0x00FFFFFF) | 0x55000000 },
                    new float[] { 0.4f, 1f }, Shader.TileMode.CLAMP));
            oval.set(cx - R, cy - R, cx + R, cy + R);
            c.drawArc(oval, -135 + pressed * 90, 90, true, glow);
        }

        for (int i = 0; i < 4; i++) {
            arrow.setColor(i == pressed ? accent : 0xCCFFFFFF);
            c.save();
            c.rotate(i * 90, cx, cy);
            float ay = cy - R * 0.72f, aw = R * 0.11f;
            path.reset();
            path.moveTo(cx - aw, ay + aw * 0.6f);
            path.lineTo(cx, ay - aw * 0.4f);
            path.lineTo(cx + aw, ay + aw * 0.6f);
            c.drawPath(path, arrow);
            c.restore();
        }

        center.setStyle(Paint.Style.FILL);
        center.setColor(pressed == CENTER ? accent : 0xFF0D1016);
        c.drawCircle(cx, cy, rc, center);
        center.setStyle(Paint.Style.STROKE);
        center.setStrokeWidth(R * 0.012f);
        center.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, rc, center);
        label.setColor(pressed == CENTER ? 0xFF001018 : 0xFFFFFFFF);
        c.drawText("OK", cx, cy + label.getTextSize() * 0.36f, label);
    }

    private int zone(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) * 0.96f;
        double dx = x - cx, dy = y - cy, d = Math.hypot(dx, dy);
        if (d < R * 0.40f) return CENTER;
        if (d > R * 1.08f) return NONE;
        double a = Math.toDegrees(Math.atan2(dy, dx)); // 0 = derecha, 90 = abajo
        if (a >= -135 && a < -45) return 0;
        if (a >= -45 && a < 45) return 1;
        if (a >= 45 && a < 135) return 2;
        return 3;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = zone(e.getX(), e.getY());
                if (pressed == NONE) return false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                longFired = false;
                if (pressed == CENTER) {
                    handler.postDelayed(longOk, 550);
                } else if (listener != null) {
                    listener.onKey(CODES[pressed]);
                    handler.postDelayed(repeat, 420);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed == CENTER && !longFired && listener != null) listener.onKey(OK);
                // fall through
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                handler.removeCallbacks(longOk);
                pressed = NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
