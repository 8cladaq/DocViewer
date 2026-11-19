package com.example.doclens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws a PDF page and lets the user place original annotations on it. */
public class PdfPageView extends View {

    public enum Tool { NONE, PEN, HIGHLIGHT, TEXT, NOTE, SIGNATURE, REDACT }

    public interface Listener {
        void onPathFinished(Annotation annotation);

        void onBoxRequested(Annotation.Type type, float pdfX, float pdfY);

        void onRedactionFinished(Annotation annotation);

        void onToggleChrome();

        void onZoomChanged(boolean zoomedIn);
    }

    /** A text line converted to top-left page coordinates for highlight snapping. */
    public static final class TextLineBox {
        public final int pageIndex;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public TextLineBox(int pageIndex, float left, float top, float right, float bottom) {
            this.pageIndex = pageIndex;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final float MIN_DISTANCE_POINTS = 1.5f;
    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private Bitmap bitmap;
    private float pdfWidth;
    private float pdfHeight;
    private int currentPage;
    private List<Annotation> annotations;
    private Tool tool = Tool.NONE;
    private Listener listener;
    private Annotation active;
    private boolean chromeVisible;
    private boolean zoomedIn;
    private List<TextLineBox> textLines = new ArrayList<>();

    private float zoom = 1f;
    private float panX;
    private float panY;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public PdfPageView(Context context) {
        super(context);
        init();
    }

    public PdfPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.rgb(30, 41, 59));
        setClickable(true);

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        active = null;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        setZoom(zoom * detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                });

        gestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (listener == null || bitmap == null) {
                            return true;
                        }
                        if (chromeVisible && (tool == Tool.TEXT || tool == Tool.NOTE)) {
                            float[] p = viewToPdf(e.getX(), e.getY());
                            Annotation.Type type = tool == Tool.TEXT
                                    ? Annotation.Type.TEXT : Annotation.Type.NOTE;
                            listener.onBoxRequested(type, p[0], p[1]);
                        }
                        if (!zoomedIn) {
                            listener.onToggleChrome();
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        active = null;
                        if (zoom > 1.01f) {
                            setZoom(1f, getWidth() / 2f, getHeight() / 2f);
                        } else {
                            setZoom(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                        }
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (tool != Tool.NONE || scaleDetector.isInProgress()) {
                            return false;
                        }
                        panX -= distanceX;
                        panY -= distanceY;
                        clampPan();
                        invalidate();
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        active = null;
        invalidate();
    }

    public void setChromeVisible(boolean chromeVisible) {
        this.chromeVisible = chromeVisible;
    }

    public void setPage(Bitmap bitmap, float pdfWidth, float pdfHeight,
                        List<Annotation> annotations, int currentPage) {
        if (this.bitmap != null && this.bitmap != bitmap) {
            this.bitmap.recycle();
        }
        this.bitmap = bitmap;
        this.pdfWidth = pdfWidth;
        this.pdfHeight = pdfHeight;
        this.annotations = annotations;
        this.currentPage = currentPage;
        resetView();
    }

    public void resetView() {
        boolean wasZoomedIn = zoomedIn;
        zoom = 1f;
        zoomedIn = false;
        panX = 0f;
        panY = 0f;
        active = null;
        if (wasZoomedIn && listener != null) {
            listener.onZoomChanged(false);
        }
        invalidate();
    }

    public void setTextLines(List<TextLineBox> textLines) {
        this.textLines = textLines == null ? new ArrayList<>() : textLines;
        invalidate();
    }

    private float renderScale() {
        if (bitmap == null || pdfWidth <= 0) {
            return 1f;
        }
        return bitmap.getWidth() / pdfWidth;
    }

    private float baseDrawScale() {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0
                || getWidth() <= 0 || getHeight() <= 0) {
            return 1f;
        }
        return Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
    }

    private float drawScale() {
        return baseDrawScale() * zoom;
    }

    private float pageWidth() {
        return bitmap == null ? 0f : bitmap.getWidth() * drawScale();
    }

    private float pageHeight() {
        return bitmap == null ? 0f : bitmap.getHeight() * drawScale();
    }

    private float pageLeft() {
        return (getWidth() - pageWidth()) / 2f + panX;
    }

    private float pageTop() {
        return (getHeight() - pageHeight()) / 2f + panY;
    }

    private void clampPan() {
        float halfOverflowX = Math.max(0f, (pageWidth() - getWidth()) / 2f);
        float halfOverflowY = Math.max(0f, (pageHeight() - getHeight()) / 2f);
        panX = Math.max(-halfOverflowX, Math.min(halfOverflowX, panX));
        panY = Math.max(-halfOverflowY, Math.min(halfOverflowY, panY));
    }

