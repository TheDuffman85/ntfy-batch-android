package de.theduffman85.ntfyrelay;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Receives ACTION_SEND_MULTIPLE and queues files for ntfy's existing share activity. Files are
 * handed over one at a time, or as one uncompressed ZIP when that mode is selected.
 *
 * ntfy does not return a success result from its share activity. The relay therefore treats the
 * return from ntfy as the end of the dispatch and automatically advances the queue.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_NTFY_SHARE = 1001;
    private static final int COLOR_PRIMARY = 0xFF338574;
    private static final int COLOR_PRIMARY_DARK = 0xFF286D60;
    private static final int COLOR_BACKGROUND = 0xFFF5F8F7;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF1D2B28;
    private static final int COLOR_MUTED = 0xFF65736F;
    private static final int COLOR_OUTLINE = 0xFFD7E1DE;
    private static final String NTFY_RELEASE_PACKAGE = "io.heckel.ntfy";
    private static final String NTFY_DEBUG_PACKAGE = "io.heckel.ntfy.debug";
    private static final String NTFY_SHARE_ACTIVITY = "io.heckel.ntfy.ui.ShareActivity";
    private static final String PREFS_NAME = "relay_state";
    private static final String PREFS_QUEUE = "queue";
    private static final String PREFS_ACTIVE_FILES = "active_files";
    private static final String PREFS_ACTIVE_ZIP = "active_zip";
    private static final String PREFS_ACTIVE_AWAITING_NTFY = "active_awaiting_ntfy";
    private static final String PREFS_LEGACY_ACTIVE_AWAITING_CONFIRMATION =
            "active_awaiting_confirmation";
    private static final String PREFS_SEND_AS_ZIP = "send_as_zip";
    private static final String QUEUE_DIRECTORY = "queue";
    private static final String EXTRA_IMPORT_HANDLED =
            "de.theduffman85.ntfyrelay.extra.IMPORT_HANDLED";

    private final List<QueuedFile> queue = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LinearLayout queueContainer;
    private TextView statusText;
    private TextView emptyText;
    private LinearLayout emptyState;
    private TextView queueCountText;
    private Switch zipModeSwitch;
    private Button sendButton;
    private Button clearButton;

    private QueuedFile activeFile;
    private List<QueuedFile> activeFiles = new ArrayList<>();
    private File activeZipFile;
    private boolean awaitingNtfy;
    private boolean preparingZip;
    private boolean importing;
    private boolean restoredTransferAwaitingNtfy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadQueue();
        buildUi();
        restoreActiveTransfer();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!restoredTransferAwaitingNtfy || !awaitingNtfy || activeFiles.isEmpty()) {
            return;
        }

        // If Android recreated this Activity while ntfy was open, the legacy activity-result
        // callback may not be delivered to the new instance. The transfer is still pending, so
        // finish it when this Activity becomes visible again.
        restoredTransferAwaitingNtfy = false;
        completeActiveTransfer();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_NTFY_SHARE || !awaitingNtfy) {
            return;
        }

        // ntfy currently calls finish() without setting RESULT_OK, so the result code cannot tell
        // us whether the user sent or cancelled. Fire-and-forget intentionally treats either
        // outcome as complete and advances the queue.
        completeActiveTransfer();
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
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setClipToPadding(false);

        LinearLayout appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setPadding(dp(20), 0, dp(20), 0);
        appBar.setBackgroundColor(COLOR_PRIMARY);
        appBar.setElevation(dp(3));

        TextView title = new TextView(this);
        title.setText("ntfy Batch Share");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_VERTICAL);
        appBar.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        root.addView(appBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(20));

        LinearLayout introCard = new LinearLayout(this);
        introCard.setGravity(Gravity.CENTER_VERTICAL);
        introCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        introCard.setBackground(cardBackground());
        introCard.setElevation(dp(1));

        ImageView relayIcon = new ImageView(this);
        relayIcon.setImageResource(R.mipmap.ic_launcher);
        relayIcon.setContentDescription("ntfy Batch Share icon");
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconParams.rightMargin = dp(14);
        introCard.addView(relayIcon, iconParams);

        LinearLayout introCopy = new LinearLayout(this);
        introCopy.setOrientation(LinearLayout.VERTICAL);
        TextView introTitle = makeText("Send files through ntfy", 18, COLOR_TEXT);
        introTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        introCopy.addView(introTitle, wrapParams());
        TextView introSubtitle = makeText(
                "Queue several files here, then send them individually or as one ZIP. The queue advances automatically.",
                14, COLOR_MUTED);
        introSubtitle.setPadding(0, dp(4), 0, 0);
        introCopy.addView(introSubtitle, wrapParams());
        introCard.addView(introCopy, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(introCard, fullWidthParams());

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(COLOR_PRIMARY_DARK);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusText.setBackgroundResource(R.drawable.bg_status);
        LinearLayout.LayoutParams statusParams = fullWidthParams();
        statusParams.topMargin = dp(12);
        content.addView(statusText, statusParams);

        LinearLayout modeCard = new LinearLayout(this);
        modeCard.setGravity(Gravity.CENTER_VERTICAL);
        modeCard.setPadding(dp(16), dp(12), dp(12), dp(12));
        modeCard.setBackground(cardBackground());
        modeCard.setElevation(dp(1));

        LinearLayout modeCopy = new LinearLayout(this);
        modeCopy.setOrientation(LinearLayout.VERTICAL);
        TextView modeTitle = makeText("Send all files as one uncompressed ZIP", 15, COLOR_TEXT);
        modeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        modeCopy.addView(modeTitle, wrapParams());
        TextView modeSubtitle = makeText(
                "One ntfy message containing every queued file.", 13, COLOR_MUTED);
        modeSubtitle.setPadding(0, dp(3), 0, 0);
        modeCopy.addView(modeSubtitle, wrapParams());
        modeCard.addView(modeCopy, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        zipModeSwitch = new Switch(this);
        zipModeSwitch.setContentDescription("Send all queued files as one uncompressed ZIP");
        zipModeSwitch.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREFS_SEND_AS_ZIP, false));
        zipModeSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREFS_SEND_AS_ZIP, checked).apply();
            if (!importing && !awaitingNtfy && !preparingZip) {
                setStatus(checked
                        ? "All queued files will be sent as one uncompressed ZIP."
                        : "Queued files will be sent through ntfy one at a time.");
            }
            renderQueue();
        });
        modeCard.addView(zipModeSwitch, wrapParams());
        LinearLayout.LayoutParams modeCardParams = fullWidthParams();
        modeCardParams.topMargin = dp(12);
        content.addView(modeCard, modeCardParams);

        LinearLayout queueHeading = new LinearLayout(this);
        queueHeading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams queueHeadingParams = fullWidthParams();
        queueHeadingParams.topMargin = dp(24);
        queueHeadingParams.bottomMargin = dp(8);
        content.addView(queueHeading, queueHeadingParams);

        TextView queueTitle = makeText("QUEUE", 12, COLOR_MUTED);
        queueTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        queueTitle.setLetterSpacing(0.12f);
        queueHeading.addView(queueTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        queueCountText = makeText("EMPTY", 12, COLOR_MUTED);
        queueCountText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        queueCountText.setGravity(Gravity.END);
        queueHeading.addView(queueCountText, wrapParams());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        queueContainer = new LinearLayout(this);
        queueContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(queueContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(24), dp(28), dp(24), dp(28));
        emptyState.setMinimumHeight(dp(190));
        emptyState.setBackground(cardBackground());
        ImageView emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_file);
        emptyIcon.setContentDescription(null);
        emptyState.addView(emptyIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        emptyText = new TextView(this);
        emptyText.setTextSize(15);
        emptyText.setTextColor(COLOR_MUTED);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, dp(12), 0, 0);
        emptyState.addView(emptyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        queueContainer.addView(emptyState, fullWidthParams());

        LinearLayout scrollWrap = new LinearLayout(this);
        scrollWrap.setOrientation(LinearLayout.VERTICAL);
        // Let the queue fill the remaining space while allowing a long queue to scroll.
        scrollWrap.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        content.addView(scrollWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.VERTICAL);
        actionPanel.setPadding(dp(16), dp(8), dp(16), dp(4));
        actionPanel.setBackgroundColor(COLOR_SURFACE);
        actionPanel.setElevation(dp(4));

        sendButton = makeButton("Send current file to ntfy", R.drawable.btn_primary,
                Color.WHITE, v -> sendCurrentFile());
        actionPanel.addView(sendButton, fullWidthButtonParams());

        clearButton = makeButton("Clear queued files", R.drawable.btn_text,
                COLOR_MUTED, v -> clearQueue());
        actionPanel.addView(clearButton, fullWidthButtonParams());

        root.addView(actionPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int top = systemBars.top;
            int bottom = systemBars.bottom;

            // Draw the teal app bar behind the status bar on edge-to-edge Android versions.
            appBar.setPadding(dp(20), top, dp(20), 0);
            LinearLayout.LayoutParams appBarParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(60) + top);
            appBar.setLayoutParams(appBarParams);
            actionPanel.setPadding(dp(16), dp(8), dp(16), dp(4) + bottom);
            return insets;
        });

        setContentView(root);
        setStatus("Ready to receive files from Android Share.");
        renderQueue();
    }

    private Button makeButton(String label, int backgroundResource, int textColor,
                              View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(textColor);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setMinWidth(0);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackgroundResource(backgroundResource);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams fullWidthButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private TextView makeText(String value, int sizeSp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        return text;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_SURFACE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), COLOR_OUTLINE);
        return background;
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_IMPORT_HANDLED, false)) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }

        List<Uri> uris = extractUris(intent);
        if (uris.size() < 2) {
            setStatus("ntfy Batch Share is available only for multiple files.");
            finishIfQueueEmpty();
            return;
        }

        // The original share Intent can be restored after the Activity is recreated. Do not
        // import the same source files a second time in that case.
        intent.putExtra(EXTRA_IMPORT_HANDLED, true);
        setIntent(intent);

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
                            + " queued. " + (isZipModeEnabled()
                            ? "Send them as one uncompressed ZIP."
                            : "Send them through ntfy one at a time."));
                } else {
                    setStatus(imported.size() + " queued; " + failures.size()
                            + " could not be imported.");
                    Toast.makeText(this, failures.get(0), Toast.LENGTH_LONG).show();
                }
                renderQueue();
                finishIfQueueEmpty();
            });
        });
    }

    private List<Uri> extractUris(Intent intent) {
        Set<String> seen = new LinkedHashSet<>();
        List<Uri> result = new ArrayList<>();

        ArrayList<Parcelable> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) {
            for (Parcelable parcelable : streams) {
                if (parcelable instanceof Uri) {
                    addUri((Uri) parcelable, seen, result);
                }
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
        if (importing || preparingZip || awaitingNtfy || queue.isEmpty()) {
            return;
        }

        String ntfyPackage = findInstalledNtfyPackage();
        if (ntfyPackage == null) {
            setStatus("The ntfy Android app is not installed.");
            Toast.makeText(this, "Install ntfy before sending files.", Toast.LENGTH_LONG).show();
            return;
        }

        if (isZipModeEnabled()) {
            prepareZipAndSend(new ArrayList<>(queue), ntfyPackage);
        } else {
            sendSingleFile(queue.get(0), ntfyPackage);
        }
    }

    private void sendSingleFile(QueuedFile file, String ntfyPackage) {
        File localFile = localFile(file);
        if (!localFile.isFile()) {
            removeFileFromQueue(file);
            saveQueue();
            renderQueue();
            setStatus("A queued file was no longer available and was removed.");
            finishIfQueueEmpty();
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
        activeFiles = new ArrayList<>();
        activeFiles.add(file);
        activeZipFile = null;
        awaitingNtfy = true;
        saveActiveTransfer();
        setStatus("Sending “" + file.displayName + "” through ntfy…");
        renderQueue();

        try {
            startActivityForResult(shareIntent, REQUEST_NTFY_SHARE);
        } catch (ActivityNotFoundException exception) {
            clearActiveTransfer();
            awaitingNtfy = false;
            setStatus("The installed ntfy app does not expose its share activity.");
            renderQueue();
        }
    }

    private void prepareZipAndSend(List<QueuedFile> files, String ntfyPackage) {
        if (files.isEmpty()) {
            return;
        }

        preparingZip = true;
        setStatus("Preparing an uncompressed ZIP of " + files.size() + " files…");
        renderQueue();

        ioExecutor.execute(() -> {
            File zipFile = null;
            try {
                zipFile = createUncompressedZip(files);
                File preparedZip = zipFile;
                mainHandler.post(() -> {
                    preparingZip = false;
                    if (isFinishing() || !queue.containsAll(files)) {
                        deleteRecursively(preparedZip.getParentFile());
                        if (!isFinishing()) {
                            setStatus("The queue changed while the ZIP was being prepared.");
                        }
                        renderQueue();
                        return;
                    }
                    sendPreparedZip(files, preparedZip, ntfyPackage);
                });
            } catch (Exception exception) {
                if (zipFile != null) {
                    deleteRecursively(zipFile.getParentFile());
                }
                mainHandler.post(() -> {
                    preparingZip = false;
                    setStatus("Could not create the uncompressed ZIP: "
                            + safeMessage(exception));
                    Toast.makeText(this, "The files could not be packaged.", Toast.LENGTH_LONG)
                            .show();
                    renderQueue();
                });
            }
        });
    }

    private File createUncompressedZip(List<QueuedFile> files) throws IOException {
        File queueDirectory = new File(getFilesDir(), QUEUE_DIRECTORY);
        File bundleDirectory = new File(queueDirectory, "bundle-" + UUID.randomUUID());
        if (!bundleDirectory.mkdirs()) {
            throw new IOException("Unable to create ZIP directory");
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File zipFile = new File(bundleDirectory, "ntfy-files-" + timestamp + ".zip");
        try {
            Set<String> entryNames = new LinkedHashSet<>();
            try (ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(zipFile))) {
                // STORED entries below are the important part: the file data is not deflated.
                zipOutput.setLevel(Deflater.NO_COMPRESSION);
                for (QueuedFile file : files) {
                    File source = localFile(file);
                    if (!source.isFile()) {
                        throw new IOException("Queued file is no longer available: "
                                + file.displayName);
                    }

                    ZipEntryMetadata metadata = inspectFileForZip(source);
                    ZipEntry entry = new ZipEntry(uniqueZipEntryName(file.displayName, entryNames));
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(metadata.size);
                    entry.setCompressedSize(metadata.size);
                    entry.setCrc(metadata.crc);
                    zipOutput.putNextEntry(entry);
                    try (InputStream input = new FileInputStream(source)) {
                        copyStream(input, zipOutput);
                    }
                    zipOutput.closeEntry();
                }
            }
            return zipFile;
        } catch (Exception exception) {
            deleteRecursively(bundleDirectory);
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException(exception);
        }
    }

    private ZipEntryMetadata inspectFileForZip(File source) throws IOException {
        CRC32 crc = new CRC32();
        long size = 0;
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
                size += read;
            }
        }
        return new ZipEntryMetadata(size, crc.getValue());
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private String uniqueZipEntryName(String displayName, Set<String> entryNames) {
        String baseName = safeFileName(displayName);
        String stem = baseName;
        String extension = "";
        int extensionStart = baseName.lastIndexOf('.');
        if (extensionStart > 0) {
            stem = baseName.substring(0, extensionStart);
            extension = baseName.substring(extensionStart);
        }

        String candidate = baseName;
        int duplicateNumber = 2;
        while (!entryNames.add(candidate)) {
            candidate = stem + " (" + duplicateNumber++ + ")" + extension;
        }
        return candidate;
    }

    private void sendPreparedZip(List<QueuedFile> files, File zipFile, String ntfyPackage) {
        if (!zipFile.isFile()) {
            deleteRecursively(zipFile.getParentFile());
            setStatus("The prepared ZIP was no longer available.");
            return;
        }

        Uri shareUri;
        activeFile = files.get(0);
        activeFiles = new ArrayList<>(files);
        activeZipFile = zipFile;
        try {
            shareUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", zipFile);
        } catch (IllegalArgumentException exception) {
            clearActiveTransfer();
            setStatus("Could not prepare the ZIP for sharing.");
            renderQueue();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setComponent(new ComponentName(ntfyPackage, NTFY_SHARE_ACTIVITY));
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
        shareIntent.setClipData(ClipData.newRawUri(zipFile.getName(), shareUri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        awaitingNtfy = true;
        saveActiveTransfer();
        setStatus("Sending " + files.size() + " files as one uncompressed ZIP through ntfy…");
        renderQueue();

        try {
            startActivityForResult(shareIntent, REQUEST_NTFY_SHARE);
        } catch (ActivityNotFoundException exception) {
            clearActiveTransfer();
            awaitingNtfy = false;
            setStatus("The installed ntfy app does not expose its share activity.");
            renderQueue();
        }
    }

    private void completeActiveTransfer() {
        if (!awaitingNtfy || activeFiles.isEmpty()) {
            return;
        }

        List<QueuedFile> completedFiles = new ArrayList<>(activeFiles);
        boolean completedZip = activeZipFile != null;
        String completedName = activeFile == null ? "file" : activeFile.displayName;
        awaitingNtfy = false;
        for (QueuedFile completed : completedFiles) {
            removeFileFromQueue(completed);
        }
        clearActiveTransfer();
        saveQueue();
        setStatus(completedZip
                ? "Dispatched the ZIP containing " + completedFiles.size() + " files."
                : "Dispatched “" + completedName + "”.");
        renderQueue();
        if (queue.isEmpty()) {
            finish();
        } else {
            sendCurrentFile();
        }
    }

    private void clearQueue() {
        if (importing || preparingZip || awaitingNtfy) {
            return;
        }

        // Clear the durable state before leaving the Activity. Using commit() here is deliberate:
        // this action immediately finishes when the queue becomes empty, so a deferred apply()
        // must not be the only record that the user explicitly cleared the queue.
        queue.clear();
        boolean stateCleared = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREFS_QUEUE)
                .remove(PREFS_ACTIVE_FILES)
                .remove(PREFS_ACTIVE_ZIP)
                .remove(PREFS_ACTIVE_AWAITING_NTFY)
                .remove(PREFS_LEGACY_ACTIVE_AWAITING_CONFIRMATION)
                .commit();

        // Delete the complete directory rather than only the files represented by the current
        // in-memory queue. This also removes abandoned imports and prepared ZIP bundles.
        boolean filesCleared = deleteRecursively(new File(getFilesDir(), QUEUE_DIRECTORY));
        if (stateCleared && filesCleared) {
            setStatus("Queue cleared.");
        } else {
            setStatus("Queue cleared, but some temporary data could not be removed.");
            Toast.makeText(this, "Some temporary queue data could not be removed.",
                    Toast.LENGTH_LONG).show();
        }
        renderQueue();
        finishIfQueueEmpty();
    }

    private void clearActiveTransfer() {
        if (activeZipFile != null) {
            deleteRecursively(activeZipFile.getParentFile());
        }
        activeZipFile = null;
        activeFile = null;
        activeFiles.clear();
        clearPersistedActiveTransfer();
    }

    private boolean isZipModeEnabled() {
        return zipModeSwitch != null && zipModeSwitch.isChecked();
    }

    private void finishIfQueueEmpty() {
        if (queue.isEmpty()) {
            finish();
        }
    }

    private void renderQueue() {
        if (queueContainer == null) {
            return;
        }

        queueContainer.removeAllViews();
        boolean hasQueue = !queue.isEmpty();
        queueCountText.setText(hasQueue
                ? queue.size() + (queue.size() == 1 ? " FILE" : " FILES") : "EMPTY");

        if (queue.isEmpty()) {
            emptyText.setText("No files queued yet. Use Android’s Share action to add one or more files.");
            queueContainer.addView(emptyState, fullWidthParams());
        } else {
            for (int index = 0; index < queue.size(); index++) {
                QueuedFile file = queue.get(index);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(14), dp(14), dp(14));
                row.setBackground(queueCardBackground(index == 0));
                row.setElevation(dp(1));

                ImageView fileIcon = new ImageView(this);
                fileIcon.setImageResource(R.drawable.ic_file);
                fileIcon.setContentDescription(null);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
                iconParams.rightMargin = dp(12);
                row.addView(fileIcon, iconParams);

                LinearLayout details = new LinearLayout(this);
                details.setOrientation(LinearLayout.VERTICAL);

                LinearLayout labelRow = new LinearLayout(this);
                labelRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView badge = makeText(index == 0 ? "NEXT" : "" + (index + 1), 11,
                        index == 0 ? COLOR_PRIMARY_DARK : COLOR_MUTED);
                badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                badge.setGravity(Gravity.CENTER);
                badge.setIncludeFontPadding(false);
                badge.setPadding(dp(8), dp(5), dp(8), dp(5));
                badge.setBackgroundResource(index == 0
                        ? R.drawable.bg_chip_primary : R.drawable.bg_chip_neutral);
                labelRow.addView(badge, wrapParams());
                details.addView(labelRow, wrapParams());

                TextView name = makeText(file.displayName, 16, COLOR_TEXT);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                LinearLayout.LayoutParams nameParams = fullWidthParams();
                nameParams.topMargin = dp(7);
                details.addView(name, nameParams);

                TextView metadata = makeText(formatBytes(file.size) + "  •  " + file.mimeType,
                        13, COLOR_MUTED);
                metadata.setSingleLine(true);
                metadata.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams metadataParams = fullWidthParams();
                metadataParams.topMargin = dp(3);
                details.addView(metadata, metadataParams);

                row.addView(details, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout.LayoutParams params = fullWidthParams();
                params.bottomMargin = dp(6);
                queueContainer.addView(row, params);
            }
        }

        sendButton.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        if (preparingZip) {
            sendButton.setText("Preparing ZIP…");
        } else if (awaitingNtfy) {
            sendButton.setText("Opening ntfy…");
        } else {
            sendButton.setText(isZipModeEnabled()
                    ? "Send all files as one ZIP"
                    : "Send queue through ntfy");
        }
        sendButton.setEnabled(hasQueue && !importing && !preparingZip
                && !awaitingNtfy);

        zipModeSwitch.setEnabled(!importing && !preparingZip && !awaitingNtfy);

        clearButton.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!importing && !preparingZip && !awaitingNtfy);
    }

    private GradientDrawable queueCardBackground(boolean next) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_SURFACE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(next ? 2 : 1), next ? COLOR_PRIMARY : COLOR_OUTLINE);
        return background;
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

    private void saveActiveTransfer() {
        JSONArray array = new JSONArray();
        for (QueuedFile file : activeFiles) {
            array.put(file.id);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREFS_ACTIVE_FILES, array.toString())
                .putString(PREFS_ACTIVE_ZIP,
                        activeZipFile == null ? "" : activeZipFile.getPath())
                .putBoolean(PREFS_ACTIVE_AWAITING_NTFY, awaitingNtfy)
                .remove(PREFS_LEGACY_ACTIVE_AWAITING_CONFIRMATION)
                .apply();
    }

    private void restoreActiveTransfer() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serialized = preferences.getString(PREFS_ACTIVE_FILES, null);
        if (serialized == null) {
            return;
        }

        try {
            JSONArray array = new JSONArray(serialized);
            List<QueuedFile> restoredFiles = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                String id = array.getString(index);
                for (QueuedFile queued : queue) {
                    if (queued.id.equals(id)) {
                        restoredFiles.add(queued);
                        break;
                    }
                }
            }

            if (restoredFiles.isEmpty()) {
                clearPersistedActiveTransfer();
                return;
            }

            activeFiles = restoredFiles;
            activeFile = restoredFiles.get(0);
            String zipPath = preferences.getString(PREFS_ACTIVE_ZIP, "");
            activeZipFile = zipPath.isEmpty() ? null : new File(zipPath);
            awaitingNtfy = preferences.getBoolean(PREFS_ACTIVE_AWAITING_NTFY, false);
            // An older version persisted a separate confirmation state. Treat that state as a
            // pending fire-and-forget transfer so an upgrade cannot leave files stranded.
            boolean legacyAwaitingConfirmation = preferences.getBoolean(
                    PREFS_LEGACY_ACTIVE_AWAITING_CONFIRMATION, false);
            if (!awaitingNtfy && !legacyAwaitingConfirmation) {
                clearActiveTransfer();
                return;
            }
            awaitingNtfy = true;

            restoredTransferAwaitingNtfy = awaitingNtfy;
            setStatus(activeZipFile == null
                    ? "A file is awaiting dispatch."
                    : "A ZIP is awaiting dispatch.");
            renderQueue();
        } catch (Exception exception) {
            clearPersistedActiveTransfer();
        }
    }

    private void clearPersistedActiveTransfer() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREFS_ACTIVE_FILES)
                .remove(PREFS_ACTIVE_ZIP)
                .remove(PREFS_ACTIVE_AWAITING_NTFY)
                .remove(PREFS_LEGACY_ACTIVE_AWAITING_CONFIRMATION)
                .apply();
    }

    private File localFile(QueuedFile file) {
        return new File(getFilesDir(), file.relativePath);
    }

    private void removeFileFromQueue(QueuedFile file) {
        // Match by the persisted ID as well as by object identity. A transfer can outlive the
        // Activity instance, so the object held by activeFiles may not be the same instance that
        // was loaded into queue when the Activity was recreated.
        for (int index = 0; index < queue.size(); index++) {
            QueuedFile queued = queue.get(index);
            if (queued.id.equals(file.id)) {
                queue.remove(index);
                deleteRecursively(localFile(queued).getParentFile());
                return;
            }
        }
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

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        boolean childrenDeleted = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    childrenDeleted &= deleteRecursively(child);
                }
            } else {
                childrenDeleted = false;
            }
        }
        // Queue files are private temporary copies created by this app.
        return childrenDeleted && (file.delete() || !file.exists());
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

        static QueuedFile fromJson(JSONObject object) throws JSONException {
            return new QueuedFile(
                    object.getString("id"),
                    object.getString("displayName"),
                    object.getString("mimeType"),
                    object.getString("relativePath"),
                    object.optLong("size", 0));
        }
    }

    private static final class ZipEntryMetadata {
        final long size;
        final long crc;

        ZipEntryMetadata(long size, long crc) {
            this.size = size;
            this.crc = crc;
        }
    }
}
