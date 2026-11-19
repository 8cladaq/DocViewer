package com.example.doclens;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** DocViewer — original PDF reading and editing, built on Android SDK + PDFBox-Android. */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OPEN_PDF = 1001;
    private static final int REQUEST_SAVE_PDF = 1002;
    private static final int REQUEST_MERGE_PDF = 1003;
    private static final int MAX_TEXTURE_DIMENSION = 2048;

    private MaterialButtonToggleGroup toolsGroup;
    private PdfPageView pageView;
    private TextView pageLabel;
    private TextView fileName;
    private View emptyState;
    private View landingView;
    private View documentView;
    private View toolsPanel;

    private PdfRenderer renderer;
    private ParcelFileDescriptor fileDescriptor;
    private byte[] sourceBytes;
    private String currentName;
    private int pageIndex;
    private int pageCount;
    private float pdfWidth;
    private float pdfHeight;

    private final List<Annotation> annotations = new ArrayList<>();
    private final EditActions actions = new EditActions();
    private Integer pendingExportPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        MaterialButton openButton = findViewById(R.id.open_button);
        MaterialButton browseButton = findViewById(R.id.browse_button);
        MaterialButton previousButton = findViewById(R.id.previous_button);
        MaterialButton nextButton = findViewById(R.id.next_button);
        MaterialButton undoButton = findViewById(R.id.undo_button);
        MaterialButton clearButton = findViewById(R.id.clear_button);
        MaterialButton pagesButton = findViewById(R.id.pages_button);
        MaterialButton saveButton = findViewById(R.id.save_button);
        toolsGroup = findViewById(R.id.tools_group);
        pageLabel = findViewById(R.id.page_label);
        fileName = findViewById(R.id.file_name);
        pageView = findViewById(R.id.page_image);
        emptyState = findViewById(R.id.empty_state);
        landingView = findViewById(R.id.landing_view);
        documentView = findViewById(R.id.document_view);
        toolsPanel = findViewById(R.id.tools_panel);

        openButton.setOnClickListener(v -> openDocument());
        browseButton.setOnClickListener(v -> openDocument());
        previousButton.setOnClickListener(v -> movePage(-1));
        nextButton.setOnClickListener(v -> movePage(1));
        undoButton.setOnClickListener(v -> undo());
        clearButton.setOnClickListener(v -> clearEdits());
        pagesButton.setOnClickListener(v -> showPagesDialog());
        saveButton.setOnClickListener(v -> openSavePicker());

        pageView.setListener(new PdfPageView.Listener() {
            @Override
            public void onPathFinished(Annotation annotation) {
                annotations.add(annotation);
            }

            @Override
            public void onBoxRequested(Annotation.Type type, float pdfX, float pdfY) {
                showTextDialog(type, pdfX, pdfY);
            }

            @Override
            public void onRedactionFinished(Annotation annotation) {
                annotations.add(annotation);
                Toast.makeText(MainActivity.this,
                        "Cover applied — save to remove the content underneath",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onToggleChrome() {
                toggleTools();
            }
        });

        toolsGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                pageView.setTool(toolFor(checkedId));
            }
        });
        toolsGroup.check(R.id.tool_view);

        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
    }

    private PdfPageView.Tool toolFor(int id) {
        if (id == R.id.tool_pen) return PdfPageView.Tool.PEN;
        if (id == R.id.tool_mark) return PdfPageView.Tool.HIGHLIGHT;
        if (id == R.id.tool_text) return PdfPageView.Tool.TEXT;
        if (id == R.id.tool_note) return PdfPageView.Tool.NOTE;
        if (id == R.id.tool_sign) return PdfPageView.Tool.SIGNATURE;
        if (id == R.id.tool_cover) return PdfPageView.Tool.REDACT;
        return PdfPageView.Tool.NONE;
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_PDF);
    }

    private void openSavePicker() {
        if (sourceBytes == null) {
            Toast.makeText(this, R.string.open_error, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "DocViewer-edited.pdf");
        startActivityForResult(intent, REQUEST_SAVE_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        if (requestCode == REQUEST_OPEN_PDF) {
            try {
                int readFlag = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (readFlag != 0) {
                    getContentResolver().takePersistableUriPermission(uri, readFlag);
                }
                byte[] bytes = readBytes(uri);
                annotations.clear();
                actions.clear();
                pendingExportPage = null;
                openBytes(bytes, documentName(uri));
            } catch (Exception e) {
                Toast.makeText(this, R.string.open_error, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_SAVE_PDF) {
            saveTo(uri);
        } else if (requestCode == REQUEST_MERGE_PDF) {
            try {
                actions.mergeBytes = readBytes(uri);
                Toast.makeText(this, R.string.merge_ready, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.open_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new IllegalStateException("No data for the selected file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private String documentName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the URI segment.
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? getString(R.string.untitled_document) : segment;
    }

    private void openBytes(byte[] bytes, String name) throws Exception {
        closeDocument();
        sourceBytes = bytes;
        currentName = name;
        File file = new File(getCacheDir(), "current.pdf");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(fileDescriptor);
        pageCount = renderer.getPageCount();
        pageIndex = 0;
        fileName.setText(name);
        landingView.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        documentView.setVisibility(View.VISIBLE);
        toolsPanel.setVisibility(View.GONE);
        pageView.setChromeVisible(false);
        toolsGroup.check(R.id.tool_view);
        enterFullscreen();
        renderPage(false);
    }

    private void toggleTools() {
        if (documentView.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean show = toolsPanel.getVisibility() != View.VISIBLE;
        toolsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        pageView.setChromeVisible(show);
    }

    private void enterFullscreen() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    private void exitFullscreen() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void closeDocumentView() {
        closeDocument();
        documentView.setVisibility(View.GONE);
        landingView.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.VISIBLE);
        toolsPanel.setVisibility(View.GONE);
        pageView.setChromeVisible(false);
        toolsGroup.check(R.id.tool_view);
        exitFullscreen();
    }

    @Override
    public void onBackPressed() {
        if (documentView.getVisibility() == View.VISIBLE) {
            closeDocumentView();
            return;
        }
        super.onBackPressed();
    }

    private void movePage(int delta) {
        int target = pageIndex + delta;
        if (renderer == null || target < 0 || target >= pageCount) {
            return;
        }
        pageIndex = target;
        renderPage(true);
    }

    private void renderPage(boolean animate) {
        if (renderer == null || pageCount == 0) {
            return;
        }
        PdfRenderer.Page page = renderer.openPage(pageIndex);
        pdfWidth = page.getWidth();
        pdfHeight = page.getHeight();
        float scale = Math.min(1f,
                (float) MAX_TEXTURE_DIMENSION / Math.max(pdfWidth, pdfHeight));
        int width = Math.max(1, Math.round(pdfWidth * scale));
        int height = Math.max(1, Math.round(pdfHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Matrix transform = new Matrix();
        transform.setScale(scale, scale);
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();

        pageView.setPage(bitmap, pdfWidth, pdfHeight, annotations, pageIndex);
        if (animate) {
            pageView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.page_in));
        }
        pageLabel.setText(getString(R.string.page_info, pageIndex + 1, pageCount));
        findViewById(R.id.previous_button).setEnabled(pageIndex > 0);
        findViewById(R.id.next_button).setEnabled(pageIndex < pageCount - 1);
    }

    private void undo() {
        if (annotations.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_undo, Toast.LENGTH_SHORT).show();
            return;
        }
        annotations.remove(annotations.size() - 1);
        pageView.invalidate();
    }

    private void clearEdits() {
        annotations.clear();
        pageView.invalidate();
        Toast.makeText(this, R.string.marks_cleared, Toast.LENGTH_SHORT).show();
    }

    private void showTextDialog(Annotation.Type type, float pdfX, float pdfY) {
        View view = getLayoutInflater().inflate(R.layout.dialog_text, null);
        TextView title = view.findViewById(R.id.dialog_title);
        EditText input = view.findViewById(R.id.dialog_input);
        boolean isNote = type == Annotation.Type.NOTE;
        title.setText(isNote ? R.string.add_note : R.string.add_text);
        input.setHint(R.string.text_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        return;
                    }
                    annotations.add(isNote
                            ? Annotation.note(pageIndex, pdfX, pdfY, value)
                            : Annotation.text(pageIndex, pdfX, pdfY, value));
                    pageView.invalidate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showPagesDialog() {
        if (sourceBytes == null) {
            Toast.makeText(this, R.string.open_error, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {
                getString(R.string.rotate_page),
                getString(R.string.delete_page),
                getString(R.string.move_left),
                getString(R.string.move_right),
                getString(R.string.merge_pdf),
                getString(R.string.export_page),
                getString(R.string.fill_forms)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.pages)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            actions.rotations.add(pageIndex);
                            Toast.makeText(this, "Rotation will apply when you save",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            actions.deletions.add(pageIndex);
                            Toast.makeText(this, "Page marked for deletion — save to apply",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            if (pageIndex > 0) {
                                actions.moves.add(new int[]{pageIndex, pageIndex - 1});
                            }
                            break;
                        case 3:
                            if (pageIndex < pageCount - 1) {
                                actions.moves.add(new int[]{pageIndex, pageIndex + 1});
                            }
                            break;
                        case 4:
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.setType("application/pdf");
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(intent, REQUEST_MERGE_PDF);
                            break;
                        case 5:
                            pendingExportPage = pageIndex;
                            openSavePicker();
                            break;
                        case 6:
                            showFormDialog();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showFormDialog() {
        try {
            List<String> fields = PdfEditor.formFieldNames(sourceBytes);
            if (fields.isEmpty()) {
                Toast.makeText(this, R.string.no_fields, Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = fields.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.fill_forms)
                    .setItems(names, (dialog, which) -> showFieldValueDialog(names[which]))
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void showFieldValueDialog(String name) {
        View view = getLayoutInflater().inflate(R.layout.dialog_text, null);
        TextView title = view.findViewById(R.id.dialog_title);
        EditText input = view.findViewById(R.id.dialog_input);
        title.setText(name);
        input.setText(actions.formValues.get(name));

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    actions.formValues.put(name, input.getText().toString());
                    Toast.makeText(this, "Field saved — choose Save to write the PDF",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveTo(Uri uri) {
        Toast.makeText(this, R.string.saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                if (pendingExportPage != null) {
                    buffer.write(PdfEditor.exportPage(sourceBytes, pendingExportPage));
                } else {
                    PdfEditor.save(sourceBytes, buffer, annotations, actions);
                }
                byte[] result = buffer.toByteArray();
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new IllegalStateException("No output stream");
                    }
                    out.write(result);
                }
                runOnUiThread(() -> {
                    pendingExportPage = null;
                    annotations.clear();
                    actions.clear();
                    try {
                        openBytes(result, currentName);
                        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.save_failed,
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        closeDocument();
        super.onDestroy();
    }

    private void closeDocument() {
        if (renderer != null) {
            try {
                renderer.close();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            renderer = null;
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            fileDescriptor = null;
        }
        sourceBytes = null;
        pageCount = 0;
        pageIndex = 0;
    }
}
