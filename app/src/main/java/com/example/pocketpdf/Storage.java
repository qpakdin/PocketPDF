package com.example.pocketpdf;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves output files into the public Download/PocketPDF folder. */
final class Storage {
    static final String FOLDER = "PocketPDF";
    static final String SHOWN_ROOT = "Download/" + FOLDER;

    static final class Saved {
        final Uri uri;      // null for multi-file results
        final String where; // human readable location
        int count;
        Saved(Uri uri, String where) { this.uri = uri; this.where = where; }
    }

    static Saved save(Context c, File src, String name, String mime, String sub) throws Exception {
        String subPath = (sub == null || sub.isEmpty()) ? "" : "/" + sub;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + subPath);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Cannot create file in Downloads");
            try (OutputStream out = r.openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                if (out == null) throw new Exception("Cannot write to Downloads");
                copy(in, out);
            } catch (Exception e) {
                try { r.delete(uri, null, null); } catch (Exception ignored) {}
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
            String real = displayName(c, uri);
            return new Saved(uri, SHOWN_ROOT + subPath + "/" + (real != null ? real : name));
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER + subPath);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create folder " + dir.getPath());
            File dst = uniqueFile(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", dst);
            return new Saved(uri, SHOWN_ROOT + subPath + "/" + dst.getName());
        }
    }

    static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
