package com.example.pocketpdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF page viewer: pinch zoom, double-tap zoom, drag, fling, swipe left/right to change page.
 * Only the visible region is rendered (at full sharpness for the current zoom), so memory stays small.
 * Zoom 1.0 = page width fits the screen width (shown as 100%).
 */
final class PageView extends View {
    interface Listener { void onChanged(); void onError(String message); }

    static final float MIN_ZOOM = 0.25f, MAX_ZOOM = 6f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint whitePaint = new Paint();
    private final Matrix drawMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    private ExecutorService exec;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int pageCount, pageIndex;
    private volatile int docGen;
    private Listener listener;

    private float pageW, pageH;
    private float zoom = 1f, tx, ty;
    private boolean positioned, pendingBottom, scaled;

    private Bitmap bmp, spare;
    private int bmpIndex = -1;
    private float bmpScale, bmpTx, bmpTy;
    private boolean rendering, dirty;

    PageView(Context c) {
        super(c);
        whitePaint.setColor(Color.WHITE);
        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        scaleDetector.setQuickScaleEnabled(false);
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (pageW == 0) return true;
                tx -= dx; ty -= dy;
                clampPosition();
                invalidate();
                requestRender();
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (pageW == 0) return false;
                float s = scale(), cw = pageW * s, ch = pageH * s;
                int vw = getWidth(), vh = getHeight();
                boolean panX = cw > vw + 1, panY = ch > vh + 1;
                float ax = Math.abs(vx), ay = Math.abs(vy);
                if (ax > ay * 1.2f && ax > 700) {
                    boolean atLeft = !panX || tx >= -2, atRight = !panX || tx <= vw - cw + 2;
                    if (vx < 0 && atRight) { goTo(pageIndex + 1, false); return true; }
                    if (vx > 0 && atLeft) { goTo(pageIndex - 1, false); return true; }
                } else if (ay > ax * 1.2f && ay > 1200) {
                    boolean atTop = !panY || ty >= -2, atBottom = !panY || ty <= vh - ch + 2;
                    if (vy < 0 && atBottom) { goTo(pageIndex + 1, false); return true; }
                    if (vy > 0 && atTop) { goTo(pageIndex - 1, true); return true; }
                }
                int maxX = Math.max(0, (int) (cw - vw)), maxY = Math.max(0, (int) (ch - vh));
                int sx = Math.max(0, Math.min(maxX, (int) -tx)), sy = Math.max(0, Math.min(maxY, (int) -ty));
                scroller.fling(sx, sy, (int) -vx, (int) -vy, 0, maxX, 0, maxY);
                postInvalidateOnAnimation();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (pageW == 0) return false;
                if (zoom > 1.05f) zoomAround(1f, e.getX(), e.getY());
                else zoomAround(2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }
    int getPageIndex() { return pageIndex; }
    int getPageCount() { return pageCount; }
    float getZoom() { return zoom; }

    void setDocument(ParcelFileDescriptor fd, PdfRenderer r) {
        release();
        pfd = fd; renderer = r; pageCount = r.getPageCount();
        exec = Executors.newSingleThreadExecutor();
        docGen++;
        zoom = 1f; rendering = false; dirty = false;
        goTo(0, false);
    }

    void release() {
        docGen++;
        if (exec != null) {
            final PdfRenderer r = renderer;
            final ParcelFileDescriptor f = pfd;
            try {
                exec.execute(() -> {
                    try { if (r != null) r.close(); } catch (Exception ignored) {}
                    try { if (f != null) f.close(); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
            exec.shutdown();
            exec = null;
        }
        renderer = null; pfd = null; pageCount = 0; pageW = pageH = 0; positioned = false;
        if (bmp != null) { bmp.recycle(); bmp = null; }
        if (spare != null) { spare.recycle(); spare = null; }
        bmpIndex = -1; rendering = false; dirty = false;
    }

    void goTo(int index, boolean toBottom) {
        if (renderer == null || index < 0 || index >= pageCount) return;
        scroller.forceFinished(true);
        pageIndex = index; pageW = pageH = 0; positioned = false; pendingBottom = toBottom;
        notifyChanged();
        invalidate();
        loadPage();
    }

    void zoomBy(float factor) { zoomAround(zoom * factor, getWidth() / 2f, getHeight() / 2f); }
    void setZoom(float z) { zoomAround(z, getWidth() / 2f, getHeight() / 2f); }

    void fitPage() {
        if (pageW == 0 || getWidth() == 0) return;
        float z = Math.min(1f, (getHeight() * pageW) / (pageH * getWidth()));
        setZoom(z);
    }

    private void notifyChanged() { if (listener != null) listener.onChanged(); }

    private float scale() {
        if (pageW == 0 || getWidth() == 0) return 1f;
        return (getWidth() / pageW) * zoom;
    }

    private void zoomAround(float newZoom, float fx, float fy) {
        if (pageW == 0 || getWidth() == 0) return;
        scroller.forceFinished(true);
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        float oldS = scale();
        float px = (fx - tx) / oldS, py = (fy - ty) / oldS;
        zoom = newZoom;
        float newS = scale();
        tx = fx - px * newS;
        ty = fy - py * newS;
        clampPosition();
        notifyChanged();
        invalidate();
        requestRender();
    }

    private void clampPosition() {
        float s = scale(), cw = pageW * s, ch = pageH * s;
        int vw = getWidth(), vh = getHeight();
        if (cw <= vw) tx = (vw - cw) / 2f; else tx = Math.max(vw - cw, Math.min(0f, tx));
        if (ch <= vh) ty = (vh - ch) / 2f; else ty = Math.max(vh - ch, Math.min(0f, ty));
    }

    private void resetPosition(boolean toBottom) {
        if (getWidth() == 0 || pageW == 0) { positioned = false; return; }
        tx = 0;
        ty = toBottom ? -1e9f : 0f;
        clampPosition();
        positioned = true;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (pageW > 0 && w > 0) {
            if (!positioned) resetPosition(pendingBottom); else clampPosition();
            requestRender();
        }
    }

    private void loadPage() {
        final PdfRenderer r = renderer;
        final ExecutorService ex = exec;
        if (r == null || ex == null) return;
        final int dg = docGen, idx = pageIndex;
        try {
            ex.execute(() -> {
                if (dg != docGen) return;
                try (PdfRenderer.Page p = r.openPage(idx)) {
                    final float w = p.getWidth(), h = p.getHeight();
                    post(() -> {
                        if (dg != docGen || idx != pageIndex) return;
                        pageW = w; pageH = h;
                        resetPosition(pendingBottom);
                        notifyChanged();
                        invalidate();
                        requestRender();
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    post(() -> { if (listener != null) listener.onError("Cannot open page: " + msg); });
                }
            });
        } catch (Exception ignored) {}
    }

    private void requestRender() {
        if (renderer == null || exec == null || pageW == 0 || getWidth() == 0 || getHeight() == 0) return;
        if (rendering) { dirty = true; return; }
        rendering = true; dirty = false;
        final PdfRenderer r = renderer;
        final int dg = docGen, idx = pageIndex, vw = getWidth(), vh = getHeight();
        final float sc = scale(), x = tx, y = ty, pw = pageW, ph = pageH;
        final Bitmap reuse;
        if (spare != null && !spare.isRecycled() && spare.getWidth() == vw && spare.getHeight() == vh) {
            reuse = spare;
        } else {
            reuse = null;
            if (spare != null) spare.recycle();
        }
        spare = null;
        try {
            exec.execute(() -> {
                Bitmap b = null;
                try {
                    b = reuse != null ? reuse : Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.TRANSPARENT);
                    Canvas c = new Canvas(b);
                    c.drawRect(x, y, x + pw * sc, y + ph * sc, whitePaint);
                    Matrix m = new Matrix();
                    m.setScale(sc, sc);
                    m.postTranslate(x, y);
                    try (PdfRenderer.Page p = r.openPage(idx)) {
                        p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                } catch (Throwable t) {
                    if (b != null) b.recycle();
                    b = null;
                }
                final Bitmap result = b;
                post(() -> {
                    if (dg != docGen) { if (result != null) result.recycle(); return; }
                    rendering = false;
                    if (result != null && idx == pageIndex) {
                        if (bmp != null) {
                            if (spare != null) spare.recycle();
                            spare = bmp;
                        }
                        bmp = result; bmpIndex = idx; bmpScale = sc; bmpTx = x; bmpTy = y;
                        invalidate();
                    } else if (result != null) {
                        if (spare == null) spare = result; else result.recycle();
                    }
                    if (dirty) requestRender();
                });
            });
        } catch (Exception e) {
            rendering = false;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFFDDE0E6);
        if (pageW == 0) return;
        float s = scale();
        canvas.drawRect(tx, ty, tx + pageW * s, ty + pageH * s, whitePaint);
        if (bmp != null && bmpIndex == pageIndex && bmpScale > 0) {
            float r = s / bmpScale;
            drawMatrix.setScale(r, r);
            drawMatrix.postTranslate(tx - bmpTx * r, ty - bmpTy * r);
            canvas.drawBitmap(bmp, drawMatrix, bmpPaint);
        }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = -scroller.getCurrX();
            ty = -scroller.getCurrY();
            clampPosition();
            requestRender();
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (renderer == null) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { scroller.forceFinished(true); scaled = false; }
        boolean wasScaled = scaled;
        scaleDetector.onTouchEvent(e);
        if (scaleDetector.isInProgress()) scaled = true;
        if (scaled) {
            if (!wasScaled) {
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                gestureDetector.onTouchEvent(cancel);
                cancel.recycle();
            }
        } else {
            gestureDetector.onTouchEvent(e);
        }
        return true;
    }
}
