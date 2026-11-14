package com.virtualscanner.gun;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 常用指令管理。
 *
 * 这里只负责维护清单；真正的一键发送在悬浮窗面板上（「指令」按钮）——
 * 因为发送时目标 App 必须在前台，在主界面里点"发送"只会打进本应用自己。
 */
public class SnippetsActivity extends AppCompatActivity {

    private final List<Snippets.Snippet> data = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_snippets);
        Ui.edgeToEdge(this);

        ListView listView = findViewById(R.id.list);
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
                Snippets.Snippet s = data.get(position);
                ((TextView) view.findViewById(R.id.tvRowTitle)).setText(s.name);
                ((TextView) view.findViewById(R.id.tvRowSub)).setText(s.text);
                return view;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            copy(data.get(position).text);
            Toast.makeText(this, R.string.snippet_copied, Toast.LENGTH_SHORT).show();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(position);
            return true;
        });

        ((Button) findViewById(R.id.btnPrimary)).setOnClickListener(v -> edit(-1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(Snippets.all(this));
        adapter.notifyDataSetChanged();
    }

    private void showActions(int index) {
        final String[] items = {
                getString(R.string.snippet_edit),
                getString(R.string.snippet_copy),
                getString(R.string.snippet_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(data.get(index).name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        edit(index);
                    } else if (which == 1) {
                        copy(data.get(index).text);
                        Toast.makeText(this, R.string.snippet_copied, Toast.LENGTH_SHORT).show();
                    } else {
                        Snippets.delete(this, index);
                        reload();
                    }
                })
                .show();
    }

    private void edit(int index) {
        Snippets.Snippet existing = index >= 0 && index < data.size() ? data.get(index) : null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        EditText etName = new EditText(this);
        etName.setHint(R.string.snippet_name_hint);
        box.addView(etName);

        EditText etText = new EditText(this);
        etText.setHint(R.string.snippet_text_hint);
        etText.setMinLines(2);
        box.addView(etText);

        if (existing != null) {
            etName.setText(existing.name);
            etText.setText(existing.text);
        }

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.snippet_new : R.string.snippet_edit)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String text = etText.getText().toString();
                    if (name.isEmpty() || text.isEmpty()) {
                        Toast.makeText(this, R.string.toast_need_content,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existing != null && !existing.name.equalsIgnoreCase(name)) {
                        Snippets.delete(this, index);
                    }
                    Snippets.save(this, name, text);
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
        }
    }
}
