package com.example.doclens;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies original edits to a PDF using the Apache-2.0 licensed PDFBox-Android port. */
public final class PdfEditor {

    private PdfEditor() {
    }

    public static void save(byte[] source, OutputStream output,
                            List<Annotation> annotations, EditActions actions) throws Exception {
        PDDocument document = PDDocument.load(source);
        try {
            Map<Integer, List<Annotation>> byPage = new HashMap<>();
            boolean hasRedaction = false;
            for (Annotation a : annotations) {
                List<Annotation> list = byPage.computeIfAbsent(a.pageIndex, k -> new ArrayList<>());
                list.add(a);
                if (a.type == Annotation.Type.REDACT) {
                    hasRedaction = true;
                }
            }

            for (Map.Entry<Integer, List<Annotation>> entry : byPage.entrySet()) {
                int index = entry.getKey();
                if (index < 0 || index >= document.getNumberOfPages()) {
                    continue;
                }
                PDPage page = document.getPage(index);
                float pageHeight = page.getMediaBox().getHeight();
                PDPageContentStream content = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true);
                try {
                    for (Annotation a : entry.getValue()) {
                        if (a.type == Annotation.Type.PEN
                                || a.type == Annotation.Type.HIGHLIGHT
                                || a.type == Annotation.Type.SIGNATURE) {
                            drawPath(content, a, pageHeight);
                        } else if (a.type == Annotation.Type.TEXT) {
                            drawText(content, a, page);
                        }
                    }
                } finally {
                    content.close();
                }

                for (Annotation a : entry.getValue()) {
                    if (a.type == Annotation.Type.NOTE) {
                        addStickyNote(document, page, a, pageHeight);
                    }
                }
            }

            applyFormValues(document, actions.formValues);

            if (hasRedaction) {
                PDDocument flattened = rebuildWithRedactions(document, annotations);
                document.close();
                document = flattened;
            }

            applyPageActions(document, actions);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            byte[] edited = buffer.toByteArray();

            if (actions.mergeBytes != null) {
                PDFMergerUtility merger = new PDFMergerUtility();
                merger.addSource(new ByteArrayInputStream(edited));
                merger.addSource(new ByteArrayInputStream(actions.mergeBytes));
                merger.setDestinationStream(output);
                merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
            } else {
                output.write(edited);
            }
            output.flush();
        } finally {
            document.close();
        }
    }

    private static void drawPath(PDPageContentStream content, Annotation a, float pageHeight)
            throws IOException {
        if (a.points.size() < 2) {
            return;
        }
        boolean highlight = a.type == Annotation.Type.HIGHLIGHT;
        PDExtendedGraphicsState state = null;
        if (highlight) {
            content.saveGraphicsState();
            state = new PDExtendedGraphicsState();
            state.setStrokingAlphaConstant(0.35f);
            content.setGraphicsStateParameters(state);
        }
        int r = Color.red(a.color);
        int g = Color.green(a.color);
        int b = Color.blue(a.color);
        content.setStrokingColor(r, g, b);
        content.setLineWidth(a.width);
        content.setLineCapStyle(2);
        content.setLineJoinStyle(2);
        PointF first = a.points.get(0);
        content.moveTo(first.x, pageHeight - first.y);
        for (int i = 1; i < a.points.size(); i++) {
            PointF p = a.points.get(i);
            content.lineTo(p.x, pageHeight - p.y);
        }
        content.stroke();
        if (state != null) {
            content.restoreGraphicsState();
        }
    }

    private static void drawText(PDPageContentStream content, Annotation a, PDPage page)
            throws IOException {
        float pageHeight = page.getMediaBox().getHeight();
        float x = a.rect.left;
        float y = pageHeight - a.rect.top - 14f;
        content.setNonStrokingColor(250, 250, 253);
        content.fillRect(x - 2f, y - 3f, Math.min(200f, a.rect.width() + 8f), 22f);
        content.beginText();
        content.setFont(PDType1Font.HELVETICA, 14f);
        content.setNonStrokingColor(30, 41, 59);
        content.newLineAtOffset(x, y);
        content.showText(sanitize(a.text));
        content.endText();
    }

    private static void addStickyNote(PDDocument document, PDPage page, Annotation a,
                                      float pageHeight) throws IOException {
        PDAnnotationText note = new PDAnnotationText();
        note.setRectangle(new PDRectangle(a.rect.left, pageHeight - a.rect.bottom,
                a.rect.width(), a.rect.height()));
        note.setContents(a.text);
        note.setName(PDAnnotationText.NAME_NOTE);
        note.setOpen(false);
        page.getAnnotations().add(note);
    }

    private static void applyFormValues(PDDocument document, Map<String, String> values)
            throws IOException {
        if (values.isEmpty()) {
            return;
        }
        PDAcroForm form = document.getDocumentCatalog().getAcroForm();
        if (form == null) {
            return;
        }
        for (PDField field : form.getFields()) {
            String name = field.getFullyQualifiedName();
            if (name == null) {
                name = field.getPartialName();
            }
            String value = values.get(name);
            if (value != null && field instanceof PDTextField) {
                ((PDTextField) field).setValue(value);
            }
        }
        form.setNeedAppearances(true);
    }

    private static void applyPageActions(PDDocument document, EditActions actions)
            throws IOException {
        for (int index : actions.rotations) {
            if (index >= 0 && index < document.getNumberOfPages()) {
                PDPage page = document.getPage(index);
                page.setRotation((page.getRotation() + 90) % 360);
            }
        }

        List<Integer> deletions = new ArrayList<>(actions.deletions);
        Collections.sort(deletions, Collections.reverseOrder());
        for (int index : deletions) {
            if (index >= 0 && index < document.getNumberOfPages()) {
                document.removePage(index);
            }
        }

        if (!actions.moves.isEmpty()) {
            COSDictionary pages = document.getDocumentCatalog().getPages().getCOSObject();
            COSArray kids = (COSArray) pages.getDictionaryObject(COSName.KIDS);
            if (kids != null) {
                for (int[] move : actions.moves) {
                    int from = move[0];
                    int to = move[1];
                    if (from >= 0 && to >= 0 && from < kids.size() && to < kids.size()) {
                        COSBase a = kids.getObject(from);
                        COSBase b = kids.getObject(to);
                        kids.set(from, b);
                        kids.set(to, a);
                    }
                }
            }
        }
    }

    /**
     * True redaction: pages containing cover boxes are re-rendered and rebuilt as images,
     * so the covered content is removed from the file rather than merely hidden.
     */
    private static PDDocument rebuildWithRedactions(PDDocument source,
                                                    List<Annotation> annotations) throws IOException {
        PDDocument result = new PDDocument();
        PDFRenderer renderer = new PDFRenderer(source);
        float scale = 2f;
        Paint black = new Paint();
        black.setStyle(Paint.Style.FILL);
        black.setColor(Color.BLACK);

        for (int i = 0; i < source.getNumberOfPages(); i++) {
            PDPage original = source.getPage(i);
            Bitmap bitmap = renderer.renderImage(i, scale);
            Canvas canvas = new Canvas(bitmap);
            for (Annotation a : annotations) {
                if (a.type == Annotation.Type.REDACT && a.pageIndex == i) {
                    canvas.drawRect(a.rect.left * scale, a.rect.top * scale,
                            a.rect.right * scale, a.rect.bottom * scale, black);
                }
            }

            PDRectangle media = original.getMediaBox();
            int rotation = original.getRotation();
            boolean swapped = rotation == 90 || rotation == 270;
            float width = swapped ? media.getHeight() : media.getWidth();
            float height = swapped ? media.getWidth() : media.getHeight();

            PDPage page = new PDPage(new PDRectangle(width, height));
            result.addPage(page);
            PDPageContentStream content = new PDPageContentStream(result, page);
            content.drawImage(LosslessFactory.createFromImage(result, bitmap),
                    0f, 0f, width, height);
            content.close();
            bitmap.recycle();
        }
        return result;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append(c >= 32 && c <= 126 ? c : '?');
        }
        return sb.toString();
    }

    public static byte[] exportPage(byte[] source, int pageIndex) throws IOException {
        PDDocument document = PDDocument.load(source);
        PDDocument single = new PDDocument();
        try {
            single.addPage(document.getPage(pageIndex));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            single.save(out);
            return out.toByteArray();
        } finally {
            single.close();
            document.close();
        }
    }

    public static List<String> formFieldNames(byte[] source) throws IOException {
        PDDocument document = PDDocument.load(source);
        try {
            List<String> names = new ArrayList<>();
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form != null) {
                for (PDField field : form.getFields()) {
                    String name = field.getFullyQualifiedName();
                    if (name == null) {
                        name = field.getPartialName();
                    }
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            return names;
        } finally {
            document.close();
        }
    }
}
