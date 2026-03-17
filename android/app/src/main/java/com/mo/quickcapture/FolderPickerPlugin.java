package com.mo.quickcapture;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResult;

import androidx.documentfile.provider.DocumentFile;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {

    private String sanitizeRelativePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private DocumentFile resolveDirectoryPath(DocumentFile baseDir, String relativePath, boolean createIfMissing) {
        String normalized = sanitizeRelativePath(relativePath);
        if (normalized.isEmpty()) {
            return baseDir;
        }

        String[] parts = normalized.split("/");
        DocumentFile current = baseDir;
        for (String rawPart : parts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }

            DocumentFile child = current.findFile(part);
            if (child == null) {
                if (!createIfMissing) {
                    return null;
                }
                child = current.createDirectory(part);
            }

            if (child == null || !child.isDirectory()) {
                return null;
            }

            current = child;
        }

        return current;
    }

    private DocumentFile findFileByRelativePath(DocumentFile baseDir, String filePath) {
        String normalized = sanitizeRelativePath(filePath);
        if (normalized.isEmpty()) {
            return null;
        }

        int slash = normalized.lastIndexOf('/');
        String parentPath = slash >= 0 ? normalized.substring(0, slash) : "";
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isEmpty()) {
            return null;
        }

        DocumentFile parentDir = resolveDirectoryPath(baseDir, parentPath, false);
        if (parentDir == null) {
            return null;
        }

        DocumentFile found = parentDir.findFile(fileName);
        if (found == null || !found.isFile()) {
            return null;
        }

        return found;
    }

    private DocumentFile getOrCreateFileByRelativePath(DocumentFile baseDir, String filePath, String mime) {
        String normalized = sanitizeRelativePath(filePath);
        if (normalized.isEmpty()) {
            return null;
        }

        int slash = normalized.lastIndexOf('/');
        String parentPath = slash >= 0 ? normalized.substring(0, slash) : "";
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isEmpty()) {
            return null;
        }

        DocumentFile parentDir = resolveDirectoryPath(baseDir, parentPath, true);
        if (parentDir == null) {
            return null;
        }

        DocumentFile existing = parentDir.findFile(fileName);
        if (existing != null) {
            return existing.isFile() ? existing : null;
        }

        return parentDir.createFile(mime, fileName);
    }

    private DocumentFile resolveTree(String folderUriStr) {
        Uri treeUri = Uri.parse(folderUriStr);
        DocumentFile tree = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (tree == null || !tree.isDirectory()) {
            return null;
        }
        return tree;
    }

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

        // Mirror the URI to SharedPreferences so the home-screen widget can read it
        getContext().getSharedPreferences("quick_capture_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", treeUri.toString())
            .apply();

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
        DocumentFile tree = resolveTree(folderUriStr);
        if (tree == null) {
            call.reject("Invalid folder URI");
            return;
        }

        try {
            String mime = "text/plain";
            if (fileName.toLowerCase().endsWith(".md") || fileName.toLowerCase().endsWith(".markdown")) {
                mime = "text/markdown";
            }
            DocumentFile file = getOrCreateFileByRelativePath(tree, fileName, mime);
            if (file == null) {
                call.reject("Unable to create file");
                return;
            }

            String mode = append ? "wa" : "wt";
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

    @PluginMethod
    public void listFiles(PluginCall call) {
        String folderUriStr = call.getString("folderUri");
        String relativePath = call.getString("relativePath");
        if (folderUriStr == null) {
            call.reject("Missing required parameters");
            return;
        }

        DocumentFile tree = resolveTree(folderUriStr);
        if (tree == null) {
            call.reject("Invalid folder URI");
            return;
        }

        DocumentFile targetDir = resolveDirectoryPath(tree, relativePath, false);
        if (targetDir == null || !targetDir.isDirectory()) {
            JSObject ret = new JSObject();
            ret.put("files", new JSArray());
            call.resolve(ret);
            return;
        }

        try {
            JSArray files = new JSArray();
            for (DocumentFile child : targetDir.listFiles()) {
                if (child == null || child.getName() == null) {
                    continue;
                }
                JSObject entry = new JSObject();
                entry.put("name", child.getName());
                entry.put("isFile", child.isFile());
                entry.put("size", child.length());
                entry.put("lastModified", child.lastModified());
                files.put(entry);
            }

            JSObject ret = new JSObject();
            ret.put("files", files);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("List files failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        String folderUriStr = call.getString("folderUri");
        String fileName = call.getString("fileName");

        if (folderUriStr == null || fileName == null) {
            call.reject("Missing required parameters");
            return;
        }

        DocumentFile tree = resolveTree(folderUriStr);
        if (tree == null) {
            call.reject("Invalid folder URI");
            return;
        }

        DocumentFile file = findFileByRelativePath(tree, fileName);
        if (file == null || !file.isFile()) {
            call.reject("File not found");
            return;
        }

        try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri())) {
            if (input == null) {
                call.reject("Unable to open file");
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    content.append('\n');
                }
                content.append(line);
                first = false;
            }

            JSObject ret = new JSObject();
            ret.put("content", content.toString());
            call.resolve(ret);
        } catch (IOException e) {
            call.reject("Read failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String folderUriStr = call.getString("folderUri");
        String fileName = call.getString("fileName");

        if (folderUriStr == null || fileName == null) {
            call.reject("Missing required parameters");
            return;
        }

        DocumentFile tree = resolveTree(folderUriStr);
        if (tree == null) {
            call.reject("Invalid folder URI");
            return;
        }

        DocumentFile file = findFileByRelativePath(tree, fileName);
        if (file == null || !file.isFile()) {
            call.reject("File not found");
            return;
        }

        try {
            boolean deleted = file.delete();
            if (!deleted) {
                call.reject("Delete failed");
                return;
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Delete failed: " + e.getMessage());
        }
    }
}