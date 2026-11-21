package com.aaron.yespdf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aaron.yespdf.common.App
import com.aaron.yespdf.common.CoverPaths
import com.aaron.yespdf.common.DBHelper
import com.aaron.yespdf.common.DataManager
import com.blankj.utilcode.util.LogUtils
import java.io.File
import java.io.FileOutputStream

/**
 * 封面的批量维护：清理孤儿 / 压缩不合规 / 整理到新目录。
 *
 * 三个操作都设计成**可重复执行**（幂等），并且在后台线程调用、不触碰 DB 连接之外的资源。
 * 安全边界：
 * - 只处理应用私有目录（covers/ 与 dataDir 根）
 * - 只处理常见图片扩展名
 * - 孤儿判定 = 文件名不在 `PDF.COVER` 引用的文件名集合里
 *
 * @author YESPDF-plus
 */
object CoverOps {

    /** 合规阈值：与 docs/PLAN_DATA_CLEANUP.md 一致 */
    private const val MAX_SIDE = 512
    private const val MAX_BYTES = 200L * 1024L
    private const val JPEG_QUALITY = 80

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    data class Result(
            var total: Int = 0,
            var changed: Int = 0,
            var skipped: Int = 0,
            var failed: Int = 0,
            var beforeBytes: Long = 0,
            var afterBytes: Long = 0
    )

    //region 公共

    /** 当前仍被数据库引用的封面文件名集合 */
    private fun referencedNames(): Set<String> {
        val names = HashSet<String>()
        for (pdf in DataManager.getPdfList()) {
            val cover = pdf.cover
            if (!cover.isNullOrEmpty()) {
                val n = File(cover).name
                if (n.isNotEmpty()) names.add(n)
            }
        }
        return names
    }

    /** 候选封面目录：新位置 + 旧位置（dataDir 根，只含直属文件） */
    private fun candidateDirs(context: Context): List<File> {
        val dirs = ArrayList<File>()
        dirs.add(CoverPaths.coversDir(context))
        CoverPaths.legacyDir(context)?.let { dirs.add(it) }
        return dirs
    }

    private fun isImage(file: File): Boolean =
            file.isFile && IMAGE_EXT.contains(file.extension.toLowerCase())

    //endregion

    //region ① 清理孤儿

