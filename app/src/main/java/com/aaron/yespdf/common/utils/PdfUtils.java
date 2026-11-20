package com.aaron.yespdf.common.utils;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.blankj.utilcode.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 用系统 PdfRenderer 做的轻量探测/封面工具。
 *
 * 与上游的差异（都是内存与句柄相关的修复）：
 * 1. 一律以 <b>MODE_READ_ONLY</b> 打开：上游用 MODE_READ_WRITE，对只读存储位置会直接
 *    SecurityException，且会无谓地请求写权限
 * 2. <b>关闭</b> ParcelFileDescriptor / PdfRenderer / Page：上游从不关闭，批量导入时
 *    会持续泄漏 fd，最终 EMFILE 表现为"某些 PDF 打不开"
 * 3. 封面按最长边 &lt;=512px 渲染并以 JPEG q80 保存，控制派生文件体积
 *
 * @author Aaron aaronzzxup@gmail.com
 */
public final class PdfUtils {

    /** 封面最长边（像素） */
    private static final int COVER_MAX_SIDE = 512;
    /** JPEG 质量 */
    private static final int COVER_QUALITY = 80;

    /**
     * 渲染指定页为缩略图（最长边 &lt;=512px，ARGB_8888）。
     */
    public static Bitmap pdfToBitmap(String path, int curPage) {
        if (StringUtils.isEmpty(path)) return null;

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            if (curPage < 0 || curPage >= renderer.getPageCount()) return null;

            page = renderer.openPage(curPage);
            int width = page.getWidth();
            int height = page.getHeight();
            if (width <= 0 || height <= 0) return null;

            float scale = Math.min(1f, (float) COVER_MAX_SIDE / Math.max(width, height));
            int outWidth = Math.max(1, Math.round(width * scale));
            int outHeight = Math.max(1, Math.round(height * scale));

            Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(page, renderer, pfd);
        }
    }

    /**
     * 以 JPEG 保存，避免上游用 PNG 导致封面动辄数 MB。
     */
    public static String saveBitmap(Bitmap bitmap, String savePath) {
        if (bitmap == null) return null;
        File file = new File(savePath);
        if (file.exists()) return savePath;

        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out);
            out.flush();
            return savePath;
        } catch (IOException e) {
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 页数；失败返回 0 */
    public static int getPdfTotalPage(String path) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            return renderer.getPageCount();
        } catch (Throwable e) {
            return 0;
        } finally {
            closeQuietly(null, renderer, pfd);
        }
    }

    /** 指定页宽度；失败返回 0 */
    public static int getPdfWidth(String path, int page) {
        return measure(path, page, true);
    }

    /** 指定页高度；失败返回 0 */
    public static int getPdfHeight(String path, int page) {
        return measure(path, page, false);
    }

    private static int measure(String path, int pageIndex, boolean width) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) return 0;

            page = renderer.openPage(pageIndex);
            return width ? page.getWidth() : page.getHeight();
        } catch (Throwable e) {
            return 0;
        } finally {
            closeQuietly(page, renderer, pfd);
        }
    }

    private static void closeQuietly(PdfRenderer.Page page,
                                     PdfRenderer renderer,
                                     ParcelFileDescriptor pfd) {
        if (page != null) {
            try {
                page.close();
            } catch (Throwable ignored) {
            }
        }
        if (renderer != null) {
            try {
                renderer.close();
            } catch (Throwable ignored) {
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private PdfUtils() {}
}
