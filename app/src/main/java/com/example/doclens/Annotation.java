package com.example.doclens;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** A single original edit, stored in PDF page coordinates (top-left origin). */
public final class Annotation {

    public enum Type { PEN, HIGHLIGHT, TEXT, NOTE, SIGNATURE, REDACT }

    public final Type type;
    public final int pageIndex;
    public final List<PointF> points = new ArrayList<>();
    public final RectF rect = new RectF();
    public String text = "";
    public int color;
    public float width;

    private Annotation(Type type, int pageIndex, int color, float width) {
        this.type = type;
        this.pageIndex = pageIndex;
        this.color = color;
        this.width = width;
    }

    public static Annotation path(Type type, int pageIndex, int color, float width) {
        return new Annotation(type, pageIndex, color, width);
    }

    public static Annotation text(int pageIndex, float x, float y, String value) {
        Annotation a = new Annotation(Type.TEXT, pageIndex, 0xFF1E293B, 14f);
        a.rect.set(x, y, x + 190f, y + 26f);
        a.text = value;
        return a;
    }

    public static Annotation note(int pageIndex, float x, float y, String value) {
        Annotation a = new Annotation(Type.NOTE, pageIndex, 0xFFFACC15, 0f);
        a.rect.set(x, y, x + 24f, y + 24f);
        a.text = value;
        return a;
    }

    public static Annotation redaction(int pageIndex, RectF bounds) {
        Annotation a = new Annotation(Type.REDACT, pageIndex, 0xFF000000, 0f);
        a.rect.set(bounds);
        return a;
    }
}
