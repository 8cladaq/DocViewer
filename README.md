# DocViewer

An original Android PDF reader and editor with a Material 3 interface. It is
written from scratch and contains no Adobe code, branding, or assets. Documents
are opened through the system document picker and rendered with Android's
built-in `PdfRenderer`; edits are written into a **new PDF file** with the
Apache-2.0-licensed `pdfbox-android` library.

## Features

### Read
- System `ACTION_OPEN_DOCUMENT` picker — no storage permission required
- Page rendering with Android `PdfRenderer`
- Animated previous/next navigation with a page indicator and disabled end states
- Light and dark Material 3 theming

### Annotate and edit
- **Pen** — freehand ink embedded into the output PDF
- **Mark** — translucent highlighter strokes
- **Text** — tap the page, type text, and it is drawn into the PDF content stream
- **Note** — tap the page to place a PDF sticky-note annotation
- **Sign** — freehand signature stroke
- **Cover** — drag a rectangle; on save, covered pages are re-rendered and
  rebuilt as image-only pages so the underlying text is actually removed
  (true redaction, not just a black overlay)
- Undo and clear for the current edit session

### Page tools and forms
- Rotate, mark for deletion, and reorder the current page
- Merge another PDF after the current document
- Export the current page as a standalone PDF
- Fill text fields in fillable PDF forms

All edits are staged in memory and applied together when **Save** is chosen.
The app writes the result through `ACTION_CREATE_DOCUMENT`, so the edited PDF
goes wherever the user chooses (Downloads, Drive, etc.).

## Design

- Gradient header with an original document-and-lens mark
- Illustrated empty state in an elevated card
- Document card with rounded page preview, filename, circular navigation buttons,
  and a pill-shaped page indicator
- Horizontal editing toolbar with toggle tools
- Subtle fade-and-slide transition when changing pages

## Build

1. Install Android Studio (Android SDK) and JDK 21 for Gradle.
2. Accept the Android SDK licenses and install `platforms;android-36` and
   `build-tools;36.0.0`.
3. Open this folder in Android Studio, or run:

   ```powershell
   $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot'
   .\gradlew.bat assembleDebug --no-daemon
   ```

4. The debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`.

### Verification

The coordinate and text-patch engines have instrumentation tests that run on an
attached emulator or device:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon
$adb = 'C:\Users\Naveen\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& $adb shell am instrument -w -m com.example.doclens.test/androidx.test.runner.AndroidJUnitRunner
```

The suite checks point/rectangle round-trips for all four page rotations with a
cropped MediaBox, tap-to-select line matching, visual patching with the bundled
font, content-stream removal of the original text operators, true redaction of
text and annotations (including text inside Form XObjects), black-cover drawing,
metadata sanitization, and an offline invisible OCR layer over an image-only scan.

## Core Kotlin engine

The `com.example.doclens.engine` package contains the editor core:

- `PdfCoordinateMapper` — lossless mapping between Android view pixels
  (top-left, zoom/pan) and absolute PDF points (bottom-left, 72 dpi), including
  crop-box offsets and 0/90/180/270-degree page rotation. `RectF` bounds keep
  the Android convention (`top > bottom` in PDF space); `PdfRect` uses explicit
  `lowerLeft`/`upperRight` fields.
- `PdfDocumentRepository` — thread-safe byte snapshots, undo/redo history,
  dispatch of all PDFBox mutation/save work to `Dispatchers.IO`, and temp-file
  atomic replacement on save.
- `PdfEditEngine` — command contract plus a `DefaultPdfBoxEditEngine` that
  emits native PDF annotations (ink, markup, notes), patched text content, and
  true redaction commands. Page operations and form/signature stamping remain
  explicit integration stubs.
- `TextPatchEngine` — a `PDFTextStripper` subclass extracts words and grouped
  lines with exact `TextPosition` x/y/width/height, font size, font name, and
  absolute PDF bounding boxes. A tap is mapped through `PdfCoordinateMapper`
  to select a line. Replacement commands support two modes:
  - **Visual patch** samples the page bitmap for a matching background color,
    masks the old glyph box, and draws the replacement with an embedded
    `PDType0Font` (bundled Roboto) scaled to the original box.
  - **Content-stream strip** uses `PDFStreamParser` and a text-state scanner to
    remove the exact intersecting `Tj` / `TJ` / `'` / `"` operators, then
    appends the replacement text. This removes the original text from the file.

- `TrueRedactionEngine` — accepts normalized `RectF` zones per page, parses each
  page content stream with `PDFStreamParser`, removes `Tj` / `TJ` / `'` / `"`
  operators whose glyph boxes intersect a zone, removes overlapping
  `PDAnnotation` objects, rewrites the cleaned tokens, and paints an opaque
  black cover over each exact zone. It also recurses into Form XObjects invoked
  by `Do`, applying the form matrix to nested text positions. `sanitizeMetadata`
  replaces `PDDocumentInformation` with an empty instance and clears catalog XMP.
- `OfflineOcrLayerEngine` — renders each selected page to a high-DPI bitmap with
  Android `PdfRenderer` (260 DPI by default), runs ML Kit's **bundled** Latin text
  recognizer entirely on-device, maps every word-level pixel box back to
  absolute PDF points through `PdfCoordinateMapper`, and appends invisible text
  with `RenderingMode.NEITHER`. Per-word `Tz` horizontal scaling makes the
  invisible text advance match the recognized box width, so scanned pages become
  searchable and copy-pasteable in PDF viewers. The embedded Roboto font supplies
  a ToUnicode map for reliable text extraction. It is currently an engine-level
  suspend API; the Java viewer layer remains the UI/viewing boundary.

`TextPatchEngine` scans direct page content streams and standard text operators;
text nested inside Form XObjects is not traversed by that engine.

The engine uses only PDFBox 2.0.x APIs: `PDDocument.load(...)`,
`AppendMode.APPEND`, `MemoryUsageSetting.setupTempFileOnly()`, and
`PDFBoxResourceLoader.init(context)`. It never uses PDFBox 3.x APIs.

## Dependencies

- AndroidX AppCompat and Material Components
- Kotlin coroutines (`kotlinx-coroutines-android`, `kotlinx-coroutines-play-services`)
- `com.google.mlkit:text-recognition:16.0.1` for fully bundled, on-device Latin
  OCR with no network or Google Play Services dependency
- `com.tom-roush:pdfbox-android:2.0.27.0` (Apache License 2.0) for embedding
  annotations, page operations, form values, merging, and image-only redaction
- Bundled `Roboto-Regular.ttf` (Apache License 2.0) for embedded replacement
  text; attribution is in `app/src/main/assets/fonts/LICENSE-Roboto.txt`

## Scope

- Original Java UI plus Kotlin engine; Android SDK, AppCompat, Material
  Components, PDFBox-Android, and bundled ML Kit.
- `minSdk 24` (ML Kit text recognition requires 23+), `targetSdk`/`compileSdk`
  set in `app/build.gradle.kts`.
- Reads and writes documents through system document pickers; no storage permission.
