package com.luma.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean active = false;
    private boolean paused = false;
    private WindowManager wm;
    private View bubble;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
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
            main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 450);
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
        b.setSmallIcon(R.drawable.ic_luma)
                .setContentTitle("Luma Voice is active")
                .setContentText("Listening and replying while you use other apps")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(R.drawable.ic_luma, "STOP", stopPi).build());
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
            main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 900);
        }
    }

    private boolean looksLikeDeviceCommand(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.US);
        return s.contains("open ") || s.contains("launch ") || s.contains("click ") || s.contains("tap ") ||
                s.contains("press ") || s.trim().equals("back") || s.trim().equals("home") || s.contains("go back") || s.contains("go home");
    }

    private void handleSpeech(final String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) { startListening(); return; }
        updateBubble("Luma\nThinking…");
        if (looksLikeDeviceCommand(spoken)) {
            speak(runOrEnableDeviceTask(spoken));
            return;
        }
        network.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String reply = AiProvider.quickChat(VoiceAssistantService.this, spoken,
                            ScreenShareService.getCombinedContext(VoiceAssistantService.this));
                    main.post(new Runnable(){ @Override public void run(){ speak(reply); }});
                } catch (final Exception e) {
                    main.post(new Runnable(){ @Override public void run(){
                        if ("SETUP_REQUIRED".equals(e.getMessage())) speak("Open Luma and tap AI Setup once to connect the fast AI.");
                        else speak("The AI request failed. Please try again.");
                    }});
                }
            }
        });
    }

    private String runOrEnableDeviceTask(String command) {
        ComponentName component = new ComponentName(this, LumaAccessibilityService.class);
        getPackageManager().setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        if (LumaAccessibilityService.isRunning()) return LumaAccessibilityService.executeCommandStatic(command);
        getSharedPreferences(LumaAccessibilityService.PREFS, MODE_PRIVATE)
                .edit().putString(LumaAccessibilityService.KEY_PENDING_COMMAND, command).apply();
        try {
            Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
        } catch (Throwable ignored) {}
        return "Android needs the one-time Luma Accessibility switch. Your command is saved and will continue after you enable it.";
    }

    private void speak(final String text) {
        if (!active) return;
        if (tts == null) {
            main.postDelayed(new Runnable(){ @Override public void run(){ speak(text); }}, 350);
            return;
        }
        updateBubble("Luma\nSpeaking…");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "luma_reply_" + System.currentTimeMillis());
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(1.03f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 300); }
                @Override public void onError(String utteranceId) { main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 450); }
            });
        }
    }

    private void showBubble() {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(this) || bubble != null) return;
        try {
            wm = (WindowManager)getSystemService(WINDOW_SERVICE);
            TextView v = new TextView(this);
            v.setText("Luma\nListening…");
            v.setTextColor(Color.WHITE);
            v.setTextSize(12);
            v.setGravity(Gravity.CENTER);
            v.setPadding(20,20,20,20);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(111,76,255), Color.rgb(0,207,255)});
            bg.setShape(GradientDrawable.OVAL);
            v.setBackground(bg);
            v.setElevation(16f);
            v.setOnClickListener(new View.OnClickListener(){
                @Override public void onClick(View view){
                    paused = !paused;
                    if (paused) {
                        try { recognizer.stopListening(); } catch (Throwable ignored) {}
                        updateBubble("Luma\nPaused");
                    } else startListening();
                }
            });
            v.setOnLongClickListener(new View.OnLongClickListener(){ @Override public boolean onLongClick(View view){ stopVoice(); return true; }});
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(150,150,type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = 160;
            bubble = v;
            wm.addView(v, lp);
        } catch (Throwable ignored) {}
    }

    private void updateBubble(final String text) {
        main.post(new Runnable(){ @Override public void run(){ if (bubble instanceof TextView) ((TextView)bubble).setText(text); }});
    }

    private void stopVoice() {
        active = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        recognizer = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null;
        try { if (bubble != null && wm != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        bubble = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() { updateBubble("Luma\nHearing you…"); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { updateBubble("Luma\nThinking…"); }
    @Override public void onError(int error) { if (active && !paused) main.postDelayed(new Runnable(){ @Override public void run(){ startListening(); }}, 700); }
    @Override public void onResults(Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        handleSpeech(list != null && !list.isEmpty() ? list.get(0) : "");
    }
    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override public void onDestroy() {
        active = false;
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        try { if (bubble != null && wm != null) wm.removeView(bubble); } catch (Throwable ignored) {}
        network.shutdownNow();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        super.onDestroy();
    }
}
