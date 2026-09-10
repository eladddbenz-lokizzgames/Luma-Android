package com.luma.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenShareService extends Service {
    public static final String ACTION_START = "com.luma.app.START_SCREEN_SHARE";
    public static final String ACTION_STOP = "com.luma.app.STOP_SCREEN_SHARE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String PREFS = "luma_screen_share";
    public static final String KEY_ACTIVE = "active";
    public static final String KEY_CONTEXT = "context";
    private static final String KEY_OCR = "ocr_context";
    private static final String KEY_VISION = "vision_context";

    private static final String CHANNEL_ID = "luma_live_screen";
    private static final int NOTIFICATION_ID = 2601;
    private static volatile List<OcrTarget> latestTargets = Collections.emptyList();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final Handler main = new Handler();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicBoolean visionBusy = new AtomicBoolean(false);
    private long lastOcrAt = 0L;
    private long lastVisionAt = 0L;
    private WindowManager windowManager;
    private View overlayView;
    private TextView overlayStatus;
    private TextRecognizer recognizer;

    private static class OcrTarget {
        final String text;
        final float x;
        final float y;
        OcrTarget(String text, float x, float y) { this.text = text; this.x = x; this.y = y; }
    }

    @Override public void onCreate() {
        super.onCreate();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSharing();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            startInForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            else data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != 0 && data != null) startProjection(resultCode, data);
        }
        return START_NOT_STICKY;
    }

    public static String getCombinedContext(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_CONTEXT, "");
        String accessibility = LumaAccessibilityService.getVisibleTextSnapshot();
        if (accessibility == null || accessibility.trim().isEmpty()) return saved == null ? "" : saved;
        String out = (saved == null ? "" : saved.trim());
        if (!out.isEmpty()) out += "\n";
        out += "Accessible screen text: " + accessibility.trim();
        return out.length() > 2600 ? out.substring(0, 2600) : out;
    }

    public static boolean tapRecognizedText(String target) {
        String needle = normalize(target);
        if (needle.isEmpty()) return false;
        OcrTarget best = null;
        for (OcrTarget item : latestTargets) {
            String candidate = normalize(item.text);
            if (candidate.equals(needle)) { best = item; break; }
            if ((candidate.contains(needle) || needle.contains(candidate)) && (best == null || candidate.length() < normalize(best.text).length())) best = item;
        }
        return best != null && LumaAccessibilityService.tapAtStatic(best.x, best.y);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9א-ת]+", " ").trim();
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Luma live screen", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Luma is viewing your screen");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, ScreenShareService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 73, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 74, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_luma)
                .setContentTitle("Luma Live Screen is active")
                .setContentText("Use the floating bar or tap STOP")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(R.drawable.ic_luma, "STOP", stopPi).build());
        startForeground(NOTIFICATION_ID, b.build());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
        showOverlayPanel();
    }

    private void startProjection(int resultCode, Intent data) {
        stopCaptureOnly();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) { stopSharing(); return; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSharing(); }
        }, new Handler(getMainLooper()));

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        final int width = metrics.widthPixels;
        final int height = metrics.heightPixels;
        final int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageThread = new HandlerThread("LumaScreenFrames");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader reader) { processLatestFrame(reader, width, height); }
        }, imageHandler);
        virtualDisplay = projection.createVirtualDisplay("LumaLiveScreen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
    }

    private void processLatestFrame(ImageReader reader, final int screenWidth, final int screenHeight) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - lastOcrAt < 850 || ocrBusy.get()) return;
            lastOcrAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap full = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, screenWidth, screenHeight);
            if (cropped != full) full.recycle();

            int outW = Math.min(720, screenWidth);
            int outH = Math.max(1, Math.round(screenHeight * (outW / (float) screenWidth)));
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true);
            if (scaled != cropped) cropped.recycle();

            if (AiProvider.hasAnyKey(this) && now - lastVisionAt >= 9000 && visionBusy.compareAndSet(false, true)) {
                lastVisionAt = now;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.WEBP, 58, bos);
                final String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                networkExecutor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            String description = AiProvider.vision(ScreenShareService.this, b64);
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VISION, description).apply();
                            updateCombinedContext();
                        } catch (Throwable ignored) {
                        } finally { visionBusy.set(false); }
                    }
                });
            }

            runOcr(scaled, screenWidth / (float) outW, screenHeight / (float) outH);
        } catch (Throwable ignored) {
            ocrBusy.set(false);
        } finally {
            if (image != null) image.close();
        }
    }

    private void runOcr(final Bitmap bitmap, final float scaleX, final float scaleY) {
        if (!ocrBusy.compareAndSet(false, true)) { bitmap.recycle(); return; }
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override public void onSuccess(Text result) {
                        ArrayList<OcrTarget> targets = new ArrayList<>();
                        StringBuilder visible = new StringBuilder();
                        for (Text.TextBlock block : result.getTextBlocks()) {
                            for (Text.Line line : block.getLines()) {
                                for (Text.Element element : line.getElements()) {
                                    String t = element.getText();
                                    Rect r = element.getBoundingBox();
                                    if (t == null || t.trim().isEmpty() || r == null) continue;
                                    float x = r.centerX() * scaleX;
                                    float y = r.centerY() * scaleY;
                                    targets.add(new OcrTarget(t.trim(), x, y));
                                    if (visible.length() < 1800) visible.append(t.trim()).append(" | ");
                                }
                            }
                        }
                        latestTargets = Collections.unmodifiableList(targets);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_OCR, visible.toString()).apply();
                        updateCombinedContext();
                        bitmap.recycle();
                        ocrBusy.set(false);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) {
                        bitmap.recycle();
                        ocrBusy.set(false);
                    }
                });
    }

    private void updateCombinedContext() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String ocr = p.getString(KEY_OCR, "");
        String vision = p.getString(KEY_VISION, "");
        StringBuilder out = new StringBuilder();
        if (vision != null && !vision.trim().isEmpty()) out.append("Visual screen: ").append(vision.trim()).append('\n');
        if (ocr != null && !ocr.trim().isEmpty()) out.append("Screen text: ").append(ocr.trim());
        String combined = out.toString();
        if (combined.length() > 2400) combined = combined.substring(0, 2400);
        p.edit().putString(KEY_CONTEXT, combined).putLong("context_time", System.currentTimeMillis()).apply();
    }

    private void showOverlayPanel() {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(this) || overlayView != null) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            final LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setColor(Color.argb(245, 249, 248, 255));
            panelBg.setCornerRadius(dp(20));
            panel.setBackground(panelBg);
            panel.setElevation(dp(14));

            overlayStatus = new TextView(this);
            overlayStatus.setText("Luma Live • type a message or device command");
            overlayStatus.setTextColor(Color.rgb(61, 55, 84));
            overlayStatus.setTextSize(12);
            overlayStatus.setMaxLines(3);
            panel.addView(overlayStatus, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);

            final EditText input = new EditText(this);
            input.setSingleLine(false);
            input.setMaxLines(3);
            input.setHint("Ask Luma or give a command…");
            input.setTextSize(14);
            input.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable inputBg = new GradientDrawable();
            inputBg.setColor(Color.WHITE);
            inputBg.setCornerRadius(dp(14));
            input.setBackground(inputBg);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(input, inputLp);

            Button send = makeButton("SEND", Color.rgb(105, 76, 255));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
            btnLp.setMargins(dp(7), 0, 0, 0);
            row.addView(send, btnLp);

            Button stop = makeButton("STOP", Color.rgb(220, 52, 90));
            LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
            stopLp.setMargins(dp(6), 0, 0, 0);
            row.addView(stop, stopLp);
            panel.addView(row);

            send.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) return;
                    input.setText("");
                    hideKeyboard(input);
                    handleOverlayMessage(q);
                }
            });
            stop.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { stopSharing(); }
            });

            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.x = 0;
            lp.y = dp(18);
            overlayView = panel;
            windowManager.addView(panel, lp);
        } catch (Throwable ignored) {}
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        return b;
    }

    private void handleOverlayMessage(final String q) {
        if (looksLikeDeviceCommand(q)) {
            setOverlayStatus("Luma: " + runOrEnableDeviceTask(q));
            return;
        }
        setOverlayStatus("Luma is thinking…");
        networkExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    String reply = AiProvider.quickChat(ScreenShareService.this, q, getCombinedContext(ScreenShareService.this));
                    setOverlayStatus("Luma: " + reply);
                } catch (Exception e) {
                    if ("SETUP_REQUIRED".equals(e.getMessage())) setOverlayStatus("Open Luma and tap AI Setup once to add a free Groq API key.");
                    else setOverlayStatus("AI error: " + (e.getMessage() == null ? "request failed" : e.getMessage()));
                }
            }
        });
    }

    private boolean looksLikeDeviceCommand(String q) {
        String s = q == null ? "" : q.toLowerCase(Locale.US);
        return s.contains("open ") || s.contains("launch ") || s.contains("click ") || s.contains("tap ") ||
                s.contains("press ") || s.trim().equals("back") || s.trim().equals("home") || s.contains("go back") || s.contains("go home");
    }

    private String runOrEnableDeviceTask(String q) {
        ComponentName component = new ComponentName(this, LumaAccessibilityService.class);
        getPackageManager().setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        if (LumaAccessibilityService.isRunning()) return LumaAccessibilityService.executeCommandStatic(q);
        getSharedPreferences(LumaAccessibilityService.PREFS, MODE_PRIVATE).edit().putString(LumaAccessibilityService.KEY_PENDING_COMMAND, q).apply();
        try {
            Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
        } catch (Throwable ignored) {}
        return "Enable Luma once in Android Accessibility. Your command is saved and will continue automatically.";
    }

    private void setOverlayStatus(final String text) {
        main.post(new Runnable() {
            @Override public void run() { if (overlayStatus != null) overlayStatus.setText(text); }
        });
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void stopCaptureOnly() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        imageReader = null;
        try {
            MediaProjection p = projection;
            projection = null;
            if (p != null) p.stop();
        } catch (Throwable ignored) { projection = null; }
        try { if (imageThread != null) imageThread.quitSafely(); } catch (Throwable ignored) {}
        imageThread = null;
        imageHandler = null;
    }

    private void stopSharing() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        latestTargets = Collections.emptyList();
        try { if (overlayView != null && windowManager != null) windowManager.removeView(overlayView); } catch (Throwable ignored) {}
        overlayView = null;
        overlayStatus = null;
        stopCaptureOnly();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        try { if (recognizer != null) recognizer.close(); } catch (Throwable ignored) {}
        stopCaptureOnly();
        networkExecutor.shutdownNow();
        latestTargets = Collections.emptyList();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        super.onDestroy();
    }
}
