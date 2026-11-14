package com.virtualscanner.gun;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 使用说明：把「怎么做」放进 App，而不是只写在 README 里。
 *
 * <p>现场最常卡住的不是功能本身，而是「授权给了没有」「光标在不在输入框里」
 * 「扫了没反应该看哪一行」。所以这一页的顺序刻意是：先三步走通，再讲面板按钮，
 * 然后是排障顺序表，最后三节是权限说明、隐私政策、开源组件——上架审核要看的
 * 内容也放这里，「关于」页只留版本与联系方式。
 *
 * <p>正文按 {@link #SECTIONS} 里的资源顺序填充，标题与正文相邻成对；
 * 三语文案键集合必须保持一致（values / values-zh / values-b+zh+Hant）。
 */
public class HelpActivity extends AppCompatActivity {

    /** 标题、正文、标题、正文…… 顺序填充，改这里就够了，不用动布局。 */
    private static final int[] SECTIONS = {
            R.string.help_s1_title, R.string.help_s1_body,
            R.string.help_s2_title, R.string.help_s2_body,
            R.string.help_s3_title, R.string.help_s3_body,
            R.string.help_s4_title, R.string.help_s4_body,
            R.string.help_s5_title, R.string.help_s5_body,
            R.string.help_s6_title, R.string.help_s6_body,
            R.string.help_s7_title, R.string.help_s7_body,
            R.string.help_s8_title, R.string.help_s8_body,
            R.string.help_s9_title, R.string.help_s9_body,
            R.string.help_s10_title, R.string.help_s10_body,
            R.string.help_s11_title, R.string.help_s11_body,
            R.string.help_s12_title, R.string.help_s12_body,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);
        Ui.edgeToEdge(this);

        LinearLayout list = findViewById(R.id.helpList);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i + 1 < SECTIONS.length; i += 2) {
            View item = inflater.inflate(R.layout.item_help_section, list, false);
            ((TextView) item.findViewById(R.id.tvSectionTitle)).setText(SECTIONS[i]);
            ((TextView) item.findViewById(R.id.tvSectionBody)).setText(SECTIONS[i + 1]);
            list.addView(item);
        }
    }
}
