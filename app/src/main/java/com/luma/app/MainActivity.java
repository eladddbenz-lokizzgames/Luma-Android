package com.luma.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends Activity {
    private static final String GATEWAY = "https://luma-gemini-gateway-x1uuq8.v2.appdeploy.ai";
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "LumaAndroid");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    public class Bridge {
        @JavascriptInterface public void status() {
            new Thread(() -> {
                try {
                    JSONObject j = new JSONObject(get(GATEWAY + "/api/status"));
                    js("window.LumaNativeCallback.status(" + j.optBoolean("configured", false) + ");");
                } catch (Exception e) {
                    js("window.LumaNativeCallback.status(false);");
                }
            }).start();
        }

        @JavascriptInterface public void chat(String requestId, String contentsJson) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("contents", new JSONArray(contentsJson));
                    JSONObject j = new JSONObject(post(GATEWAY + "/api/chat", body.toString()));
                    String text = j.optString("text", "");
                    if (text.trim().isEmpty()) throw new Exception("empty response");
                    callbackText("chat", requestId, text, false);
                } catch (Exception e) {
                    callbackText("chat", requestId, friendly(e), true);
                }
            }).start();
        }

        @JavascriptInterface public void image(String requestId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("prompt", prompt);
                    JSONObject j = new JSONObject(post(GATEWAY + "/api/image", body.toString()));
                    String url = j.optString("url", "");
                    String caption = j.optString("caption", "");
                    if (url.isEmpty()) throw new Exception("No image returned");
                    js("window.LumaNativeCallback.image(" + JSONObject.quote(requestId) + "," + JSONObject.quote(url) + "," + JSONObject.quote(caption) + ",false);");
                } catch (Exception e) {
                    js("window.LumaNativeCallback.image(" + JSONObject.quote(requestId) + ",\"\"," + JSONObject.quote(friendly(e)) + ",true);");
                }
            }).start();
        }

        @JavascriptInterface public void video(String requestId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("prompt", prompt);
                    JSONObject j = new JSONObject(post(GATEWAY + "/api/video", body.toString()));
                    String url = j.optString("url", "");
                    if (url.isEmpty()) throw new Exception("No video returned");
                    js("window.LumaNativeCallback.video(" + JSONObject.quote(requestId) + "," + JSONObject.quote(url) + ",\"\",false);");
                } catch (Exception e) {
                    js("window.LumaNativeCallback.video(" + JSONObject.quote(requestId) + ",\"\"," + JSONObject.quote(friendly(e)) + ",true);");
                }
            }).start();
        }

        @JavascriptInterface public void title(String conversationId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject().put("prompt", prompt);
                    JSONObject j = new JSONObject(post(GATEWAY + "/api/title", body.toString()));
                    String title = j.optString("title", "New chat");
                    js("window.LumaNativeCallback.title(" + JSONObject.quote(conversationId) + "," + JSONObject.quote(title) + ");");
                } catch (Exception ignored) { }
            }).start();
        }
    }

    private String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("gemini_not_configured")) return "Gemini setup is not finished yet. Open Luma again after the API key is securely connected.";
        if (m.contains("429")) return "Gemini rate limit reached. Please try again in a moment.";
        if (m.contains("403")) return "Gemini rejected this API key or model access.";
        return "Gemini is unavailable right now. Please try again.";
    }

    private void callbackText(String type, String requestId, String text, boolean error) {
        js("window.LumaNativeCallback." + type + "(" + JSONObject.quote(requestId) + "," + JSONObject.quote(text) + "," + error + ");");
    }

    private String get(String address) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        String out = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out);
        return out;
    }

    private String post(String address, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30000);
        c.setReadTimeout(240000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        String out = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out);
        return out;
    }

    private String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        }
    }

    private void js(String script) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(script, null));
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
