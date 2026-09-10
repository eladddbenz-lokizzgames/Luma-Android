package com.luma.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import org.json.JSONObject;

public class MainActivity extends Activity {
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
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "LumaAndroid");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    public class Bridge {
        @JavascriptInterface public void ask(String prompt) {
            if (prompt == null || prompt.trim().isEmpty()) return;
            new Thread(() -> requestAI(prompt)).start();
        }
    }

    private void requestAI(String prompt) {
        try {
            String encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20");
            String text;
            try {
                text = get("https://text.pollinations.ai/" + encoded + "?model=openai&private=true");
            } catch (Exception first) {
                text = get("https://text.pollinations.ai/" + encoded);
            }
            deliver(text == null || text.trim().isEmpty() ? "I couldn't generate a response. Please try again." : text.trim(), false);
        } catch (Exception e) {
            deliver("The free AI service is unavailable right now. Check your internet connection and try again.", true);
        }
    }

    private String get(String address) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "text/plain, application/json");
        c.setRequestProperty("User-Agent", "Luma-Android/2.0");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) out.append(line).append('\n');
        r.close();
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return out.toString();
    }

    private void deliver(String text, boolean error) {
        web.post(() -> web.evaluateJavascript("window.LumaNativeCallback(" + JSONObject.quote(text) + "," + error + ");", null));
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
