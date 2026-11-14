package com.virtualscanner.gun;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * 摄像头枚举与选择。
 *
 * 一台手机通常有 1 个前置 + N 个后置（主摄 / 超广角 / 长焦 / 微距）。
 * CameraX 的 DEFAULT_BACK_CAMERA 只会挑系统认定的主摄，无法指定具体那颗，
 * 因此这里按 cameraId 精确筛选。
 */
public final class Cameras {

    /** 空串表示"系统默认后置"，用于兼容早期配置与无 ID 场景。 */
    public static final String DEFAULT = "";

    public static final class Choice {
        public final String id;
        public final int facing;
        public final float focalMm;
        public final String label;

        Choice(String id, int facing, float focalMm, String label) {
            this.id = id;
            this.facing = facing;
            this.focalMm = focalMm;
            this.label = label;
        }
    }

    private Cameras() {
    }

    public static List<Choice> list(Context ctx) {
        List<Choice> result = new ArrayList<>();
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return result;
        }

        String[] ids;
        try {
            ids = cm.getCameraIdList();
        } catch (Throwable t) {
            return result;
        }

        // 先收集原始信息，再按朝向计数决定是否追加序号
        List<String> rawIds = new ArrayList<>();
        List<Integer> rawFacings = new ArrayList<>();
        List<Float> rawFocals = new ArrayList<>();
        int frontCount = 0, backCount = 0, otherCount = 0;

        for (String id : ids) {
            CameraCharacteristics c;
            try {
                c = cm.getCameraCharacteristics(id);
            } catch (Throwable t) {
                continue;
            }
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            int facing = f == null ? CameraCharacteristics.LENS_FACING_EXTERNAL : f;
            float focal = 0f;
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals != null && focals.length > 0) {
                focal = focals[0];
            }
            rawIds.add(id);
            rawFacings.add(facing);
            rawFocals.add(focal);
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                frontCount++;
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                backCount++;
            } else {
                otherCount++;
            }
        }

        int frontIdx = 0, backIdx = 0, otherIdx = 0;
        for (int i = 0; i < rawIds.size(); i++) {
            String id = rawIds.get(i);
            int facing = rawFacings.get(i);
            float focal = rawFocals.get(i);

            int baseRes;
            boolean multi;
            int index;
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                baseRes = R.string.camera_front;
                index = ++frontIdx;
                multi = frontCount > 1;
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                baseRes = R.string.camera_back;
                index = ++backIdx;
                multi = backCount > 1;
            } else {
                baseRes = R.string.camera_external;
                index = ++otherIdx;
                multi = otherCount > 1;
            }

            String base = ctx.getString(baseRes);
            if (multi) {
                base = ctx.getString(R.string.camera_label_index, base, index);
            }
            String detail = focal > 0f
                    ? ctx.getString(R.string.camera_detail_focal, focal, id)
                    : ctx.getString(R.string.camera_detail_id, id);
            String label = ctx.getString(R.string.camera_label, base, detail);

            result.add(new Choice(id, facing, focal, label));
        }
        return result;
    }

    /** 当前选择对应的 label，找不到时返回第一个后置或第一个摄像头。 */
    public static Choice findSelected(Context ctx, List<Choice> all) {
        String id = Prefs.cameraId(ctx);
        if (all.isEmpty()) {
            return null;
        }
        for (Choice c : all) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        for (Choice c : all) {
            if (c.facing == CameraCharacteristics.LENS_FACING_BACK) {
                return c;
            }
        }
        return all.get(0);
    }

    /**
     * 生成只保留指定 cameraId 的选择器。
     * 若列表为空（摄像头不可用 / ID 失效）则回退到系统默认后置。
     */
    public static CameraSelector selector(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        CameraSelector.Builder builder = new CameraSelector.Builder();
        builder.addCameraFilter(infos -> {
            List<CameraInfo> keep = new ArrayList<>();
            for (CameraInfo info : infos) {
                try {
                    if (cameraId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                        keep.add(info);
                    }
                } catch (Throwable ignored) {
                    // 非 Camera2 实现（如部分模拟器）忽略
                }
            }
            return keep;
        });
        return builder.build();
    }
}
