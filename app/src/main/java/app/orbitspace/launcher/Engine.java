package app.orbitspace.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.AbiUtils;

/**
 * The only class that talks to the virtualization engine.
 *
 * Every method here does a blocking call into the engine's service process, so
 * call them from {@link #IO}, never from the main thread.
 */
final class Engine {

    private static final String TAG = "OrbitEngine";

    /** Single worker thread: engine calls run one at a time, in order. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final Handler UI = new Handler(Looper.getMainLooper());

    private Engine() {
    }

    /** An app installed inside a space. */
    static final class VirtualApp {
        String packageName;
        String label;
        Drawable icon;
        boolean game;
        boolean attachable;
    }

    /** An app installed on the phone that could be cloned into a space. */
    static final class DeviceApp {
        String packageName;
        String label;
        Drawable icon;
        boolean added;
        boolean supported;
        boolean split;
    }

    /** Outcome of an install. */
    static final class Result {
        final boolean ok;
        final String packageName;
        final String message;

        Result(boolean ok, String packageName, String message) {
            this.ok = ok;
            this.packageName = packageName;
            this.message = message;
        }
    }

    private static BlackBoxCore core() {
        return BlackBoxCore.get();
    }

    /** Makes the engine connect to its service process; callers carry on either way. */
    static void awaitReady() {
        try {
            core().areServicesAvailable();
        } catch (Throwable t) {
            Log.w(TAG, "Engine not ready yet", t);
        }
    }

    // ------------------------------------------------------------------ apps

