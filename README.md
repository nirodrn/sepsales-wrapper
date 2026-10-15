# SEP Sales - Android APK Capacitor Wrapper

This directory (`D:\A0\sewanagala\wrapper`) contains the complete **Capacitor Android wrapper** for the SEP Sales web application (`D:\A0\sewanagala\sep-sale`).

> **Note:** The `sep-sale` project root has **NOT** been modified. All wrapper files, configurations, Android native modules, and build scripts are isolated inside `D:\A0\sewanagala\wrapper`.

---

## Features & Supported Capabilities

### 1. Location Access (GPS)
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`.
- **Plugin:** `@capacitor/geolocation` + native Android WebView geolocation prompt auto-granting.
- Supports precision invoice location tagging and location gates.

### 2. Camera & Barcode/QR Code Scanning
- **Permissions:** `CAMERA`, `android.hardware.camera`, `android.hardware.camera.autofocus`.
- **Plugin:** `@capacitor/camera` + native WebChromeClient auto-permission handler.
- Works with `html5-qrcode` and web camera APIs inside WebView.

### 3. RawBT Printing Integration
- **Custom Scheme Handler:** Native URL scheme interceptor in `MainActivity.java` for `rawbt:` and `intent:` protocol schemes (`rawbt:data:text/plain;base64,...` / `rawbt:data:image/png;base64,...`).
- **Android Package Queries:** Configured `<queries>` in `AndroidManifest.xml` targeting `ru.a404.rawbt` for package visibility on Android 11+ (API level 30+).
- Automatic fallback: Redirects to Google Play Store if RawBT app is not installed.

### 4. Direct Bluetooth Thermal Printing
- **Native Plugin:** Custom Capacitor Plugin (`DirectBluetoothPlugin.java`) supporting direct Bluetooth Serial Port Profile (SPP - UUID `00001101-0000-1000-8000-00805F9B34FB`).
- **Permissions:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`.
- **JavaScript API:** Injected globally as `window.DirectBluetooth`:
  ```javascript
  // List paired Bluetooth devices (thermal printers)
  window.DirectBluetooth.listDevices().then(res => console.log(res.devices));

  // Print ESC/POS text or raw data directly to a MAC address
  window.DirectBluetooth.print("00:11:22:33:44:55", "Hello Thermal Printer\n\n");
  ```

### 5. Network, Storage & Other Permissions
- **Network:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`.
- **Storage:** `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`.

---

## Directory Structure

```
D:\A0\sewanagala\wrapper
├── package.json               # Capacitor & wrapper npm dependencies & scripts
├── capacitor.config.json      # Capacitor app configuration
├── scripts/
│   └── copy-dist.js           # Copies sep-sale/dist into wrapper/www & injects Android bridge
├── www/                       # Built web app assets (copied from sep-sale/dist)
└── android/                   # Native Android Studio project
    ├── app/
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml # Complete permissions, queries & intent filters
    │   │   └── java/com/sewanagala/sepsale/
    │   │       ├── MainActivity.java           # BridgeActivity with RawBT & Web Chrome Client
    │   │       └── DirectBluetoothPlugin.java  # Native Bluetooth SPP thermal printer plugin
    │   └── build.gradle
    ├── gradlew.bat            # Windows Gradle executable wrapper
    └── local.properties       # Android SDK location configuration
```

---

## How to Build the APK

### Command to Sync Web Assets & Build Debug APK:

Run from `D:\A0\sewanagala\wrapper`:

```bash
# 1. Sync web dist files & Capacitor plugins
npm run sync

# 2. Build the Debug APK using Gradle
npm run build:apk
```

### Generated APK Output Location:
```
D:\A0\sewanagala\wrapper\android\app\build\outputs\apk\debug\app-debug.apk
```

### Open in Android Studio (Optional):
```bash
npm run open
```
