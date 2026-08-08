# ntfy Multi-file Relay

This is a small Android share-target app for the ntfy Android app. It accepts one or more files,
copies them into a private queue, and opens ntfy's existing share activity for one file at a time.
It does not modify or fork ntfy.

## Behavior

1. In a file manager or gallery, select several files and choose **ntfy Multi-file Relay**.
2. The relay copies the incoming `content://` URIs into app-private storage so the queue remains
   readable while ntfy is open.
3. Tap **Send current file to ntfy**.
4. Complete the normal ntfy share flow, including choosing the topic and tapping Send.
5. When ntfy returns, choose **Sent — open next**, **Retry current file**, or **Skip current file**.

The confirmation is intentional: ntfy's current share activity finishes without returning a success
result, so the relay cannot distinguish a successful send from pressing Back.

## Build

Open the directory in Android Studio and let it install the Android SDK and Gradle components, or
run this from a machine with Android SDK platform 35 installed:

```text
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it with Android Studio or:

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Compatibility note

The relay targets ntfy's current exported activity:

```text
io.heckel.ntfy/.ui.ShareActivity
```

That is an integration point rather than a stable API. If ntfy changes the package name, activity
name, or stops exporting the activity, this relay will need a small update. It deliberately does not
depend on ntfy's private storage, credentials, or source tree.
