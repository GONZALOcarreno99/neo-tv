package com.gonzalo.neoremote;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Íconos de línea dibujados con código para los botones del control. */
final class Ico extends Drawable {

    static final int BACK = 0, HOME = 1, POWER = 2, VOL_UP = 3, VOL_DOWN = 4, MUTE = 5,
            PLAY = 6, KEYBOARD = 7, GEAR = 8, SEARCH = 9, MIC = 10, CLEAN = 11;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Ico(int type, int color) {
        this.type = type;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int c) { p.setColor(c); invalidateSelf(); }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        p.setStrokeWidth(0.09f * s);
        p.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (type) {
            case BACK:
                path.moveTo(0.42f * s, 0.22f * s); path.lineTo(0.16f * s, 0.46f * s); path.lineTo(0.42f * s, 0.70f * s);
                path.moveTo(0.18f * s, 0.46f * s); path.lineTo(0.62f * s, 0.46f * s);
                path.quadTo(0.86f * s, 0.46f * s, 0.86f * s, 0.66f * s);
                c.drawPath(path, p);
                break;
            case HOME:
                path.moveTo(0.14f * s, 0.48f * s); path.lineTo(0.5f * s, 0.16f * s); path.lineTo(0.86f * s, 0.48f * s);
                path.moveTo(0.24f * s, 0.40f * s); path.lineTo(0.24f * s, 0.84f * s); path.lineTo(0.76f * s, 0.84f * s);
                path.lineTo(0.76f * s, 0.40f * s);
                c.drawPath(path, p);
                break;
            case POWER:
                r.set(0.16f * s, 0.18f * s, 0.84f * s, 0.86f * s);
                c.drawArc(r, -60, 300, false, p);
                c.drawLine(0.5f * s, 0.08f * s, 0.5f * s, 0.48f * s, p);
                break;
            case VOL_UP:
            case VOL_DOWN:
                c.drawLine(0.22f * s, 0.5f * s, 0.78f * s, 0.5f * s, p);
                if (type == VOL_UP) c.drawLine(0.5f * s, 0.22f * s, 0.5f * s, 0.78f * s, p);
                break;
            case MUTE:
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(0.12f * s, 0.38f * s); path.lineTo(0.28f * s, 0.38f * s); path.lineTo(0.48f * s, 0.2f * s);
                path.lineTo(0.48f * s, 0.8f * s); path.lineTo(0.28f * s, 0.62f * s); path.lineTo(0.12f * s, 0.62f * s); path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(0.62f * s, 0.38f * s, 0.86f * s, 0.62f * s, p);
                c.drawLine(0.86f * s, 0.38f * s, 0.62f * s, 0.62f * s, p);
                break;
            case PLAY:
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(0.14f * s, 0.24f * s); path.lineTo(0.48f * s, 0.5f * s); path.lineTo(0.14f * s, 0.76f * s); path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(0.66f * s, 0.26f * s, 0.66f * s, 0.74f * s, p);
                c.drawLine(0.84f * s, 0.26f * s, 0.84f * s, 0.74f * s, p);
                break;
            case KEYBOARD:
                p.setStrokeWidth(0.07f * s);
                r.set(0.08f * s, 0.24f * s, 0.92f * s, 0.78f * s);
                c.drawRoundRect(r, 0.1f * s, 0.1f * s, p);
                p.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; row++)
                    for (int k = 0; k < 5; k++)
                        c.drawCircle((0.22f + k * 0.14f) * s, (0.38f + row * 0.13f) * s, 0.035f * s, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(0.32f * s, 0.65f * s, 0.68f * s, 0.65f * s, p);
                break;
            case MIC:
                r.set(0.36f * s, 0.08f * s, 0.64f * s, 0.6f * s);
                c.drawRoundRect(r, 0.14f * s, 0.14f * s, p);
                r.set(0.22f * s, 0.22f * s, 0.78f * s, 0.74f * s);
                c.drawArc(r, 20, 140, false, p);
                c.drawLine(0.5f * s, 0.74f * s, 0.5f * s, 0.9f * s, p);
                break;
            case CLEAN: // escoba
                c.drawLine(0.80f * s, 0.10f * s, 0.52f * s, 0.46f * s, p);
                path.moveTo(0.40f * s, 0.38f * s); path.lineTo(0.66f * s, 0.58f * s);
                path.lineTo(0.48f * s, 0.88f * s); path.lineTo(0.14f * s, 0.62f * s); path.close();
                c.drawPath(path, p);
                c.drawLine(0.40f * s, 0.62f * s, 0.30f * s, 0.75f * s, p);
                c.drawLine(0.52f * s, 0.70f * s, 0.42f * s, 0.83f * s, p);
                break;
            case SEARCH:
                c.drawCircle(0.42f * s, 0.42f * s, 0.24f * s, p);
                c.drawLine(0.6f * s, 0.6f * s, 0.84f * s, 0.84f * s, p);
                break;
            default: {
                p.setStrokeWidth(0.08f * s);
                float cx = 0.5f * s, cy = 0.5f * s;
                c.drawCircle(cx, cy, 0.13f * s, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    c.drawLine(cx + (float) Math.cos(a) * 0.27f * s, cy + (float) Math.sin(a) * 0.27f * s,
                            cx + (float) Math.cos(a) * 0.40f * s, cy + (float) Math.sin(a) * 0.40f * s, p);
                }
                c.drawCircle(cx, cy, 0.27f * s, p);
                break;
            }
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
