package com.luma.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class LumaAccessibilityService extends AccessibilityService {
    public static final String PREFS = "luma_device_control";
    public static final String KEY_PENDING_COMMAND = "pending_command";
    private static volatile LumaAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager cursorWindowManager;
    private View cursorView;
    private WindowManager.LayoutParams cursorParams;
    private float cursorX = -1f;
    private float cursorY = -1f;
    private boolean cursorAnimating = false;

    public static boolean isRunning() { return instance != null; }

    public static String executeCommandStatic(String command) {
        LumaAccessibilityService s = instance;
        if (s == null) return "Device Control needs Android's one-time Accessibility switch.";
        return s.executeCommand(command == null ? "" : command.trim());
    }

    public static boolean tapAtStatic(float x, float y) {
        LumaAccessibilityService s = instance;
        return s != null && s.tapAt(x, y);
    }

    public static boolean animateTapAtStatic(float x, float y) {
        LumaAccessibilityService s = instance;
        return s != null && s.animateCursorAndTap(x, y);
    }

    public static String getVisibleTextSnapshot() {
        LumaAccessibilityService s = instance;
        if (s == null) return "";
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder out = new StringBuilder();
        s.collectText(root, out, 0);
        if (out.length() > 1500) return out.substring(0, 1500);
        return out.toString().trim();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        cursorWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        final String pending = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING_COMMAND, "");
        if (!pending.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING_COMMAND).apply();
            main.postDelayed(new Runnable() {
                @Override public void run() { executeCommand(pending); }
            }, 900);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        removeCursor();
        super.onDestroy();
    }

    private String executeCommand(String command) {
        if (command.isEmpty()) return "Tell me what to open, tap, click, or press.";
        String lower = command.toLowerCase(Locale.ROOT).replace("please ", "").trim();

        if (lower.equals("back") || lower.equals("go back") || lower.equals("press back") || lower.equals("חזור")) {
            performGlobalAction(GLOBAL_ACTION_BACK); return "Going back.";
        }
        if (lower.equals("home") || lower.equals("go home") || lower.equals("מסך הבית")) {
            performGlobalAction(GLOBAL_ACTION_HOME); return "Opening Home.";
        }

        int openPos = firstPositiveWord(lower, "open ", "launch ", "פתח ");
        if (openPos >= 0) {
            String actionWord = lower.startsWith("launch ", openPos) ? "launch " : (lower.startsWith("פתח ", openPos) ? "פתח " : "open ");
            int start = openPos + actionWord.length();
            int split = firstPositive(
                    lower.indexOf(" then click ", start), lower.indexOf(" and click ", start), lower.indexOf(" click ", start),
                    lower.indexOf(" then tap ", start), lower.indexOf(" and tap ", start), lower.indexOf(" tap ", start),
                    lower.indexOf(" ואז לחץ ", start), lower.indexOf(" ותלחץ ", start), lower.indexOf(" לחץ ", start));
            String appName = (split > 0 ? command.substring(start, split) : command.substring(start)).trim();
            appName = appName.replaceFirst("(?i)^the\\s+", "").replaceFirst("(?i)\\s+app$", "").trim();
            boolean opened = openAppByLabel(appName);
            if (!opened) return "I couldn't find an installed app called " + appName + ".";
            if (split > 0) {
                final String target = cleanTarget(command.substring(split)
                        .replaceFirst("(?i)^\\s*(then|and)?\\s*(click|tap|press)\\s+", "")
                        .replaceFirst("^\\s*(ואז|ו)?\\s*(לחץ|תלחץ)\\s+", ""));
                scheduleClickRetries(target);
                return "Opening " + appName + " and looking for " + target + ".";
            }
            return "Opening " + appName + ".";
        }

        int tapPos = firstPositiveWord(lower, "click ", "tap ", "press ", "לחץ ", "תלחץ ");
        if (tapPos >= 0) {
            int space = command.indexOf(' ', tapPos);
            String target = space >= 0 ? command.substring(space + 1).trim() : "";
            target = cleanTarget(target);
            boolean ok = clickText(target);
            if (!ok) scheduleClickRetries(target);
            return ok ? "Moving to " + target + " and tapping it." : "Looking for " + target + " on the live screen.";
        }

        return "Device Control is ready. Try: open Brawl Stars then click Brawlers.";
    }

    private String cleanTarget(String target) {
        if (target == null) return "";
        String t = target.trim();
        t = t.replaceFirst("(?i)^the\\s+", "");
        t = t.replaceFirst("(?i)^(button|icon)\\s+", "");
        t = t.replaceFirst("(?i)\\s+(button|icon)$", "");
        t = t.replaceFirst("^(הכפתור|כפתור)\\s+", "");
        return t.trim();
    }

    private int firstPositiveWord(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int p = text.indexOf(needle);
            if (p >= 0 && (best < 0 || p < best)) best = p;
        }
        return best;
    }

    private int firstPositive(int... values) {
        int best = -1;
        for (int v : values) if (v >= 0 && (best < 0 || v < best)) best = v;
        return best;
    }

    private boolean openAppByLabel(String wanted) {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN, null);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        String needle = wanted.toLowerCase(Locale.ROOT).trim();
        ResolveInfo best = null;
        for (ResolveInfo ri : apps) {
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString().toLowerCase(Locale.ROOT);
            if (label.equals(needle)) { best = ri; break; }
            if (best == null && !needle.isEmpty() && (label.contains(needle) || needle.contains(label))) best = ri;
        }
        if (best == null || best.activityInfo == null) return false;
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); return true; } catch (Throwable e) { return false; }
    }

    private void scheduleClickRetries(final String target) {
        if (target == null || target.trim().isEmpty()) return;
        final AtomicBoolean done = new AtomicBoolean(false);
        for (int i = 0; i < 12; i++) {
            final long delay = 250L + i * 350L;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!done.get() && clickText(target)) done.set(true);
                }
            }, delay);
        }
    }

    private boolean clickText(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(target.trim());
            if (found != null) {
                for (AccessibilityNodeInfo node : found) {
                    AccessibilityNodeInfo cur = node;
                    for (int depth = 0; cur != null && depth < 6; depth++) {
                        Rect bounds = new Rect();
                        cur.getBoundsInScreen(bounds);
                        if (cur.isEnabled() && !bounds.isEmpty()) {
                            if (animateCursorAndTap(bounds.exactCenterX(), bounds.exactCenterY())) return true;
                        }
                        if (cur.isClickable() && cur.isEnabled()) {
                            try { if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; } catch (Throwable ignored) {}
                        }
                        cur = cur.getParent();
                    }
                }
            }
        }
        return ScreenShareService.tapRecognizedText(target.trim());
    }

    private boolean animateCursorAndTap(final float targetX, final float targetY) {
        if (targetX < 0 || targetY < 0) return false;
        main.post(new Runnable() {
            @Override public void run() {
                ensureCursor();
                if (cursorView == null || cursorParams == null || cursorWindowManager == null) {
                    tapAt(targetX, targetY);
                    return;
                }
                if (cursorAnimating) return;
                cursorAnimating = true;

                DisplayMetrics dm = getResources().getDisplayMetrics();
                if (cursorX < 0 || cursorY < 0) {
                    cursorX = dm.widthPixels / 2f;
                    cursorY = dm.heightPixels / 2f;
                }
                cursorView.setVisibility(View.VISIBLE);
                final float startX = cursorX;
                final float startY = cursorY;
                final int steps = 14;
                final long stepMs = 24L;

                for (int i = 1; i <= steps; i++) {
                    final int step = i;
                    main.postDelayed(new Runnable() {
                        @Override public void run() {
                            float t = step / (float) steps;
                            float eased = 1f - (1f - t) * (1f - t);
                            cursorX = startX + (targetX - startX) * eased;
                            cursorY = startY + (targetY - startY) * eased;
                            moveCursorWindow(cursorX, cursorY);
                            if (step == steps) {
                                tapAt(targetX, targetY);
                                pulseCursor();
                                cursorAnimating = false;
                            }
                        }
                    }, i * stepMs);
                }
            }
        });
        return true;
    }

    private void ensureCursor() {
        if (cursorView != null || cursorWindowManager == null) return;
        try {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.argb(235, 110, 76, 255));
            bg.setStroke(dp(3), Color.WHITE);
            dot.setBackground(bg);
            dot.setElevation(dp(10));
            dot.setAlpha(0.96f);

            cursorParams = new WindowManager.LayoutParams(
                    dp(30), dp(30),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.START;
            cursorParams.x = 0;
            cursorParams.y = 0;
            cursorView = dot;
            cursorWindowManager.addView(cursorView, cursorParams);
            cursorView.setVisibility(View.GONE);
        } catch (Throwable e) {
            cursorView = null;
            cursorParams = null;
        }
    }

    private void moveCursorWindow(float x, float y) {
        if (cursorView == null || cursorParams == null || cursorWindowManager == null) return;
        try {
            cursorParams.x = Math.round(x - dp(15));
            cursorParams.y = Math.round(y - dp(15));
            cursorWindowManager.updateViewLayout(cursorView, cursorParams);
        } catch (Throwable ignored) {}
    }

    private void pulseCursor() {
        if (cursorView == null) return;
        cursorView.animate().scaleX(1.55f).scaleY(1.55f).alpha(0.55f).setDuration(90).withEndAction(new Runnable() {
            @Override public void run() {
                if (cursorView == null) return;
                cursorView.animate().scaleX(1f).scaleY(1f).alpha(0.96f).setDuration(110).withEndAction(new Runnable() {
                    @Override public void run() {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { if (cursorView != null && !cursorAnimating) cursorView.setVisibility(View.GONE); }
                        }, 360);
                    }
                });
            }
        });
    }

    private void removeCursor() {
        try { if (cursorView != null && cursorWindowManager != null) cursorWindowManager.removeView(cursorView); } catch (Throwable ignored) {}
        cursorView = null;
        cursorParams = null;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private boolean tapAt(float x, float y) {
        if (x < 0 || y < 0) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable e) {
            return false;
        }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 1800) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(" | ");
        if (desc != null && desc.length() > 0 && (text == null || !desc.toString().equals(text.toString()))) out.append(desc).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }
}
