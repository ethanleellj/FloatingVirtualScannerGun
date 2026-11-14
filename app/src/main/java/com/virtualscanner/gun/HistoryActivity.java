package com.virtualscanner.gun;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 扫码历史：搜索、复制、单条删除、清空。
 *
 * 内容只存本机 SQLite，上限 500 条，不联网。点击条目即复制到剪贴板，
 * 因为在本应用里"注入"只会打到本应用自己的输入框。
 */
public class HistoryActivity extends AppCompatActivity {

    private static final int LIMIT = 500;

    private final List<ScanHistory.Entry> data = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView tvCount;
    private final SimpleDateFormat format =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        Ui.edgeToEdge(this);

        tvCount = findViewById(R.id.tvCount);
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
                return data.get(position).id;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_row, parent, false);
                ScanHistory.Entry e = data.get(position);
                ((TextView) view.findViewById(R.id.tvRowTitle)).setText(e.content);
                ((TextView) view.findViewById(R.id.tvRowSub)).setText(getString(
                        R.string.history_meta,
                        format.format(new Date(e.ts)),
                        InjectRouter.channelName(HistoryActivity.this, e.channel),
                        e.ok ? getString(R.string.state_ok) : getString(R.string.state_failed)));
                return view;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            copy(data.get(position).content);
            Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            ScanHistory.Entry e = data.get(position);
            new AlertDialog.Builder(this)
                    .setTitle(e.content)
                    .setItems(new String[]{getString(R.string.history_delete)},
                            (dialog, which) -> {
                                ScanHistory.get(this).delete(e.id);
                                reload();
                            })
                    .show();
            return true;
        });

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                reload();
            }
        });

        ((Button) findViewById(R.id.btnPrimary)).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.history_clear)
                        .setMessage(R.string.history_clear_confirm)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            ScanHistory.get(this).clear();
                            reload();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        EditText etSearch = findViewById(R.id.etSearch);
        String keyword = etSearch.getText().toString();
        data.clear();
        data.addAll(ScanHistory.get(this).query(keyword, LIMIT));
        adapter.notifyDataSetChanged();
        tvCount.setText(getString(R.string.history_count, data.size(), LIMIT));
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
        }
    }
}
