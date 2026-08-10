package de.theduffman85.ntfybatch;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.util.Locale;

/**
 * Exposes queued files and keeps generated ZIP metadata independent of Android's MIME map.
 *
 * ntfy obtains the upload Content-Type from ContentResolver#getType(Uri), not from the share
 * intent. Return the archive type explicitly so generated .zip files do not depend on the
 * platform MIME mapping.
 */
public class QueuedFileProvider extends FileProvider {
    public static final String ZIP_MIME_TYPE = "application/zip";

    @Override
    public String getType(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return ZIP_MIME_TYPE;
        }
        return super.getType(uri);
    }
}
