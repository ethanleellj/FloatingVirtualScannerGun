package com.virtualscanner.gun;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 从图库选一张图片，识别里面的条码/二维码。
 *
 * <p>这个 Activity 没有界面（透明主题），只做三件事：拉起系统选择器、解码识别、
 * 把结果交回 {@link OverlayService}。识别完立刻 {@code finish()}，让目标 App 重新
 * 拿到焦点之后再注入——否则 {@code ACTION_SET_TEXT} 会写进我们这个正在退出的窗口。
 *
 * <p>用系统 Photo Picker（Android 13+）或 SAF 选择器，都不需要申请读图权限。
 */
public class PickImageActivity extends Activity {

    private static final String TAG = "VSG-Gallery";
    private static final int REQ_PICK = 4101;
    /** 解码时最长边压到这个尺寸：ML Kit 不需要原始 4000px，压小能省几百毫秒 */
    private static final int MAX_EDGE = 2000;

    private boolean closed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            // 被系统重建（如旋转）时不再弹一次选择器，直接退出
            finish();
            return;
        }
        openPicker();
    }

    private void openPicker() {
        if (tryStart(pickerIntent())) {
            return;
        }
        // 少数 ROM 没装 Photo Picker：退到文件选择器，同样不需要权限
        Intent saf = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        if (tryStart(saf)) {
            return;
        }
        toast(R.string.image_pick_unavailable);
        finish();
    }

    private Intent pickerIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        }
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivityForResult(intent, REQ_PICK);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "picker unavailable: " + e);
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            finish();
            return;
        }
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            finish();  // 用户放弃选择
            return;
        }
        recognize(uri);
    }

    /** 收尾时通知服务把面板放回来；finish() 可能被调用多次，只发一次。 */
    @Override
    public void finish() {
        if (!closed) {
            closed = true;
            OverlayService.onPickClosed();
        }
        super.finish();
    }

    // ---------------- 识别 ----------------

    private void recognize(Uri uri) {
        final Bitmap bitmap = decode(uri);
        if (bitmap == null) {
            toast(R.string.image_open_failed);
            finish();
            return;
        }
        final BarcodeScanner scanner = BarcodeScanning.getClient();
        scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(list -> onCodes(list, bitmap.getWidth(), bitmap.getHeight()))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "process failed: " + e);
                    toast(R.string.image_open_failed);
                })
                .addOnCompleteListener(task -> {
                    try {
                        scanner.close();
                    } catch (Throwable ignored) {
                    }
                    bitmap.recycle();
                    // 成功路径已经自己 finish 过，这里兜住失败路径
                    if (!isFinishing()) {
                        finish();
                    }
                });
    }

    private void onCodes(List<Barcode> barcodes, int width, int height) {
        final int type = Prefs.codeType(this);
        List<BarcodePick.Candidate> candidates = new ArrayList<>();
        int total = barcodes == null ? 0 : barcodes.size();
        if (barcodes != null) {
            for (Barcode barcode : barcodes) {
                if (!CodeTypes.accepts(barcode.getFormat(), type)) {
                    continue;
                }
                Rect box = barcode.getBoundingBox();
                candidates.add(box == null
                        ? new BarcodePick.Candidate(barcode.getRawValue())
                        : new BarcodePick.Candidate(barcode.getRawValue(),
                        box.left, box.top, box.right, box.bottom));
            }
        }
        final int policy = Prefs.multiCodePolicy(this);
        // 整页扫描模式下，一张图里的码全都要：这正是"一页纸几十个序列号"的用法
        if (policy == BarcodePick.POLICY_ALL) {
            List<String> values = BarcodePick.pickAll(candidates, BarcodePick.MAX_ALL);
            Log.i(TAG, "image " + width + "x" + height + ": " + total + " code(s), type=" + type
                    + ", accepted=" + candidates.size() + ", batch=" + values.size() + " " + values);
            if (values.isEmpty()) {
                toast(total > 0 ? R.string.image_no_code_of_type : R.string.image_no_code);
                return;
            }
            OverlayService.deliverFromImage(values);
            return;
        }
        // 和实时扫码用同一套采信规则，行为不因"图片还是镜头"而改变
        String value = BarcodePick.pick(candidates, width, height, policy);
        Log.i(TAG, "image " + width + "x" + height + ": " + total + " code(s), type=" + type
                + ", accepted=" + candidates.size() + ", picked='" + value + "'");
        if (value == null) {
            toast(total > 0 ? R.string.image_no_code_of_type : R.string.image_no_code);
            return;
        }
        OverlayService.deliverFromImage(value);
    }

    private Bitmap decode(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int sample = Math.max(1, Math.max(w, h) / MAX_EDGE);
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample);
                    }
                    // ML Kit 需要能读像素的软件位图，硬件位图会直接抛异常
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Throwable t) {
                Log.w(TAG, "ImageDecoder failed, fallback to BitmapFactory: " + t);
            }
        }
        return decodeLegacy(uri);
    }

    /** API 26/27 以及 ImageDecoder 失败的兜底：按采样率解码，避免大图 OOM。 */
    private Bitmap decodeLegacy(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            // 只按 2 的幂降到"仍不小于上限"为止：降过头会把密集的条码抹掉
            while (max / (sample * 2) >= MAX_EDGE) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable t) {
            Log.w(TAG, "decode failed: " + t);
            return null;
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
