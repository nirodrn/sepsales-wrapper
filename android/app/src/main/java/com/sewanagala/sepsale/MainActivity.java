package com.sewanagala.sepsale;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    // Max time to show splash before hiding anyway (safety fallback: 8 seconds)
    private static final int SPLASH_TIMEOUT_MS = 8000;
    private static final int PERMISSION_REQUEST_CODE = 4201;

    private boolean splashHidden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Register Direct Bluetooth, Tracking, and AppUpdate Capacitor Plugins
        registerPlugin(DirectBluetoothPlugin.class);
        registerPlugin(TrackingPlugin.class);
        registerPlugin(AppUpdatePlugin.class);
        registerPlugin(DocumentExportPlugin.class);

        super.onCreate(savedInstanceState);

        // ── Boost WebView network performance ────────────────────────────────
        configureWebView();
        new Handler(Looper.getMainLooper()).postDelayed(this::promptForWrapperPermissions, 1800);

        // Safety fallback: hide splash after max timeout even if page never fires onPageFinished
        new Handler(Looper.getMainLooper()).postDelayed(this::hideSplash, SPLASH_TIMEOUT_MS);
    }

    private void configureWebView() {
        WebView webView = this.getBridge().getWebView();
        if (webView == null) return;

        WebSettings settings = webView.getSettings();

        // ── JavaScript & Storage ─────────────────────────────────────────────
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);         // LocalStorage & SessionStorage
        settings.setDatabaseEnabled(true);           // IndexedDB & Web SQL
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // ── Cache Mode — validate with server (ETag/304) so live updates arrive immediately ──
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // ── Mixed Content — allow HTTP resources inside HTTPS pages ──────────
        // Without this, Firebase Hosting assets (fonts, CDN) can be silently blocked
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // ── Rendering Performance ─────────────────────────────────────────────
        // Hardware acceleration is already in manifest; enable layer type for smooth render
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);


        // ── Cookies — enable third-party & persistent cookies ─────────────────
        // Firebase Auth, Firestore sessions all rely on cookies being fully enabled
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // ── User Agent — identify as modern Chrome to get best Firebase CDN responses ─
        // Removes slow "Android WebView" content negotiation fallbacks from CDNs
        String defaultUA = settings.getUserAgentString();
        String modernUA = defaultUA.replace("; wv", "")  // remove "wv" flag that marks as embedded WebView
                .replace("Version/4.0 ", "");             // remove old-style version prefix
        settings.setUserAgentString(modernUA);

        // ── Enable viewport and zoom controls ──────────────────────────────────
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);               // Disable pinch-zoom (app-like feel)
        settings.setBuiltInZoomControls(false);

        // ── Text encoding ──────────────────────────────────────────────────────
        settings.setDefaultTextEncodingName("UTF-8");

        // ── File & Content Access ─────────────────────────────────────────────
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // ── WebChromeClient: auto-grant permissions + splash hide on load progress ──
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress >= 90) {
                    hideSplash();
                }
            }
        });

        // ── BridgeWebViewClient: splash hide + RawBT URL interception ──────────
        BridgeWebViewClient bridgeClient = new BridgeWebViewClient(this.getBridge()) {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Flush cookies to disk so Firebase sessions persist across restarts
                CookieManager.getInstance().flush();
                injectDocumentDownloadBridge(view);
                hideSplash();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("intent://") && url.contains("package=com.android.chrome")) {
                        openExternalBrowser("https://sepmsale.web.app/apk-download");
                        return true;
                    }
                    if (url.startsWith("rawbt:") || (url.startsWith("intent:") && !url.contains("package=com.android.chrome"))) {
                        handleRawBTUrl(url);
                        return true;
                    }
                    if (isPublicApkPage(url)) {
                        openExternalBrowser(url);
                        return true;
                    }
                }
                return super.shouldOverrideUrlLoading(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("intent://") && url.contains("package=com.android.chrome")) {
                    openExternalBrowser("https://sepmsale.web.app/apk-download");
                    return true;
                }
                if (url != null && (url.startsWith("rawbt:") || (url.startsWith("intent:") && !url.contains("package=com.android.chrome")))) {
                    handleRawBTUrl(url);
                    return true;
                }
                if (isPublicApkPage(url)) {
                    openExternalBrowser(url);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }
        };

        webView.setWebViewClient(bridgeClient);
    }

    /**
     * Browser-created Blob downloads do not reach Android's DownloadManager.
     * Capture generated report links and send their bytes to the native save/share
     * chooser. This supports XLSX.writeFile, html2pdf.save and normal download links.
     */
    private void injectDocumentDownloadBridge(WebView webView) {
        String script = "(function(){"
                + "if(window.__sepDocumentExportBridge)return;"
                + "window.__sepDocumentExportBridge=true;"
                + "function exportAnchor(anchor){"
                + "if(!anchor||anchor.__sepNativeExportStarted)return !!(anchor&&anchor.__sepNativeExportStarted);"
                + "var href=anchor.href||'';"
                + "if(!(href.indexOf('blob:')===0||href.indexOf('data:')===0))return false;"
                + "var plugin=window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.DocumentExportPlugin;"
                + "if(!plugin||!plugin.presentDocument)return false;"
                + "anchor.__sepNativeExportStarted=true;"
                + "var name=anchor.getAttribute('download')||'SEP-Sales-report';"
                + "fetch(href).then(function(response){return response.blob();}).then(function(blob){"
                + "return new Promise(function(resolve,reject){var reader=new FileReader();reader.onload=function(){resolve({blob:blob,data:String(reader.result||'')});};reader.onerror=reject;reader.readAsDataURL(blob);});"
                + "}).then(function(result){var comma=result.data.indexOf(',');return plugin.presentDocument({fileName:name,mimeType:result.blob.type||'application/octet-stream',base64Data:comma>=0?result.data.substring(comma+1):result.data});"
                + "}).then(function(){anchor.__sepNativeExportStarted=false;"
                + "}).catch(function(error){anchor.__sepNativeExportStarted=false;console.error('[SEP document export]',error);alert('Could not prepare this report for saving. Please try again.');});"
                + "return true;"
                + "}"
                // Some export libraries click a detached anchor, so a document
                // event listener alone cannot see the download.
                + "var nativeClick=HTMLAnchorElement.prototype.click;"
                + "HTMLAnchorElement.prototype.click=function(){if(exportAnchor(this))return;return nativeClick.apply(this,arguments);};"
                + "var nativeDispatch=HTMLAnchorElement.prototype.dispatchEvent;"
                + "HTMLAnchorElement.prototype.dispatchEvent=function(event){if(event&&event.type==='click'&&exportAnchor(this))return true;return nativeDispatch.call(this,event);};"
                + "document.addEventListener('click',function(event){"
                + "var node=event.target;"
                + "var anchor=node&&node.closest?node.closest('a[download]'):null;"
                + "if(exportAnchor(anchor)){event.preventDefault();event.stopImmediatePropagation();}"
                + "},true);"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    /** Show one clear rationale, then request the Android permissions used by the wrapper. */
    private void promptForWrapperPermissions() {
        if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;

        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.CAMERA);
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            addIfMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        // Modern Android does not require broad storage access for SQLite or
        // app-private files. Only older Android versions need these grants.
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            addIfMissing(missing, Manifest.permission.READ_EXTERNAL_STORAGE);
            addIfMissing(missing, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (missing.isEmpty()) {
            promptForBackgroundLocationIfNeeded();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("SEP Sales permissions / අවසර")
                .setMessage("SEP Sales needs Camera, Location, Nearby devices/Bluetooth and Notifications for scanning, live tracking and printing.\n\nQR / ස්කෑන්, live location සහ Bluetooth printing සඳහා අවසර ලබා දෙන්න.")
                .setPositiveButton("Allow / අවසර දෙන්න", (dialog, which) -> ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQUEST_CODE))
                .setNegativeButton("Later / පසුව", null)
                .show();
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
    }

    private void promptForBackgroundLocationIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            promptForUnknownAppsIfNeeded();
            return;
        }
        boolean foregroundGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean backgroundGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!foregroundGranted || backgroundGranted) {
            promptForUnknownAppsIfNeeded();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Background location / පසුබිම් location")
                .setMessage("To keep live location tracking active while SEP Sales is minimized, allow location access all the time in Android settings.\n\nApp එක minimize කළත් live tracking සඳහා 'Allow all the time' තෝරන්න.")
                .setPositiveButton("Continue / ඉදිරියට", (dialog, which) -> ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, PERMISSION_REQUEST_CODE + 1))
                .setNegativeButton("Not now / දැන් නොවේ", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptForBackgroundLocationIfNeeded, 400);
        } else if (requestCode == PERMISSION_REQUEST_CODE + 1) {
            new Handler(Looper.getMainLooper()).postDelayed(this::promptForUnknownAppsIfNeeded, 400);
        }
    }

    /**
     * APK installation permission is a special Android settings grant; it is
     * not a normal runtime permission and Android will never grant it silently.
     */
    private void promptForUnknownAppsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        if (getPackageManager().canRequestPackageInstalls()) return;

        new AlertDialog.Builder(this)
                .setTitle("Allow SEP Sales to install updates")
                .setMessage("To install SEP Sales updates downloaded from Cloudflare, enable 'Allow from this source' for SEP Sales in Android settings. You only need to do this once.")
                .setPositiveButton("Open setting", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(this, "Open Android Settings > Install unknown apps > SEP Sales", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    /**
     * Switch the window background from the splash drawable to white.
     * Called once only — safe to call multiple times.
     */
    private void hideSplash() {
        if (splashHidden) return;
        splashHidden = true;
        runOnUiThread(() -> getWindow().setBackgroundDrawableResource(android.R.color.white));
    }

    private void handleRawBTUrl(String url) {
        try {
            Intent intent;
            if (url.startsWith("intent:")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }

            intent.setPackage("ru.a404.rawbt");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (fallback.resolveActivity(getPackageManager()) != null) {
                    startActivity(fallback);
                } else {
                    Toast.makeText(this, "RawBT App not installed. Redirecting to Play Store...", Toast.LENGTH_LONG).show();
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=ru.a404.rawbt"));
                    marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(marketIntent);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error launching RawBT: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPublicApkPage(String url) {
        return url != null && (url.startsWith("https://sepmsale.web.app/apk-download")
                || url.startsWith("https://sepmsale.firebaseapp.com/apk-download"));
    }

    private void openExternalBrowser(String url) {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browser);
        } catch (Exception error) {
            Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show();
        }
    }
}
