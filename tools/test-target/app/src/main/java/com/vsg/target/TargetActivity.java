package com.vsg.target;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 注入验证靶子。
 *
 * 只做一件事：把收到的文本、长度、回车次数显示在屏幕上，
 * 这样既可以用眼睛看，也可以用 uiautomator dump 精确读取，
 * 不需要依赖 Chrome 之类的重型应用。
 */
public class TargetActivity extends Activity {

    private EditText edit;
    private TextView log;
    private int enterCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(20));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("VSG Target");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Injection probe: the field below shows what it received.");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        root.addView(hint);

        edit = new EditText(this);
        edit.setHint("scan into here");
        edit.setTextSize(20);
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setId(1001);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(edit, lp);

        log = new TextView(this);
        log.setTextSize(15);
        log.setTextColor(Color.parseColor("#1A237E"));
        log.setPadding(0, dp(24), 0, 0);
        log.setText("text=[]\nlen=0\nenter=0");
        root.addView(log);

        // 模态弹窗探针：验证注入前的弹窗检测是否会拦住按键
        Button dialogBtn = new Button(this);
        dialogBtn.setText("Show dialog");
        dialogBtn.setId(1002);
        dialogBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Modal dialog")
                .setMessage("A modal window is covering the input field.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null)
                .show());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(24);
        root.addView(dialogBtn, btnLp);

        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh(s.toString());
            }
        });

        edit.setOnEditorActionListener((v, actionId, event) -> {
            enterCount++;
            refresh(edit.getText().toString());
            return true;
        });

        setContentView(root);
        edit.requestFocus();
    }

    private void refresh(String text) {
        String shown = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        log.setText("text=[" + shown + "]\nlen=" + text.length() + "\nenter=" + enterCount);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
