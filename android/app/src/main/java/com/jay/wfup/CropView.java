package com.jay.wfup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Square crop viewport with drag and pinch zoom, the same idea as the vendor app's
 * custom face dialog: the square is what the round watch will show, so the photo is
 * cropped to that square instead of being squashed into 466x466.
 *
 * The bitmap is always kept larger than the viewport, so the crop can never run past
 * the image edges.
 */
public class CropView extends View {

    private Bitmap src;
    private float scale = 1f;
    private float tx = 0f, ty = 0f;

    private float vx, vy, vs;                     // viewport rect (square)
    private float lastX, lastY, lastDist;
    private float minScale = 1f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final RectF tmp = new RectF();

    public CropView(Context c) {
        super(c);
        dimPaint.setColor(0xAA000000);
        linePaint.setColor(0xCCFFFFFF);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
    }

    public void setBitmap(Bitmap b) {
        src = b;
        if (getWidth() > 0) fit();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        vs = Math.min(w, h) * 0.94f;
        vx = (w - vs) / 2f;
        vy = (h - vs) / 2f;
        if (src != null) fit();
    }

    /** Cover the viewport and centre the image. */
    private void fit() {
        if (src == null || vs <= 0) return;
        minScale = Math.max(vs / src.getWidth(), vs / src.getHeight());
        scale = minScale;
        tx = vx + (vs - src.getWidth() * scale) / 2f;
        ty = vy + (vs - src.getHeight() * scale) / 2f;
        clamp();
        invalidate();
    }

    private void clamp() {
        if (src == null) return;
        float w = src.getWidth() * scale, h = src.getHeight() * scale;
        if (w <= vs) tx = vx + (vs - w) / 2f;
        else tx = Math.max(vx + vs - w, Math.min(vx, tx));
        if (h <= vs) ty = vy + (vs - h) / 2f;
        else ty = Math.max(vy + vs - h, Math.min(vy, ty));
    }

    private void zoomAt(float fx, float fy, float factor) {
        if (src == null) return;
        float want = scale * factor;
        float limit = minScale * 8f;
        want = Math.max(minScale, Math.min(limit, want));
        float k = want / scale;
        tx = fx - (fx - tx) * k;
        ty = fy - (fy - ty) * k;
        scale = want;
        clamp();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (src == null) return;
        canvas.save();
        canvas.translate(tx, ty);
        canvas.scale(scale, scale);
        canvas.drawBitmap(src, 0, 0, bmpPaint);
        canvas.restore();

        // dim everything outside the square
        canvas.drawRect(0, 0, getWidth(), vy, dimPaint);
        canvas.drawRect(0, vy + vs, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, vy, vx, vy + vs, dimPaint);
        canvas.drawRect(vx + vs, vy, getWidth(), vy + vs, dimPaint);

        // frame + thirds
        canvas.drawRect(vx, vy, vx + vs, vy + vs, linePaint);
        linePaint.setAlpha(0x66);
        tmp.set(vx + vs / 3f, vy, vx + vs / 3f, vy + vs);
        canvas.drawRect(tmp, linePaint);
        tmp.set(vx + 2 * vs / 3f, vy, vx + 2 * vs / 3f, vy + vs);
        canvas.drawRect(tmp, linePaint);
        tmp.set(vx, vy + vs / 3f, vx + vs, vy + vs / 3f);
        canvas.drawRect(tmp, linePaint);
        tmp.set(vx, vy + 2 * vs / 3f, vx + vs, vy + 2 * vs / 3f);
        canvas.drawRect(tmp, linePaint);
        linePaint.setAlpha(0xCC);
    }

    private float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                lastDist = 0f;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastDist = dist(e);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                lastDist = 0f;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float d = dist(e);
                    if (lastDist > 0f) zoomAt((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f, d / lastDist);
                    lastDist = d;
                } else {
                    tx += e.getX() - lastX;
                    ty += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    clamp();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastDist = 0f;
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** The square under the viewport, scaled to {@code out} x {@code out}. */
    public Bitmap crop(int out) {
        if (src == null || vs <= 0) return null;
        float left = (vx - tx) / scale;
        float top = (vy - ty) / scale;
        float size = vs / scale;

        int x = Math.round(left);
        int y = Math.round(top);
        int s = Math.round(size);
        x = Math.max(0, Math.min(src.getWidth() - 1, x));
        y = Math.max(0, Math.min(src.getHeight() - 1, y));
        s = Math.max(1, Math.min(s, Math.min(src.getWidth() - x, src.getHeight() - y)));

        Bitmap cut = Bitmap.createBitmap(src, x, y, s, s);
        if (s == out) return cut;
        Bitmap scaled = Bitmap.createScaledBitmap(cut, out, out, true);
        if (scaled != cut) cut.recycle();
        return scaled;
    }

    /** True when the crop covers less than the full frame, used for the report line. */
    public float zoomFactor() {
        return minScale <= 0 ? 1f : scale / minScale;
    }
}
