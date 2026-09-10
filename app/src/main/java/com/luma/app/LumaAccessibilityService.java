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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class LumaAccessibilityService extends AccessibilityService {
    public static final String PREFS = "luma_device_control";
    public static final String KEY_PENDING_COMMAND = "pending_command";
    private static final String KEY_ACTION_LOG = "action_log";
    private static volatile LumaAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager cursorWindowManager;
    private View cursorView;
    private WindowManager.LayoutParams cursorParams;
    private float cursorX = -1f;
    private float cursorY = -1f;
    private boolean cursorAnimating = false;

    private boolean agentPaused = false;
    private boolean suggestOnly = false;
    private String continuousTarget = "";
    private Runnable continuousRunnable;
    private long continuousStartedAt = 0L;

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
        if (out.length() > 1800) return out.substring(0, 1800);
        return out.toString().trim();
    }

    public static String getAgentStatusStatic() {
        LumaAccessibilityService s = instance;
        if (s == null) return "Device Control off";
        if (!s.continuousTarget.isEmpty()) return (s.agentPaused ? "Paused" : "Watching") + ": " + s.continuousTarget;
        return s.suggestOnly ? "Suggest-only mode" : "Ready";
    }

    public static void pauseAgentStatic() { if (instance != null) instance.agentPaused = true; }
    public static void resumeAgentStatic() { if (instance != null) instance.agentPaused = false; }
    public static void stopAgentStatic() { if (instance != null) instance.stopContinuousInternal(); }
    public static void setSuggestOnlyStatic(boolean enabled) { if (instance != null) instance.suggestOnly = enabled; }
    public static boolean isSuggestOnlyStatic() { return instance != null && instance.suggestOnly; }
    public static String getActionLogStatic() {
        LumaAccessibilityService s = instance;
        if (s == null) return "";
        return s.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACTION_LOG, "");
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
        stopContinuousInternal();
        removeCursor();
        super.onDestroy();
    }

    private String executeCommand(String command) {
        if (command.isEmpty()) return "Tell me what to open, tap, click, type, swipe, wait for, or watch.";
        String lower = command.toLowerCase(Locale.ROOT).replace("please ", "").trim();

        if (requestsCompetitiveAutoplay(lower)) {
            return "I can navigate menus and give live tactical suggestions, but I won't autonomously play competitive online matches. Continuous Agent is available for device workflows, testing, accessibility, and offline/single-player apps.";
        }

        if (lower.equals("agent status") || lower.equals("automation status") || lower.equals("סטטוס משימה")) {
            return getAgentStatusStatic();
        }
        if (lower.equals("stop agent") || lower.equals("stop automation") || lower.equals("עצור משימה") || lower.equals("עצור אוטומציה")) {
            stopContinuousInternal();
            return result("Agent stopped", "Continuous Agent stopped.");
        }
        if (lower.equals("pause agent") || lower.equals("pause automation") || lower.equals("השהה משימה")) {
            agentPaused = true;
            return result("Agent paused", "Continuous Agent paused.");
        }
        if (lower.equals("resume agent") || lower.equals("resume automation") || lower.equals("המשך משימה")) {
            agentPaused = false;
            return result("Agent resumed", "Continuous Agent resumed.");
        }
        if (lower.equals("suggest only") || lower.equals("suggest mode") || lower.equals("מצב הצעות")) {
            suggestOnly = true;
            return result("Suggest-only enabled", "Suggest-only mode enabled. I will locate actions but not tap them.");
        }
        if (lower.equals("control mode") || lower.equals("take control") || lower.equals("מצב שליטה")) {
            suggestOnly = false;
            return result("Control mode enabled", "Control mode enabled.");
        }

        String repeatTarget = extractAfterPrefix(lower, command,
                new String[]{"keep clicking ", "keep tapping ", "watch for ", "click whenever you see ", "tap whenever you see ", "תמשיך ללחוץ על ", "חפש כל הזמן "});
        if (repeatTarget != null && !repeatTarget.trim().isEmpty()) {
            if (isCompetitiveGameForeground()) return "Continuous tapping is disabled inside competitive online matches. I can still locate the target and suggest the tap.";
            startContinuousWatch(cleanTarget(repeatTarget));
            return result("Continuous watch: " + repeatTarget, "Continuous Agent is watching for " + repeatTarget + " until you stop it.");
        }

        if (lower.startsWith("wait for ") && lower.contains(" then ")) {
            int p = lower.indexOf(" then ");
            String waitTarget = cleanTarget(command.substring(9, p));
            String next = command.substring(p + 6).trim();
            scheduleWaitThen(waitTarget, next);
            return result("Wait for " + waitTarget, "Watching for " + waitTarget + ", then I will run the next step.");
        }

        if (lower.startsWith("swipe ") || lower.startsWith("scroll ") || lower.startsWith("גלול ")) {
            String dir = lower.replaceFirst("^(swipe|scroll|גלול)\\s+", "").trim();
            if (suggestOnly) return "Suggest-only: I would swipe " + dir + ".";
            boolean ok = swipeDirection(dir);
            return result("Swipe " + dir, ok ? "Swiped " + dir + "." : "Couldn't perform that swipe.");
        }

        if (lower.startsWith("type ") || lower.startsWith("enter text ") || lower.startsWith("כתוב ")) {
            String text = command.substring(command.indexOf(' ') + 1).trim();
            if (suggestOnly) return "Suggest-only: I would type: " + text;
            boolean ok = typeIntoFocusedField(text);
            return result("Type text", ok ? "Typed the text." : "I couldn't find a focused editable field.");
        }

        if (lower.startsWith("long press ") || lower.startsWith("hold ") || lower.startsWith("לחיצה ארוכה ")) {
            String target = cleanTarget(command.substring(command.indexOf(' ') + 1).replaceFirst("(?i)^press\\s+", ""));
            if (suggestOnly) return "Suggest-only: I found the long-press action for " + target + ".";
            boolean ok = gestureOnText(target, 720, false);
            return result("Long press " + target, ok ? "Long-pressed " + target + "." : "I couldn't find " + target + ".");
        }

        if (lower.startsWith("double tap ") || lower.startsWith("double click ") || lower.startsWith("לחץ פעמיים ")) {
            String target = cleanTarget(command.substring(command.indexOf(' ') + 1).replaceFirst("(?i)^(tap|click)\\s+", ""));
            if (suggestOnly) return "Suggest-only: I found the double-tap action for " + target + ".";
            boolean ok = gestureOnText(target, 70, true);
            return result("Double tap " + target, ok ? "Double-tapped " + target + "." : "I couldn't find " + target + ".");
        }

        if (lower.equals("back") || lower.equals("go back") || lower.equals("press back") || lower.equals("undo") || lower.equals("חזור")) {
            if (suggestOnly) return "Suggest-only: I would go Back.";
            performGlobalAction(GLOBAL_ACTION_BACK); return result("Back", "Going back.");
        }
        if (lower.equals("home") || lower.equals("go home") || lower.equals("מסך הבית")) {
            if (suggestOnly) return "Suggest-only: I would open Home.";
            performGlobalAction(GLOBAL_ACTION_HOME); return result("Home", "Opening Home.");
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
            if (suggestOnly) return "Suggest-only: I would open " + appName + ".";
            boolean opened = openAppByLabel(appName);
            if (!opened) return "I couldn't find an installed app called " + appName + ".";
            if (split > 0) {
                final String target = cleanTarget(command.substring(split)
                        .replaceFirst("(?i)^\\s*(then|and)?\\s*(click|tap|press)\\s+", "")
                        .replaceFirst("^\\s*(ואז|ו)?\\s*(לחץ|תלחץ)\\s+", ""));
                scheduleClickRetries(target);
                return result("Open " + appName + " then tap " + target, "Opening " + appName + " and looking for " + target + ".");
            }
            return result("Open " + appName, "Opening " + appName + ".");
        }

        int tapPos = firstPositiveWord(lower, "click ", "tap ", "press ", "לחץ ", "תלחץ ");
        if (tapPos >= 0) {
            int space = command.indexOf(' ', tapPos);
            String target = space >= 0 ? command.substring(space + 1).trim() : "";
            target = cleanTarget(target);
            if (suggestOnly) return "Suggest-only: I would move the cursor to " + target + " and tap it.";
            boolean ok = clickText(target);
            if (!ok) scheduleClickRetries(target);
            return result("Tap " + target, ok ? "Moving to " + target + " and tapping it." : "Looking for " + target + " on the live screen.");
        }

        return "Device Control is ready. Commands include open, click, type, swipe, long press, double tap, wait for, keep clicking, pause agent, resume agent, and stop agent.";
    }

    private boolean requestsCompetitiveAutoplay(String lower) {
        boolean gameAutoplay = lower.contains("play like a pro") || lower.contains("play constantly") || lower.contains("play for me") || lower.contains("auto play") || lower.contains("autoplay") || lower.contains("שחק במקומי") || lower.contains("שחק לבד");
        return gameAutoplay && (lower.contains("brawl") || isCompetitiveGameForeground());
    }

    private boolean isCompetitiveGameForeground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return false;
            String pkg = root.getPackageName().toString().toLowerCase(Locale.ROOT);
            return pkg.contains("supercell.brawlstars");
        } catch (Throwable e) { return false; }
    }

    private String extractAfterPrefix(String lower, String original, String[] prefixes) {
        for (String p : prefixes) {
            int idx = lower.indexOf(p);
            if (idx == 0) return original.substring(Math.min(original.length(), p.length())).trim();
        }
        return null;
    }

    private void startContinuousWatch(final String target) {
        stopContinuousInternal();
        if (target == null || target.trim().isEmpty()) return;
        continuousTarget = target.trim();
        continuousStartedAt = System.currentTimeMillis();
        agentPaused = false;
        continuousRunnable = new Runnable() {
            @Override public void run() {
                if (continuousTarget.isEmpty()) return;
                if (!agentPaused && !suggestOnly && !isCompetitiveGameForeground()) clickText(continuousTarget);
                main.postDelayed(this, 250L);
            }
        };
        main.post(continuousRunnable);
    }

    private void stopContinuousInternal() {
        continuousTarget = "";
        continuousStartedAt = 0L;
        if (continuousRunnable != null) main.removeCallbacks(continuousRunnable);
        continuousRunnable = null;
        agentPaused = false;
    }

    private void scheduleWaitThen(final String waitTarget, final String nextCommand) {
        final long started = System.currentTimeMillis();
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override public void run() {
                if (System.currentTimeMillis() - started > 300000L) return;
                if (isTextVisible(waitTarget)) {
                    executeCommand(nextCommand);
                    return;
                }
                main.postDelayed(holder[0], 250L);
            }
        };
        main.post(holder[0]);
    }

    private boolean isTextVisible(String target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(target);
            if (found != null && !found.isEmpty()) return true;
        }
        return ScreenShareService.hasRecognizedText(target);
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
        for (int i = 0; i < 18; i++) {
            final long delay = 140L + i * 180L;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!done.get() && clickText(target)) done.set(true);
                }
            }, delay);
        }
    }

    private Rect findTextBounds(String target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(target.trim());
        if (found == null) return null;
        for (AccessibilityNodeInfo node : found) {
            AccessibilityNodeInfo cur = node;
            for (int depth = 0; cur != null && depth < 6; depth++) {
                Rect bounds = new Rect();
                cur.getBoundsInScreen(bounds);
                if (cur.isEnabled() && !bounds.isEmpty()) return bounds;
                cur = cur.getParent();
            }
        }
        return null;
    }

    private boolean clickText(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        Rect bounds = findTextBounds(target);
        if (bounds != null) return animateCursorAndTap(bounds.exactCenterX(), bounds.exactCenterY());
        return ScreenShareService.tapRecognizedText(target.trim());
    }

    private boolean gestureOnText(String target, long duration, boolean doubleTap) {
        Rect bounds = findTextBounds(target);
        if (bounds == null) return false;
        final float x = bounds.exactCenterX();
        final float y = bounds.exactCenterY();
        showCursorAt(x, y);
        if (doubleTap) {
            if (!tapAt(x, y)) return false;
            main.postDelayed(new Runnable(){ @Override public void run(){ tapAt(x, y); pulseCursor(); }}, 120L);
            return true;
        }
        return pressAt(x, y, duration);
    }

    private boolean typeIntoFocusedField(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null || !focused.isEditable()) return false;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable e) { return false; }
    }

    private boolean swipeDirection(String direction) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = dm.widthPixels / 2f;
        float cy = dm.heightPixels / 2f;
        float dx = dm.widthPixels * 0.32f;
        float dy = dm.heightPixels * 0.30f;
        String d = direction.toLowerCase(Locale.ROOT);
        if (d.contains("up") || d.contains("מעלה")) return swipe(cx, cy + dy, cx, cy - dy, 360);
        if (d.contains("down") || d.contains("מטה")) return swipe(cx, cy - dy, cx, cy + dy, 360);
        if (d.contains("left") || d.contains("שמאלה")) return swipe(cx + dx, cy, cx - dx, cy, 360);
        if (d.contains("right") || d.contains("ימינה")) return swipe(cx - dx, cy, cx + dx, cy, 360);
        return false;
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
            return dispatchGesture(g, null, null);
        } catch (Throwable e) { return false; }
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
                final int steps = 18;
                final long stepMs = 16L;
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

    private void showCursorAt(float x, float y) {
        ensureCursor();
        if (cursorView != null) {
            cursorX = x; cursorY = y;
            cursorView.setVisibility(View.VISIBLE);
            moveCursorWindow(x, y);
        }
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
                    dp(30), dp(30), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.START;
            cursorView = dot;
            cursorWindowManager.addView(cursorView, cursorParams);
            cursorView.setVisibility(View.GONE);
        } catch (Throwable e) {
            cursorView = null; cursorParams = null;
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
        cursorView.animate().scaleX(1.55f).scaleY(1.55f).alpha(0.55f).setDuration(80).withEndAction(new Runnable() {
            @Override public void run() {
                if (cursorView == null) return;
                cursorView.animate().scaleX(1f).scaleY(1f).alpha(0.96f).setDuration(100).withEndAction(new Runnable() {
                    @Override public void run() {
                        main.postDelayed(new Runnable() {
                            @Override public void run() { if (cursorView != null && !cursorAnimating) cursorView.setVisibility(View.GONE); }
                        }, 320);
                    }
                });
            }
        });
    }

    private void removeCursor() {
        try { if (cursorView != null && cursorWindowManager != null) cursorWindowManager.removeView(cursorView); } catch (Throwable ignored) {}
        cursorView = null; cursorParams = null;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private boolean tapAt(float x, float y) { return pressAt(x, y, 70L); }

    private boolean pressAt(float x, float y, long duration) {
        if (x < 0 || y < 0) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable e) { return false; }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 2000) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(" | ");
        if (desc != null && desc.length() > 0 && (text == null || !desc.toString().equals(text.toString()))) out.append(desc).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    private String result(String action, String message) {
        logAction(action);
        return message;
    }

    private void logAction(String action) {
        try {
            String old = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACTION_LOG, "");
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String next = stamp + "  " + action + "\n" + old;
            if (next.length() > 5000) next = next.substring(0, 5000);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACTION_LOG, next).apply();
        } catch (Throwable ignored) {}
    }
}
