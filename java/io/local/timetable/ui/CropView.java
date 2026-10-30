package io.local.timetable.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** 手动裁剪：拖动/双指缩放，裁剪框比例与屏幕一致。 */
public class CropView extends View {

    private final Bitmap bitmap;
    private final RectF frame = new RectF();
    private final RectF dst = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float scale = 1f;
    private float minScale = 1f;
    private float tx;
    private float ty;
    private float lastX;
    private float lastY;
    private boolean dragging;

    private final ScaleGestureDetector scaleDetector;

    public CropView(Context context, Bitmap bmp) {
        super(context);
        bitmap = bmp;
        dimPaint.setColor(0xB3000000);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        gridPaint.setColor(0x40FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.8f));
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float next = Math.max(minScale, Math.min(minScale * 6f, scale * factor));
                if (next == scale) {
                    return true;
                }
                float fx = detector.getFocusX();
                float fy = detector.getFocusY();
                tx = fx - (fx - tx) * (next / scale);
                ty = fy - (fy - ty) * (next / scale);
                scale = next;
                clamp();
                invalidate();
                return true;
            }
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float aspect = dm.widthPixels / (float) dm.heightPixels;
        if (aspect <= 0) {
            aspect = 9f / 19.5f;
        }
        float pad = dp(18);
        float availW = Math.max(1, w - pad * 2);
        float availH = Math.max(1, h - pad * 2);
        float fw = availW;
        float fh = fw / aspect;
        if (fh > availH) {
            fh = availH;
            fw = fh * aspect;
        }
        frame.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f);

        minScale = Math.max(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight());
        scale = minScale;
        tx = frame.centerX() - bitmap.getWidth() * scale / 2f;
        ty = frame.centerY() - bitmap.getHeight() * scale / 2f;
        clamp();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        dst.set(tx, ty, tx + bitmap.getWidth() * scale, ty + bitmap.getHeight() * scale);
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);

        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, frame.top, dimPaint);
        canvas.drawRect(0, frame.bottom, w, h, dimPaint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, dimPaint);
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint);

        canvas.drawRect(frame, borderPaint);
        for (int i = 1; i <= 2; i++) {
            float x = frame.left + frame.width() * i / 3f;
            float y = frame.top + frame.height() * i / 3f;
            canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint);
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()) {
                    tx += event.getX() - lastX;
                    ty += event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    clamp();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return true;
    }

    private void clamp() {
        float iw = bitmap.getWidth() * scale;
        float ih = bitmap.getHeight() * scale;
        if (tx > frame.left) {
            tx = frame.left;
        }
        if (ty > frame.top) {
            ty = frame.top;
        }
        if (tx + iw < frame.right) {
            tx = frame.right - iw;
        }
        if (ty + ih < frame.bottom) {
            ty = frame.bottom - ih;
        }
    }

    /** 输出裁剪结果（比例 = 裁剪框 = 屏幕）。 */
    public Bitmap crop() {
        if (bitmap == null) {
            return null;
        }
        float sx = (frame.left - tx) / scale;
        float sy = (frame.top - ty) / scale;
        float sw = frame.width() / scale;
        float sh = frame.height() / scale;
        sx = Math.max(0, sx);
        sy = Math.max(0, sy);
        sw = Math.min(sw, bitmap.getWidth() - sx);
        sh = Math.min(sh, bitmap.getHeight() - sy);
        int x = Math.round(sx);
        int y = Math.round(sy);
        int w = Math.max(1, Math.min(bitmap.getWidth() - x, Math.round(sw)));
        int h = Math.max(1, Math.min(bitmap.getHeight() - y, Math.round(sh)));
        Bitmap sub = Bitmap.createBitmap(bitmap, x, y, w, h);
        int outW = Math.min(1440, w);
        int outH = Math.max(1, Math.round(outW * frame.height() / frame.width()));
        Bitmap out = Bitmap.createScaledBitmap(sub, outW, outH, true);
        if (out != sub) {
            sub.recycle();
        }
        return out;
    }
}
