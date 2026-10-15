package com.sewanagala.sepsale;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@CapacitorPlugin(
    name = "DirectBluetoothPlugin",
    permissions = {
        @Permission(strings = { Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN }, alias = "bluetooth_legacy"),
        @Permission(strings = { Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN }, alias = "bluetooth_modern"),
        @Permission(strings = { Manifest.permission.ACCESS_FINE_LOCATION }, alias = "location")
    }
)
public class DirectBluetoothPlugin extends Plugin {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @PluginMethod
    public void listDevices(PluginCall call) {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                call.reject("Bluetooth is not supported on this device.");
                return;
            }

            if (!bluetoothAdapter.isEnabled()) {
                call.reject("Bluetooth is disabled. Please enable Bluetooth.");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionForAlias("bluetooth_modern", call, "bluetoothPermCallback");
                    return;
                }
            }

            fetchPairedDevices(call, bluetoothAdapter);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied: " + se.getMessage());
        } catch (Exception e) {
            call.reject("Failed to list Bluetooth devices: " + e.getMessage());
        }
    }

    @com.getcapacitor.annotation.ActivityCallback
    private void bluetoothPermCallback(PluginCall call) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            fetchPairedDevices(call, bluetoothAdapter);
        } else {
            call.reject("Bluetooth permission was not granted.");
        }
    }

    private void fetchPairedDevices(PluginCall call, BluetoothAdapter bluetoothAdapter) {
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            JSArray devicesList = new JSArray();

            if (pairedDevices != null) {
                for (BluetoothDevice device : pairedDevices) {
                    JSObject devObj = new JSObject();
                    devObj.put("name", device.getName() != null ? device.getName() : "Unknown Device");
                    devObj.put("address", device.getAddress());
                    devicesList.put(devObj);
                }
            }

            JSObject result = new JSObject();
            result.put("devices", devicesList);
            call.resolve(result);
        } catch (SecurityException se) {
            call.reject("Bluetooth permission missing: " + se.getMessage());
        } catch (Exception e) {
            call.reject("Error fetching paired Bluetooth devices: " + e.getMessage());
        }
    }

    @PluginMethod
    public void printRawData(PluginCall call) {
        String macAddress = call.getString("macAddress");
        String data = call.getString("data");

        if (macAddress == null || macAddress.isEmpty()) {
            call.reject("MAC address is required.");
            return;
        }

        if (data == null) {
            call.reject("Data to print is required.");
            return;
        }

        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                call.reject("Bluetooth is not available or disabled.");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    call.reject("Bluetooth connect permission is required.");
                    return;
                }
            }

            new Thread(() -> {
                BluetoothSocket socket = null;
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    bluetoothAdapter.cancelDiscovery();
                    socket.connect();

                    OutputStream outputStream = socket.getOutputStream();
                    byte[] printBytes = data.getBytes(StandardCharsets.UTF_8);
                    outputStream.write(printBytes);
                    outputStream.flush();

                    Thread.sleep(500);

                    socket.close();
                    JSObject res = new JSObject();
                    res.put("success", true);
                    res.put("message", "Printed successfully via Direct Bluetooth");
                    call.resolve(res);
                } catch (Exception e) {
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                    call.reject("Bluetooth print failed: " + e.getMessage(), e);
                }
            }).start();
        } catch (SecurityException se) {
            call.reject("Bluetooth permission denied: " + se.getMessage());
        } catch (Exception e) {
            call.reject("Direct Bluetooth error: " + e.getMessage());
        }
    }
}
