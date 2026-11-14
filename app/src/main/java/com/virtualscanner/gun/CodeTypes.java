package com.virtualscanner.gun;

import com.google.mlkit.vision.barcode.common.Barcode;

/**
 * 码制过滤：设置里可选「一维条码 / 二维码 / 全部」，默认全部。
 *
 * <p>过滤放在<b>结果层</b>而不是识别层：识别器始终按全格式创建，命中后按这里的规则取舍。
 * 这样改设置不需要重建 ML Kit 客户端——正在出帧时关闭旧 client 容易抛异常，
 * 而且 {@code BarcodeScannerOptions} 一旦定型就只能重建，代价远大于一次 int 比较。
 *
 * <p>"二维码"在业务上通常被当成所有二维码的统称，所以这里把 QR、Data Matrix、Aztec、
 * PDF417 都归入 2D；一维码是最常见的商品条码与内部编码那一组。
 */
public final class CodeTypes {

    /** 全部（默认） */
    public static final int ALL = 0;
    /** 只认一维条码：EAN/UPC/Code128/Code39/Code93/Codabar/ITF */
    public static final int LINEAR = 1;
    /** 只认二维码：QR / Data Matrix / Aztec / PDF417 */
    public static final int MATRIX = 2;

    private static final int[] LINEAR_FORMATS = {
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_ITF,
    };

    private static final int[] MATRIX_FORMATS = {
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_PDF417,
    };

    private CodeTypes() {
    }

    /** 越界或未知值一律当"全部"处理，避免旧数据把扫码关死。 */
    public static int normalize(int type) {
        return type == LINEAR || type == MATRIX ? type : ALL;
    }

    /** 该格式在当前设置下是否采信。 */
    public static boolean accepts(int format, int type) {
        switch (normalize(type)) {
            case LINEAR:
                return contains(LINEAR_FORMATS, format);
            case MATRIX:
                return contains(MATRIX_FORMATS, format);
            default:
                return true;
        }
    }

    /** 供日志/排障用，例如 {@code 6901236347102(EAN_13)}。 */
    public static String formatName(int format) {
        switch (format) {
            case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_EAN_8: return "EAN_8";
            case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E";
            case Barcode.FORMAT_CODE_128: return "CODE_128";
            case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_CODE_93: return "CODE_93";
            case Barcode.FORMAT_CODABAR: return "CODABAR";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_QR_CODE: return "QR_CODE";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX";
            case Barcode.FORMAT_AZTEC: return "AZTEC";
            case Barcode.FORMAT_PDF417: return "PDF417";
            default: return "FORMAT_" + format;
        }
    }

    private static boolean contains(int[] pool, int value) {
        for (int item : pool) {
            if (item == value) {
                return true;
            }
        }
        return false;
    }
}