    private void setZoom(float targetZoom, float focusX, float focusY) {
        if (bitmap == null) {
            return;
        }
        float nextZoom = Math.max(1f, Math.min(MAX_ZOOM, targetZoom));
        float oldScale = drawScale();
        float bitmapX = (focusX - pageLeft()) / oldScale;
        float bitmapY = (focusY - pageTop()) / oldScale;

        boolean nowZoomedIn = nextZoom > 1.01f;
        if (nowZoomedIn != zoomedIn) {
            zoomedIn = nowZoomedIn;
            if (listener != null) {
                listener.onZoomChanged(zoomedIn);
            }
        }
        zoom = nextZoom;
        float nextScale = drawScale();
        float centeredLeft = (getWidth() - bitmap.getWidth() * nextScale) / 2f;
        float centeredTop = (getHeight() - bitmap.getHeight() * nextScale) / 2f;
        panX = focusX - centeredLeft - bitmapX * nextScale;
        panY = focusY - centeredTop - bitmapY * nextScale;
        clampPan();
        invalidate();
    }

    private float[] viewToPdf(float x, float y) {
        float scale = drawScale() * renderScale();
        if (scale <= 0f) {
            scale = 1f;
        }
        return new float[]{(x - pageLeft()) / scale, (y - pageTop()) / scale};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || annotations == null) {
            return;
        }
        float ds = drawScale();
        canvas.drawBitmap(bitmap, null,
                new RectF(pageLeft(), pageTop(),
                        pageLeft() + bitmap.getWidth() * ds,
                        pageTop() + bitmap.getHeight() * ds), bitmapPaint);

