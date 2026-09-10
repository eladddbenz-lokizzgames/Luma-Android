package com.luma.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

public class LumaAccessibilityService extends AccessibilityService {
    private static volatile LumaAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isRunning() { return instance != null; }

    public static String executeCommandStatic(String command) {
        LumaAccessibilityService s = instance;
        if (s == null) return "Device Control is off. Open Luma's Control button and enable Luma in Android Accessibility settings first.";
        return s.executeCommand(command == null ? "" : command.trim());
    }

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
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

        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            int start = lower.startsWith("open ") ? 5 : 7;
            int split = firstPositive(lower.indexOf(" then click ", start), lower.indexOf(" and click ", start), lower.indexOf(" click ", start), lower.indexOf(" then tap ", start), lower.indexOf(" and tap ", start), lower.indexOf(" tap ", start));
            String appName = (split > 0 ? command.substring(start, split) : command.substring(start)).trim();
            boolean opened = openAppByLabel(appName);
            if (!opened) return "I couldn't find an installed app called " + appName + ".";
            if (split > 0) {
                String rest = command.substring(split).replaceFirst("(?i)^\\s*(then|and)?\\s*(click|tap)\\s+", "").replaceFirst("(?i)\\s+button$", "").trim();
                main.postDelayed(new Runnable(){ @Override public void run(){ clickText(rest); }}, 1600);
                return "Opening " + appName + " and then trying to tap " + rest + ".";
            }
            return "Opening " + appName + ".";
        }

        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) {
            String target = command.substring(command.indexOf(' ') + 1).replaceFirst("(?i)\\s+button$", "").trim();
            boolean ok = clickText(target);
            return ok ? "Tapped " + target + "." : "I couldn't find a clickable control named " + target + ". Some games hide their buttons from Android Accessibility.";
        }

        return "I can currently open apps, tap accessible buttons, go Back, and go Home. Try: open Brawl Stars then click Brawlers.";
    }

    private int firstPositive(int... values) {
        int best = -1;
        for (int v : values) if (v >= 0 && (best < 0 || v < best)) best = v;
        return best;
    }

    private boolean openAppByLabel(String wanted) {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN, null); query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        String needle = wanted.toLowerCase(Locale.US).replace(" app", "").trim();
        ResolveInfo best = null;
        for (ResolveInfo ri : apps) {
            CharSequence labelCs = ri.loadLabel(pm);
            String label = labelCs == null ? "" : labelCs.toString().toLowerCase(Locale.US);
            if (label.equals(needle)) { best = ri; break; }
            if (best == null && (label.contains(needle) || needle.contains(label))) best = ri;
        }
        if (best == null || best.activityInfo == null) return false;
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN); launch.addCategory(Intent.CATEGORY_LAUNCHER); launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); return true; } catch (Throwable e) { return false; }
    }

    private boolean clickText(String target) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || target == null || target.trim().isEmpty()) return false;
        String t = target.trim();
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(t);
        if (found == null || found.isEmpty()) return false;
        for (AccessibilityNodeInfo node : found) {
            AccessibilityNodeInfo cur = node;
            for (int depth = 0; cur != null && depth < 5; depth++) {
                if (cur.isClickable() && cur.isEnabled()) {
                    try { return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Throwable ignored) {}
                }
                cur = cur.getParent();
            }
        }
        return false;
    }
}
