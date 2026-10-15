package com.sewanagala.sepsale;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AppUpdatePlugin")
public class AppUpdatePlugin extends Plugin {

    private static final String TAG = "AppUpdatePlugin";
    private static final String MANIFEST_URL = "https://pub-ea8812066a2f45849522e9ddf17296fb.r2.dev/version.json";
    private static final String UPDATE_PREFS = "sep_sales_update";
    private static final String DOWNLOAD_ID_KEY = "download_id";
    private static final String PENDING_INSTALL_URI_KEY = "pending_install_uri";
    private static final String READY_APK_PATH_KEY = "ready_apk_path";
    private static final String READY_VERSION_KEY = "ready_version";
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AlertDialog updateDialog = null;
    private TextView statusTextView = null;
    private ProgressBar progressBar = null;
    private TextView percentTextView = null;
    private Button actionButton = null;
    private File downloadedApkFile = null;
    private volatile boolean downloadInProgress = false;

    /**
     * Request all required Android runtime permissions (Camera, Location, Bluetooth, Storage, Notifications)
     */
    @PluginMethod
    public void requestAllSystemPermissions(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity not available");
            return;
        }

        List<String> permissionsToRequest = new ArrayList<>();

        // Camera
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }

        // Location
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            activity.runOnUiThread(() -> {
                ActivityCompat.requestPermissions(
                        activity,
                        permissionsToRequest.toArray(new String[0]),
                        1001
                );
            });
        }

        // Also check unknown apps permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())
                    );
                    activity.startActivity(intent);
                } catch (Exception ignored) {}
            });
        }

        JSObject ret = new JSObject();
        ret.put("requested", permissionsToRequest.size());
        call.resolve(ret);
    }

    /**
     * Opens the native Android update dialog that performs the full check -> download -> install flow.
     */
    @PluginMethod
    public void openNativeUpdateDialog(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }

        activity.runOnUiThread(() -> {
            showUpdateModal(activity);
            call.resolve();
        });
    }

    /**
     * Download an APK to app-private storage, report progress to the web UI,
     * then open Android's package installer. Android still requires the user
     * to confirm the installation unless the device is managed/system-owned.
     */
    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }

        String apkUrl = call.getString(
                "apkUrl",
                "https://pub-ea8812066a2f45849522e9ddf17296fb.r2.dev/app-release.apk"
        );

        if (apkUrl == null || !(apkUrl.startsWith("https://") || apkUrl.startsWith("http://"))) {
            call.reject("Invalid APK download URL");
            return;
        }

        String version = call.getString("version", "latest");
        if (downloadInProgress) {
            call.reject("An APK update download is already in progress");
            return;
        }
        downloadInProgress = true;
        executor.execute(() -> downloadWithAndroidDownloadManager(activity, apkUrl, version, true, call));
    }

    /** Download and retain an update without opening Android's installer. */
    @PluginMethod
    public void downloadUpdate(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        String apkUrl = call.getString("apkUrl", "");
        String version = call.getString("version", "latest");
        if (apkUrl == null || !(apkUrl.startsWith("https://") || apkUrl.startsWith("http://"))) {
            call.reject("Invalid APK download URL");
            return;
        }

        File readyFile = getReadyApk(activity);
        String readyVersion = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .getString(READY_VERSION_KEY, "");
        if (readyFile != null && readyFile.exists() && version.equals(readyVersion)) {
            call.resolve(updateStatusPayload(activity, readyFile, readyVersion, true));
            return;
        }
        if (downloadInProgress) {
            JSObject result = updateStatusPayload(activity, null, version, false);
            result.put("downloading", true);
            call.resolve(result);
            return;
        }
        downloadInProgress = true;
        executor.execute(() -> downloadWithAndroidDownloadManager(activity, apkUrl, version, false, call));
    }

    /** Open the installer for an APK that was already downloaded. */
    @PluginMethod
    public void installDownloadedUpdate(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        File readyFile = getReadyApk(activity);
        if (readyFile == null || !readyFile.exists() || readyFile.length() <= 0) {
            clearReadyUpdateState(activity);
            call.reject("No downloaded APK update is ready");
            return;
        }
        boolean installerStarted = installDownloadedApk(activity, readyFile);
        JSObject result = updateStatusPayload(
                activity,
                readyFile,
                activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getString(READY_VERSION_KEY, ""),
                true
        );
        result.put("installerStarted", installerStarted);
        result.put("permissionRequired", !installerStarted);
        call.resolve(result);
    }

    /** Report whether a reusable APK is already available on this device. */
    @PluginMethod
    public void getDownloadedUpdate(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        File readyFile = getReadyApk(activity);
        String readyVersion = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .getString(READY_VERSION_KEY, "");
        boolean ready = readyFile != null && readyFile.exists() && readyFile.length() > 0;
        if (!ready) clearReadyUpdateState(activity);
        call.resolve(updateStatusPayload(activity, ready ? readyFile : null, ready ? readyVersion : "", ready));
    }

    /** Return the installed native package version to remote WebView pages. */
    @PluginMethod
    public void getInstalledVersion(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }
        try {
            android.content.pm.PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            JSObject result = new JSObject();
            result.put("version", info.versionName == null ? "" : info.versionName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                result.put("versionCode", info.getLongVersionCode());
            } else {
                result.put("versionCode", info.versionCode);
            }
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Could not read installed app version", error);
        }
    }

    /**
     * Android returns here after the user enables "Allow from this source".
     * The old implementation opened Settings but lost the downloaded APK URI,
     * so pressing the permission switch appeared to do nothing.
     */
    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        Activity activity = getActivity();
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) return;

        String pendingUri = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .getString(PENDING_INSTALL_URI_KEY, null);
        if (pendingUri == null || pendingUri.trim().isEmpty()) return;

        activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(PENDING_INSTALL_URI_KEY).apply();
        mainHandler.postDelayed(() -> installDownloadedApk(activity, Uri.parse(pendingUri)), 300);
    }

    /** Open a web URL in the user's external browser, not inside the wrapper WebView. */
    @PluginMethod
    public void openExternalUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || url.trim().isEmpty()) {
            call.reject("URL is required");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception error) {
            call.reject("No browser is available", error);
        }
    }

    /**
     * DownloadManager is owned by Android and survives the WebView going to
     * the background. It also shows a system download notification, unlike a
     * plain thread which can be stopped when the app process is reclaimed.
     */
    private void downloadWithAndroidDownloadManager(Activity activity, String apkUrl, String version, boolean installAfterDownload, PluginCall call) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            call.reject("Android download service is unavailable");
            return;
        }

        long downloadId = -1;
        try {
            cleanupOwnedUpdateFiles(activity, null);
            String fileName = "SEP-Sales-update-" + System.currentTimeMillis() + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("SEP Sales update");
            request.setDescription("Downloading the latest SEP Sales APK in the background");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

            downloadId = manager.enqueue(request);
            activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(DOWNLOAD_ID_KEY, downloadId).apply();

            boolean finished = false;
            while (!finished) {
                Thread.sleep(500);
                try (android.database.Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new Exception("Background APK download disappeared");
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int percent = total > 0 ? (int) Math.min(99, (downloaded * 100) / total) : 0;
                    notifyListeners("downloadProgress", progressPayload(percent, downloaded, total, "background://" + downloadId));

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri apkUri = manager.getUriForDownloadedFile(downloadId);
                        if (apkUri == null) throw new Exception("Android did not provide the downloaded APK");
                        // DownloadManager returns a protected content URI. The
                        // package installer cannot reliably receive that URI on
                        // Pixel/Android 14, so copy it into our FileProvider
                        // storage before launching the installer.
                        File privateApk = copyDownloadedApk(activity, apkUri, version);
                        validateDownloadedApk(activity, privateApk, version);
                        manager.remove(downloadId);
                        activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                                .edit()
                                .putString(READY_APK_PATH_KEY, privateApk.getAbsolutePath())
                                .putString(READY_VERSION_KEY, version)
                                .apply();
                        Uri installUri = FileProvider.getUriForFile(
                                activity,
                                activity.getPackageName() + ".fileprovider",
                                privateApk
                        );
                        finished = true;
                        mainHandler.post(() -> {
                            notifyListeners("downloadProgress", progressPayload(100, total, total, installUri.toString()));
                            if (!installAfterDownload) {
                                call.resolve(updateStatusPayload(activity, privateApk, version, true));
                            } else {
                                boolean installerStarted = installDownloadedApk(activity, installUri);
                                JSObject result = updateStatusPayload(activity, privateApk, version, true)
                                        .put("installerStarted", installerStarted);
                                call.resolve(installerStarted ? result : result.put("permissionRequired", true));
                            }
                        });
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        throw new Exception("Background APK download failed (" + reason + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Background APK download/install error", e);
            if (downloadId != -1) manager.remove(downloadId);
            cleanupOwnedUpdateFiles(activity, null);
            notifyListeners("downloadError", new JSObject().put("message", e.getMessage() == null ? "APK update failed" : e.getMessage()));
            call.reject(e.getMessage() == null ? "APK update failed" : e.getMessage(), e);
        } finally {
            downloadInProgress = false;
            activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().remove(DOWNLOAD_ID_KEY).apply();
        }
    }

    private File copyDownloadedApk(Activity activity, Uri sourceUri, String version) throws Exception {
        File externalDownloads = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File updateDirectory = externalDownloads == null
                ? new File(activity.getFilesDir(), "updates")
                : new File(externalDownloads, "updates");
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw new Exception("Could not create private APK update directory");
        }

        String safeVersion = version == null ? "latest" : version.replaceAll("[^A-Za-z0-9._-]", "-");
        File target = new File(updateDirectory, "SEP-Sales-update-" + safeVersion + ".apk");
        File temporary = new File(updateDirectory, target.getName() + ".part");
        if (temporary.exists()) temporary.delete();

        try (InputStream input = activity.getContentResolver().openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(temporary)) {
            if (input == null) throw new Exception("Could not open downloaded APK");
            byte[] buffer = new byte[32 * 1024];
            int count;
            long copied = 0;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }
            output.flush();
            if (copied <= 0) throw new Exception("Downloaded APK is empty");
        }

        if (target.exists()) target.delete();
        if (!temporary.renameTo(target)) throw new Exception("Could not prepare APK for Android installer");
        return target;
    }

    /** Reject corrupt, unrelated, or incorrectly-versioned packages before offering installation. */
    private void validateDownloadedApk(Activity activity, File apkFile, String expectedVersion) throws Exception {
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (archive == null || archive.packageName == null) {
            apkFile.delete();
            throw new Exception("Downloaded file is not a valid Android APK");
        }
        if (!activity.getPackageName().equals(archive.packageName)) {
            apkFile.delete();
            throw new Exception("Downloaded APK belongs to a different application");
        }
        String expected = normalizeVersion(expectedVersion);
        String actual = normalizeVersion(archive.versionName);
        if (!expected.isEmpty() && !"latest".equalsIgnoreCase(expectedVersion) && !expected.equals(actual)) {
            apkFile.delete();
            throw new Exception("Downloaded APK version " + archive.versionName + " does not match expected version " + expectedVersion);
        }
    }

    private String normalizeVersion(String version) {
        if (version == null) return "";
        return version.trim().replaceFirst("^[vV]", "");
    }

    private File getReadyApk(Activity activity) {
        String path = activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .getString(READY_APK_PATH_KEY, "");
        return path == null || path.trim().isEmpty() ? null : new File(path);
    }

    private JSObject updateStatusPayload(Activity activity, File apkFile, String version, boolean ready) {
        JSObject result = new JSObject();
        result.put("ready", ready);
        result.put("version", version == null ? "" : version);
        result.put("path", apkFile == null ? "" : apkFile.getAbsolutePath());
        result.put("size", apkFile == null || !apkFile.exists() ? 0 : apkFile.length());
        result.put("storageLocation", "Android/data/" + activity.getPackageName() + "/files/Download/updates");
        result.put("background", true);
        return result;
    }

    private void clearReadyUpdateState(Activity activity) {
        activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(READY_APK_PATH_KEY).remove(READY_VERSION_KEY).apply();
    }

    /** Deletes only APK artifacts created inside SEP Sales' own update folders. */
    private void cleanupOwnedUpdateFiles(Activity activity, File keepFile) {
        File externalRoot = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File updateDirectory = externalRoot == null ? null : new File(externalRoot, "updates");
        deleteOwnedApks(externalRoot, keepFile);
        deleteOwnedApks(updateDirectory, keepFile);
        deleteOwnedApks(new File(activity.getFilesDir(), "updates"), keepFile);
        clearReadyUpdateState(activity);
    }

    private void deleteOwnedApks(File directory, File keepFile) {
        if (directory == null || !directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        String keepPath = keepFile == null ? "" : keepFile.getAbsolutePath();
        for (File file : files) {
            if (file == null || file.getAbsolutePath().equals(keepPath)) continue;
            String name = file.getName();
            if (name.startsWith("SEP-Sales-") || name.startsWith("sep-sales-")) {
                if (!file.delete()) Log.w(TAG, "Could not delete stale updater file: " + file.getAbsolutePath());
            }
        }
    }

    private File downloadApk(Activity activity, String apkUrl) throws Exception {
        URL downloadUrl = new URL(apkUrl);
        HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("User-Agent", "SEPSales-Android-Updater");
        connection.connect();

        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
            throw new Exception("APK server response code: " + connection.getResponseCode());
        }

        File updateDirectory = new File(activity.getFilesDir(), "updates");
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw new Exception("Could not create app update directory");
        }

        File temporaryFile = new File(updateDirectory, "sep-sales-update.apk.part");
        File finalFile = new File(updateDirectory, "sep-sales-update.apk");
        if (temporaryFile.exists()) temporaryFile.delete();

        int fileLength = connection.getContentLength();
        long total = 0;
        long lastProgress = 0;

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(temporaryFile)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                output.write(buffer, 0, count);
                long now = System.currentTimeMillis();
                if (fileLength > 0 && (now - lastProgress > 150 || total == fileLength)) {
                    lastProgress = now;
                    int percent = (int) Math.min(99, (total * 100) / fileLength);
                    notifyListeners("downloadProgress", progressPayload(percent, total, fileLength, finalFile.getAbsolutePath()));
                }
            }
            output.flush();
        } finally {
            connection.disconnect();
        }

        if (total <= 0) {
            temporaryFile.delete();
            throw new Exception("Downloaded APK is empty");
        }
        if (finalFile.exists()) finalFile.delete();
        if (!temporaryFile.renameTo(finalFile)) {
            throw new Exception("Could not save APK to " + finalFile.getAbsolutePath());
        }
        return finalFile;
    }

    private JSObject progressPayload(int percent, long downloaded, long total, String path) {
        JSObject payload = new JSObject();
        payload.put("percent", percent);
        payload.put("downloaded", downloaded);
        payload.put("total", total);
        payload.put("path", path);
        return payload;
    }

    private void showUpdateModal(Activity activity) {
        if (updateDialog != null && updateDialog.isShowing()) {
            updateDialog.dismiss();
        }

        // Build a sleek modern native dialog programmatically
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 60, 60, 60);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(activity);
        title.setText("SEP Sales App Update");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#0f172a"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        statusTextView = new TextView(activity);
        statusTextView.setText("Checking for updates and verifying sync...");
        statusTextView.setTextSize(13);
        statusTextView.setTextColor(Color.parseColor("#475569"));
        statusTextView.setPadding(0, 20, 0, 30);
        root.addView(statusTextView);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setMax(100);
        progressBar.setVisibility(View.VISIBLE);
        root.addView(progressBar);

        percentTextView = new TextView(activity);
        percentTextView.setText("");
        percentTextView.setTextSize(12);
        percentTextView.setTextColor(Color.parseColor("#2563eb"));
        percentTextView.setGravity(Gravity.END);
        percentTextView.setPadding(0, 10, 0, 30);
        root.addView(percentTextView);

        actionButton = new Button(activity);
        actionButton.setText("Cancel");
        actionButton.setBackgroundColor(Color.parseColor("#f1f5f9"));
        actionButton.setTextColor(Color.parseColor("#334155"));
        actionButton.setOnClickListener(v -> {
            if (updateDialog != null) updateDialog.dismiss();
        });
        root.addView(actionButton);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(root);
        builder.setCancelable(false);

        updateDialog = builder.create();
        if (updateDialog.getWindow() != null) {
            updateDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        updateDialog.show();

        // Start background update process
        startBackgroundUpdateFlow(activity);
    }

    private void startBackgroundUpdateFlow(Activity activity) {
        executor.execute(() -> {
            try {
                // 1. Fetch manifest from Cloudflare R2
                updateStatusOnUI("Checking latest version from cloud...", true, 0);
                URL url = new URL(MANIFEST_URL + "?t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new Exception("Server response code: " + conn.getResponseCode());
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject manifest = new JSONObject(sb.toString());
                String remoteVer = manifest.optString("version", "12.3.1");
                String apkUrl = manifest.optString("apkUrl", "https://pub-ea8812066a2f45849522e9ddf17296fb.r2.dev/app-release.apk");

                // 2. Clear only HTTP/WebView resource cache. Do not clear
                // cookies, localStorage, IndexedDB, or WebStorage: those
                // contain Firebase auth and offline sales records.
                updateStatusOnUI("Refreshing web resources...", true, 0);
                activity.runOnUiThread(() -> {
                    try {
                        if (getBridge() != null && getBridge().getWebView() != null) {
                            getBridge().getWebView().clearCache(true);
                        }
                    } catch (Exception ignored) {}
                });

                // 3. Download APK
                updateStatusOnUI("Downloading SEP Sales v" + remoteVer + "...", false, 0);

                URL downloadUrl = new URL(apkUrl);
                HttpURLConnection downloadConn = (HttpURLConnection) downloadUrl.openConnection();
                downloadConn.setConnectTimeout(30000);
                downloadConn.setReadTimeout(60000);
                downloadConn.setRequestProperty("User-Agent", "SEPSales-Android-Updater");
                downloadConn.connect();

                int fileLength = downloadConn.getContentLength();
                // Keep the installer file out of cache storage: Android may
                // remove cache files before the package installer reads them.
                File apkDir = new File(activity.getFilesDir(), "updates");
                if (!apkDir.exists()) apkDir.mkdirs();

                downloadedApkFile = new File(apkDir, "sep-sales-update.apk");
                if (downloadedApkFile.exists()) downloadedApkFile.delete();

                InputStream input = new BufferedInputStream(downloadConn.getInputStream());
                FileOutputStream output = new FileOutputStream(downloadedApkFile);

                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                long lastProgress = 0;

                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    output.write(buffer, 0, count);

                    long now = System.currentTimeMillis();
                    if (fileLength > 0 && (now - lastProgress > 200 || total == fileLength)) {
                        lastProgress = now;
                        int percent = (int) ((total * 100) / fileLength);
                        updateStatusOnUI("Downloading SEP Sales v" + remoteVer + " (" + (total / (1024 * 1024)) + "MB / " + (fileLength / (1024 * 1024)) + "MB)", false, percent);
                    }
                }

                output.flush();
                output.close();
                input.close();
                downloadConn.disconnect();

                // 4. Trigger Install
                updateStatusOnUI("Download complete! Launching installer...", true, 100);

                mainHandler.postDelayed(() -> {
                    if (updateDialog != null) updateDialog.dismiss();
                    installDownloadedApk(activity, downloadedApkFile);
                }, 800);

            } catch (Exception e) {
                Log.e(TAG, "Update flow error", e);
                mainHandler.post(() -> {
                    if (statusTextView != null) {
                        statusTextView.setText("Update failed: " + e.getMessage());
                        statusTextView.setTextColor(Color.RED);
                    }
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (percentTextView != null) percentTextView.setVisibility(View.GONE);
                    if (actionButton != null) {
                        actionButton.setText("Close");
                        actionButton.setOnClickListener(v -> {
                            if (updateDialog != null) updateDialog.dismiss();
                        });
                    }
                });
            }
        });
    }

    private void updateStatusOnUI(String msg, boolean indeterminate, int progress) {
        mainHandler.post(() -> {
            if (statusTextView != null) statusTextView.setText(msg);
            if (progressBar != null) {
                progressBar.setIndeterminate(indeterminate);
                if (!indeterminate) {
                    progressBar.setProgress(progress);
                }
            }
            if (percentTextView != null) {
                percentTextView.setText(indeterminate ? "" : progress + "%");
            }
        });
    }

    private boolean installDownloadedApk(Activity activity, File apkFile) {
        try {
            // Check Unknown App Install permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                return requestInstallPermission(activity, FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".fileprovider",
                        apkFile
                ));
            }

            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );

            return installDownloadedApk(activity, apkUri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare installer", e);
            Toast.makeText(activity, "Installer error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean installDownloadedApk(Activity activity, Uri apkUri) {
        try {
            // Check Unknown App Install permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                return requestInstallPermission(activity, apkUri);
            }

            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }

            if (intent.resolveActivity(activity.getPackageManager()) == null) {
                throw new Exception("No Android package installer is available");
            }
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch installer", e);
            Toast.makeText(activity, "Installer error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean requestInstallPermission(Activity activity, Uri apkUri) {
        activity.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(PENDING_INSTALL_URI_KEY, apkUri.toString()).apply();
        Toast.makeText(activity, "Allow 'Install unknown apps', then return to SEP Sales", Toast.LENGTH_LONG).show();
        try {
            Intent reqIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
            );
            activity.startActivity(reqIntent);
            notifyListeners("installPermissionRequired", new JSObject().put("packageName", activity.getPackageName()));
            return false;
        } catch (Exception error) {
            Log.e(TAG, "Could not open install permission settings", error);
            return false;
        }
    }
}
