package app.orbitspace.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Game-mode switches, engine options and a short status read-out. */
public class SettingsActivity extends Activity {

    private TextView filesStatus;
    private TextView overlayStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ViewGroup game = findViewById(R.id.section_game);
        addSwitch(game, R.string.set_boost, R.string.set_boost_sum, Prefs.KEY_BOOST);
        addSwitch(game, R.string.set_refresh, R.string.set_refresh_sum, Prefs.KEY_MAX_REFRESH);
        addSwitch(game, R.string.set_screen_on, R.string.set_screen_on_sum, Prefs.KEY_KEEP_SCREEN_ON);
        addSwitch(game, R.string.set_sustained, R.string.set_sustained_sum, Prefs.KEY_SUSTAINED);

        ViewGroup engine = findViewById(R.id.section_engine);
        addSwitch(engine, R.string.set_daemon, R.string.set_daemon_sum, Prefs.KEY_DAEMON);

        filesStatus = findViewById(R.id.files_status);
        findViewById(R.id.row_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Engine.requestAllFilesAccess(SettingsActivity.this);
            }
        });

        overlayStatus = findViewById(R.id.overlay_status);
        findViewById(R.id.row_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });

        ((TextView) findViewById(R.id.root_status)).setText(
                hasSuBinary() ? R.string.root_found : R.string.root_missing);

        ((TextView) findViewById(R.id.about_text)).setText(getString(R.string.about_text,
                versionName(), Engine.is64Bit() ? "64" : "32"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            filesStatus.setText(R.string.files_not_needed);
        } else {
            filesStatus.setText(Engine.hasAllFilesAccess() ? R.string.files_granted : R.string.files_missing);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            overlayStatus.setText(R.string.overlay_not_needed);
        } else {
            overlayStatus.setText(Settings.canDrawOverlays(this)
                    ? R.string.overlay_granted : R.string.overlay_missing);
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.overlay_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void addSwitch(ViewGroup parent, int title, int summary, final String key) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_switch, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.summary)).setText(summary);
        final Switch toggle = row.findViewById(R.id.toggle);
        toggle.setChecked(Prefs.get(this, key));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.set(SettingsActivity.this, key, isChecked);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle.toggle();
            }
        });
        parent.addView(row);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** True when a su binary is visible, which is how apps normally reach root. */
    private static boolean hasSuBinary() {
        String[] dirs = {
                "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/vendor/bin",
                "/system_ext/bin", "/product/bin", "/debug_ramdisk", "/data/adb/ksu/bin"
        };
        try {
            for (String dir : dirs) {
                if (new File(dir, "su").exists()) {
                    return true;
                }
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(":")) {
                    if (!dir.isEmpty() && new File(dir, "su").exists()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
