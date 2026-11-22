package app.orbitspace.launcher;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Starts the virtualization engine.
 *
 * Android creates this class in every process of the app: the launcher UI, the
 * engine's service process, and each process that hosts a guest app. The engine
 * works out which of those it is in and sets itself up accordingly.
 */
public class App extends Application {

    private static final String TAG = "OrbitSpace";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // Read settings before the engine starts redirecting file access.
        Prefs.Snapshot settings;
        try {
            settings = Prefs.snapshot(base);
        } catch (Throwable t) {
            Log.w(TAG, "Could not read settings, using defaults", t);
            settings = Prefs.Snapshot.defaults();
        }

        try {
            BlackBoxCore.get().doAttachBaseContext(base, new SpaceConfig(base.getPackageName(), settings.daemon));
            BlackBoxCore.get().addAppLifecycleCallback(new GuestHooks(settings));
        } catch (Throwable t) {
            Log.e(TAG, "Engine failed to attach", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            Log.e(TAG, "Engine failed to start", t);
        }
    }

    /** How this app configures the engine. */
    private static final class SpaceConfig extends ClientConfiguration {
        private final String hostPackage;
        private final boolean daemon;

        SpaceConfig(String hostPackage, boolean daemon) {
            this.hostPackage = hostPackage;
            this.daemon = daemon;
        }

        @Override
        public String getHostPackageName() {
            return hostPackage;
        }

        /** Foreground service that stops Android killing the engine mid-game. */
        @Override
        public boolean isEnableDaemonService() {
            return daemon;
        }

        /** Root is left visible, so guest apps on a rooted phone can ask for it. */
        @Override
        public boolean isHideRoot() {
            return false;
        }

        /** No crash-log upload. */
        @Override
        public String getLogSenderChatId() {
            return null;
        }

        @Override
        public boolean isEnableLogcatCapture() {
            return false;
        }

        /**
         * A guest app asked Android to install an APK (via an install intent).
         * Inside a space that request can't go to the real system installer: the
         * APK sits in the space's own storage, which the system can't read, and a
         * system install would land on the phone rather than in the space. So we
         * install it into the same space ourselves and consume the request.
         *
         * This is the normal path for any in-space installer. It runs off the
         * guest's main thread because copying the APK can take a moment.
         */
        @Override
        public boolean requestInstallPackage(final File file, final int userId) {
            if (file == null || !file.exists()) {
                return false;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    installIntoSpace(file, userId);
                }
            }, "orbit-guest-install").start();
            return true;
        }

        private void installIntoSpace(File file, int userId) {
            String message;
            try {
                InstallResult result = BlackBoxCore.get().installPackageAsUser(file, userId);
                if (result != null && result.success) {
                    message = "Installed into this space";
                } else {
                    String reason = result != null && result.msg != null ? result.msg : "unknown error";
                    message = "Install into the space failed: " + reason;
                    Log.w(TAG, "Guest-requested install failed: " + reason);
                }
            } catch (Throwable t) {
                message = "Install into the space failed";
                Log.e(TAG, "Guest-requested install threw", t);
            }
            toast(message);
        }

        private void toast(final String message) {
            try {
                final Context ctx = BlackBoxCore.getContext();
                if (ctx == null) {
                    return;
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                            // No usable UI context in this process; the log line is enough.
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }
}
