package io.github.cleantv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

/** Frosted glass: the blurred wallpaper, lined up with the real one behind it, plus a tint. */
final class Frost extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] loc = new int[2];
    private final int overlay;
    private Bitmap blur;
    private int tint;

    Frost(Context c, int overlay) {
        super(c);
        this.overlay = overlay;
    }

    /** Takes whatever size its parent gives it, never decides that size itself. */
    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(
                MeasureSpec.getMode(w) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(w) : 0,
                MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0);
    }

    void set(Wall w) {
        blur = w.blur;
        tint = w.tint;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (blur != null) {
            View root = getRootView();
            getLocationInWindow(loc);
            float s = Math.max(root.getWidth() / (float) blur.getWidth(),
                    root.getHeight() / (float) blur.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate((root.getWidth() - blur.getWidth() * s) / 2f - loc[0],
                    (root.getHeight() - blur.getHeight() * s) / 2f - loc[1]);
            c.drawBitmap(blur, matrix, paint);
        }
        c.drawColor(tint);
        if (overlay != 0) c.drawColor(overlay);
    }
}
