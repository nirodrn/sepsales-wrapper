package com.sewanagala.sepsale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives browser-generated reports (blob/data URLs) and hands them to
 * Android's Storage Access Framework or share sheet. No broad storage
 * permission is required, and the user always chooses the destination.
 */
@CapacitorPlugin(name = "DocumentExportPlugin")
public class DocumentExportPlugin extends Plugin {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, File> pendingSaves = new ConcurrentHashMap<>();

    @PluginMethod
    public void presentDocument(PluginCall call) {
        String base64Data = call.getString("base64Data");
        String requestedName = call.getString("fileName", "SEP-Sales-report");
        String mimeType = call.getString("mimeType", "application/octet-stream");

        if (base64Data == null || base64Data.trim().isEmpty()) {
            call.reject("The generated document is empty");
            return;
        }

        final String fileName = sanitizeFileName(requestedName);
        final String safeMimeType = mimeType == null || mimeType.trim().isEmpty()
                ? "application/octet-stream"
                : mimeType.split(";", 2)[0].trim();

        executor.execute(() -> {
            File exportFile = null;
            try {
                String payload = base64Data;
                int comma = payload.indexOf(',');
                if (payload.startsWith("data:") && comma >= 0) payload = payload.substring(comma + 1);

                byte[] bytes = Base64.decode(payload, Base64.DEFAULT);
                File exportDir = new File(getContext().getCacheDir(), "document_exports");
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    throw new IllegalStateException("Could not create the temporary export folder");
                }
                removeExpiredExports(exportDir);
                exportFile = new File(exportDir, System.currentTimeMillis() + "-" + fileName);
                try (FileOutputStream output = new FileOutputStream(exportFile)) {
                    output.write(bytes);
                }

                File readyFile = exportFile;
                getActivity().runOnUiThread(() -> showExportChoice(call, readyFile, fileName, safeMimeType));
            } catch (Exception error) {
                if (exportFile != null) exportFile.delete();
                call.reject("Could not prepare the document: " + error.getMessage(), error);
            }
        });
    }

    private void showExportChoice(PluginCall call, File file, String fileName, String mimeType) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            file.delete();
            call.reject("Activity is not available");
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Report ready")
                .setMessage(fileName + "\n\nChoose where you want to send the report.")
                .setPositiveButton("Save to device", (dialog, which) -> saveDocument(call, file, fileName, mimeType))
                .setNeutralButton("Share", (dialog, which) -> shareDocument(call, file, fileName, mimeType))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    file.delete();
                    JSObject result = new JSObject();
                    result.put("cancelled", true);
                    call.resolve(result);
                })
                .setOnCancelListener(dialog -> {
                    file.delete();
                    JSObject result = new JSObject();
                    result.put("cancelled", true);
                    call.resolve(result);
                })
                .show();
    }

    private void saveDocument(PluginCall call, File file, String fileName, String mimeType) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            pendingSaves.put(call.getCallbackId(), file);
            startActivityForResult(call, intent, "saveDocumentResult");
        } catch (Exception error) {
            pendingSaves.remove(call.getCallbackId());
            file.delete();
            call.reject("Could not open the save location picker", error);
        }
    }

    @ActivityCallback
    private void saveDocumentResult(PluginCall call, ActivityResult activityResult) {
        File source = pendingSaves.remove(call.getCallbackId());
        if (source == null) {
            call.reject("The prepared document is no longer available");
            return;
        }

        Intent data = activityResult.getData();
        Uri destination = data == null ? null : data.getData();
        if (activityResult.getResultCode() != Activity.RESULT_OK || destination == null) {
            source.delete();
            JSObject result = new JSObject();
            result.put("cancelled", true);
            call.resolve(result);
            return;
        }

        executor.execute(() -> {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = getContext().getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Selected location cannot be written");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();

                JSObject result = new JSObject();
                result.put("saved", true);
                result.put("uri", destination.toString());
                call.resolve(result);
                getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Report saved", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                call.reject("Could not save the document: " + error.getMessage(), error);
            } finally {
                source.delete();
            }
        });
    }

    private void shareDocument(PluginCall call, File file, String fileName, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, fileName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(share, "Share report"));

            JSObject result = new JSObject();
            result.put("shared", true);
            call.resolve(result);
            // Keep the cache file available while the receiving app reads it.
            file.setLastModified(System.currentTimeMillis());
        } catch (Exception error) {
            file.delete();
            call.reject("Could not share the document: " + error.getMessage(), error);
        }
    }

    private String sanitizeFileName(String value) {
        String cleaned = value == null ? "SEP-Sales-report" : value.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        cleaned = cleaned.replaceAll("^[. ]+|[. ]+$", "");
        return cleaned.isEmpty() ? "SEP-Sales-report" : cleaned;
    }

    private void removeExpiredExports(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}
