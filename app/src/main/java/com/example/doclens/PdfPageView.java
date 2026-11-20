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
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Continuous, vertically scrolling PDF surface. Pages are laid out in one strip,
 * so swiping behaves like a document viewer instead of a slide deck. Ink, page
 * coordinates, zoom and the chrome toggle are handled here as before.
 */
public class PdfPageView extends View {

    public enum Tool { NONE, PEN, HIGHLIGHT, TEXT, NOTE, SIGNATURE, REDACT }

    public interface Listener {
        void onPathFinished(Annotation annotation);

        void onBoxRequested(Annotation.Type type, int page, float pdfX, float pdfY);

        void onRedactionFinished(Annotation annotation);

        void onToggleChrome();

        void onZoomChanged(boolean zoomedIn);

        void onPageChanged(int pageIndex);

        void onTextSelected(int pageIndex, String text);

        void onTextSelectionUnavailable();
    }

    /** Supplies page bitmaps on demand so the whole document is never in memory. */
    public interface PageSource {
        int pageCount();

        List<Annotation> annotations();

        void requestPage(int index);
    }

    /** One word with exact top-left PDF point bounds, used for precise copying. */
    public static final class WordBox {
        public final String text;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final int lineIndex;

        public WordBox(String text, float left, float top, float right, float bottom) {
            this(text, left, top, right, bottom, -1);
        }

        public WordBox(String text, float left, float top, float right, float bottom,
                       int lineIndex) {
            this.text = text == null ? "" : text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.lineIndex = lineIndex;
        }
    }

    /** A text line converted to top-left page coordinates for highlights and copying. */
    public static final class TextLineBox {
        public final int pageIndex;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final String text;
        public final List<WordBox> words;

        public TextLineBox(int pageIndex, float left, float top, float right, float bottom) {
            this(pageIndex, left, top, right, bottom, "", new ArrayList<>());
        }

        public TextLineBox(int pageIndex, float left, float top, float right, float bottom,
                           String text, List<WordBox> words) {
            this.pageIndex = pageIndex;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.text = text == null ? "" : text;
            this.words = words == null ? new ArrayList<>() : words;
        }
    }

    private static final float MIN_DISTANCE_POINTS = 1.5f;
    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private int pageCount;
    private float pdfWidth = 1f;
    private float pdfHeight = 1f;
    private int bitmapWidth;
    private int bitmapHeight;
    private final SparseArray<Bitmap> pages = new SparseArray<>();
    private PageSource pageSource;
    private List<Annotation> annotations = new ArrayList<>();
    private int currentPage;

    private Tool tool = Tool.NONE;
    private Listener listener;
    private Annotation active;
    private boolean chromeVisible;
    private boolean zoomedIn;
    private boolean selectionMode;
    private RectF selectionRect;
    private int selectionPage;
    private final List<WordBox> selectionWords = new ArrayList<>();
    private int selectionStartIndex = -1;
    private int selectionEndIndex = -1;
    private List<TextLineBox> textLines = new ArrayList<>();

    private float zoom = 1f;
    private float scrollY;
    private float panX;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private Scroller scroller;
    private VelocityTracker velocityTracker;

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
        placeholderPaint.setColor(Color.rgb(248, 250, 252));
        dividerPaint.setColor(Color.rgb(55, 65, 81));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(1f);
        selectionPaint.setColor(Color.rgb(37, 99, 235));
        setClickable(true);

