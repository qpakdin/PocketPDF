package com.example.pocketpdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** PDF operations. Single-file results are returned as temp files in the cache dir; the caller saves them. */
final class PdfOps {
    static void init(Context c) { PDFBoxResourceLoader.init(c); }

    static File imagesToPdf(Context c, List<Uri> images) throws Exception {
        PdfDocument doc = new PdfDocument();
        int n = 0;
        File out = File.createTempFile("out_", ".pdf", c.getCacheDir());
        try {
            for (Uri u : images) {
                Bitmap bitmap;
                try (InputStream in = c.getContentResolver().openInputStream(u)) { bitmap = BitmapFactory.decodeStream(in); }
                if (bitmap == null) continue;
                int w = bitmap.getWidth(), h = bitmap.getHeight();
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, ++n).create();
                PdfDocument.Page page = doc.startPage(info);
                page.getCanvas().drawBitmap(bitmap, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                doc.finishPage(page);
                bitmap.recycle();
            }
            if (n == 0) throw new IllegalArgumentException("No readable images were selected");
            try (FileOutputStream fos = new FileOutputStream(out)) { doc.writeTo(fos); }
            return out;
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            doc.close();
        }
    }

    /** Renders every page to PNG and saves it into Download/PocketPDF/sub. Returns the page count. */
    static int pdfToImages(Context c, Uri input, String sub) throws Exception {
        int count = 0;
        try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(input, "r");
             PdfRenderer renderer = new PdfRenderer(fd)) {
            for (int i = 0; i < renderer.getPageCount(); i++) {
                File tmp = File.createTempFile("pg_", ".png", c.getCacheDir());
                try {
                    try (PdfRenderer.Page p = renderer.openPage(i)) {
                        int width = Math.max(1, p.getWidth() * 2), height = Math.max(1, p.getHeight() * 2);
                        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        try (FileOutputStream out = new FileOutputStream(tmp)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        b.recycle();
                    }
                    Storage.save(c, tmp, String.format(Locale.US, "page_%03d.png", i + 1), "image/png", sub);
                } finally {
                    tmp.delete();
                }
                count++;
            }
        }
        return count;
    }

    static File deletePages(Context c, Uri input, String expression) throws Exception {
        File src = copyToCache(c, input);
        File out = null;
        PDDocument doc = null;
        try {
            doc = PDDocument.load(src);
            boolean[] remove = new boolean[doc.getNumberOfPages()];
            for (String part : expression.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                String[] range = s.split("-");
                int a = Integer.parseInt(range[0].trim()), b = range.length > 1 ? Integer.parseInt(range[1].trim()) : a;
                if (a < 1 || b < a || b > remove.length) throw new IllegalArgumentException("Invalid page range: " + s);
                for (int x = a; x <= b; x++) remove[x - 1] = true;
            }
            int remaining = doc.getNumberOfPages();
            for (boolean x : remove) if (x) remaining--;
            if (remaining < 1) throw new IllegalArgumentException("At least one page must remain");
            if (remaining == remove.length) throw new IllegalArgumentException("No pages selected");
            for (int i = remove.length - 1; i >= 0; i--) if (remove[i]) doc.removePage(i);
            out = File.createTempFile("out_", ".pdf", c.getCacheDir());
            doc.save(out);
            return out;
        } catch (Exception e) {
            if (out != null) out.delete();
            throw e;
        } finally {
            if (doc != null) doc.close();
            src.delete();
        }
    }

    static File merge(Context c, Uri first, Uri second) throws Exception {
        File a = copyToCache(c, first), b = copyToCache(c, second);
        File out = File.createTempFile("out_", ".pdf", c.getCacheDir());
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.addSource(a);
            merger.addSource(b);
            merger.setDestinationFileName(out.getAbsolutePath());
            merger.mergeDocuments(null);
            return out;
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            a.delete();
            b.delete();
        }
    }

    private static File copyToCache(Context c, Uri u) throws Exception {
        File f = File.createTempFile("pdf_", ".pdf", c.getCacheDir());
        try (InputStream in = c.getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f;
    }
}