    static List<VirtualApp> listApps(Context ctx, int userId) {
        List<VirtualApp> out = new ArrayList<>();
        List<ApplicationInfo> infos = null;
        for (int attempt = 0; attempt < 3 && infos == null; attempt++) {
            try {
                infos = core().getInstalledApplications(0, userId);
            } catch (Throwable t) {
                Log.w(TAG, "listApps attempt " + attempt + " failed", t);
            }
            if (infos == null) {
                sleep(250);
            }
        }
        if (infos == null) {
            return out;
        }
        PackageManager pm = ctx.getPackageManager();
        for (ApplicationInfo ai : infos) {
            if (ai == null || ai.packageName == null) {
                continue;
            }
            if (GmsCore.isGoogleService(ai.packageName)) {
                // Background services with nothing to open.
                continue;
            }
            VirtualApp app = new VirtualApp();
            app.packageName = ai.packageName;
            app.label = loadLabel(pm, ai);
            app.icon = loadIcon(pm, ai);
            if (!Prefs.hasGameFlag(ctx, ai.packageName)) {
                Prefs.setGame(ctx, ai.packageName, looksLikeGame(ai));
            }
            app.game = Prefs.isGame(ctx, ai.packageName);
            app.attachable = Prefs.isAttachable(ctx, ai.packageName);
            out.add(app);
        }
        Collections.sort(out, new Comparator<VirtualApp>() {
            @Override
            public int compare(VirtualApp a, VirtualApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /** User-installed, launchable apps on the phone. */
    static List<DeviceApp> listDeviceApps(Context ctx, int userId) {
        List<DeviceApp> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> infos;
        try {
            infos = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            Log.w(TAG, "Could not list device apps", t);
            return out;
        }
        // One engine call for the whole list instead of one per app.
        Set<String> inSpace = new HashSet<>();
        try {
            List<ApplicationInfo> virtual = core().getInstalledApplications(0, userId);
            if (virtual != null) {
                for (ApplicationInfo ai : virtual) {
                    if (ai != null && ai.packageName != null) {
                        inSpace.add(ai.packageName);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read the space's app list", t);
        }
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : infos) {
            if (ai == null || ai.packageName == null || ai.sourceDir == null) {
                continue;
            }
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(self)) {
                continue;
            }
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue;
            }
            DeviceApp app = new DeviceApp();
            app.packageName = ai.packageName;
            app.label = loadLabel(pm, ai);
            app.icon = loadIcon(pm, ai);
            app.split = ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0;
            try {
                app.supported = AbiUtils.isSupport(new File(ai.sourceDir));
            } catch (Throwable t) {
                app.supported = true;
            }
            app.added = inSpace.contains(ai.packageName);
            out.add(app);
        }
        Collections.sort(out, new Comparator<DeviceApp>() {
            @Override
            public int compare(DeviceApp a, DeviceApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /**
     * Clones an app that is installed on the phone. The space keeps using the
     * phone's copy of the APK files (including split APKs), so the original has
     * to stay installed.
     */
    static Result cloneInstalled(Context ctx, String pkg, int userId) {
        try {
            return toResult(ctx, core().installPackageAsUser(pkg, userId), pkg);
        } catch (Throwable t) {
            Log.e(TAG, "clone failed: " + pkg, t);
            return new Result(false, pkg, String.valueOf(t.getMessage()));
        }
    }

    /** Installs a single .apk picked by the user. The engine keeps its own copy. */
    static Result installApk(Context ctx, Uri uri, int userId) {
        File tmp = new File(ctx.getCacheDir(), "install-" + System.currentTimeMillis() + ".apk");
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return new Result(false, null, ctx.getString(R.string.err_read_file));
            }
            try {
                OutputStream os = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            if (ctx.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0) == null) {
                return new Result(false, null, ctx.getString(R.string.err_not_apk));
            }
            return toResult(ctx, core().installPackageAsUser(tmp, userId), null);
        } catch (Throwable t) {
            Log.e(TAG, "installApk failed", t);
            return new Result(false, null, String.valueOf(t.getMessage()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Result toResult(Context ctx, InstallResult r, String fallbackPkg) {
        if (r == null) {
            return new Result(false, fallbackPkg, ctx.getString(R.string.err_no_answer));
        }
        String pkg = r.packageName != null ? r.packageName : fallbackPkg;
        return new Result(r.success, pkg, r.msg);
    }

    /** Starts a guest app. Frees memory first when it is a game and that option is on. */
    static boolean launch(Context ctx, String pkg, int userId) {
        try {
            if (Prefs.isGame(ctx, pkg) && Prefs.get(ctx, Prefs.KEY_BOOST)) {
                stopOthers(pkg, userId);
            }
            return core().launchApk(pkg, userId);
        } catch (Throwable t) {
            Log.e(TAG, "launch failed: " + pkg, t);
            return false;
        }
    }

    /**
     * For cold starts (home-screen shortcuts): the engine's service process may
     * still be coming up, so a first attempt can fail for no lasting reason.
     */
    static boolean launchWithRetry(Context ctx, String pkg, int userId, int attempts) {
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                sleep(700);
            }
            if (launch(ctx, pkg, userId)) {
                return true;
            }
        }
        return false;
    }

    /** Stops every guest app in every space except the one given (pass null to stop all). */
    static int stopOthers(String keepPkg, int keepUserId) {
        int stopped = 0;
        for (int userId : spaces()) {
            List<ApplicationInfo> infos;
            try {
                infos = core().getInstalledApplications(0, userId);
            } catch (Throwable t) {
                continue;
            }
            if (infos == null) {
                continue;
            }
            for (ApplicationInfo ai : infos) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                if (userId == keepUserId && ai.packageName.equals(keepPkg)) {
                    continue;
                }
                if (keepPkg != null && GmsCore.isGoogleAppOrService(ai.packageName)) {
                    // The game being started may be about to sign in through these.
                    continue;
                }
                stop(ai.packageName, userId);
                stopped++;
            }
        }
        return stopped;
    }

    static void stop(String pkg, int userId) {
        try {
            core().stopPackage(pkg, userId);
        } catch (Throwable t) {
            Log.w(TAG, "stop failed: " + pkg, t);
        }
    }

    static void clearData(String pkg, int userId) {
        try {
            core().stopPackage(pkg, userId);
            core().clearPackage(pkg, userId);
        } catch (Throwable t) {
            Log.w(TAG, "clear failed: " + pkg, t);
        }
    }

    static void remove(String pkg, int userId) {
        try {
            core().stopPackage(pkg, userId);
            core().uninstallPackageAsUser(pkg, userId);
        } catch (Throwable t) {
            Log.w(TAG, "remove failed: " + pkg, t);
        }
    }

    // ---------------------------------------------------------------- spaces

    /** Ids of the existing spaces, ascending. Space 0 always exists. */
    static List<Integer> spaces() {
        List<Integer> ids = new ArrayList<>();
        try {
            List<BUserInfo> users = core().getUsers();
            if (users != null) {
                for (BUserInfo u : users) {
                    if (u != null && u.id >= 0 && !ids.contains(u.id)) {
                        ids.add(u.id);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not list spaces", t);
        }
        if (!ids.contains(0)) {
            ids.add(0);
        }
        Collections.sort(ids);
        return ids;
    }

    static int createSpace() {
        List<Integer> ids = spaces();
        int next = ids.get(ids.size() - 1) + 1;
        try {
            core().createUser(next);
        } catch (Throwable t) {
            Log.w(TAG, "Could not create space " + next, t);
        }
        return next;
    }

    static void deleteSpace(int userId) {
        try {
            List<ApplicationInfo> infos = core().getInstalledApplications(0, userId);
            if (infos != null) {
                for (ApplicationInfo ai : infos) {
                    if (ai != null && ai.packageName != null) {
                        stop(ai.packageName, userId);
                    }
                }
            }
            core().deleteUser(userId);
        } catch (Throwable t) {
            Log.w(TAG, "Could not delete space " + userId, t);
        }
    }

    // -------------------------------------------------------- Google services

    /** True when the phone itself has Google Play services to clone. */
    static boolean phoneHasGoogleServices() {
        try {
            return core().isSupportGms();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasGoogleServices(int userId) {
        try {
            return core().isInstallGms(userId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clones Play services, Play Store and Play Games from the phone into a space. */
    static Result addGoogleServices(Context ctx, int userId) {
        try {
            return toResult(ctx, core().installGms(userId), null);
        } catch (Throwable t) {
            Log.e(TAG, "addGoogleServices failed", t);
            return new Result(false, null, String.valueOf(t.getMessage()));
        }
    }

    static void removeGoogleServices(int userId) {
        try {
            core().uninstallGms(userId);
        } catch (Throwable t) {
            Log.w(TAG, "removeGoogleServices failed", t);
        }
    }

    // ----------------------------------------------------------- permissions

    static boolean hasAllFilesAccess() {
        try {
            return core().hasAllFilesAccess();
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestAllFilesAccess(android.app.Activity activity) {
        try {
            core().requestAllFilesAccess(activity);
        } catch (Throwable t) {
            Log.w(TAG, "Could not open all-files access settings", t);
        }
    }

    static boolean is64Bit() {
        try {
            return BlackBoxCore.is64Bit();
        } catch (Throwable t) {
            return true;
        }
    }

    // --------------------------------------------------------------- helpers

    private static boolean looksLikeGame(ApplicationInfo ai) {
        if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && ai.category == ApplicationInfo.CATEGORY_GAME;
    }

    private static String loadLabel(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return ai.packageName;
    }

    private static Drawable loadIcon(PackageManager pm, ApplicationInfo ai) {
        try {
            return pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            return pm.getDefaultActivityIcon();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