        float s = ds * renderScale();
        canvas.save();
        canvas.translate(pageLeft(), pageTop());
        for (Annotation a : annotations) {
            if (a.pageIndex != currentPage) {
                continue;
            }
            if (a.type == Annotation.Type.HIGHLIGHT && hasHighlightBox(a)) {
                drawHighlightBox(canvas, a, s);
            } else if (a.type == Annotation.Type.PEN || a.type == Annotation.Type.HIGHLIGHT
                    || a.type == Annotation.Type.SIGNATURE) {
                drawPath(canvas, a, s);
            } else if (a.type == Annotation.Type.TEXT) {
                drawText(canvas, a, s);
            } else if (a.type == Annotation.Type.NOTE) {
                drawNote(canvas, a, s);
            } else if (a.type == Annotation.Type.REDACT) {
                drawRedaction(canvas, a, s);
            }
        }
        if (active != null && active.pageIndex == currentPage) {
            if (active.type == Annotation.Type.HIGHLIGHT && hasHighlightBox(active)) {
                drawHighlightBox(canvas, active, s);
            } else if (active.type != Annotation.Type.REDACT) {
                drawPath(canvas, active, s);
            } else {
                drawRedaction(canvas, active, s);
            }
        }
        canvas.restore();
    }

    private static boolean hasHighlightBox(Annotation a) {
        return a.rect.width() > 1f && a.rect.height() > 1f;
    }

    private void drawHighlightBox(Canvas canvas, Annotation a, float s) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(a.color);
        boxPaint.setAlpha(95);
        canvas.drawRect(a.rect.left * s, a.rect.top * s,
                a.rect.right * s, a.rect.bottom * s, boxPaint);
        boxPaint.setAlpha(255);
    }

    private void drawPath(Canvas canvas, Annotation a, float s) {
        if (a.points.isEmpty()) {
            return;
        }
        strokePaint.setColor(a.color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f, a.width * s));
        strokePaint.setAlpha(a.type == Annotation.Type.HIGHLIGHT ? 110 : 255);
        if (a.points.size() == 1) {
            PointF only = a.points.get(0);
            canvas.drawPoint(only.x * s, only.y * s, strokePaint);
            strokePaint.setAlpha(255);
            return;
        }
        Path path = new Path();
        PointF first = a.points.get(0);
        path.moveTo(first.x * s, first.y * s);
        for (int i = 1; i < a.points.size(); i++) {
            PointF p = a.points.get(i);
            path.lineTo(p.x * s, p.y * s);
        }
        canvas.drawPath(path, strokePaint);
        strokePaint.setAlpha(255);
    }

    private void drawText(Canvas canvas, Annotation a, float s) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawRoundRect(a.rect.left * s, a.rect.top * s,
                a.rect.right * s, a.rect.bottom * s, 6 * s, 6 * s, boxPaint);
        textPaint.setTextSize(13f * s);
        String label = a.text.length() > 34 ? a.text.substring(0, 34) + "…" : a.text;
        canvas.drawText(label, a.rect.left * s + 6 * s, a.rect.top * s + 18 * s, textPaint);
    }

    private void drawNote(Canvas canvas, Annotation a, float s) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(a.color);
        canvas.drawRoundRect(a.rect.left * s, a.rect.top * s,
                a.rect.right * s, a.rect.bottom * s, 4 * s, 4 * s, boxPaint);
        textPaint.setTextSize(13f * s);
        textPaint.setColor(Color.rgb(120, 53, 15));
        canvas.drawText("N", a.rect.left * s + 7 * s, a.rect.top * s + 17 * s, textPaint);
        textPaint.setColor(Color.rgb(30, 41, 59));
    }

    private void drawRedaction(Canvas canvas, Annotation a, float s) {
        boxPaint.setStyle(Paint.Style.FILL);
        boxPaint.setColor(Color.BLACK);
        canvas.drawRect(a.rect.left * s, a.rect.top * s,
                a.rect.right * s, a.rect.bottom * s, boxPaint);
    }

    private void straightenHighlight(Annotation annotation) {
        straightenHighlight(annotation, textLines, currentPage);
    }

    /** Converts a freehand highlight into a straight band snapped to detected text. */
    static void straightenHighlight(Annotation annotation, List<TextLineBox> lines, int page) {
        float minX = annotation.points.get(0).x;
        float maxX = minX;
        float minY = annotation.points.get(0).y;
        float maxY = minY;
        for (PointF point : annotation.points) {
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        float centerY = (minY + maxY) / 2f;

        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        boolean foundText = false;
        for (TextLineBox line : lines) {
            if (line.pageIndex != page) {
                continue;
            }
            float overlap = Math.min(maxX, line.right) - Math.max(minX, line.left);
            if (overlap <= 0f) {
                continue;
            }
            float lineCenter = (line.top + line.bottom) / 2f;
            float tolerance = Math.max(10f,
                    Math.max((line.bottom - line.top) * 1.2f, (maxY - minY) / 2f + 4f));
            if (Math.abs(lineCenter - centerY) > tolerance) {
                continue;
            }
            foundText = true;
            left = Math.min(left, line.left);
            top = Math.min(top, line.top);
            right = Math.max(right, line.right);
            bottom = Math.max(bottom, line.bottom);
        }

        if (foundText) {
            annotation.rect.set(left - 1.5f, top - 1f, right + 1.5f, bottom + 1f);
        } else {
            float halfHeight = Math.max(5f, Math.min(9f, (maxY - minY) / 2f + 2f));
            annotation.rect.set(minX, centerY - halfHeight, maxX, centerY + halfHeight);
        }
        annotation.rect.sort();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return super.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
            active = null;
            return true;
        }

        if (tool == Tool.NONE || listener == null
                || tool == Tool.TEXT || tool == Tool.NOTE) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }

        float[] p = viewToPdf(event.getX(), event.getY());
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (tool == Tool.REDACT) {
                    active = Annotation.redaction(currentPage,
                            new RectF(p[0], p[1], p[0], p[1]));
                } else {
                    int color;
                    float width;
                    Annotation.Type type;
                    if (tool == Tool.HIGHLIGHT) {
                        color = Color.rgb(250, 204, 21);
                        width = 14f;
                        type = Annotation.Type.HIGHLIGHT;
                    } else if (tool == Tool.SIGNATURE) {
                        color = Color.rgb(37, 99, 235);
                        width = 2.2f;
                        type = Annotation.Type.SIGNATURE;
                    } else {
                        color = Color.rgb(220, 38, 38);
                        width = 2f;
                        type = Annotation.Type.PEN;
                    }
                    active = Annotation.path(type, currentPage, color, width);
                    active.points.add(new PointF(p[0], p[1]));
                }
                invalidate();
                break;

            case MotionEvent.ACTION_MOVE:
                if (active == null) {
                    break;
                }
                if (active.type == Annotation.Type.REDACT) {
                    active.rect.right = p[0];
                    active.rect.bottom = p[1];
                } else {
                    PointF last = active.points.get(active.points.size() - 1);
                    if (Math.hypot(p[0] - last.x, p[1] - last.y) >= MIN_DISTANCE_POINTS) {
                        active.points.add(new PointF(p[0], p[1]));
                    }
                }
                invalidate();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (active != null) {
                    boolean valid = active.type == Annotation.Type.REDACT
                            ? Math.abs(active.rect.width()) > 4 && Math.abs(active.rect.height()) > 4
                            : active.points.size() > 1;
                    if (valid && action == MotionEvent.ACTION_UP) {
                        if (active.type == Annotation.Type.REDACT) {
                            active.rect.sort();
                            listener.onRedactionFinished(active);
                        } else {
                            if (active.type == Annotation.Type.HIGHLIGHT) {
                                straightenHighlight(active);
                            }
                            listener.onPathFinished(active);
                        }
                    }
                    active = null;
                    invalidate();
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                break;

            default:
                break;
        }
        return true;
    }
}
