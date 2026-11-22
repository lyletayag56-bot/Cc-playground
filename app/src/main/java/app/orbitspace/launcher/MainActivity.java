package app.orbitspace.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Home screen: the apps inside the current space. */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 41;
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_FREE_MEMORY = 2;
    private static final int MENU_GOOGLE = 3;

    private final List<Engine.VirtualApp> apps = new ArrayList<>();
    private final GridAdapter adapter = new GridAdapter();

    private GridView grid;
    private View empty;
    private View loading;
    private TextView subtitle;
    private TextView spaceChip;

    private int space;
    private boolean launching;
    private boolean retriedEmpty;
    private int loadToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        subtitle = findViewById(R.id.subtitle);
        spaceChip = findViewById(R.id.btn_space);

        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(apps.get(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showAppMenu(apps.get(position));
                return true;
            }
        });

        View.OnClickListener add = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppPickerActivity.class));
            }
        };
        findViewById(R.id.fab).setOnClickListener(add);
        findViewById(R.id.empty_add).setOnClickListener(add);

        spaceChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSpaces();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreMenu(v);
            }
        });

        askForStorageOnce();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launching = false;
        space = Prefs.space(this);
        reload();
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        final int token = ++loadToken;
        final int userId = space;
        spaceChip.setText(getString(R.string.space_n, userId + 1));
        if (apps.isEmpty()) {
            loading.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
        }
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                Engine.awaitReady();
                final List<Engine.VirtualApp> loaded = Engine.listApps(getApplicationContext(), userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken || isFinishing()) {
                            return;
                        }
                        show(loaded);
                    }
                });
            }
        });
    }

    private void show(List<Engine.VirtualApp> loaded) {
        // Right after a cold start the engine can answer before its service
        // process is up, which looks like an empty space. Ask once more.
        if (loaded.isEmpty() && !retriedEmpty) {
            retriedEmpty = true;
            Engine.UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        reload();
                    }
                }
            }, 1200);
            return;
        }
        apps.clear();
        apps.addAll(loaded);
        adapter.notifyDataSetChanged();
        loading.setVisibility(View.GONE);
        empty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);
        subtitle.setText(apps.isEmpty()
                ? getString(R.string.subtitle_empty)
                : getResources().getQuantityString(R.plurals.app_count, apps.size(), apps.size()));
    }

    // --------------------------------------------------------------- actions

    private void launch(final Engine.VirtualApp app) {
        if (launching) {
            return;
        }
        launching = true;
        final int userId = space;
        Toast.makeText(this, getString(R.string.starting, app.label), Toast.LENGTH_SHORT).show();
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Engine.launch(getApplicationContext(), app.packageName, userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        launching = false;
                        if (!ok && !isFinishing()) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.err_launch, app.label), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void showAppMenu(final Engine.VirtualApp app) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.action_open));
        actions.add(new Runnable() {
            @Override
            public void run() {
                launch(app);
            }
        });

        labels.add(getString(app.game ? R.string.action_game_off : R.string.action_game_on));
        actions.add(new Runnable() {
            @Override
            public void run() {
                Prefs.setGame(MainActivity.this, app.packageName, !app.game);
                Engine.IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Game mode is read when the app's process starts.
                        Engine.stop(app.packageName, space);
                    }
                });
                reload();
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            labels.add(getString(R.string.action_shortcut));
            actions.add(new Runnable() {
                @Override
                public void run() {
                    pinShortcut(app);
                }
            });
        }

        labels.add(getString(R.string.action_stop));
        actions.add(new Runnable() {
            @Override
            public void run() {
                runOnEngine(new Runnable() {
                    @Override
                    public void run() {
                        Engine.stop(app.packageName, space);
                    }
                }, getString(R.string.done_stopped, app.label));
            }
        });

        labels.add(getString(R.string.action_clear));
        actions.add(new Runnable() {
            @Override
            public void run() {
                confirm(getString(R.string.confirm_clear, app.label), new Runnable() {
                    @Override
                    public void run() {
                        runOnEngine(new Runnable() {
                            @Override
                            public void run() {
                                Engine.clearData(app.packageName, space);
                            }
                        }, getString(R.string.done_cleared, app.label));
                    }
                });
            }
        });

        labels.add(getString(R.string.action_remove));
        actions.add(new Runnable() {
            @Override
            public void run() {
                confirm(getString(R.string.confirm_remove, app.label), new Runnable() {
                    @Override
                    public void run() {
                        runOnEngine(new Runnable() {
                            @Override
                            public void run() {
                                Engine.remove(app.packageName, space);
                            }
                        }, getString(R.string.done_removed, app.label));
                    }
                });
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    /** Runs an engine call in the background, then refreshes the grid. */
    private void runOnEngine(final Runnable work, final String doneMessage) {
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                work.run();
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (doneMessage != null) {
                            Toast.makeText(MainActivity.this, doneMessage, Toast.LENGTH_SHORT).show();
                        }
                        retriedEmpty = true;
                        reload();
                    }
                });
            }
        });
    }

    private void confirm(String message, final Runnable onYes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                })
                .show();
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_FREE_MEMORY, 0, R.string.menu_free_memory);
        menu.getMenu().add(0, MENU_GOOGLE, 1, R.string.menu_google);
        menu.getMenu().add(0, MENU_SETTINGS, 2, R.string.menu_settings);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == MENU_SETTINGS) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;
                }
                if (item.getItemId() == MENU_FREE_MEMORY) {
                    runOnEngine(new Runnable() {
                        @Override
                        public void run() {
                            Engine.stopOthers(null, -1);
                        }
                    }, getString(R.string.done_freed));
                    return true;
                }
                if (item.getItemId() == MENU_GOOGLE) {
                    showGoogleServices();
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    // -------------------------------------------------------- Google services

    private void showGoogleServices() {
        final int userId = space;
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final boolean onPhone = Engine.phoneHasGoogleServices();
                final boolean inSpace = onPhone && Engine.hasGoogleServices(userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            showGoogleServicesDialog(userId, onPhone, inSpace);
                        }
                    }
                });
            }
        });
    }

    private void showGoogleServicesDialog(final int userId, boolean onPhone, boolean inSpace) {
        String spaceName = getString(R.string.space_n, userId + 1);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.menu_google);
        if (!onPhone) {
            dialog.setMessage(R.string.google_unavailable)
                    .setPositiveButton(android.R.string.ok, null);
        } else if (inSpace) {
            dialog.setMessage(getString(R.string.google_remove_body, spaceName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.google_remove, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            runOnEngine(new Runnable() {
                                @Override
                                public void run() {
                                    Engine.removeGoogleServices(userId);
                                }
                            }, getString(R.string.google_removed));
                        }
                    });
        } else {
            dialog.setMessage(getString(R.string.google_add_body, spaceName))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.google_add, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            addGoogleServices(userId);
                        }
                    });
        }
        dialog.show();
    }

    private void addGoogleServices(final int userId) {
        Toast.makeText(this, R.string.google_adding, Toast.LENGTH_SHORT).show();
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Engine.Result result = Engine.addGoogleServices(getApplicationContext(), userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (result.ok) {
                            Toast.makeText(MainActivity.this, R.string.google_added, Toast.LENGTH_SHORT).show();
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.google_failed)
                                    .setMessage(result.message != null && !result.message.isEmpty()
                                            ? result.message
                                            : getString(R.string.err_unknown))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        }
                        retriedEmpty = true;
                        reload();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- spaces

    private void showSpaces() {
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<Integer> ids = Engine.spaces();
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            showSpacesDialog(ids);
                        }
                    }
                });
            }
        });
    }

    private void showSpacesDialog(final List<Integer> ids) {
        final List<String> labels = new ArrayList<>();
        for (int id : ids) {
            String name = getString(R.string.space_n, id + 1);
            labels.add(id == space ? getString(R.string.space_current, name) : name);
        }
        labels.add(getString(R.string.space_new));
        final boolean canDelete = ids.size() > 1;
        if (canDelete) {
            labels.add(getString(R.string.space_delete, getString(R.string.space_n, space + 1)));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.spaces_title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < ids.size()) {
                            switchSpace(ids.get(which));
                        } else if (which == ids.size()) {
                            Engine.IO.execute(new Runnable() {
                                @Override
                                public void run() {
                                    final int created = Engine.createSpace();
                                    Engine.UI.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            switchSpace(created);
                                        }
                                    });
                                }
                            });
                        } else {
                            confirmDeleteSpace(ids);
                        }
                    }
                })
                .show();
    }

    private void confirmDeleteSpace(final List<Integer> ids) {
        final int doomed = space;
        confirm(getString(R.string.confirm_delete_space, getString(R.string.space_n, doomed + 1)),
                new Runnable() {
                    @Override
                    public void run() {
                        int fallback = 0;
                        for (int id : ids) {
                            if (id != doomed) {
                                fallback = id;
                                break;
                            }
                        }
                        final int next = fallback;
                        Engine.IO.execute(new Runnable() {
                            @Override
                            public void run() {
                                Engine.deleteSpace(doomed);
                                Engine.UI.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        switchSpace(next);
                                    }
                                });
                            }
                        });
                    }
                });
    }

    private void switchSpace(int userId) {
        if (isFinishing()) {
            return;
        }
        space = userId;
        Prefs.setSpace(this, userId);
        apps.clear();
        adapter.notifyDataSetChanged();
        retriedEmpty = true;
        reload();
    }

    // ------------------------------------------------------------- shortcuts

    private void pinShortcut(Engine.VirtualApp app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, R.string.err_shortcut, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, ShortcutActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ShortcutActivity.EXTRA_PACKAGE, app.packageName)
                .putExtra(ShortcutActivity.EXTRA_USER, space);
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(this, app.packageName + "@" + space)
                .setShortLabel(app.label)
                .setIntent(intent);
        Bitmap bitmap = toBitmap(app.icon);
        if (bitmap != null) {
            builder.setIcon(Icon.createWithBitmap(bitmap));
        }
        try {
            sm.requestPinShortcut(builder.build(), null);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.err_shortcut, Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap toBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        int size = (int) (48 * getResources().getDisplayMetrics().density * 2);
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable copy = drawable.getConstantState() != null
                    ? drawable.getConstantState().newDrawable().mutate()
                    : drawable;
            copy.setBounds(0, 0, size, size);
            copy.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------- permissions

    private void askForStorageOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Prefs.get(this, Prefs.KEY_ASKED_STORAGE)) {
            return;
        }
        Prefs.set(this, Prefs.KEY_ASKED_STORAGE, true);
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    // ------------------------------------------------------------------ grid

    private final class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            Engine.VirtualApp app = apps.get(position);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) view.findViewById(R.id.label)).setText(app.label);
            view.findViewById(R.id.badge).setVisibility(app.game ? View.VISIBLE : View.GONE);
            return view;
        }
    }
}
