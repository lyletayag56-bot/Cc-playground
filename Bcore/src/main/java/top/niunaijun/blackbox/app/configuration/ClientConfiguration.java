package top.niunaijun.blackbox.app.configuration;

import java.io.File;


public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }



    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return true;
    }

    
    public boolean isUseVpnNetwork() {
        return false;
    }

    public boolean isDisableFlagSecure() {
        return false;
    }

    
    public boolean requestInstallPackage(File file, int userId) {
        return false;
    }

    
    // Modified for Orbit Space: upstream returned a hard-coded chat id here, which made
    // the engine upload logcat and device details to a third-party server on every
    // crash. Returning null turns that upload off (see BlackBoxCore.sendLogs).
    public String getLogSenderChatId() {
        return null;
    }

    // Added for Orbit Space: upstream always copied the whole device logcat into
    // Downloads/logs while the app was open. It is now opt-in.
    public boolean isEnableLogcatCapture() {
        return false;
    }
}
