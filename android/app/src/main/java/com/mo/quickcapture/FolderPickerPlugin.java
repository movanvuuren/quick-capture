package com.mo.quickcapture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResult;

import androidx.documentfile.provider.DocumentFile;
import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        startActivityForResult(call, intent, "handlePickFolderResult");
    }

    @ActivityCallback
    private void handlePickFolderResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Folder selection cancelled");
            return;
        }

        Uri treeUri = result.getData().getData();

        if (treeUri == null) {
            call.reject("No folder selected");
            return;
        }

        int takeFlags =
                result.getData().getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            getContext().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (Exception e) {
            call.reject("Failed to persist folder permission: " + e.getMessage());
            return;
        }

        String folderName = DocumentsContract.getTreeDocumentId(treeUri);

        JSObject ret = new JSObject();
        ret.put("uri", treeUri.toString());
        ret.put("name", folderName);

        call.resolve(ret);
    }

    @PluginMethod
    public void appendToFile(PluginCall call) {
        String folderUriStr = call.getString("folderUri");
        String fileName = call.getString("fileName");
        String content = call.getString("content");

        if (folderUriStr == null || fileName == null || content == null) {
            call.reject("Missing required parameters");
            return;
        }

        writeInternal(folderUriStr, fileName, content, /*append*/ true, call);
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String folderUriStr = call.getString("folderUri");
        String fileName = call.getString("fileName");
        String content = call.getString("content");

        if (folderUriStr == null || fileName == null || content == null) {
            call.reject("Missing required parameters");
            return;
        }

        writeInternal(folderUriStr, fileName, content, /*append*/ false, call);
    }

    private void writeInternal(String folderUriStr, String fileName, String content, boolean append, PluginCall call) {
        Uri treeUri = Uri.parse(folderUriStr);
        DocumentFile tree = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (tree == null || !tree.isDirectory()) {
            call.reject("Invalid folder URI");
            return;
        }

        DocumentFile file = tree.findFile(fileName);
        try {
            String mime = "text/plain";
            if (fileName.toLowerCase().endsWith(".md") || fileName.toLowerCase().endsWith(".markdown")) {
                mime = "text/markdown";
            }
            if (file == null) {
                file = tree.createFile(mime, fileName);
                if (file == null) {
                    call.reject("Unable to create file");
                    return;
                }
            }

            String mode = append ? "wa" : "w";
            ParcelFileDescriptor pfd = getContext().getContentResolver().openFileDescriptor(file.getUri(), mode);
            if (pfd == null) {
                call.reject("Unable to open file descriptor");
                return;
            }
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            pfd.close();

            call.resolve();
        } catch (IOException e) {
            Log.e("FolderPickerPlugin", "Error writing to file", e);
            call.reject("Write failed: " + e.getMessage());
        }
    }
}