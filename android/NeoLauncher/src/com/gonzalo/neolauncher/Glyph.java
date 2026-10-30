package com.gonzalo.neolauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Íconos simples dibujados con código (sin imágenes): entrada HDMI, Wi-Fi, Ethernet y engranaje. */
final class Glyph extends Drawable {

    static final int INPUT = 0, WIFI = 1, ETHERNET = 2, GEAR = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private int type;

    Glyph(int type) {
        this.type = type;
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setColor(0xFFFFFFFF);
    }

    void setColor(int c) {
        fill.setColor(c);
        line.setColor(c);
        invalidateSelf();
    }

    void setType(int t) {
        type = t;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.left, b.top);
        switch (type) {
            case INPUT:
                r.set(0.30f * s, 0.30f * s, 0.70f * s, 0.74f * s);
                c.drawRoundRect(r, 0.08f * s, 0.08f * s, fill);
                r.set(0.37f * s, 0.06f * s, 0.63f * s, 0.32f * s);
                c.drawRect(r, fill);
                r.set(0.455f * s, 0.72f * s, 0.545f * s, 0.96f * s);
                c.drawRect(r, fill);
                break;
            case WIFI: {
                float cx = 0.5f * s, cy = 0.80f * s;
                line.setStrokeWidth(0.11f * s);
                for (float rad : new float[] { 0.22f, 0.42f, 0.62f }) {
                    r.set(cx - rad * s, cy - rad * s, cx + rad * s, cy + rad * s);
                    c.drawArc(r, -135, 90, false, line);
                }
                c.drawCircle(cx, cy, 0.08f * s, fill);
                break;
            }
            case ETHERNET:
                line.setStrokeWidth(0.08f * s);
                r.set(0.36f * s, 0.06f * s, 0.64f * s, 0.34f * s);
                c.drawRoundRect(r, 0.04f * s, 0.04f * s, fill);
                r.set(0.06f * s, 0.66f * s, 0.34f * s, 0.94f * s);
                c.drawRoundRect(r, 0.04f * s, 0.04f * s, fill);
                r.set(0.66f * s, 0.66f * s, 0.94f * s, 0.94f * s);
                c.drawRoundRect(r, 0.04f * s, 0.04f * s, fill);
                c.drawLine(0.5f * s, 0.34f * s, 0.5f * s, 0.5f * s, line);
                c.drawLine(0.2f * s, 0.5f * s, 0.8f * s, 0.5f * s, line);
                c.drawLine(0.2f * s, 0.5f * s, 0.2f * s, 0.66f * s, line);
                c.drawLine(0.8f * s, 0.5f * s, 0.8f * s, 0.66f * s, line);
                break;
            default: {
                float cx = 0.5f * s, cy = 0.5f * s;
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, cx, cy);
                    r.set(cx - 0.09f * s, 0.03f * s, cx + 0.09f * s, 0.30f * s);
                    c.drawRoundRect(r, 0.03f * s, 0.03f * s, fill);
                    c.restore();
                }
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                path.addCircle(cx, cy, 0.33f * s, Path.Direction.CW);
                path.addCircle(cx, cy, 0.13f * s, Path.Direction.CW);
                c.drawPath(path, fill);
                break;
            }
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { fill.setAlpha(a); line.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); line.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
