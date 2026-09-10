package com.luma.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4421;
    private static final int REQ_SCREEN_OVERLAY = 4422;
    private static final int REQ_SCREEN_NOTIFICATIONS = 4423;
    private static final int REQ_VOICE_PERMS = 4424;

    private WebView web;
    private MediaProjectionManager projectionManager;
    private final ExecutorService aiExecutor = Executors.newCachedThreadPool();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle savedInstanceState) {
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
        web.addJavascriptInterface(new LumaNativeBridge(), "LumaNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectUpgradeScript();
            }
        });
        setContentView(web);
        loadApp();
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line).append('\n');
        reader.close();
        return out.toString();
    }

    private void loadApp() {
        try {
            web.loadDataWithBaseURL("https://luma.local/", readAsset("horde.html"), "text/html", "UTF-8", null);
        } catch (Exception e) {
            web.loadData("<h2>Luma could not load.</h2>", "text/html", "UTF-8");
        }
    }

    private void injectUpgradeScript() {
        try {
            final String js = readAsset("upgrade27.js");
            web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
        } catch (Exception ignored) {}
    }

    private void beginScreenShareFlow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(overlay, REQ_SCREEN_OVERLAY);
            return;
        }
        continueScreenShareFlow();
    }

    private void continueScreenShareFlow() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_SCREEN_NOTIFICATIONS);
            return;
        }
        requestProjectionPermission();
    }

    private void requestProjectionPermission() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void beginVoiceFlow() {
        boolean micMissing = Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
        boolean notificationMissing = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (micMissing || notificationMissing) {
            if (notificationMissing) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_VOICE_PERMS);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMS);
            return;
        }
        startVoiceService();
    }

    private void startVoiceService() {
        Intent i = new Intent(this, VoiceAssistantService.class);
        i.setAction(VoiceAssistantService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        notifyWebVoiceState(true);
    }

    private void stopVoiceService() {
        Intent i = new Intent(this, VoiceAssistantService.class);
        i.setAction(VoiceAssistantService.ACTION_STOP);
        startService(i);
        notifyWebVoiceState(false);
    }

    private void ensureAccessibilityComponentEnabled() {
        ComponentName component = new ComponentName(this, LumaAccessibilityService.class);
        getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private String executeOrEnableDeviceTask(final String command) {
        ensureAccessibilityComponentEnabled();
        if (LumaAccessibilityService.isRunning()) return LumaAccessibilityService.executeCommandStatic(command);
        getSharedPreferences(LumaAccessibilityService.PREFS, MODE_PRIVATE)
                .edit().putString(LumaAccessibilityService.KEY_PENDING_COMMAND, command).apply();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                web.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!LumaAccessibilityService.isRunning()) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }, 450);
            }
        });
        return "Starting Device Control. If Android opens Accessibility settings, enable Luma once; after that this command will continue automatically.";
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_VOICE_PERMS) {
            boolean micGranted = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (micGranted) startVoiceService();
        } else if (requestCode == REQ_SCREEN_NOTIFICATIONS) {
            requestProjectionPermission();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCREEN_OVERLAY) {
            continueScreenShareFlow();
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenShareService.class);
                service.setAction(ScreenShareService.ACTION_START);
                service.putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(ScreenShareService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
                notifyWebScreenState(true, "Live Screen active");
            } else notifyWebScreenState(false, "Screen share permission cancelled");
        }
    }

    private void deliverChatResult(final String requestId, final boolean ok, final String text) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() {
                String js = "window.onNativeChatResult&&window.onNativeChatResult(" + JSONObject.quote(requestId) + "," + ok + "," + JSONObject.quote(text) + ")";
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void notifyWebScreenState(final boolean active, final String message) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.onNativeScreenShareStatus&&window.onNativeScreenShareStatus(" + active + "," + JSONObject.quote(message) + ")", null);
            }
        });
    }

    private void notifyWebVoiceState(final boolean active) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript("window.onNativeVoiceStatus&&window.onNativeVoiceStatus(" + active + ")", null); }
        });
    }

    public class LumaNativeBridge {
        @JavascriptInterface public void startChat(final String requestId, final String messagesJson) {
            aiExecutor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        String reply = AiProvider.chat(MainActivity.this, new JSONArray(messagesJson));
                        deliverChatResult(requestId, true, reply);
                    } catch (Exception e) {
                        deliverChatResult(requestId, false, e.getMessage() == null ? "AI request failed" : e.getMessage());
                    }
                }
            });
        }
        @JavascriptInterface public String saveAiKey(String key) { return AiProvider.saveApiKey(MainActivity.this, key); }
        @JavascriptInterface public void clearAiKeys() { AiProvider.clearKeys(MainActivity.this); }
        @JavascriptInterface public boolean hasAiKey() { return AiProvider.hasAnyKey(MainActivity.this); }
        @JavascriptInterface public String getAiProvider() { return AiProvider.providerName(MainActivity.this); }
        @JavascriptInterface public void openGroqKeysPage() {
            runOnUiThread(new Runnable(){ @Override public void run(){ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))); }});
        }
        @JavascriptInterface public void openOpenRouterKeysPage() {
            runOnUiThread(new Runnable(){ @Override public void run(){ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys"))); }});
        }
        @JavascriptInterface public void startScreenShare() { runOnUiThread(new Runnable(){ @Override public void run(){ beginScreenShareFlow(); }}); }
        @JavascriptInterface public void stopScreenShare() {
            Intent service = new Intent(MainActivity.this, ScreenShareService.class);
            service.setAction(ScreenShareService.ACTION_STOP);
            startService(service);
            notifyWebScreenState(false, "Screen share stopped");
        }
        @JavascriptInterface public boolean isScreenSharing() {
            SharedPreferences p = getSharedPreferences(ScreenShareService.PREFS, MODE_PRIVATE);
            return p.getBoolean(ScreenShareService.KEY_ACTIVE, false);
        }
        @JavascriptInterface public String getScreenContext() { return ScreenShareService.getCombinedContext(MainActivity.this); }
        @JavascriptInterface public void startVoiceMode() { runOnUiThread(new Runnable(){ @Override public void run(){ beginVoiceFlow(); }}); }
        @JavascriptInterface public void stopVoiceMode() { runOnUiThread(new Runnable(){ @Override public void run(){ stopVoiceService(); }}); }
        @JavascriptInterface public boolean isVoiceActive() {
            return getSharedPreferences(VoiceAssistantService.PREFS, MODE_PRIVATE).getBoolean(VoiceAssistantService.KEY_ACTIVE, false);
        }
        @JavascriptInterface public boolean isDeviceControlEnabled() { return LumaAccessibilityService.isRunning(); }
        @JavascriptInterface public String runOrEnableDeviceTask(String command) { return MainActivity.this.executeOrEnableDeviceTask(command); }
        @JavascriptInterface public void openDeviceControlSettings() {
            ensureAccessibilityComponentEnabled();
            runOnUiThread(new Runnable(){ @Override public void run(){ startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        aiExecutor.shutdownNow();
        super.onDestroy();
    }
}
