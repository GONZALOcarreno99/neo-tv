package com.gonzalo.neolauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import java.io.InputStream;

/**
 * Fondo por sección: imágenes JPEG de assets/bg0..bgN, decodificadas una sola vez en un hilo aparte
 * (RGB_565, la mitad de memoria) y cambiadas con un fundido. Fuera del fundido no se redibuja nada.
 */
final class BackdropView extends View {

    private static final int BASE = 0xFF05070D;

    private final Bitmap[] scenes;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private int from = -1, to = -1;
    private float t = 1f;
    private ValueAnimator anim;

    BackdropView(Context c, int count) {
        super(c);
        scenes = new Bitmap[count];
        final AssetManager am = c.getAssets();
        Thread loader = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                Bitmap b = decode(am, "bg" + i + ".jpg");
                synchronized (scenes) { scenes[i] = b; }
                post(this::invalidate);
            }
        }, "neo-bg");
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private static Bitmap decode(AssetManager am, String name) {
        try (InputStream in = am.open(name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    void setScene(int i) {
        if (i == to) return;
        if (anim != null) anim.cancel();
        from = to;
        to = i;
        if (from < 0) { t = 1f; invalidate(); return; }
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.addUpdateListener(a -> {
            t = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private Bitmap scene(int i) {
        if (i < 0) return null;
        synchronized (scenes) { return scenes[i]; }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BASE);
        dst.set(0, 0, getWidth(), getHeight());
        Bitmap a = scene(from), b = scene(to);
        if (a != null && t < 1f) {
            paint.setAlpha(255);
            c.drawBitmap(a, null, dst, paint);
        }
        if (b != null) {
            paint.setAlpha(t < 1f ? (int) (255 * t) : 255);
            c.drawBitmap(b, null, dst, paint);
        }
    }
}
