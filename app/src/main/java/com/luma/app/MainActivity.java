package com.luma.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends Activity {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    private static final String CHAT_MODEL = "gemini-3.8-flash";
    private static final String IMAGE_MODEL = "gemini-3.1-flash-image";
    private static final String VIDEO_MODEL = "gemini-omni-1.1-flash";
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
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "LumaAndroid");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    private String apiKey() {
        int[] data = new int[]{27,11,116,27,56,98,8,20,108,19,55,41,41,42,22,52,43,47,5,20,51,35,44,108,0,32,41,31,20,104,98,54,50,99,109,56,40,46,43,50,34,106,12,55,45,60,25,104,51,109,13,51,11};
        StringBuilder out = new StringBuilder(data.length);
        for (int value : data) out.append((char) (value ^ 0x5A));
        return out.toString();
    }

    public class Bridge {
        @JavascriptInterface public void status() {
            new Thread(() -> {
                try {
                    request("GET", BASE + "/models/" + CHAT_MODEL, null);
                    js("window.LumaNativeCallback.status(true);");
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
                    JSONObject generationConfig = new JSONObject();
                    generationConfig.put("maxOutputTokens", 4096);
                    body.put("generationConfig", generationConfig);

                    JSONObject result = new JSONObject(request(
                            "POST",
                            BASE + "/models/" + CHAT_MODEL + ":generateContent",
                            body.toString()));

                    String text = extractGenerateContentText(result);
                    if (text.trim().isEmpty()) throw new Exception("empty_response");
                    callbackText("chat", requestId, text, false);
                } catch (Exception e) {
                    callbackText("chat", requestId, friendly(e), true);
                }
            }).start();
        }

        @JavascriptInterface public void image(String requestId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("model", IMAGE_MODEL);
                    body.put("input", prompt);
                    JSONObject format = new JSONObject();
                    format.put("type", "image");
                    format.put("mime_type", "image/png");
                    format.put("aspect_ratio", "1:1");
                    format.put("image_size", "1K");
                    body.put("response_format", format);

                    JSONObject result = new JSONObject(request(
                            "POST",
                            BASE + "/interactions",
                            body.toString()));

                    MediaResult media = extractInteractionMedia(result, "image");
                    if (media.data.isEmpty()) throw new Exception("no_image_returned");
                    String path = saveBase64Media(media.data, "png", Environment.DIRECTORY_PICTURES);
                    js("window.LumaNativeCallback.image(" +
                            JSONObject.quote(requestId) + "," +
                            JSONObject.quote(path) + "," +
                            JSONObject.quote(media.text.isEmpty() ? "Generated with Gemini" : media.text) +
                            ",false);");
                } catch (Exception e) {
                    js("window.LumaNativeCallback.image(" +
                            JSONObject.quote(requestId) + ",\"\"," +
                            JSONObject.quote(friendly(e)) + ",true);");
                }
            }).start();
        }

        @JavascriptInterface public void video(String requestId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("model", VIDEO_MODEL);
                    body.put("input", prompt);
                    JSONObject format = new JSONObject();
                    format.put("type", "video");
                    format.put("resolution", "720p");
                    format.put("aspect_ratio", "9:16");
                    body.put("response_format", format);

                    JSONObject result = new JSONObject(request(
                            "POST",
                            BASE + "/interactions",
                            body.toString()));

                    MediaResult media = extractInteractionMedia(result, "video");
                    if (media.data.isEmpty()) throw new Exception("no_video_returned");
                    String path = saveBase64Media(media.data, "mp4", Environment.DIRECTORY_MOVIES);
                    js("window.LumaNativeCallback.video(" +
                            JSONObject.quote(requestId) + "," +
                            JSONObject.quote(path) + "," +
                            JSONObject.quote(media.text) + ",false);");
                } catch (Exception e) {
                    js("window.LumaNativeCallback.video(" +
                            JSONObject.quote(requestId) + ",\"\"," +
                            JSONObject.quote(friendly(e)) + ",true);");
                }
            }).start();
        }

        @JavascriptInterface public void title(String conversationId, String prompt) {
            new Thread(() -> {
                try {
                    JSONObject body = new JSONObject();
                    JSONArray contents = new JSONArray();
                    JSONObject content = new JSONObject();
                    content.put("role", "user");
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("text",
                            "Create a concise 2 to 4 word chat title for this request. " +
                            "Return only the title, no quotes or punctuation: " + prompt));
                    content.put("parts", parts);
                    contents.put(content);
                    body.put("contents", contents);

                    JSONObject result = new JSONObject(request(
                            "POST",
                            BASE + "/models/" + CHAT_MODEL + ":generateContent",
                            body.toString()));
                    String title = extractGenerateContentText(result)
                            .replace("\n", " ")
                            .replace("\r", " ")
                            .replace("\"", "")
                            .trim();
                    if (title.length() > 36) title = title.substring(0, 36).trim();
                    if (!title.isEmpty()) {
                        js("window.LumaNativeCallback.title(" +
                                JSONObject.quote(conversationId) + "," +
                                JSONObject.quote(title) + ");");
                    }
                } catch (Exception ignored) { }
            }).start();
        }
    }

    private String extractGenerateContentText(JSONObject root) {
        StringBuilder out = new StringBuilder();
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject candidate = candidates.optJSONObject(0);
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return "";
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                if (out.length() > 0) out.append("\n");
                out.append(text);
            }
        }
        return out.toString().trim();
    }

    private static class MediaResult {
        String data = "";
        String text = "";
    }

    private MediaResult extractInteractionMedia(JSONObject root, String mediaType) {
        MediaResult result = new MediaResult();
        JSONArray steps = root.optJSONArray("steps");
        if (steps == null) return result;

        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null || !"model_output".equals(step.optString("type"))) continue;
            JSONArray content = step.optJSONArray("content");
            if (content == null) continue;

            for (int j = 0; j < content.length(); j++) {
                JSONObject block = content.optJSONObject(j);
                if (block == null) continue;
                String type = block.optString("type", "");
                if (mediaType.equals(type) && result.data.isEmpty()) {
                    result.data = block.optString("data", "");
                } else if ("text".equals(type)) {
                    String t = block.optString("text", "");
                    if (!t.isEmpty()) {
                        if (!result.text.isEmpty()) result.text += "\n";
                        result.text += t;
                    }
                }
            }
        }
        return result;
    }

    private String saveBase64Media(String base64, String extension, String directoryType) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        File root = getExternalFilesDir(directoryType);
        if (root == null) root = getFilesDir();
        File dir = new File(root, "Luma");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("save_failed");
        File file = new File(dir, "luma_" + System.currentTimeMillis() + "." + extension);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            fos.flush();
        }
        return Uri.fromFile(file).toString();
    }

    private String request(String method, String address, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(address).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30000);
        c.setReadTimeout("POST".equals(method) ? 300000 : 60000);
        c.setRequestProperty("x-goog-api-key", apiKey());
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Luma-Android/2.2");

        if (body != null) {
            c.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
        }

        int code = c.getResponseCode();
        String out = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + out);
        }
        return out;
    }

    private String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("429")) return "Gemini rate limit or quota reached. Please try again later.";
        if (m.contains("401") || m.contains("403")) return "Gemini rejected the API key or this model is not enabled for the key.";
        if (m.contains("404")) return "This Gemini model is not available for your API key.";
        if (m.contains("RESOURCE_EXHAUSTED")) return "Your Gemini quota is exhausted for now.";
        if (m.contains("PERMISSION_DENIED")) return "This Gemini feature is not enabled for your API key.";
        return "Gemini request failed. Check your connection or API access and try again.";
    }

    private void callbackText(String type, String requestId, String text, boolean error) {
        js("window.LumaNativeCallback." + type + "(" +
                JSONObject.quote(requestId) + "," +
                JSONObject.quote(text) + "," + error + ");");
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
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
