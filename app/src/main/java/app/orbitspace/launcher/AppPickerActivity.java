package app.orbitspace.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick an app installed on the phone, or an .apk file, to add to the current space. */
public class AppPickerActivity extends Activity {

    private static final int REQ_PICK_APK = 7;

    private final List<Engine.DeviceApp> all = new ArrayList<>();
    private final List<Engine.DeviceApp> shown = new ArrayList<>();
    private final ListAdapter adapter = new ListAdapter();

    private View loading;
    private View busy;
    private TextView busyText;
    private TextView emptyText;
    private int space;
    private String query = "";
    private boolean working;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        space = Prefs.space(this);

        loading = findViewById(R.id.loading);
        busy = findViewById(R.id.busy);
        busyText = findViewById(R.id.busy_text);
        emptyText = findViewById(R.id.empty_text);

        ((TextView) findViewById(R.id.subtitle)).setText(
                getString(R.string.picker_subtitle, getString(R.string.space_n, space + 1)));

        ListView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onPicked(shown.get(position));
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_apk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApkFile();
            }
        });

        ((EditText) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });

        load();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        final int userId = space;
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                Engine.awaitReady();
                final List<Engine.DeviceApp> loaded = Engine.listDeviceApps(getApplicationContext(), userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        all.clear();
                        all.addAll(loaded);
                        loading.setVisibility(View.GONE);
                        filter();
                    }
                });
            }
        });
    }

    private void filter() {
        shown.clear();
        for (Engine.DeviceApp app : all) {
            if (query.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(query)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        boolean nothing = shown.isEmpty() && loading.getVisibility() != View.VISIBLE;
        emptyText.setVisibility(nothing ? View.VISIBLE : View.GONE);
    }

    private void onPicked(final Engine.DeviceApp app) {
        if (working) {
            return;
        }
        if (app.added) {
            Toast.makeText(this, getString(R.string.already_added, app.label), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!app.supported) {
            new AlertDialog.Builder(this)
                    .setTitle(app.label)
                    .setMessage(getString(Engine.is64Bit() ? R.string.err_abi_32 : R.string.err_abi_64))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final int userId = space;
        setBusy(getString(R.string.adding, app.label));
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Engine.Result result =
                        Engine.cloneInstalled(getApplicationContext(), app.packageName, userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        onInstalled(result, app.label);
                        if (result.ok) {
                            app.added = true;
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        });
    }

    private void pickApkFile() {
        if (working) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.android.package-archive",
                        "application/octet-stream"
                });
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.err_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final int userId = space;
        setBusy(getString(R.string.installing_file));
        Engine.IO.execute(new Runnable() {
            @Override
            public void run() {
                final Engine.Result result = Engine.installApk(getApplicationContext(), uri, userId);
                Engine.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        onInstalled(result, result.packageName != null
                                ? result.packageName
                                : getString(R.string.the_apk));
                        if (result.ok && result.packageName != null) {
                            for (Engine.DeviceApp app : all) {
                                if (app.packageName.equals(result.packageName)) {
                                    app.added = true;
                                }
                            }
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        });
    }

    private void onInstalled(Engine.Result result, String name) {
        if (isFinishing()) {
            return;
        }
        clearBusy();
        if (result.ok) {
            Toast.makeText(this, getString(R.string.added, name), Toast.LENGTH_SHORT).show();
        } else {
            String reason = result.message != null && !result.message.isEmpty()
                    ? result.message
                    : getString(R.string.err_unknown);
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.err_add_title, name))
                    .setMessage(reason)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void setBusy(String message) {
        working = true;
        busyText.setText(message);
        busy.setVisibility(View.VISIBLE);
    }

    private void clearBusy() {
        working = false;
        busy.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (!working) {
            super.onBackPressed();
        }
    }

    private final class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : LayoutInflater.from(AppPickerActivity.this).inflate(R.layout.item_picker, parent, false);
            Engine.DeviceApp app = shown.get(position);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) view.findViewById(R.id.label)).setText(app.label);
            ((TextView) view.findViewById(R.id.detail)).setText(app.packageName);
            TextView state = view.findViewById(R.id.state);
            if (app.added) {
                state.setText(R.string.state_added);
                state.setEnabled(false);
            } else if (!app.supported) {
                state.setText(R.string.state_unsupported);
                state.setEnabled(false);
            } else {
                state.setText(R.string.state_add);
                state.setEnabled(true);
            }
            view.setAlpha(app.supported ? 1f : 0.5f);
            return view;
        }
    }
}
