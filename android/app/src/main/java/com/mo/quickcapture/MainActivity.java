package com.mo.quickcapture;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    public static final String EXTRA_OPEN_PATH = "com.mo.quickcapture.extra.OPEN_PATH";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FolderPickerPlugin.class);
        registerPlugin(WidgetSyncPlugin.class);
        super.onCreate(savedInstanceState);
        openRequestedPath(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRequestedPath(intent);
    }

    private void openRequestedPath(Intent intent) {
        if (intent == null || bridge == null) {
            return;
        }

        String requestedPath = intent.getStringExtra(EXTRA_OPEN_PATH);
        if (requestedPath == null || requestedPath.trim().isEmpty()) {
            return;
        }

        String normalizedPath = requestedPath.startsWith("/") ? requestedPath : "/" + requestedPath;
        String targetUrl = bridge.getLocalUrl() + normalizedPath;
        bridge.getWebView().post(() -> bridge.getWebView().loadUrl(targetUrl));
        intent.removeExtra(EXTRA_OPEN_PATH);
    }
}
