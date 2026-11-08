package app.lfm3.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Only the synthetic photo is grantable. No user files or write operations are exposed. */
public final class ShareFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/photo".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException("Unknown fixture");
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "share-fixture.jpg"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String sort) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] arguments) { return 0; }
}
