package com.luma.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.luma.app.START_VOICE";
    public static final String ACTION_STOP = "com.luma.app.STOP_VOICE";
    public static final String PREFS = "luma_voice";
    public static final String KEY_ACTIVE = "active";

    private static final String CHANNEL = "luma_voice_channel";
    private static final int NOTIFICATION_ID = 2711;
    private static final String KEYLESS = "https://keylessai.thryx.workers.dev/v1/chat/completions";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean active = false;
    private boolean paused = false;
    private WindowManager wm;
    private View bubble;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVoice();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startForegroundMode();
            active = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
            if (tts == null) tts = new TextToSpeech(this, this);
            if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
            }
            showBubble();
            main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 500);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundMode() {
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Luma voice", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Shown while Luma voice mode is active");
            nm.createNotificationChannel(c);
        }
        Intent stop = new Intent(this, VoiceAssistantService.class); stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 811, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 812, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_luma).setContentTitle("Luma Voice is active").setContentText("Listening and replying while you use other apps").setOngoing(true).setContentIntent(openPi).addAction(new Notification.Action.Builder(R.drawable.ic_luma, "STOP", stopPi).build());
        startForeground(NOTIFICATION_ID, b.build());
    }

    private void startListening() {
        if (!active || paused || recognizer == null) return;
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recognizer.startListening(i);
            updateBubble("Luma\nListening…");
        } catch (Throwable e) {
            main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 1200);
        }
    }

    private boolean looksLikeDeviceCommand(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.US);
        return s.startsWith("open ") || s.startsWith("launch ") || s.startsWith("click ") || s.startsWith("tap ") || s.startsWith("press ") || s.equals("back") || s.equals("go back") || s.equals("home") || s.contains(" then click ") || s.contains(" and click ");
    }

    private void handleSpeech(final String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) { startListening(); return; }
        updateBubble("Luma\nThinking…");
        if (looksLikeDeviceCommand(spoken)) {
            String result = LumaAccessibilityService.executeCommandStatic(spoken);
            speak(result);
            return;
        }
        network.execute(new Runnable() {
            @Override public void run() {
                try {
                    String reply = askFastAI(spoken);
                    main.post(new Runnable(){ @Override public void run(){ speak(reply); }});
                } catch (Exception e) {
                    main.post(new Runnable(){ @Override public void run(){ speak("I couldn't reach the AI service. Please try again."); }});
                }
            }
        });
    }

    private String askFastAI(String prompt) throws Exception {
        String screen = getSharedPreferences(ScreenShareService.PREFS, MODE_PRIVATE).getString(ScreenShareService.KEY_CONTEXT, "");
        JSONObject body = new JSONObject();
        body.put("model", "openai-fast");
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        String sys = "You are Luma, a fast mobile voice assistant. Reply naturally and briefly for spoken conversation.";
        if (screen != null && !screen.isEmpty()) sys += " The user explicitly enabled Live Screen. Current screen context: " + screen;
        messages.put(new JSONObject().put("role", "system").put("content", sys));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection)new URL(KEYLESS).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer not-needed");
        OutputStream out = c.getOutputStream();
        out.write(body.toString().getBytes(StandardCharsets.UTF_8)); out.close();
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line; while ((line = br.readLine()) != null) sb.append(line); br.close(); c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        JSONObject j = new JSONObject(sb.toString());
        JSONArray choices = j.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("No reply");
        JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
        String text = msg == null ? "" : msg.optString("content", "");
        if (text.isEmpty()) throw new Exception("No reply");
        return text;
    }

    private void speak(String text) {
        if (!active) return;
        if (tts == null) { main.postDelayed(new Runnable(){ @Override public void run(){ speak(text); }}, 500); return; }
        updateBubble("Luma\nSpeaking…");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "luma_reply_" + System.currentTimeMillis());
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(1.03f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 350); }
                @Override public void onError(String utteranceId) { main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 500); }
            });
        }
    }

    private void showBubble() {
        if (Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(this) || bubble != null) return;
        try {
            wm = (WindowManager)getSystemService(WINDOW_SERVICE);
            TextView v = new TextView(this);
            v.setText("Luma\nListening…"); v.setTextColor(Color.WHITE); v.setTextSize(12); v.setGravity(Gravity.CENTER); v.setPadding(20,20,20,20);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(111,76,255), Color.rgb(0,207,255)}); bg.setShape(GradientDrawable.OVAL); v.setBackground(bg); v.setElevation(16f);
            v.setOnClickListener(new View.OnClickListener(){ @Override public void onClick(View view){ paused = !paused; if (paused) { try{recognizer.stopListening();}catch(Throwable ignored){} updateBubble("Luma\nPaused"); } else startListening(); }});
            v.setOnLongClickListener(new View.OnLongClickListener(){ @Override public boolean onLongClick(View view){ stopVoice(); return true; }});
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(150,150,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; lp.y = 160;
            bubble = v; wm.addView(v, lp);
        } catch (Throwable ignored) {}
    }

    private void updateBubble(final String text) {
        main.post(new Runnable(){ @Override public void run(){ if (bubble instanceof TextView) ((TextView)bubble).setText(text); }});
    }

    private void stopVoice() {
        active = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {} recognizer = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {} tts = null;
        try { if (bubble != null && wm != null) wm.removeView(bubble); } catch (Throwable ignored) {} bubble = null;
        stopForeground(true); stopSelf();
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() { updateBubble("Luma\nHearing you…"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { updateBubble("Luma\nThinking…"); }
    @Override public void onError(int error) { if (active && !paused) main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 800); }
    @Override public void onResults(Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        handleSpeech(list != null && !list.isEmpty() ? list.get(0) : "");
    }
    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onDestroy() { stopVoice(); network.shutdownNow(); super.onDestroy(); }
}
