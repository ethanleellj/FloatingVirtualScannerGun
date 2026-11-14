package com.virtualscanner.gun;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置档管理：新建 / 重命名 / 复制 / 删除 / 设为当前。
 *
 * 每个配置档是一整套按键楔参数（前后缀、回车、通道、弹窗策略），
 * 换目标 App 时整体切换，不用逐项改。
 */
public class ProfilesActivity extends AppCompatActivity {

    /** SAF 请求码：导出到文件 / 从文件导入 */
    private static final int REQ_EXPORT = 3101;
    private static final int REQ_IMPORT = 3102;

    private final List<Profile> data = new ArrayList<>();
    private ListView listView;
    private BaseAdapter adapter;
    /** 当前档 id 缓存一份，避免每行都去查一遍存储 */
    private String currentId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);
        Ui.edgeToEdge(this);

        listView = findViewById(R.id.list);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int position) {
                return data.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_row, parent, false);
                Profile p = data.get(position);
                boolean current = p.id.equals(currentId);

                TextView title = view.findViewById(R.id.tvRowTitle);
                TextView sub = view.findViewById(R.id.tvRowSub);
                title.setText(current
                        ? "✓ " + Profiles.displayName(ProfilesActivity.this, p)
                        : Profiles.displayName(ProfilesActivity.this, p));
                sub.setText(getString(R.string.profile_summary,
                        p.prefix.isEmpty() ? "—" : p.prefix,
                        p.suffix.isEmpty() ? "—" : p.suffix,
                        p.enter ? getString(R.string.state_on) : getString(R.string.state_off),
                        InjectRouter.channelName(ProfilesActivity.this, p.channel)));
                return view;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Profile p = data.get(position);
            Profiles.setCurrent(this, p.id);
            reload();
            Toast.makeText(this, getString(R.string.profile_switched,
                    Profiles.displayName(this, p)), Toast.LENGTH_SHORT).show();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(data.get(position));
            return true;
        });

        ((Button) findViewById(R.id.btnPrimary)).setOnClickListener(v -> showNewDialog());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportBundle());
        findViewById(R.id.btnImport).setOnClickListener(v -> importBundle());
    }

    // ---------------- 导入 / 导出 ----------------

    /** 导出走系统「新建文件」：不需要存储权限，位置由用户自己选。 */
    private void exportBundle() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, ProfileBundle.suggestedName());
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.bundle_err_no_picker));
        }
    }

    /** 导入用「打开文件」；类型给 *\/* 是因为部分文件管理器把 .json 认成 octet-stream。 */
    private void importBundle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.bundle_err_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            writeBundle(uri);
        } else if (requestCode == REQ_IMPORT) {
            readBundle(uri);
        }
    }

    private void writeBundle(Uri uri) {
        final String json = ProfileBundle.export(this);
        new Thread(() -> {
            String error = null;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IOException("no output stream");
                }
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            }
            final String message = error == null
                    ? getString(R.string.bundle_exported)
                    : getString(R.string.bundle_err_io, error);
            runOnUiThread(() -> toast(message));
        }, "vsg-bundle-export").start();
    }

    private void readBundle(Uri uri) {
        new Thread(() -> {
            String text = null;
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("no input stream");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                text = out.toString("UTF-8");
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            }
            final String content = text;
            final String ioError = error;
            final ProfileBundle.Result result = ioError == null
                    ? ProfileBundle.importFrom(this, content) : null;
            runOnUiThread(() -> {
                if (ioError != null) {
                    toast(getString(R.string.bundle_err_io, ioError));
                    return;
                }
                if (!result.ok()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.profile_import)
                            .setMessage(result.error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                reload();
                toast(getString(R.string.bundle_imported,
                        result.profilesAdded, result.profilesUpdated, result.snippets));
            });
        }, "vsg-bundle-import").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(Profiles.all(this));
        currentId = Profiles.current(this).id;
        adapter.notifyDataSetChanged();
    }

    private void showNewDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        EditText etName = new EditText(this);
        etName.setHint(R.string.profile_name_hint);
        box.addView(etName);

        EditText etPrefix = new EditText(this);
        etPrefix.setHint(R.string.profile_prefix_hint);
        box.addView(etPrefix);

        EditText etSuffix = new EditText(this);
        etSuffix.setHint(R.string.profile_suffix_hint);
        box.addView(etSuffix);

        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_new)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Profile p = Profile.createDefault();
                    p.name = etName.getText().toString().trim();
                    p.prefix = etPrefix.getText().toString();
                    p.suffix = etSuffix.getText().toString();
                    Profiles.save(this, p);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showActions(Profile p) {
        final String[] items = {
                getString(R.string.profile_rename),
                getString(R.string.profile_copy),
                getString(R.string.profile_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(Profiles.displayName(this, p))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        rename(p);
                    } else if (which == 1) {
                        Profiles.duplicate(this, p);
                        reload();
                    } else {
                        if (Profiles.delete(this, p.id)) {
                            reload();
                        } else {
                            Toast.makeText(this, R.string.profile_cannot_delete,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void rename(Profile p) {
        EditText et = new EditText(this);
        et.setText(p.name);
        et.setHint(R.string.profile_name_hint);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_rename)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Profile copy = p.copy();
                    copy.name = et.getText().toString().trim();
                    Profiles.save(this, copy);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
