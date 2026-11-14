package com.virtualscanner.gun;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 项目说明：README 全文。
 *
 * <p>README 是这个项目最完整的一份文档，但只躺在仓库里——不碰代码的
 * 使用者（店员、装机人员）看不到。构建时把它拷进 assets（见 app/build.gradle
 * 的 syncReadme），这一页读出来展示，离线可看、永远和仓库同一份。
 *
 * <p>不做完整 markdown 渲染，只做几样最有用的样式化：标题加粗放大、
 * 表格行与代码块等宽、粗体/行内代码/链接就地转换。README 的表格以中文为主，
 * 等宽字体下对不齐也比对错强；追求像素级对齐就该换 WebView，不值当。
 */
public class ReadmeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_readme);
        Ui.edgeToEdge(this);

        TextView tv = findViewById(R.id.tvReadme);
        tv.setText(render(readAsset("readme.md")));
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            // 资产读取失败时把原因摆在页面上，好过空白页或崩溃
            return "#" + getString(R.string.readme_title) + "\n\n"
                    + getString(R.string.readme_load_failed);
        }
        return sb.toString();
    }

    /** 轻量 markdown 样式化：标题、表格、代码块、粗体、链接。 */
    private CharSequence render(String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean inCode = false;
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            int start = sb.length();

            if (inCode || line.startsWith("    ")) {
                sb.append(line).append('\n');
                span(sb, start, line.length(), new TypefaceSpan("monospace"));
            } else if (line.startsWith("|")) {
                // 表格行等宽且略小，列宽才不会被撑爆；单元格里的粗体/反引号照常转换
                sb.append(inline(sb, line)).append('\n');
                int len = sb.length() - start - 1;
                span(sb, start, len, new TypefaceSpan("monospace"));
                span(sb, start, len, new RelativeSizeSpan(0.85f));
            } else if (line.startsWith("#")) {
                String heading = line.replaceFirst("^#+\\s*", "");
                sb.append(inline(sb, heading)).append('\n');
                int len = sb.length() - start - 1;
                span(sb, start, len, new StyleSpan(Typeface.BOLD));
                span(sb, start, len, new RelativeSizeSpan(line.startsWith("# ") ? 1.3f : 1.12f));
            } else {
                sb.append(inline(sb, line)).append('\n');
            }
        }
        return sb;
    }

    /**
     * 行内元素：[文字](链接) 只留文字；反引号去掉；**粗体** 转成真正的粗体。
     * 没匹配上的星号、下划线原样保留——宁可显示原文，不做错的转换。
     */
    private String inline(SpannableStringBuilder sb, String s) {
        s = s.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1").replace("`", "");
        int i;
        while ((i = s.indexOf("**")) >= 0) {
            int j = s.indexOf("**", i + 2);
            if (j < 0) {
                break;
            }
            sb.append(s, 0, i);
            int start = sb.length();
            sb.append(s, i + 2, j);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s = s.substring(j + 2);
        }
        return s;
    }

    private void span(SpannableStringBuilder sb, int start, int len, Object o) {
        sb.setSpan(o, start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
