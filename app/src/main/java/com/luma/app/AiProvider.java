package com.luma.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AiProvider {
    private static final String PREFS = "luma_ai_provider";
    private static final String KEY_GROQ = "groq_key";
    private static final String KEY_OPENROUTER = "openrouter_key";
    private static final String GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String OPENROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions";

    private AiProvider() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String saveApiKey(Context context, String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.startsWith("gsk_")) {
            prefs(context).edit().putString(KEY_GROQ, key).apply();
            return "Groq";
        }
        if (key.startsWith("sk-or-") || key.startsWith("sk-or-v1-")) {
            prefs(context).edit().putString(KEY_OPENROUTER, key).apply();
            return "OpenRouter";
        }
        return "";
    }

    public static void clearKeys(Context context) {
        prefs(context).edit().remove(KEY_GROQ).remove(KEY_OPENROUTER).apply();
    }

    public static boolean hasAnyKey(Context context) {
        SharedPreferences p = prefs(context);
        return !p.getString(KEY_GROQ, "").isEmpty() || !p.getString(KEY_OPENROUTER, "").isEmpty();
    }

    public static String providerName(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getString(KEY_GROQ, "").isEmpty()) return "Groq · GPT-OSS 120B";
        if (!p.getString(KEY_OPENROUTER, "").isEmpty()) return "OpenRouter · Free Router";
        return "Fast AI setup needed";
    }

    public static String chat(Context context, JSONArray messages) throws Exception {
        SharedPreferences p = prefs(context);
        String groq = p.getString(KEY_GROQ, "");
        String openrouter = p.getString(KEY_OPENROUTER, "");
        Exception last = null;

        if (!groq.isEmpty()) {
            try { return groqChat(groq, messages); }
            catch (Exception e) { last = e; }
        }
        if (!openrouter.isEmpty()) {
            try { return openRouterChat(openrouter, messages); }
            catch (Exception e) { last = e; }
        }
        if (!groq.isEmpty() || !openrouter.isEmpty()) {
            throw new Exception(last == null ? "Fast AI request failed" : last.getMessage());
        }
        throw new Exception("SETUP_REQUIRED");
    }

    public static String quickChat(Context context, String prompt, String screenContext) throws Exception {
        JSONArray messages = new JSONArray();
        String system = "You are Luma, a fast, capable Android assistant. Reply clearly and directly. Keep spoken answers concise unless detail is requested.";
        if (screenContext != null && !screenContext.trim().isEmpty()) {
            system += " The user explicitly enabled Live Screen. Current screen context: " + screenContext.trim();
        }
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", prompt == null ? "" : prompt));
        return chat(context, messages);
    }

    public static String vision(Context context, String base64Webp) throws Exception {
        SharedPreferences p = prefs(context);
        String groq = p.getString(KEY_GROQ, "");
        if (!groq.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("model", "qwen/qwen3.6-27b");
            body.put("temperature", 0.1);
            body.put("max_completion_tokens", 260);
            JSONArray messages = new JSONArray();
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text",
                    "Describe this Android screen for an assistant that may need to help the user operate it. Mention the app/screen, visible controls, important text, dialogs, and likely next actions. Be concise."));
            JSONObject imageUrl = new JSONObject().put("url", "data:image/webp;base64," + base64Webp);
            content.put(new JSONObject().put("type", "image_url").put("image_url", imageUrl));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);
            return extractText(postJson(GROQ_CHAT, groq, body, 8000, 18000, false));
        }

        String openrouter = p.getString(KEY_OPENROUTER, "");
        if (!openrouter.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("model", "openrouter/free");
            body.put("max_tokens", 260);
            JSONArray messages = new JSONArray();
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text",
                    "Describe this Android screen concisely, including visible controls and text."));
            content.put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/webp;base64," + base64Webp)));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);
            return extractText(postJson(OPENROUTER_CHAT, openrouter, body, 8000, 20000, true));
        }
        throw new Exception("SETUP_REQUIRED");
    }

    private static String groqChat(String key, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "openai/gpt-oss-120b");
        body.put("messages", messages);
        body.put("temperature", 0.55);
        body.put("max_completion_tokens", 1400);
        return extractText(postJson(GROQ_CHAT, key, body, 7000, 18000, false));
    }

    private static String openRouterChat(String key, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "openrouter/free");
        body.put("messages", messages);
        body.put("temperature", 0.6);
        body.put("max_tokens", 1400);
        return extractText(postJson(OPENROUTER_CHAT, key, body, 8000, 20000, true));
    }

    private static JSONObject postJson(String endpoint, String key, JSONObject body,
                                       int connectTimeout, int readTimeout, boolean openRouterHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        if (openRouterHeaders) {
            c.setRequestProperty("X-Title", "Luma Android");
        }
        OutputStream out = c.getOutputStream();
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String raw = readAll(in);
        c.disconnect();
        if (code < 200 || code >= 300) {
            String detail = raw == null ? "" : raw.replace('\n', ' ').trim();
            if (detail.length() > 220) detail = detail.substring(0, 220);
            throw new Exception("HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
        }
        return new JSONObject(raw);
    }

    private static String extractText(JSONObject json) throws Exception {
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("AI returned no response");
        JSONObject first = choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        if (message == null) throw new Exception("AI returned no message");
        Object content = message.opt("content");
        String text = content instanceof String ? ((String) content).trim() : String.valueOf(content).trim();
        if (text.isEmpty() || "null".equals(text)) throw new Exception("AI returned an empty response");
        return text;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
}
