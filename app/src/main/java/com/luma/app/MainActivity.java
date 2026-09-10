package com.luma.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4421;
    private static final int REQ_OVERLAY = 4422;
    private WebView web;
    private MediaProjectionManager projectionManager;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new LumaNativeBridge(), "LumaNative");
        setContentView(web);
        loadApp();
    }

    private void loadApp() {
        try {
            InputStream in = getAssets().open("horde.html");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) html.append(line).append('\n');
            reader.close();
            web.loadDataWithBaseURL("https://luma.local/", html.toString(), "text/html", "UTF-8", null);
        } catch (Exception e) {
            web.loadData("<h2>Luma could not load.</h2>", "text/html", "UTF-8");
        }
    }

    private void beginScreenShareFlow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            try {
                Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(overlay, REQ_OVERLAY);
                return;
            } catch (Exception ignored) {
            }
        }
        requestProjectionPermission();
    }

    private void requestProjectionPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4423);
        }
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            requestProjectionPermission();
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenShareService.class);
                service.setAction(ScreenShareService.ACTION_START);
                service.putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(ScreenShareService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                notifyWebScreenState(true, "Screen share active");
            } else {
                notifyWebScreenState(false, "Screen share permission cancelled");
            }
        }
    }

    private void notifyWebScreenState(final boolean active, final String message) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() {
                String safe = message.replace("\\", "\\\\").replace("'", "\\'");
                web.evaluateJavascript("window.onNativeScreenShareStatus&&window.onNativeScreenShareStatus(" + active + ",'" + safe + "')", null);
            }
        });
    }

    public class LumaNativeBridge {
        @JavascriptInterface
        public void startScreenShare() {
            runOnUiThread(new Runnable() {
                @Override public void run() { beginScreenShareFlow(); }
            });
        }

        @JavascriptInterface
        public void stopScreenShare() {
            Intent service = new Intent(MainActivity.this, ScreenShareService.class);
            service.setAction(ScreenShareService.ACTION_STOP);
            startService(service);
            notifyWebScreenState(false, "Screen share stopped");
        }

        @JavascriptInterface
        public boolean isScreenSharing() {
            SharedPreferences p = getSharedPreferences(ScreenShareService.PREFS, MODE_PRIVATE);
            return p.getBoolean(ScreenShareService.KEY_ACTIVE, false);
        }

        @JavascriptInterface
        public String getScreenContext() {
            SharedPreferences p = getSharedPreferences(ScreenShareService.PREFS, MODE_PRIVATE);
            return p.getString(ScreenShareService.KEY_CONTEXT, "");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
