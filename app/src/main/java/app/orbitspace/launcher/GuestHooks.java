package app.orbitspace.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;

/**
 * Runs inside guest processes.
 *
 * Game mode: for apps marked as games it asks the system for the things a game
 * normally has to request itself, and which phones often only give to games on
 * their own allow-list: screen kept awake, the display's fastest refresh rate,
 * and (optionally) sustained performance mode.
 *
 * Attaching: for apps the user has opened up, it lets other processes of this
 * app (that is, other apps in the spaces) attach to the process.
 *
 * Nothing here may throw: an exception would crash the guest app.
 */
final class GuestHooks extends AppLifecycleCallback {

    private static final String TAG = "OrbitGuest";

    /** prctl option from the Yama security module; not in OsConstants. */
    private static final int PR_SET_PTRACER = 0x59616d61;
    private static final long PR_SET_PTRACER_ANY = -1L;

    private final Prefs.Snapshot settings;

    GuestHooks(Prefs.Snapshot settings) {
        this.settings = settings;
    }

    /** Called in each guest process before the guest's Application object exists. */
    @Override
    public void beforeCreateApplication(String packageName, String processName, Context context, int userId) {
        try {
            if (settings.isAttachable(packageName)) {
                allowAttach();
                Log.i(TAG, "Attaching allowed for " + processName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not allow attaching for " + processName, t);
        }
    }

    /**
     * Android starts app processes "non-dumpable", which stops every other
     * process, even one with the same user id, from attaching to them or reading
     * /proc/PID/mem. Debuggable apps are the exception: the system makes these
     * same two calls for them at startup. Doing it here gives one chosen guest
     * the same openness without making the whole app debuggable, which would
     * slow everything down.
     */
    private static void allowAttach() throws ErrnoException {
        Os.prctl(OsConstants.PR_SET_DUMPABLE, 1, 0, 0, 0);
        try {
            Os.prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);
        } catch (ErrnoException e) {
            // Kernels built without Yama reject this option; nothing more is needed there.
        }
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
