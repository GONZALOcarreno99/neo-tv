package com.gonzalo.neolauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * Fondo negro con un brillo ambiental del color de la app enfocada.
 * El brillo se pinta en un bitmap chiquito (128x72) y se estira: el GPU solo copia una imagen por cuadro.
 */
final class GlowView extends View {

    private static final int SW = 128, SH = 72;

    private final Bitmap small = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final Canvas smallCanvas = new Canvas(small);
    private final Paint glowPaint = new Paint();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private Bitmap wallpaper;
    private int color;
    private ValueAnimator anim;

    GlowView(Context c) {
        super(c);
    }

    void setWallpaper(Bitmap b) {
        wallpaper = b;
        invalidate();
    }

    /** Lleva el brillo hacia un color; 0 lo apaga con un fundido. */
    void glowTo(int target) {
        int to = target == 0 ? (color & 0x00FFFFFF) : target;
        if (to == color) return;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofObject(new ArgbEvaluator(), color, to);
        anim.setDuration(420);
        anim.addUpdateListener(a -> {
            color = (Integer) a.getAnimatedValue();
            render();
            invalidate();
        });
        anim.start();
    }

    private void render() {
        small.eraseColor(0);
        int alpha = color >>> 24;
        if (alpha == 0) return;
        int rgb = color & 0x00FFFFFF;
        glowPaint.setShader(new RadialGradient(SW * 0.30f, SH * 0.42f, SW * 0.62f,
                new int[] { (alpha * 3 / 4) << 24 | rgb, (alpha / 4) << 24 | rgb, rgb },
                new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        smallCanvas.drawRect(0, 0, SW, SH, glowPaint);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF000000);
        dst.set(0, 0, getWidth(), getHeight());
        if (wallpaper != null) {
            c.drawBitmap(wallpaper, null, dst, bmp);
            c.drawColor(0x66000000);
        }
        c.drawBitmap(small, null, dst, bmp);
    }
}
