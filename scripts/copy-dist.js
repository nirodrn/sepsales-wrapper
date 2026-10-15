import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const srcDir = path.resolve(__dirname, '../../sep-sale/dist');
const destDir = path.resolve(__dirname, '../www');
const expectedDestDir = path.resolve(__dirname, '../www');

if (!fs.existsSync(srcDir)) {
  console.error(`Source build directory does not exist: ${srcDir}`);
  console.error('Please build sep-sale first using npm run build inside sep-sale directory.');
  process.exit(1);
}

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  const entries = fs.readdirSync(src, { withFileTypes: true });

  for (let entry of entries) {
    const srcPath = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDir(srcPath, destPath);
    } else {
      fs.copyFileSync(srcPath, destPath);
    }
  }
}

// Vite gives compiled assets content-hashed names. Leaving the previous files
// in www makes every old build get packaged into the APK even though index.html
// references only the newest one. Restrict cleanup to this exact generated
// directory so source files and application data can never be affected.
if (destDir !== expectedDestDir || path.basename(destDir).toLowerCase() !== 'www') {
  throw new Error(`Refusing to clean an unexpected destination: ${destDir}`);
}
if (fs.existsSync(destDir)) {
  console.log(`Removing stale generated web assets from ${destDir}...`);
  fs.rmSync(destDir, { recursive: true, force: true });
}

console.log(`Copying dist files from ${srcDir} to ${destDir}...`);
copyDir(srcDir, destDir);

const pkgPath = path.resolve(__dirname, '../package.json');
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
const pkgVersion = pkg.version || '12.3.1';

// Keep the Android package identity aligned with wrapper/package.json. If this
// is not updated, Android may treat a newly built APK as the same release and
// the native update checker can appear to do nothing.
const versionParts = pkgVersion.split('.').map((part) => parseInt(part, 10) || 0);
const androidVersionCode = (versionParts[0] * 10000) + (versionParts[1] * 100) + versionParts[2];
const gradlePath = path.resolve(__dirname, '../android/app/build.gradle');
if (fs.existsSync(gradlePath)) {
  let gradle = fs.readFileSync(gradlePath, 'utf8');
  gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${androidVersionCode}`);
  gradle = gradle.replace(/versionName\s+"[^"]+"/, `versionName "V${pkgVersion.replace(/^V/i, '')}"`);
  fs.writeFileSync(gradlePath, gradle, 'utf8');
  console.log(`Synced Android version identity: V${pkgVersion} (${androidVersionCode})`);
}

// Inject Native Android Bridge helper & Offline/Online Auto-Sync monitor into www/index.html (without modifying sep-sale)
const indexPath = path.join(destDir, 'index.html');
if (fs.existsSync(indexPath)) {
  let html = fs.readFileSync(indexPath, 'utf-8');
  const bridgeScript = `
  <script>
    // Native Android Web View Bridge Helper & Offline Sync Monitor
    (function() {
      console.log("[AndroidWrapper] Native bridge and offline sync monitor loaded.");
      
      // Handle RawBT URL overrides if needed
      var origOpen = window.open;
      window.open = function(url, target, features) {
        if (url && (url.startsWith('rawbt:') || url.startsWith('intent:'))) {
          console.log('[AndroidWrapper] Intercepted RawBT URL in window.open:', url);
          if (window.AndroidBridge && window.AndroidBridge.openRawBT) {
            window.AndroidBridge.openRawBT(url);
            return null;
          }
        }
        return origOpen.apply(this, arguments);
      };

      // Expose Capacitor Native App Info to window
      window.isCapacitorApp = true;
      window.CapacitorAppVersion = "V${pkgVersion}";

      // Native Bluetooth API exposed to Window
      window.DirectBluetooth = {
        isAvailable: function() {
          return !!(window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DirectBluetoothPlugin);
        },
        listDevices: function() {
          if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DirectBluetoothPlugin) {
            return window.Capacitor.Plugins.DirectBluetoothPlugin.listDevices();
          }
          return Promise.reject("DirectBluetoothPlugin native plugin not available.");
        },
        print: function(macAddress, textData) {
          if (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.DirectBluetoothPlugin) {
            return window.Capacitor.Plugins.DirectBluetoothPlugin.printRawData({ macAddress: macAddress, data: textData });
          }
          return Promise.reject("DirectBluetoothPlugin native plugin not available.");
        }
      };

      // Offline / Online Banner & Sync Manager UI
      function createSyncBanner() {
        var banner = document.createElement('div');
        banner.id = 'offline-sync-banner';
        banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;padding:8px 16px;text-align:center;font-size:13px;font-weight:600;font-family:sans-serif;color:#fff;display:none;transition:all 0.3s ease;box-shadow:0 2px 8px rgba(0,0,0,0.2);';
        document.body.appendChild(banner);
        return banner;
      }

      function updateNetworkStatus() {
        var banner = document.getElementById('offline-sync-banner') || createSyncBanner();
        if (!navigator.onLine) {
          banner.style.background = '#d97706';
          banner.innerHTML = '⚡ Offline Mode — Working locally (All sales & prints saved)';
          banner.style.display = 'block';
        } else {
          banner.style.background = '#059669';
          banner.innerHTML = '🟢 Back Online — Syncing offline data with server...';
          banner.style.display = 'block';
          setTimeout(function() {
            if (navigator.onLine && banner) {
              banner.style.display = 'none';
            }
          }, 4000);
        }
      }

      window.addEventListener('online', updateNetworkStatus);
      window.addEventListener('offline', updateNetworkStatus);
      window.addEventListener('DOMContentLoaded', function() {
        if (!navigator.onLine) {
          updateNetworkStatus();
        }
      });
    })();
  </script>
  `;

  if (!html.includes('[AndroidWrapper]')) {
    html = html.replace('</head>', `${bridgeScript}\n</head>`);
    fs.writeFileSync(indexPath, html, 'utf-8');
    console.log('Injected Android Native Bridge & Offline Sync helper into www/index.html');
  }
}

console.log('Dist copy completed successfully!');

// ── Sync capacitor.config.json to Android assets (keeps live server.url in APK) ──
const rootConfigSrc  = path.resolve(__dirname, '../capacitor.config.json');
const androidAssets  = path.resolve(__dirname, '../android/app/src/main/assets/capacitor.config.json');
if (fs.existsSync(rootConfigSrc) && fs.existsSync(path.dirname(androidAssets))) {
  fs.copyFileSync(rootConfigSrc, androidAssets);
  console.log('✅ Synced capacitor.config.json → android/app/src/main/assets/');
}
