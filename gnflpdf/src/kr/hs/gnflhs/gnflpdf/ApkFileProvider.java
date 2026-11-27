package kr.hs.gnflhs.gnflpdf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lightweight zero-dependency ContentProvider to serve downloaded OTA APK files
 * safely to Android's PackageInstaller on Android 7.0+.
 */
public class ApkFileProvider extends ContentProvider {
    public static final String AUTHORITY = "kr.hs.gnflhs.gnflpdf.apkprovider";

    public static Uri getApkUri() {
        return Uri.parse("content://" + AUTHORITY + "/ota/GNFLPDF_update.apk");
    }

    private File resolveApkFile() {
        if (getContext() == null) return null;
        File dir = new File(getContext().getCacheDir(), "ota");
        return new File(dir, "GNFLPDF_update.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveApkFile();
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("OTA APK not found");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolveApkFile();
        String[] cols = new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor mc = new MatrixCursor(cols);
        if (f != null && f.exists()) {
            mc.addRow(new Object[] { f.getName(), f.length() });
        }
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
