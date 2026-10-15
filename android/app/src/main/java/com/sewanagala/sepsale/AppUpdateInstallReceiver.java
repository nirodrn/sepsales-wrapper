package com.sewanagala.sepsale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import java.io.File;

/** Reopens SEP Sales after Android finishes replacing the APK. */
public class AppUpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (intent.getData() == null || !context.getPackageName().equals(intent.getData().getSchemeSpecificPart())) return;

        // The update completed successfully. Remove only SEP Sales updater
        // artifacts from app-owned storage; never touch user Downloads.
        deleteOwnedUpdates(new File(context.getFilesDir(), "updates"));
        File externalDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (externalDownloads != null) {
            deleteOwnedUpdates(externalDownloads);
            deleteOwnedUpdates(new File(externalDownloads, "updates"));
        }
        context.getSharedPreferences("sep_sales_update", Context.MODE_PRIVATE).edit().clear().apply();

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) return;
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launchIntent);
    }

    private void deleteOwnedUpdates(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null) continue;
            String name = file.getName();
            if ((name.startsWith("SEP-Sales-") || name.startsWith("sep-sales-")) && !file.delete()) {
                android.util.Log.w("AppUpdateReceiver", "Could not delete stale update file: " + file.getAbsolutePath());
            }
        }
    }
}
