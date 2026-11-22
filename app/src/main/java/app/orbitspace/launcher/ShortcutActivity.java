package app.orbitspace.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity behind pinned home-screen shortcuts: starts one guest app, then closes. */
public class ShortcutActivity extends Activity {

    static final String EXTRA_PACKAGE = "pkg";
    static final String EXTRA_USER = "user";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String pkg = getIntent() != null ? getIntent().getStringExtra(EXTRA_PACKAGE) : null;
        final int userId = getIntent() != null ? getIntent().getIntExtra(EXTRA_USER, 0) : 0;
        if (pkg == null) {
            finish();
            return;
        }
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                Engine.awaitReady();
                final boolean ok = Engine.launchWithRetry(getApplicationContext(), pkg, userId, 4);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            Toast.makeText(getApplicationContext(),
                                    R.string.err_shortcut_gone, Toast.LENGTH_LONG).show();
                        }
                        finish();
                    }
                });
            }
        });
    }
}
