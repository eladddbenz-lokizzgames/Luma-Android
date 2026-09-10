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
import java.util.Locale;

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
        if (!p.getString(KEY_GROQ, "").isEmpty()) return "Groq · GPT-OSS + Web + Vision";
        if (!p.getString(KEY_OPENROUTER, "").isEmpty()) return "OpenRouter · Free Router";
        return "Fast AI setup needed";
    }

    public static String chat(Context context, JSONArray messages) throws Exception {
        SharedPreferences p = prefs(context);
        String groq = p.getString(KEY_GROQ, "");
        String openrouter = p.getString(KEY_OPENROUTER, "");
        Exception last = null;

        if (!groq.isEmpty()) {
            try {
                if (wantsWeb(messages)) return groqWebChat(groq, messages);
                return groqChat(groq, messages);
            } catch (Exception e) { last = e; }
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

    public static String chatWithFreshScreen(Context context, JSONArray messages, String base64Webp) throws Exception {
        String user = lastUserText(messages);
        if (base64Webp == null || base64Webp.isEmpty() || !isScreenQuestion(messages)) return chat(context, messages);

        String visual = visionQuestion(context, base64Webp, user);
        if (!wantsWeb(messages)) return visual;

        JSONArray enriched = new JSONArray();
        enriched.put(new JSONObject().put("role", "system").put("content",
                "Fresh visual inspection of the user's shared Android screen: " + visual +
                "\nUse web search when it helps identify the app, game, character, event, product, or current information. Do not pretend you searched if you did not."));
        for (int i = 0; i < messages.length(); i++) enriched.put(messages.get(i));
        return chat(context, enriched);
    }

    public static String quickChat(Context context, String prompt, String screenContext) throws Exception {
        JSONArray messages = new JSONArray();
        String system = "You are Luma, a fast, capable Android assistant. Reply clearly and directly. Keep spoken answers concise unless detail is requested. You can use current web search when the user asks to search, look something up, or needs fresh information.";
        if (screenContext != null && !screenContext.trim().isEmpty()) {
            system += " The user explicitly enabled Live Screen. Current screen context: " + screenContext.trim();
        }
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", prompt == null ? "" : prompt));
        return chat(context, messages);
    }

    public static String quickChatWithScreen(Context context, String prompt, String screenContext, String base64Webp) throws Exception {
        JSONArray messages = new JSONArray();
        String system = "You are Luma, a fast Android assistant. The user explicitly enabled Live Screen. Answer from the actual current screen when relevant. You may use web search for identification or current facts.";
        if (screenContext != null && !screenContext.trim().isEmpty()) system += " Current OCR/accessibility context: " + screenContext.trim();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", prompt == null ? "" : prompt));
        return chatWithFreshScreen(context, messages, base64Webp);
    }

    public static boolean isScreenQuestion(JSONArray messages) {
        String s = lastUserText(messages).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        return s.contains("screen") || s.contains("what is this") || s.contains("what am i looking") ||
                s.contains("what do you see") || s.contains("which game") || s.contains("what game") ||
                s.contains("identify this") || s.contains("on my phone") || s.contains("on my device") ||
                s.contains("במסך") || s.contains("מה זה") || s.contains("מה אתה רואה") || s.contains("איזה משחק") ||
                s.contains("מה המשחק") || s.contains("תזהה") || s.contains("בטלפון") || s.contains("במכשיר");
    }

    public static boolean wantsWeb(JSONArray messages) {
        String s = lastUserText(messages).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        return s.contains("search") || s.contains("look up") || s.contains("google") || s.contains("web") ||
                s.contains("internet") || s.contains("online") || s.contains("latest") || s.contains("today") ||
                s.contains("current") || s.contains("right now") || s.contains("news") || s.contains("price") ||
                s.contains("תחפש") || s.contains("חפש") || s.contains("באינטרנט") || s.contains("עדכני") ||
                s.contains("היום") || s.contains("עכשיו") || s.contains("חדשות") || s.contains("מחיר");
    }

    private static String lastUserText(JSONArray messages) {
        if (messages == null) return "";
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null || !"user".equals(m.optString("role"))) continue;
            Object content = m.opt("content");
            if (content instanceof String) return ((String) content).trim();
        }
        return "";
    }

    public static String vision(Context context, String base64Webp) throws Exception {
        return visionQuestion(context, base64Webp,
                "Identify exactly what is shown on this Android screen. If this is a game or app, name it when you can. Mention distinctive logos, characters, menus, visible buttons, dialogs, and important text. If uncertain, say what clues you used and give the most likely identification.");
    }

    public static String visionQuestion(Context context, String base64Webp, String question) throws Exception {
        SharedPreferences p = prefs(context);
        String groq = p.getString(KEY_GROQ, "");
        if (!groq.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("model", "qwen/qwen3.6-27b");
            body.put("temperature", 0.1);
            body.put("max_completion_tokens", 650);
            JSONArray messages = new JSONArray();
            JSONArray content = new JSONArray();
            String q = question == null || question.trim().isEmpty() ? "What is on this screen?" : question.trim();
            content.put(new JSONObject().put("type", "text").put("text",
                    "You are Luma looking at the user's CURRENT Android screen with permission. Answer the user's question about the image. Identify the exact app or game when possible from visual design, logos, characters, menus and text; do not rely only on OCR. If you are not certain, state the most likely answer and the visual clues. User question: " + q));
            JSONObject imageUrl = new JSONObject().put("url", "data:image/webp;base64," + base64Webp);
            content.put(new JSONObject().put("type", "image_url").put("image_url", imageUrl));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);
            return extractText(postJson(GROQ_CHAT, groq, body, 8000, 20000, false, false));
        }

        String openrouter = p.getString(KEY_OPENROUTER, "");
        if (!openrouter.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("model", "openrouter/free");
            body.put("max_tokens", 650);
            JSONArray messages = new JSONArray();
            JSONArray content = new JSONArray();
            String q = question == null || question.trim().isEmpty() ? "What is on this screen?" : question.trim();
            content.put(new JSONObject().put("type", "text").put("text",
                    "Look at this CURRENT Android screenshot. Answer the user's question and identify the exact app/game when possible. User question: " + q));
            content.put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/webp;base64," + base64Webp)));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            body.put("messages", messages);
            return extractText(postJson(OPENROUTER_CHAT, openrouter, body, 8000, 22000, true, false));
        }
        throw new Exception("SETUP_REQUIRED");
    }

    private static String groqChat(String key, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "openai/gpt-oss-120b");
        body.put("messages", messages);
        body.put("temperature", 0.55);
        body.put("max_completion_tokens", 1500);
        return extractText(postJson(GROQ_CHAT, key, body, 7000, 18000, false, false));
    }

    private static String groqWebChat(String key, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "groq/compound-mini");
        body.put("messages", messages);
        JSONObject tools = new JSONObject();
        JSONArray enabled = new JSONArray();
        enabled.put("web_search");
        enabled.put("visit_website");
        tools.put("enabled_tools", enabled);
        body.put("compound_custom", new JSONObject().put("tools", tools));
        return extractText(postJson(GROQ_CHAT, key, body, 7000, 24000, false, true));
    }

    private static String openRouterChat(String key, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "openrouter/free");
        body.put("messages", messages);
        body.put("temperature", 0.6);
        body.put("max_tokens", 1500);
        return extractText(postJson(OPENROUTER_CHAT, key, body, 8000, 22000, true, false));
    }

    private static JSONObject postJson(String endpoint, String key, JSONObject body,
                                       int connectTimeout, int readTimeout,
                                       boolean openRouterHeaders, boolean groqLatest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        if (openRouterHeaders) c.setRequestProperty("X-Title", "Luma Android");
        if (groqLatest) c.setRequestProperty("Groq-Model-Version", "latest");
        OutputStream out = c.getOutputStream();
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String raw = readAll(in);
        c.disconnect();
        if (code < 200 || code >= 300) {
            String detail = raw == null ? "" : raw.replace('\n', ' ').trim();
            if (detail.length() > 260) detail = detail.substring(0, 260);
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
