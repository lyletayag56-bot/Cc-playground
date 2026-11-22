package app.orbitspace.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/**
 * Runs inside guest processes. For apps marked as games it asks the system for
 * the things a game normally has to request itself, and which phones often only
 * give to games on their own allow-list: screen kept awake, the display's
 * fastest refresh rate, and (optionally) sustained performance mode.
 *
 * Nothing here may throw: an exception would crash the guest app.
 */
final class GameMode extends AppLifecycleCallback {

    private static final String TAG = "OrbitGameMode";

    private final Prefs.Snapshot settings;

    GameMode(Prefs.Snapshot settings) {
        this.settings = settings;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        try {
            apply(activity);
        } catch (Throwable t) {
            Log.w(TAG, "Could not apply game mode", t);
        }
    }

    private void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        String pkg = BlackBoxCore.getAppPackageName();
        if (pkg == null) {
            pkg = activity.getPackageName();
        }
        if (!settings.isGame(pkg)) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (settings.maxRefresh && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestFastestRefresh(activity, window);
        }
        if (settings.sustained && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(true);
        }
    }

    /** Picks the display mode with the highest refresh rate at the current resolution. */
    private static void requestFastestRefresh(Activity activity, Window window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Display display = activity.getWindowManager().getDefaultDisplay();
        if (display == null) {
            return;
        }
        Display.Mode current = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        if (current == null || modes == null) {
            return;
        }
        Display.Mode best = current;
        for (Display.Mode mode : modes) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && mode.getRefreshRate() > best.getRefreshRate() + 0.5f) {
                best = mode;
            }
        }
        if (best.getModeId() != current.getModeId()) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            window.setAttributes(lp);
        }
    }
}
