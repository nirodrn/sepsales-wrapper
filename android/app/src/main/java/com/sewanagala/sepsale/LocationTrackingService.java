package com.sewanagala.sepsale;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class LocationTrackingService extends Service implements LocationListener {

    private static final String TAG = "LocationTrackingService";
    public static final String CHANNEL_ID = "sep_sales_location_tracking";
    private static final int NOTIFICATION_ID = 9981;
    private static final String FIREBASE_RTDB_URL = "https://sepm-location-track-service-default-rtdb.firebaseio.com";

    private LocationManager locationManager;
    private TelephonyManager telephonyManager;
    private ConnectivityManager connectivityManager;
    private DatabaseReference mDatabase;
    private Handler trackingHandler;
    private Runnable trackingRunnable;

    private String currentUserId = "unknown_user";
    private String currentUserName = "Unknown Rep";
    private String currentUserRole = "Rep";
    private String currentDistributorId = "";
    private int lastSignalStrengthDbm = -999;
    private int lastSignalLevel = 0; // 0-4 scale

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        try {
            // Initialize standalone Firebase Database for location tracking service
            FirebaseApp app = FirebaseApp.getInstance();
            FirebaseDatabase database = FirebaseDatabase.getInstance(app, FIREBASE_RTDB_URL);
            mDatabase = database.getReference();
        } catch (Exception e) {
            try {
                FirebaseDatabase database = FirebaseDatabase.getInstance(FIREBASE_RTDB_URL);
                mDatabase = database.getReference();
            } catch (Exception ex) {
                Log.e(TAG, "Error initializing Firebase Database", ex);
            }
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        listenToSignalStrength();
        startLocationUpdates();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_STOP_SERVICE".equals(action)) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }

            if (intent.hasExtra("userId")) {
                currentUserId = intent.getStringExtra("userId");
            }
            if (intent.hasExtra("userName")) {
                currentUserName = intent.getStringExtra("userName");
            }
            if (intent.hasExtra("userRole")) {
                currentUserRole = intent.getStringExtra("userRole");
            }
            if (intent.hasExtra("distributorId")) {
                currentDistributorId = intent.getStringExtra("distributorId");
            }
        }

        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);

        return START_STICKY;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SEP Sales Location Active")
                .setContentText("Live GPS & signal tracking is active for sales management")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SEP Sales Location Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background location and signal strength monitoring");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        try {
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000, 5, this);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000, 5, this);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting location updates", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void listenToSignalStrength() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyManager != null) {
                telephonyManager.registerTelephonyCallback(
                        getMainExecutor(),
                        new SignalListener()
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting signal listener", e);
        }
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.S)
    private class SignalListener extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            parseSignalStrength(signalStrength);
        }
    }

    private void parseSignalStrength(SignalStrength signalStrength) {
        if (signalStrength == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lastSignalLevel = signalStrength.getLevel(); // 0 (none) to 4 (great)
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            publishTelemetry(location);
        }
    }

    private void publishTelemetry(Location location) {
        if (mDatabase == null || currentUserId == null || currentUserId.isEmpty()) return;

        long now = System.currentTimeMillis();
        String networkType = getNetworkType();
        String phoneModel = Build.MANUFACTURER + " " + Build.MODEL;

        int signalStrength10 = Math.min(10, Math.max(0, lastSignalLevel * 2 + 2)); // map 0..4 level to 0..10 score scale

        // Extract Cell Tower Info
        Map<String, Object> cellInfoMap = getConnectedCellInfo();

        Map<String, Object> data = new HashMap<>();
        data.put("userId", currentUserId);
        data.put("userName", currentUserName);
        data.put("userRole", currentUserRole);
        data.put("distributorId", currentDistributorId);
        data.put("lat", location.getLatitude());
        data.put("lng", location.getLongitude());
        data.put("accuracy", location.getAccuracy());
        data.put("speed", location.getSpeed());
        data.put("timestamp", now);
        data.put("phoneModel", phoneModel);
        data.put("networkType", networkType);
        data.put("signalLevel", lastSignalLevel); // 0 to 4
        data.put("signalStrength10", signalStrength10); // 0 to 10
        data.put("isLowSignal", signalStrength10 <= 3);
        data.put("cellInfo", cellInfoMap);

        // SDK4 current-location node consumed by the web dashboard.
        mDatabase.child("user_locations").child(currentUserId).setValue(data);

        // 2. Append to Trail / Signal History Node for Heatmap Analysis
        Map<String, Object> historyPoint = new HashMap<>();
        historyPoint.put("lat", location.getLatitude());
        historyPoint.put("lng", location.getLongitude());
        historyPoint.put("timestamp", now);
        historyPoint.put("signalLevel", lastSignalLevel);
        historyPoint.put("signalStrength10", signalStrength10);
        historyPoint.put("isLowSignal", signalStrength10 <= 3);
        historyPoint.put("networkType", networkType);
        historyPoint.put("cellInfo", cellInfoMap);

        // SDK4 ordered trail. The timestamp key makes points easy to prune
        // after one month and preserves the true travel order for road routing.
        mDatabase.child("location_tracking").child(currentUserId).child(String.valueOf(now)).setValue(historyPoint);
    }

    @SuppressLint("MissingPermission")
    private Map<String, Object> getConnectedCellInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cellId", -1);
        info.put("lacTac", -1);
        info.put("mcc", "");
        info.put("mnc", "");
        info.put("type", "Unknown");

        try {
            if (telephonyManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                java.util.List<android.telephony.CellInfo> cellInfos = telephonyManager.getAllCellInfo();
                if (cellInfos != null) {
                    for (android.telephony.CellInfo cell : cellInfos) {
                        if (cell.isRegistered()) {
                            if (cell instanceof android.telephony.CellInfoLte) {
                                android.telephony.CellIdentityLte identity = ((android.telephony.CellInfoLte) cell).getCellIdentity();
                                info.put("cellId", identity.getCi());
                                info.put("lacTac", identity.getTac());
                                info.put("pci", identity.getPci());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    info.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                    info.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                }
                                info.put("type", "LTE/4G");
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell instanceof android.telephony.CellInfoNr) {
                                android.telephony.CellIdentityNr identity = (android.telephony.CellIdentityNr) ((android.telephony.CellInfoNr) cell).getCellIdentity();
                                info.put("cellId", identity.getNci());
                                info.put("lacTac", identity.getTac());
                                info.put("pci", identity.getPci());
                                info.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                info.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                info.put("type", "5G NR");
                            } else if (cell instanceof android.telephony.CellInfoGsm) {
                                android.telephony.CellIdentityGsm identity = ((android.telephony.CellInfoGsm) cell).getCellIdentity();
                                info.put("cellId", identity.getCid());
                                info.put("lacTac", identity.getLac());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    info.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                    info.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                }
                                info.put("type", "GSM/2G");
                            } else if (cell instanceof android.telephony.CellInfoWcdma) {
                                android.telephony.CellIdentityWcdma identity = ((android.telephony.CellInfoWcdma) cell).getCellIdentity();
                                info.put("cellId", identity.getCid());
                                info.put("lacTac", identity.getLac());
                                info.put("psc", identity.getPsc());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    info.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                    info.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                }
                                info.put("type", "WCDMA/3G");
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract Cell Tower info", e);
        }
        return info;
    }

    private String getNetworkType() {
        if (connectivityManager == null) return "Unknown";
        try {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return "Offline";
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) return "Offline";

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "Wi-Fi";
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "Cellular";
            }
        } catch (Exception e) {
            // fallback
        }
        return "Mobile";
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }
}
