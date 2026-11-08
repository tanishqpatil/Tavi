package app.lfm3.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Standalone test-APK entry point: platform Java only, independent of the target app's Kotlin runtime. */
public final class ShareFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        try (FileOutputStream stream = new FileOutputStream(new File(getCacheDir(), "share-fixture.jpg"))) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        } catch (IOException error) {
            throw new IllegalStateException("Could not create the synthetic photo fixture", error);
        } finally { bitmap.recycle(); }
        Uri uri = Uri.parse("content://app.lfm3.probe.test.sharefixture/photo");
        Intent request = new Intent(Intent.ACTION_SEND)
            .setComponent(new ComponentName("app.lfm3.probe", "app.lfm3.probe.MainActivity"))
            .setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Tavi photo sharing QA fixture")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        request.setClipData(ClipData.newRawUri("fixture", uri));
        startActivity(request);
        finish();
    }
}
