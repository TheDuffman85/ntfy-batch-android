package com.example.ntfyrelay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives ACTION_SEND_MULTIPLE and hands files to ntfy's existing share activity one at a time.
 *
 * ntfy does not return a success result from its share activity, so returning from ntfy is followed
 * by an explicit confirmation step. This prevents accidentally deleting a file when the user
 * pressed Back instead of Send.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_NTFY_SHARE = 1001;
    private static final String NTFY_RELEASE_PACKAGE = "io.heckel.ntfy";
    private static final String NTFY_DEBUG_PACKAGE = "io.heckel.ntfy.debug";
    private static final String NTFY_SHARE_ACTIVITY = "io.heckel.ntfy.ui.ShareActivity";
    private static final String PREFS_NAME = "relay_state";
    private static final String PREFS_QUEUE = "queue";
    private static final String QUEUE_DIRECTORY = "queue";

    private final List<QueuedFile> queue = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout queueContainer;
    private TextView statusText;
    private TextView emptyText;
    private Button sendButton;
    private Button markSentButton;
    private Button retryButton;
    private Button skipButton;
    private Button clearButton;

    private QueuedFile activeFile;
    private boolean awaitingNtfy;
    private boolean awaitingConfirmation;
    private boolean importing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadQueue();
        buildUi();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_NTFY_SHARE || !awaitingNtfy) {
            return;
        }

        // ntfy currently calls finish() without setting RESULT_OK, so the result code cannot tell
        // us whether the user sent or cancelled. Ask explicitly before removing the queued file.
        awaitingNtfy = false;
        awaitingConfirmation = true;
        setStatus("ntfy returned. Confirm whether this file was sent.");
        renderQueue();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFFFFFFF);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = systemBars.top;
            int bottom = systemBars.bottom;
            view.setPadding(dp(16), dp(12) + top, dp(16), dp(12) + bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("ntfy Multi-file Relay");
        title.setTextSize(22);
        title.setTextColor(0xFF202124);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(0xFF4A4A4A);
        statusText.setPadding(0, dp(4), 0, dp(10));
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(this);
        queueContainer = new LinearLayout(this);
        queueContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(queueContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyText = new TextView(this);
        emptyText.setTextSize(16);
        emptyText.setTextColor(0xFF666666);
        emptyText.setPadding(0, dp(20), 0, dp(20));
        queueContainer.addView(emptyText);

        sendButton = makeButton("Send current file to ntfy", v -> sendCurrentFile());
        root.addView(sendButton, fullWidthButtonParams());

        markSentButton = makeButton("Sent — open next", v -> completeActiveFile());
        root.addView(markSentButton, fullWidthButtonParams());

        retryButton = makeButton("Retry current file", v -> retryCurrentFile());
        root.addView(retryButton, fullWidthButtonParams());

        skipButton = makeButton("Skip current file", v -> skipCurrentFile());
        root.addView(skipButton, fullWidthButtonParams());

        clearButton = makeButton("Clear queued files", v -> clearQueue());
        root.addView(clearButton, fullWidthButtonParams());

        setContentView(root);
        renderQueue();
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams fullWidthButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        return params;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }

        List<Uri> uris = extractUris(intent);
        if (uris.isEmpty()) {
            setStatus("No files were found in the share request.");
            return;
        }

        final String incomingMimeType = intent.getType();
        importing = true;
        setStatus("Copying " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + " into the queue…");
        renderQueue();

        ioExecutor.execute(() -> {
            List<QueuedFile> imported = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            for (Uri uri : uris) {
                try {
                    imported.add(copyIntoQueue(uri, incomingMimeType));
                } catch (Exception exception) {
                    failures.add(uri.toString() + ": " + safeMessage(exception));
                }
            }

            mainHandler.post(() -> {
                queue.addAll(imported);
                importing = false;
                saveQueue();

                if (failures.isEmpty()) {
                    setStatus(imported.size() + " file" + (imported.size() == 1 ? "" : "s")
                            + " queued. Send them through ntfy one at a time.");
                } else {
                    setStatus(imported.size() + " queued; " + failures.size()
                            + " could not be imported.");
                    Toast.makeText(this, failures.get(0), Toast.LENGTH_LONG).show();
                }
                renderQueue();
            });
        });
    }

    private List<Uri> extractUris(Intent intent) {
        Set<String> seen = new LinkedHashSet<>();
        List<Uri> result = new ArrayList<>();

        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Parcelable parcelable : streams) {
                    if (parcelable instanceof Uri) {
                        addUri((Uri) parcelable, seen, result);
                    }
                }
            }
        } else {
            Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                addUri((Uri) stream, seen, result);
            }
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null) {
                    addUri(uri, seen, result);
                }
            }
        }
        return result;
    }

    private void addUri(Uri uri, Set<String> seen, List<Uri> result) {
        String key = uri.toString();
        if (seen.add(key)) {
            result.add(uri);
        }
    }

    private QueuedFile copyIntoQueue(Uri source, String incomingMimeType) throws IOException {
        ContentResolver resolver = getContentResolver();
        String displayName = queryDisplayName(source);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "shared-file";
        }
        String safeName = safeFileName(displayName);
        String mimeType = resolver.getType(source);
        if (mimeType == null || mimeType.trim().isEmpty() || "*/*".equals(mimeType)) {
            mimeType = incomingMimeType;
        }
        if (mimeType == null || mimeType.trim().isEmpty() || "*/*".equals(mimeType)) {
            mimeType = "application/octet-stream";
        }

        String id = UUID.randomUUID().toString();
        File itemDirectory = new File(new File(getFilesDir(), QUEUE_DIRECTORY), id);
        File target = new File(itemDirectory, safeName);
        if (!itemDirectory.mkdirs()) {
            throw new IOException("Unable to create queue directory");
        }

        long copiedBytes = 0;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new IOException("The source returned no readable stream");
            }
            try (OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copiedBytes += read;
                }
            }
        } catch (Exception exception) {
            deleteRecursively(itemDirectory);
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException(exception);
        }

        String relativePath = QUEUE_DIRECTORY + "/" + id + "/" + target.getName();
        return new QueuedFile(id, displayName, mimeType, relativePath, copiedBytes);
    }

    private void sendCurrentFile() {
        if (importing || awaitingNtfy || awaitingConfirmation || queue.isEmpty()) {
            return;
        }

        QueuedFile file = queue.get(0);
        File localFile = localFile(file);
        if (!localFile.isFile()) {
            removeFileFromQueue(file);
            saveQueue();
            renderQueue();
            setStatus("A queued file was no longer available and was removed.");
            return;
        }

        String ntfyPackage = findInstalledNtfyPackage();
        if (ntfyPackage == null) {
            setStatus("The ntfy Android app is not installed.");
            Toast.makeText(this, "Install ntfy before sending files.", Toast.LENGTH_LONG).show();
            return;
        }

        Uri shareUri;
        try {
            shareUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", localFile);
        } catch (IllegalArgumentException exception) {
            setStatus("Could not prepare the queued file for sharing.");
            return;
        }

        // ntfy treats an exact text/plain share as text rather than as a file. The wildcard still
        // matches ntfy's text/* filter and makes ShareActivity take its file path.
        String shareMimeType = "text/plain".equalsIgnoreCase(file.mimeType)
                ? "text/*" : file.mimeType;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setComponent(new ComponentName(ntfyPackage, NTFY_SHARE_ACTIVITY));
        shareIntent.setType(shareMimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
        shareIntent.setClipData(ClipData.newRawUri(file.displayName, shareUri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        activeFile = file;
        awaitingNtfy = true;
        awaitingConfirmation = false;
        setStatus("Sending “" + file.displayName + "” through ntfy…");
        renderQueue();

        try {
            startActivityForResult(shareIntent, REQUEST_NTFY_SHARE);
        } catch (ActivityNotFoundException exception) {
            activeFile = null;
            awaitingNtfy = false;
            setStatus("The installed ntfy app does not expose its share activity.");
            renderQueue();
        }
    }

    private void completeActiveFile() {
        if (!awaitingConfirmation || activeFile == null) {
            return;
        }
        QueuedFile completed = activeFile;
        activeFile = null;
        awaitingConfirmation = false;
        removeFileFromQueue(completed);
        saveQueue();
        setStatus("Marked “" + completed.displayName + "” as sent.");
        renderQueue();
        if (!queue.isEmpty()) {
            sendCurrentFile();
        }
    }

    private void retryCurrentFile() {
        if (!awaitingConfirmation) {
            return;
        }
        awaitingConfirmation = false;
        activeFile = null;
        setStatus("Retrying the current file.");
        renderQueue();
        sendCurrentFile();
    }

    private void skipCurrentFile() {
        if (!awaitingConfirmation || activeFile == null) {
            return;
        }
        QueuedFile skipped = activeFile;
        activeFile = null;
        awaitingConfirmation = false;
        removeFileFromQueue(skipped);
        saveQueue();
        setStatus("Skipped “" + skipped.displayName + "”.");
        renderQueue();
    }

    private void clearQueue() {
        if (importing || awaitingNtfy || awaitingConfirmation) {
            return;
        }
        for (QueuedFile file : new ArrayList<>(queue)) {
            deleteRecursively(localFile(file).getParentFile());
        }
        queue.clear();
        saveQueue();
        setStatus("Queue cleared.");
        renderQueue();
    }

    private void renderQueue() {
        if (queueContainer == null) {
            return;
        }

        queueContainer.removeAllViews();
        if (queue.isEmpty()) {
            emptyText.setText("No files queued. Use Android’s Share action to send one or more files here.");
            queueContainer.addView(emptyText);
        } else {
            for (int index = 0; index < queue.size(); index++) {
                QueuedFile file = queue.get(index);
                TextView row = new TextView(this);
                String prefix = index == 0 ? "NEXT  " : (index + 1) + "  ";
                row.setText(prefix + file.displayName + "\n" + formatBytes(file.size)
                        + "  •  " + file.mimeType);
                row.setTextSize(16);
                row.setTextColor(index == 0 ? 0xFF202124 : 0xFF5F6368);
                row.setPadding(dp(12), dp(12), dp(12), dp(12));
                row.setBackgroundColor(index == 0 ? 0xFFE8F0FE : 0xFFF8F9FA);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(6);
                queueContainer.addView(row, params);
            }
        }

        boolean hasQueue = !queue.isEmpty();
        sendButton.setVisibility(hasQueue && !awaitingConfirmation ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(hasQueue && !importing && !awaitingNtfy && !awaitingConfirmation);

        boolean confirmation = awaitingConfirmation && activeFile != null;
        markSentButton.setVisibility(confirmation ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(confirmation ? View.VISIBLE : View.GONE);
        skipButton.setVisibility(confirmation ? View.VISIBLE : View.GONE);
        clearButton.setVisibility(hasQueue && !confirmation ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!importing && !awaitingNtfy);
    }

    private void loadQueue() {
        String serialized = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREFS_QUEUE, "[]");
        try {
            JSONArray array = new JSONArray(serialized);
            boolean changed = false;
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                QueuedFile file = QueuedFile.fromJson(object);
                if (localFile(file).isFile()) {
                    queue.add(file);
                } else {
                    changed = true;
                }
            }
            if (changed) {
                saveQueue();
            }
        } catch (Exception exception) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(PREFS_QUEUE).apply();
        }
    }

    private void saveQueue() {
        JSONArray array = new JSONArray();
        for (QueuedFile file : queue) {
            array.put(file.toJson());
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREFS_QUEUE, array.toString()).apply();
    }

    private File localFile(QueuedFile file) {
        return new File(getFilesDir(), file.relativePath);
    }

    private void removeFileFromQueue(QueuedFile file) {
        queue.remove(file);
        deleteRecursively(localFile(file).getParentFile());
    }

    private String findInstalledNtfyPackage() {
        for (String packageName : new String[]{NTFY_RELEASE_PACKAGE, NTFY_DEBUG_PACKAGE}) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return packageName;
            } catch (Exception ignored) {
                // Try the next known ntfy build package.
            }
        }
        return null;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // Fall back to the URI below.
        }
        String lastPathSegment = uri.getLastPathSegment();
        return lastPathSegment == null ? null : lastPathSegment;
    }

    private String safeFileName(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == '/' || character == '\\') {
                result.append('_');
            } else {
                result.append(character);
            }
        }
        String clean = result.toString().trim();
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) {
            clean = "shared-file";
        }
        return clean.length() > 180 ? clean.substring(0, 180) : clean;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        // Queue files are private temporary copies created by this app.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void setStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class QueuedFile {
        final String id;
        final String displayName;
        final String mimeType;
        final String relativePath;
        final long size;

        QueuedFile(String id, String displayName, String mimeType, String relativePath, long size) {
            this.id = id;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.relativePath = relativePath;
            this.size = size;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("displayName", displayName);
                object.put("mimeType", mimeType);
                object.put("relativePath", relativePath);
                object.put("size", size);
            } catch (Exception ignored) {
                // All values are primitive strings/numbers; this cannot normally fail.
            }
            return object;
        }

        static QueuedFile fromJson(JSONObject object) {
            return new QueuedFile(
                    object.getString("id"),
                    object.getString("displayName"),
                    object.getString("mimeType"),
                    object.getString("relativePath"),
                    object.optLong("size", 0));
        }
    }
}
