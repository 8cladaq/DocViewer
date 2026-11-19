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
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** Draws a PDF page and lets the user place original annotations on it. */
public class PdfPageView extends View {

    public enum Tool { NONE, PEN, HIGHLIGHT, TEXT, NOTE, SIGNATURE, REDACT }

    public interface Listener {
        void onPathFinished(Annotation annotation);

        void onBoxRequested(Annotation.Type type, float pdfX, float pdfY);

        void onRedactionFinished(Annotation annotation);
    }

    private static final float MIN_DISTANCE_POINTS = 1.5f;

    private Bitmap bitmap;
    private float pdfWidth;
    private float pdfHeight;
    private int currentPage;
    private List<Annotation> annotations;
    private Tool tool = Tool.NONE;
    private Listener listener;
    private Annotation active;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        active = null;
        invalidate();
    }

    public void setPage(Bitmap bitmap, float pdfWidth, float pdfHeight,
                        List<Annotation> annotations, int currentPage) {
        this.bitmap = bitmap;
        this.pdfWidth = pdfWidth;
        this.pdfHeight = pdfHeight;
        this.annotations = annotations;
        this.currentPage = currentPage;
        active = null;
        invalidate();
    }

    private float renderScale() {
        if (bitmap == null || pdfWidth <= 0) {
            return 1f;
        }
        return bitmap.getWidth() / pdfWidth;
    }

    private float drawScale() {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return 1f;
        }
        return Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
    }

    private float originX() {
        return (getWidth() - bitmap.getWidth() * drawScale()) / 2f;
    }

    private float originY() {
        return (getHeight() - bitmap.getHeight() * drawScale()) / 2f;
    }

    private float[] viewToPdf(float x, float y) {
        float scale = drawScale();
        float bx = (x - originX()) / scale / renderScale();
        float by = (y - originY()) / scale / renderScale();
        return new float[]{bx, by};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || annotations == null) {
            return;
        }
        float ds = drawScale();
        canvas.drawBitmap(bitmap, null,
                new RectF(originX(), originY(),
                        originX() + bitmap.getWidth() * ds,
                        originY() + bitmap.getHeight() * ds), bitmapPaint);

        float s = ds * renderScale();
        canvas.save();
        canvas.translate(originX(), originY());
        for (Annotation a : annotations) {
            if (a.pageIndex != currentPage) {
                continue;
            }
            if (a.type == Annotation.Type.PEN || a.type == Annotation.Type.HIGHLIGHT
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
        canvas.restore();
    }

    private void drawPath(Canvas canvas, Annotation a, float s) {
        if (a.points.size() < 2) {
            return;
        }
        strokePaint.setColor(a.color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f, a.width * s));
        strokePaint.setAlpha(a.type == Annotation.Type.HIGHLIGHT ? 110 : 255);
        Path path = new Path();
        PointF first = a.points.get(0);
        path.moveTo(first.x * s, first.y * s);
        for (int i = 1; i < a.points.size(); i++) {
            PointF p = a.points.get(i);
            path.lineTo(p.x * s, p.y * s);
        }
        canvas.drawPath(path, strokePaint);
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tool == Tool.NONE || bitmap == null || listener == null) {
            return false;
        }
        float[] p = viewToPdf(event.getX(), event.getY());

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (tool == Tool.TEXT || tool == Tool.NOTE) {
                    Annotation.Type type = tool == Tool.TEXT
                            ? Annotation.Type.TEXT : Annotation.Type.NOTE;
                    listener.onBoxRequested(type, p[0], p[1]);
                    return true;
                }
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
                    if (valid && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (active.type == Annotation.Type.REDACT) {
                            active.rect.sort();
                            listener.onRedactionFinished(active);
                        } else {
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
