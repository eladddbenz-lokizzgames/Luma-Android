package com.luma.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
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
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    private String executeCommand(String command) {
        if (command.isEmpty()) return "Tell me what to open, tap, click, or press.";
        String lower = command.toLowerCase(Locale.US).replace("please ", "").trim();

        if (lower.equals("back") || lower.equals("go back") || lower.equals("press back")) {
            performGlobalAction(GLOBAL_ACTION_BACK); return "Going back.";
        }
        if (lower.equals("home") || lower.equals("go home")) {
            performGlobalAction(GLOBAL_ACTION_HOME); return "Opening Home.";
        }

        int openPos = firstPositiveWord(lower, "open ", "launch ");
        if (openPos >= 0) {
            String actionWord = lower.startsWith("launch ", openPos) ? "launch " : "open ";
            int start = openPos + actionWord.length();
            int split = firstPositive(
                    lower.indexOf(" then click ", start), lower.indexOf(" and click ", start), lower.indexOf(" click ", start),
                    lower.indexOf(" then tap ", start), lower.indexOf(" and tap ", start), lower.indexOf(" tap ", start));
            String appName = (split > 0 ? command.substring(start, split) : command.substring(start)).trim();
            appName = appName.replaceFirst("(?i)^the\\s+", "").replaceFirst("(?i)\\s+app$", "").trim();
            boolean opened = openAppByLabel(appName);
            if (!opened) return "I couldn't find an installed app called " + appName + ".";
            if (split > 0) {
                final String target = command.substring(split)
                        .replaceFirst("(?i)^\\s*(then|and)?\\s*(click|tap)\\s+", "")
                        .replaceFirst("(?i)\\s+button$", "").trim();
                scheduleClickRetries(target);
                return "Opening " + appName + " and looking for " + target + ".";
            }
            return "Opening " + appName + ".";
        }

        int tapPos = firstPositiveWord(lower, "click ", "tap ", "press ");
        if (tapPos >= 0) {
            int space = command.indexOf(' ', tapPos);
            String target = space >= 0 ? command.substring(space + 1).trim() : "";
            target = target.replaceFirst("(?i)\\s+button$", "").trim();
            boolean ok = clickText(target);
            return ok ? "Tapped " + target + "." : "I couldn't find " + target + " yet. If Live Screen is on, I'll also use screen OCR for the tap.";
        }

        return "Device Control is ready. Try: open Brawl Stars then click Brawlers.";
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
        String needle = wanted.toLowerCase(Locale.US).trim();
        ResolveInfo best = null;
        for (ResolveInfo ri : apps) {
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString().toLowerCase(Locale.US);
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
        final AtomicBoolean done = new AtomicBoolean(false);
        for (int i = 0; i < 7; i++) {
            final long delay = 1300L + i * 900L;
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