        scroller = new Scroller(getContext());

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        active = null;
                        scroller.forceFinished(true);
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
                        if (selectionMode) {
                            return true;
                        }
                        if (listener == null || pageCount == 0) {
                            return true;
                        }
                        if (chromeVisible && (tool == Tool.TEXT || tool == Tool.NOTE)) {
                            int page = pageIndexAtY(e.getY());
                            float[] p = viewToPdf(e.getX(), e.getY(), page);
                            Annotation.Type type = tool == Tool.TEXT
                                    ? Annotation.Type.TEXT : Annotation.Type.NOTE;
                            listener.onBoxRequested(type, page, p[0], p[1]);
                        }
                        if (!zoomedIn) {
                            listener.onToggleChrome();
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (selectionMode) {
                            return true;
                        }
                        active = null;
                        scroller.forceFinished(true);
                        if (zoom > 1.01f) {
                            setZoom(1f, getWidth() / 2f, getHeight() / 2f);
                        } else {
                            setZoom(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                        }
                        invalidate();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (selectionMode || scaleDetector.isInProgress()
                                || tool != Tool.NONE || listener == null) {
                            return;
                        }
                        beginSelection(e.getX(), e.getY());
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (selectionMode || tool != Tool.NONE || scaleDetector.isInProgress()) {
                            return false;
                        }
                        scroller.forceFinished(true);
                        panX -= distanceX;
                        scrollY += distanceY;
                        clampPan();
                        clampScroll();
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (selectionMode || tool != Tool.NONE || scaleDetector.isInProgress()) {
                            return false;
                        }
                        float panLimit = Math.max(0f,
                                (pageWidth() * zoom - getWidth()) / 2f);
                        float scrollLimit = Math.max(0f,
                                contentHeight() - getHeight() / Math.max(0.01f, zoom));
                        scroller.fling(Math.round(panX), Math.round(scrollY),
                                Math.round(velocityX), Math.round(-velocityY),
                                Math.round(-panLimit), Math.round(panLimit),
                                0, Math.round(scrollLimit));
                        invalidate();
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPageSource(PageSource pageSource) {
        this.pageSource = pageSource;
    }

    public void setDocument(int pageCount, float pdfWidth, float pdfHeight,
                            int bitmapWidth, int bitmapHeight,
                            List<Annotation> annotations) {
        this.pageCount = Math.max(0, pageCount);
        this.pdfWidth = Math.max(1f, pdfWidth);
        this.pdfHeight = Math.max(1f, pdfHeight);
        this.bitmapWidth = Math.max(1, bitmapWidth);
        this.bitmapHeight = Math.max(1, bitmapHeight);
        this.annotations = annotations == null ? new ArrayList<>() : annotations;
        recyclePages();
        currentPage = 0;
        resetView();
    }

    /** Drops all decoded pages without touching the document or annotation list. */
    public void clear() {
        pageCount = 0;
        annotations = new ArrayList<>();
        recyclePages();
        resetView();
    }

    private void recyclePages() {
        for (int i = 0; i < pages.size(); i++) {
            Bitmap bitmap = pages.valueAt(i);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        pages.clear();
    }

    public void setPageBitmap(int index, Bitmap bitmap) {
        if (index < 0 || index >= pageCount || bitmap == null) {
            return;
        }
        Bitmap old = pages.get(index);
        if (old != null && old != bitmap && !old.isRecycled()) {
            old.recycle();
        }
        pages.put(index, bitmap);
        invalidate();
    }

    public void setTool(Tool tool) {
        clearSelection();
        this.tool = tool;
        active = null;
        invalidate();
    }

    /** Enables drag-to-copy text selection without adding a fourth edit tool. */
    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (enabled) {
            tool = Tool.NONE;
        }
        if (!enabled) {
            clearSelection();
        }
        active = null;
        scroller.forceFinished(true);
        invalidate();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    private void clearSelection() {
        selectionMode = false;
        selectionRect = null;
        selectionWords.clear();
        selectionStartIndex = -1;
        selectionEndIndex = -1;
    }

    /** Starts a word selection from a long press and returns true when text was hit. */
    private boolean beginSelection(float x, float y) {
        int page = pageIndexAtY(y);
        float[] point = viewToPdf(x, y, page);
        selectionWords.clear();
        for (TextLineBox line : textLines) {
            if (line.pageIndex == page) {
                selectionWords.addAll(line.words);
            }
        }
        if (selectionWords.isEmpty()) {
            notifySelectionUnavailable();
            return false;
        }
        Collections.sort(selectionWords, (a, b) -> {
            if (a.lineIndex != b.lineIndex) {
                return Integer.compare(a.lineIndex, b.lineIndex);
            }
            if (Math.abs(a.top - b.top) > 2f) {
                return Float.compare(a.top, b.top);
            }
            return Float.compare(a.left, b.left);
        });
        int hit = nearestWordIndex(selectionWords, point[0], point[1], 8f);
        if (hit < 0) {
            notifySelectionUnavailable();
            return false;
        }
        selectionPage = page;
        selectionStartIndex = hit;
        selectionEndIndex = hit;
        selectionMode = true;
        updateSelectionRect();
        invalidate();
        return true;
    }

    /** Extends the active selection to the word nearest the drag point. */
    private void updateSelection(float x, float y) {
        if (selectionStartIndex < 0 || pageIndexAtY(y) != selectionPage) {
            return;
        }
        float[] point = viewToPdf(x, y, selectionPage);
        int hit = nearestWordIndex(selectionWords, point[0], point[1], 14f);
        if (hit < 0 || hit == selectionEndIndex) {
            return;
        }
        selectionEndIndex = hit;
        updateSelectionRect();
        invalidate();
    }

    private static int nearestWordIndex(List<WordBox> words, float x, float y, float tolerance) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            WordBox word = words.get(i);
            if (word.text.trim().isEmpty()) {
                continue;
            }
            float dx = Math.max(Math.max(word.left - x, x - word.right), 0f);
            float dy = Math.max(Math.max(word.top - y, y - word.bottom), 0f);
            float distance = dx * dx + dy * dy;
            if (distance <= tolerance * tolerance && distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void updateSelectionRect() {
        if (selectionStartIndex < 0 || selectionEndIndex < 0
                || selectionWords.isEmpty()) {
            selectionRect = null;
            return;
        }
        int from = Math.min(selectionStartIndex, selectionEndIndex);
        int to = Math.max(selectionStartIndex, selectionEndIndex);
        RectF rect = null;
        for (int i = from; i <= to; i++) {
            WordBox word = selectionWords.get(i);
            if (rect == null) {
                rect = new RectF(word.left, word.top, word.right, word.bottom);
            } else {
                rect.union(word.left, word.top, word.right, word.bottom);
            }
        }
        selectionRect = rect;
    }

    private String selectedText() {
        if (selectionStartIndex < 0 || selectionEndIndex < 0
                || selectionWords.isEmpty()) {
            return "";
        }
        int from = Math.min(selectionStartIndex, selectionEndIndex);
        int to = Math.max(selectionStartIndex, selectionEndIndex);
        StringBuilder text = new StringBuilder();
        int previousLine = Integer.MIN_VALUE;
        for (int i = from; i <= to; i++) {
            WordBox word = selectionWords.get(i);
            String token = word.text.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                if (word.lineIndex != previousLine) {
                    text.append('\n');
                } else if (!Character.isWhitespace(text.charAt(text.length() - 1))
                        && !Character.isWhitespace(token.charAt(0))) {
                    text.append(' ');
                }
            }
            text.append(token);
            previousLine = word.lineIndex;
        }
        return text.toString();
    }

    private void commitSelection() {
        int page = selectionPage;
        String text = selectedText();
        clearSelection();
        invalidate();
        if (listener != null) {
            listener.onTextSelected(page, text);
        }
    }

    private void notifySelectionUnavailable() {
        if (listener != null) {
            listener.onTextSelectionUnavailable();
        }
    }

    private void drawSelection(Canvas canvas, float scale) {
        if (selectionRect == null) {
            return;
        }
        float left = selectionRect.left * scale;
        float top = selectionRect.top * scale;
        float right = selectionRect.right * scale;
        float bottom = selectionRect.bottom * scale;
        selectionPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(Color.argb(70, 37, 99, 235));
        canvas.drawRect(left, top, right, bottom, selectionPaint);
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setColor(Color.rgb(37, 99, 235));
        selectionPaint.setStrokeWidth(Math.max(1f, 1.2f * scale));
        canvas.drawRect(left, top, right, bottom, selectionPaint);
    }

    public void setChromeVisible(boolean chromeVisible) {
        this.chromeVisible = chromeVisible;
    }

    public void setTextLines(List<TextLineBox> textLines) {
        clearSelection();
        this.textLines = textLines == null ? new ArrayList<>() : textLines;
        invalidate();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void scrollToPage(int index) {
        if (pageCount == 0) {
            return;
        }
        int target = Math.max(0, Math.min(pageCount - 1, index));
        scroller.forceFinished(true);
        float destination = pageContentTop(target);
        scroller.startScroll(Math.round(panX), Math.round(scrollY), 0,
                Math.round(destination - scrollY), 420);
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            scrollY = scroller.getCurrY();
            clampPan();
            clampScroll();
            invalidate();
        }
    }

    public void resetView() {
        boolean wasZoomedIn = zoomedIn;
        zoom = 1f;
        zoomedIn = false;
        panX = 0f;
        scrollY = 0f;
        active = null;
        clearSelection();
        scroller.forceFinished(true);
        if (wasZoomedIn && listener != null) {
            listener.onZoomChanged(false);
        }
        invalidate();
    }

    private float gapPx() {
        return 5f * getResources().getDisplayMetrics().density;
    }

    private float baseScale() {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return 1f;
        }
        return Math.min((float) getWidth() / bitmapWidth,
                (float) getHeight() / bitmapHeight);
    }

    private float pageWidth() {
        return bitmapWidth * baseScale();
    }

    private float pageHeight() {
        return bitmapHeight * baseScale();
    }

    /** Pages start at the top edge; leftover space continues into the next page. */
    private float topPadding() {
        return 0f;
    }

    private float contentHeight() {
        return topPadding() * 2f + pageCount * pageHeight()
                + Math.max(0, pageCount - 1) * gapPx();
    }

    private float pageContentTop(int index) {
        return topPadding() + index * (pageHeight() + gapPx());
    }

    private float offsetX() {
        return (getWidth() - pageWidth() * zoom) / 2f + panX;
    }

    private float viewY(float contentY) {
        return (contentY - scrollY) * zoom;
    }

    private RectF pageRect(int index) {
        float left = offsetX();
        float top = viewY(pageContentTop(index));
        return new RectF(left, top, left + pageWidth() * zoom,
                top + pageHeight() * zoom);
    }

    private void clampPan() {
        float overflow = Math.max(0f, (pageWidth() * zoom - getWidth()) / 2f);
        panX = Math.max(-overflow, Math.min(overflow, panX));
    }

    private void clampScroll() {
        float viewport = getHeight() / Math.max(0.01f, zoom);
        float max = Math.max(0f, contentHeight() - viewport);
        scrollY = Math.max(0f, Math.min(max, scrollY));
    }

    private void setZoom(float targetZoom, float focusX, float focusY) {
        if (pageCount == 0) {
            return;
        }
        float oldZoom = zoom;
        float nextZoom = Math.max(1f, Math.min(MAX_ZOOM, targetZoom));
        float contentFocusX = (focusX - offsetX()) / oldZoom;
        float contentFocusY = focusY / oldZoom + scrollY;

        zoom = nextZoom;
        panX = focusX - contentFocusX * zoom
                - (getWidth() - pageWidth() * zoom) / 2f;
        scrollY = contentFocusY - focusY / zoom;
        clampPan();
        clampScroll();

        boolean nowZoomedIn = zoom > 1.01f;
        if (nowZoomedIn != zoomedIn) {
            zoomedIn = nowZoomedIn;
            if (listener != null) {
                listener.onZoomChanged(zoomedIn);
            }
        }
        invalidate();
    }

    private int pageIndexAtY(float y) {
        if (pageCount <= 0) {
            return 0;
        }
        float centre = y / Math.max(0.01f, zoom) + scrollY;
        float stride = pageHeight() + gapPx();
        int index = Math.round((centre - topPadding() - pageHeight() / 2f) / stride);
        return Math.max(0, Math.min(pageCount - 1, index));
    }

    private float[] viewToPdf(float x, float y) {
        return viewToPdf(x, y, pageIndexAtY(y));
    }

    private float[] viewToPdf(float x, float y, int page) {
        float pxPerPoint = baseScale() * (bitmapWidth / pdfWidth) * zoom;
        if (pxPerPoint <= 0f) {
            pxPerPoint = 1f;
        }
        RectF rect = pageRect(page);
        return new float[]{(x - rect.left) / pxPerPoint, (y - rect.top) / pxPerPoint};
    }

    private void updateCurrentPage() {
        if (pageCount <= 0) {
            return;
        }
        float centre = scrollY + getHeight() / (2f * Math.max(0.01f, zoom));
        float stride = pageHeight() + gapPx();
        int index = Math.round((centre - topPadding() - pageHeight() / 2f) / stride);
        index = Math.max(0, Math.min(pageCount - 1, index));
        if (index != currentPage) {
            currentPage = index;
            if (listener != null) {
                listener.onPageChanged(index);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pageCount <= 0 || annotations == null) {
            return;
        }

        float scale = baseScale() * (bitmapWidth / pdfWidth) * zoom;
        for (int i = 0; i < pageCount; i++) {
            RectF rect = pageRect(i);
            if (rect.bottom < -8f || rect.top > getHeight() + 8f) {
                continue;
            }
            Bitmap bitmap = pages.get(i);
            if (bitmap == null || bitmap.isRecycled()) {
                placeholderPaint.setColor(Color.rgb(248, 250, 252));
                canvas.drawRect(rect, placeholderPaint);
                if (pageSource != null) {
                    pageSource.requestPage(i);
                }
            } else {
                canvas.drawBitmap(bitmap, null, rect, bitmapPaint);
            }
            canvas.drawRect(rect, dividerPaint);

            canvas.save();
            canvas.translate(rect.left, rect.top);
            for (Annotation a : annotations) {
                if (a.pageIndex == i) {
                    drawAnnotation(canvas, a, scale);
                }
            }
            if (active != null && active.pageIndex == i) {
                drawAnnotation(canvas, active, scale);
            }
            if (selectionRect != null && selectionPage == i) {
                drawSelection(canvas, scale);
            }
            canvas.restore();
        }
        updateCurrentPage();
        trimDistantPages();
    }

    /** Keeps a small window of decoded pages so long documents stay smooth. */
    private void trimDistantPages() {
        float visibleTop = scrollY - pageHeight();
        float visibleBottom = scrollY + getHeight() / Math.max(0.01f, zoom) + pageHeight();
        for (int i = pages.size() - 1; i >= 0; i--) {
            int index = pages.keyAt(i);
            float top = pageContentTop(index);
            float bottom = top + pageHeight();
            if (bottom < visibleTop || top > visibleBottom) {
                Bitmap bitmap = pages.valueAt(i);
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                pages.removeAt(i);
            }
        }
    }

    private void drawAnnotation(Canvas canvas, Annotation a, float s) {
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
        straightenHighlight(annotation, textLines, annotation.pageIndex);
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
        if (pageCount == 0) {
            return super.onTouchEvent(event);
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            scroller.forceFinished(true);
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            } else {
                velocityTracker.clear();
            }
        }
        if (velocityTracker != null && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            velocityTracker.addMovement(event);
        }

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
            active = null;
            return true;
        }

        if (selectionMode) {
            if (action == MotionEvent.ACTION_MOVE) {
                updateSelection(event.getX(), event.getY());
            } else if (action == MotionEvent.ACTION_UP) {
                commitSelection();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                clearSelection();
                invalidate();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
            }
            return true;
        }

        if (tool == Tool.NONE || listener == null
                || tool == Tool.TEXT || tool == Tool.NOTE) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
            }
            return true;
        }

        float[] p = viewToPdf(event.getX(), event.getY(),
                active == null ? pageIndexAtY(event.getY()) : active.pageIndex);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (tool == Tool.REDACT) {
                    int page = pageIndexAtY(event.getY());
                    active = Annotation.redaction(page, new RectF(p[0], p[1], p[0], p[1]));
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
                    int page = pageIndexAtY(event.getY());
                    active = Annotation.path(type, page, color, width);
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
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            default:
                break;
        }
        return true;
    }
}
