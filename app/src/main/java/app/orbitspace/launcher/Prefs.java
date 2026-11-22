package app.orbitspace.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * All settings live in one small SharedPreferences file.
 *
 * Writes use commit() rather than apply(): guest apps run in separate processes
 * that read this file once when they start, so a change has to be on disk before
 * the next launch.
 */
final class Prefs {

    private static final String FILE = "orbit_prefs";

    static final String KEY_BOOST = "gm_boost";
    static final String KEY_KEEP_SCREEN_ON = "gm_keep_screen_on";
    static final String KEY_MAX_REFRESH = "gm_max_refresh";
    static final String KEY_SUSTAINED = "gm_sustained";
    static final String KEY_DAEMON = "engine_daemon";
    static final String KEY_ASKED_STORAGE = "asked_storage";

    private static final String KEY_SPACE = "current_space";
    private static final String GAME_PREFIX = "game:";
    private static final String ATTACH_PREFIX = "attach:";

    private Prefs() {
    }

    /** Default value for each switch shown in Settings. */
    static boolean defaultOf(String key) {
        return !KEY_SUSTAINED.equals(key) && !KEY_ASKED_STORAGE.equals(key);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean get(Context c, String key) {
        return sp(c).getBoolean(key, defaultOf(key));
    }

    static void set(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).commit();
    }

    static int space(Context c) {
        return sp(c).getInt(KEY_SPACE, 0);
    }

    static void setSpace(Context c, int userId) {
        sp(c).edit().putInt(KEY_SPACE, userId).commit();
    }

    static boolean hasGameFlag(Context c, String pkg) {
        return sp(c).contains(GAME_PREFIX + pkg);
    }

    static boolean isGame(Context c, String pkg) {
        return sp(c).getBoolean(GAME_PREFIX + pkg, false);
    }

    static void setGame(Context c, String pkg, boolean game) {
        sp(c).edit().putBoolean(GAME_PREFIX + pkg, game).commit();
    }

    /** Whether other apps in the spaces may attach to this app's processes. Off unless set. */
    static boolean isAttachable(Context c, String pkg) {
        return sp(c).getBoolean(ATTACH_PREFIX + pkg, false);
    }

    static void setAttachable(Context c, String pkg, boolean attachable) {
        sp(c).edit().putBoolean(ATTACH_PREFIX + pkg, attachable).commit();
    }

    /** Read-only copy of the settings, taken once per process at startup. */
    static final class Snapshot {
        final boolean boost;
        final boolean keepScreenOn;
        final boolean maxRefresh;
        final boolean sustained;
        final boolean daemon;
        private final Set<String> games;
        private final Set<String> attachable;

        Snapshot(boolean boost, boolean keepScreenOn, boolean maxRefresh,
                 boolean sustained, boolean daemon, Set<String> games, Set<String> attachable) {
            this.boost = boost;
            this.keepScreenOn = keepScreenOn;
            this.maxRefresh = maxRefresh;
            this.sustained = sustained;
            this.daemon = daemon;
            this.games = games;
            this.attachable = attachable;
        }

        boolean isGame(String pkg) {
            return pkg != null && games.contains(pkg);
        }

        boolean isAttachable(String pkg) {
            return pkg != null && attachable.contains(pkg);
        }

        static Snapshot defaults() {
            return new Snapshot(defaultOf(KEY_BOOST), defaultOf(KEY_KEEP_SCREEN_ON),
                    defaultOf(KEY_MAX_REFRESH), defaultOf(KEY_SUSTAINED),
                    defaultOf(KEY_DAEMON), new HashSet<String>(), new HashSet<String>());
        }
    }

    static Snapshot snapshot(Context c) {
        SharedPreferences sp = sp(c);
        Set<String> games = new HashSet<>();
        Set<String> attachable = new HashSet<>();
        // getAll() blocks until the file has been read from disk.
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String key = e.getKey();
            if (key == null || !Boolean.TRUE.equals(e.getValue())) {
                continue;
            }
            if (key.startsWith(GAME_PREFIX)) {
                games.add(key.substring(GAME_PREFIX.length()));
            } else if (key.startsWith(ATTACH_PREFIX)) {
                attachable.add(key.substring(ATTACH_PREFIX.length()));
            }
        }
        return new Snapshot(
                sp.getBoolean(KEY_BOOST, defaultOf(KEY_BOOST)),
                sp.getBoolean(KEY_KEEP_SCREEN_ON, defaultOf(KEY_KEEP_SCREEN_ON)),
                sp.getBoolean(KEY_MAX_REFRESH, defaultOf(KEY_MAX_REFRESH)),
                sp.getBoolean(KEY_SUSTAINED, defaultOf(KEY_SUSTAINED)),
                sp.getBoolean(KEY_DAEMON, defaultOf(KEY_DAEMON)),
                games, attachable);
    }
}
