package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Intercepts Home key and stock Google TV launcher window changes to bring
 * NLRC Home to the foreground seamlessly, without EVER disabling the stock launcher.
 *
 * This guarantees 100% fail-safety: Direct Boot, system services, and RescueParty
 * remain completely stable, eliminating any risk of boot loops or black screens.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKeyService";
    private static final String STOCK_LAUNCHER_PKG = "com.google.android.apps.tv.launcherx";
    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String PRIME_PKG = "com.amazon.amazonvideo.livingroom";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunchTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            if (pkgName != null) {
                if (STOCK_LAUNCHER_PKG.contentEquals(pkgName)) {
                    // Stock Google TV is trying to display its home screen. Intercept and show NLRC.
                    launchNlrcHome();
                } else if ((NETFLIX_PKG.contentEquals(pkgName) || PRIME_PKG.contentEquals(pkgName))
                        && HdmiHelper.isAvailable(this)) {
                    // Netflix or Prime Video opened (e.g. from dedicated remote hotkey)
                    // Intercept and redirect immediately to HDMI 2 (Jio TV Set Top Box)
                    Log.i(TAG, "Intercepted " + pkgName + " -> Redirecting to HDMI 2 (Jio TV)");
                    HdmiHelper.switchToHdmi2(this, true);
                }
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            Log.d(TAG, "Remote key pressed: " + keyCode);

            if (keyCode == KeyEvent.KEYCODE_HOME) {
                launchNlrcHome();
                return true; // Consume event so stock launcher isn't invoked
            }

            // Remote hotkeys for Netflix, Prime Video, or color buttons
            if ((keyCode == KeyEvent.KEYCODE_BUTTON_3     // Standard Netflix button
                    || keyCode == KeyEvent.KEYCODE_BUTTON_4  // Standard Prime Video button
                    || keyCode == KeyEvent.KEYCODE_PROG_RED  // Color Red
                    || keyCode == KeyEvent.KEYCODE_PROG_GREEN)   // Color Green
                    && HdmiHelper.isAvailable(this)) {
                Log.i(TAG, "Intercepted remote key " + keyCode + " -> Switching to HDMI 2 (Jio TV)");
                HdmiHelper.switchToHdmi2(this, true);
                return true; // Consume event so default app isn't launched
            }
        }
        return super.onKeyEvent(event);
    }

    private synchronized void launchNlrcHome() {
        long now = System.currentTimeMillis();
        // Debounce launches within 400ms to prevent duplicate intents
        if (now - lastLaunchTime < 400) {
            return;
        }
        lastLaunchTime = now;

        handler.post(() -> {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                              | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                              | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch NLRC Home", e);
            }
        });
    }

    @Override
    public void onInterrupt() {
        // Required callback for AccessibilityService
    }
}
