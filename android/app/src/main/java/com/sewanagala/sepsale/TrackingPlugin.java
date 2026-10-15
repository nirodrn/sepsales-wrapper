package com.sewanagala.sepsale;

import android.content.Intent;
import android.os.Build;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "TrackingPlugin")
public class TrackingPlugin extends Plugin {

    @PluginMethod
    public void startTracking(PluginCall call) {
        String userId = call.getString("userId", "unknown");
        String userName = call.getString("userName", "Unknown Rep");
        String userRole = call.getString("userRole", "Rep");
        String distributorId = call.getString("distributorId", "");

        Intent intent = new Intent(getContext(), LocationTrackingService.class);
        intent.putExtra("userId", userId);
        intent.putExtra("userName", userName);
        intent.putExtra("userRole", userRole);
        intent.putExtra("distributorId", distributorId);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to start location tracking service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), LocationTrackingService.class);
            intent.setAction("ACTION_STOP_SERVICE");
            getContext().startService(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop location tracking service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getCurrentCellTower(PluginCall call) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getContext().getSystemService(android.content.Context.TELEPHONY_SERVICE);
            com.getcapacitor.JSObject ret = new com.getcapacitor.JSObject();
            com.getcapacitor.JSObject cellObj = new com.getcapacitor.JSObject();

            cellObj.put("cellId", -1);
            cellObj.put("lacTac", -1);
            cellObj.put("mcc", "");
            cellObj.put("mnc", "");
            cellObj.put("type", "Unknown");

            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                @SuppressWarnings("MissingPermission")
                java.util.List<android.telephony.CellInfo> cellInfos = tm.getAllCellInfo();
                if (cellInfos != null) {
                    for (android.telephony.CellInfo cell : cellInfos) {
                        if (cell.isRegistered()) {
                            if (cell instanceof android.telephony.CellInfoLte) {
                                android.telephony.CellIdentityLte identity = ((android.telephony.CellInfoLte) cell).getCellIdentity();
                                cellObj.put("cellId", identity.getCi());
                                cellObj.put("lacTac", identity.getTac());
                                cellObj.put("pci", identity.getPci());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    cellObj.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                    cellObj.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                }
                                cellObj.put("type", "LTE/4G");
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell instanceof android.telephony.CellInfoNr) {
                                android.telephony.CellIdentityNr identity = (android.telephony.CellIdentityNr) ((android.telephony.CellInfoNr) cell).getCellIdentity();
                                cellObj.put("cellId", identity.getNci());
                                cellObj.put("lacTac", identity.getTac());
                                cellObj.put("pci", identity.getPci());
                                cellObj.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                cellObj.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                cellObj.put("type", "5G NR");
                            } else if (cell instanceof android.telephony.CellInfoGsm) {
                                android.telephony.CellIdentityGsm identity = ((android.telephony.CellInfoGsm) cell).getCellIdentity();
                                cellObj.put("cellId", identity.getCid());
                                cellObj.put("lacTac", identity.getLac());
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    cellObj.put("mcc", identity.getMccString() != null ? identity.getMccString() : "");
                                    cellObj.put("mnc", identity.getMncString() != null ? identity.getMncString() : "");
                                }
                                cellObj.put("type", "GSM/2G");
                            }
                            break;
                        }
                    }
                }
            }

            ret.put("cellInfo", cellObj);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to get cell tower info: " + e.getMessage());
        }
    }
}