    /** 列出不再被任何书籍引用的封面（预览用） */
    fun findOrphans(context: Context): List<File> {
        val referenced = referencedNames()
        val orphans = ArrayList<File>()
        for (dir in candidateDirs(context)) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (!isImage(f)) return@forEach
                if (dir == CoverPaths.coversDir(context) || f.parentFile == CoverPaths.legacyDir(context)) {
                    if (!referenced.contains(f.name)) orphans.add(f)
                }
            }
        }
        return orphans
    }

    /** 删除孤儿封面 */
    fun deleteOrphans(context: Context): Result {
        val result = Result()
        val orphans = findOrphans(context)
        result.total = orphans.size
        for (f in orphans) {
            result.beforeBytes += f.length()
            try {
                if (f.delete()) result.changed++ else result.failed++
            } catch (t: Throwable) {
                result.failed++
            }
        }
        LogUtils.i("CoverOps.deleteOrphans: ${result.changed}/${result.total}")
        return result
    }

    //endregion

    //region ② 压缩不合规封面

    /**
     * 对仍被引用的封面做体检：**已合规就跳过**（最长边 ≤512 且 ≤200KB 且文件头是 JPEG）。
     */
    fun compress(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val result = Result()
        val files = ArrayList<File>()
        for (pdf in DataManager.getPdfList()) {
            CoverPaths.resolve(context, pdf.cover)?.let { if (isImage(it)) files.add(it) }
        }
        result.total = files.size
        var index = 0
        for (f in files) {
            index++
            onProgress(index, files.size)
            val before = f.length()
            result.beforeBytes += before
            try {
                if (isCompliant(f)) {
                    result.skipped++
                    result.afterBytes += before
                    continue
                }
                val bitmap = decodeScaled(f, MAX_SIDE)
                if (bitmap == null) {
                    result.failed++
                    result.afterBytes += before
                    continue
                }
                if (saveJpeg(bitmap, f)) {
                    result.changed++
                    result.afterBytes += f.length()
                } else {
                    result.failed++
                    result.afterBytes += before
                }
                bitmap.recycle()
            } catch (t: Throwable) {
                LogUtils.w("CoverOps.compress: " + t.message)
                result.failed++
                result.afterBytes += before
            }
        }
        LogUtils.i("CoverOps.compress: changed=${result.changed} skipped=${result.skipped} failed=${result.failed}")
        return result
    }

    /** 最长边 ≤512 且 ≤200KB 且真的是 JPEG（FFD8） */
    private fun isCompliant(file: File): Boolean {
        if (file.length() > MAX_BYTES) return false
        if (!looksLikeJpeg(file)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        return maxSide in 1..MAX_SIDE
    }

    private fun looksLikeJpeg(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                if (input.read(head) != 2) return false
                (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xFF) == 0xD8
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= maxSide) return bitmap
        val scale = maxSide.toFloat() / currentMax
        return Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true)
    }

    private fun saveJpeg(bitmap: Bitmap, target: File): Boolean {
        var quality = JPEG_QUALITY
        while (quality >= 40) {
            try {
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                if (target.length() in 1..(2L * 1024 * 1024)) return true
            } catch (t: Throwable) {
                return false
            }
            quality -= 15
        }
        return target.length() in 1..(2L * 1024 * 1024)
    }

    //endregion

    //region ③ 整理到新目录

    /**
     * 把 dataDir 根的封面移动到 `files/covers/`，并在事务内同步 `PDF.COVER`。
     * 幂等：已经在 covers/ 的会跳过。
     */
    fun reorganize(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val result = Result()
        val legacy = CoverPaths.legacyDir(context) ?: return result
        val covers = CoverPaths.coversDir(context)
        if (!covers.exists()) covers.mkdirs()

        val pdfs = ArrayList(DataManager.getPdfList())
        result.total = pdfs.size
        var index = 0
        val dirty = ArrayList<com.aaron.yespdf.common.bean.PDF>()

        for (pdf in pdfs) {
            index++
            onProgress(index, pdfs.size)
            val cover = pdf.cover
            if (cover.isNullOrEmpty()) {
                result.skipped++
                continue
            }
            val src = File(cover)
            val target = File(covers, src.name)
            try {
                if (src.absolutePath == target.absolutePath && src.isFile) {
                    result.skipped++
                    continue
                }
                if (!src.isFile) {
                    // 记录路径不存在：若 covers/ 下已有同名文件，修正路径即可
                    if (target.isFile) {
                        pdf.cover = target.absolutePath
                        dirty.add(pdf)
                        result.changed++
                    } else {
                        result.failed++
                    }
                    continue
                }
                // dataDir 根的直属文件才移动；其他位置（用户手机里）不动
                if (src.parentFile?.absolutePath != legacy.absolutePath) {
                    result.skipped++
                    continue
                }
                if (target.exists() && target.length() >= src.length()) {
                    // 目标已有更完整的同名文件，删源即可
                    src.delete()
                    pdf.cover = target.absolutePath
                    dirty.add(pdf)
                    result.changed++
                    continue
                }
                if (src.renameTo(target)) {
                    pdf.cover = target.absolutePath
                    dirty.add(pdf)
                    result.changed++
                } else {
                    result.failed++
                }
            } catch (t: Throwable) {
                LogUtils.w("CoverOps.reorganize: " + t.message)
                result.failed++
            }
        }

        if (dirty.isNotEmpty()) {
            DBHelper.updatePDFs(dirty)
            DataManager.updatePDFs()
        }
        LogUtils.i("CoverOps.reorganize: moved=${result.changed} skipped=${result.skipped} failed=${result.failed}")
        return result
    }

    //endregion

    /** 给 UI 用的：统计当前封面总体积（含新旧两处） */
    fun totalCoverBytes(context: Context): Long {
        var total = 0L
        for (dir in candidateDirs(context)) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (isImage(f)) total += f.length()
            }
        }
        return total
    }

    /** 便捷：全局上下文（供 Activity 之外的调用） */
    fun appContext(): Context = App.getContext()
}
