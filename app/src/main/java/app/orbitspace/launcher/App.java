package app.orbitspace.launcher;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

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
    }
}
