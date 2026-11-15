# Pocket PDF (Android)

A minimal offline Android PDF reader with four local file operations:
- Images to PDF (multi-select)
- PDF pages to PNG images
- Delete selected PDF page numbers/ranges
- Merge two PDF files

## Reader controls
- Swipe left/right to change page (when the page is not zoomed sideways); drag to pan; fling for momentum.
- Pinch or double-tap to zoom; +/- buttons; tap the percentage for presets or a custom value; "Fit page".
- Tap the "12 / 200" label to jump to any page.

## Where files are saved
All created files go to `Download/PocketPDF/` (images: `Download/PocketPDF/<name>_pages_<time>/`).

## Default PDF app support
The manifest registers `ACTION_VIEW` and `application/pdf`. Android will list Pocket PDF as a compatible app when a PDF is opened from a file manager or another app. The user must choose it and select **Always** where Android offers that option; the app cannot silently set itself as default.

## Build
Requirements: JDK 17, Android SDK Platform 35, Android Build Tools, and Gradle 8.7+.

From this directory run:

```bash
gradle assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes
- Minimum Android version: API 23 (Android 6.0).
- PDF display uses Android's built-in `PdfRenderer`.
- PDF merge/delete use PDFBox-Android; first build needs Maven Central access to resolve this dependency.
- Saving uses MediaStore on Android 10+ (no permission needed); on Android 6-9 the storage permission is requested once.
- This is a starter implementation. It should be built and tested on real devices before release; production hardening should include SAF-based output saving, larger-file/memory testing, and Android 13–15 behavior checks.
- PDF output sharing uses Android FileProvider content URIs rather than exposing raw `file://` paths.
