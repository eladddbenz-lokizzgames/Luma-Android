package com.luma.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
