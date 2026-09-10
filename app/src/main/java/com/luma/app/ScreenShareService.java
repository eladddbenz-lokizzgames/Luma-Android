package com.luma.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

    private static final String CHANNEL_ID = "luma_live_screen";
    private static final int NOTIFICATION_ID = 2601;
    private static final String HORDE = "https://aihorde.net/api/v2";
    private static final String ANON = "0000000000";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean captionBusy = new AtomicBoolean(false);
    private long lastFrameAt = 0L;
    private WindowManager windowManager;
    private View overlayView;

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

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Luma live screen", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Luma is viewing your screen");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, ScreenShareService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 73, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 74, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_luma)
                .setContentTitle("Luma is viewing your screen")
                .setContentText("Tap STOP at any time")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(R.drawable.ic_luma, "STOP", stopPi).build());
        startForeground(NOTIFICATION_ID, b.build());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply();
        showStopOverlay();
    }

    private void startProjection(int resultCode, Intent data) {
        stopCaptureOnly();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSharing();
            return;
        }
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

    private void processLatestFrame(ImageReader reader, int width, int height) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < 4500 || captionBusy.get()) return;
            lastFrameAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, width, height);
            if (cropped != full) full.recycle();
            int outW = Math.min(640, cropped.getWidth());
            int outH = Math.max(1, Math.round(cropped.getHeight() * (outW / (float) cropped.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true);
            if (scaled != cropped) cropped.recycle();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.WEBP, 62, bos);
            scaled.recycle();
            final String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            captionBusy.set(true);
            networkExecutor.execute(new Runnable() {
                @Override public void run() {
                    try { captionScreen(b64); }
                    finally { captionBusy.set(false); }
                }
            });
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void captionScreen(String base64Image) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("source_image", base64Image);
            payload.put("trusted_workers", false);
            JSONArray forms = new JSONArray();
            forms.put(new JSONObject().put("name", "caption"));
            payload.put("forms", forms);

            JSONObject submitted = requestJson("POST", HORDE + "/interrogate/async", payload.toString(), true);
            String id = submitted.optString("id", "");
            if (id.length() == 0) return;
            for (int i = 0; i < 18; i++) {
                Thread.sleep(1200);
                JSONObject status = requestJson("GET", HORDE + "/interrogate/status/" + id, null, false);
                String state = status.optString("state", "");
                if ("done".equals(state)) {
                    JSONArray resultForms = status.optJSONArray("forms");
                    if (resultForms != null) {
                        for (int j = 0; j < resultForms.length(); j++) {
                            JSONObject form = resultForms.optJSONObject(j);
                            if (form == null || !"caption".equals(form.optString("form"))) continue;
                            JSONObject result = form.optJSONObject("result");
                            if (result != null) {
                                String caption = result.optString("caption", "");
                                if (caption.length() > 0) {
                                    SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                                    e.putString(KEY_CONTEXT, caption);
                                    e.putLong("context_time", System.currentTimeMillis());
                                    e.apply();
                                }
                            }
                        }
                    }
                    return;
                }
                if ("faulted".equals(state)) return;
            }
        } catch (Throwable ignored) {}
    }

    private JSONObject requestJson(String method, String url, String body, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Client-Agent", "Luma:2.6:android");
        if (auth) c.setRequestProperty("apikey", ANON);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return new JSONObject(sb.toString());
    }

    private void showStopOverlay() {
        if (Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(this)) return;
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView stop = new TextView(this);
            stop.setText("STOP LUMA SCREEN");
            stop.setTextColor(Color.WHITE);
            stop.setTextSize(12);
            stop.setGravity(Gravity.CENTER);
            stop.setPadding(28, 14, 28, 14);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.rgb(220, 52, 90));
            bg.setCornerRadius(60f);
            stop.setBackground(bg);
            stop.setElevation(12f);
            stop.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { stopSharing(); }
            });
            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = 18;
            lp.y = 110;
            overlayView = stop;
            windowManager.addView(stop, lp);
        } catch (Throwable ignored) {}
    }

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
        try { if (overlayView != null && windowManager != null) windowManager.removeView(overlayView); } catch (Throwable ignored) {}
        overlayView = null;
        stopCaptureOnly();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopCaptureOnly();
        networkExecutor.shutdownNow();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
        super.onDestroy();
    }
}
