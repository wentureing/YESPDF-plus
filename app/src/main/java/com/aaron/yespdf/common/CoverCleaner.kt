package com.aaron.yespdf.common

import com.aaron.yespdf.common.bean.PDF
import com.blankj.utilcode.util.LogUtils
import java.io.File

/**
 * 删除书籍后清理它的封面文件。
 *
 * 背景：上游把封面写成 `dataDir/<书名>.jpg`（derived file），
 * 而删除书籍只删数据库记录，封面会永久残留在应用私有目录里 —— 越删越多。
 *
 * 安全边界（重要）：
 * 1. **只删应用私有目录内**的图片（dataDir 或其 files/ 下），绝不碰用户外部文件
 * 2. 只删常见图片扩展名
 * 3. **仍被其他记录引用**的封面不删（同名书可能共用同一封面文件）
 * 4. 全程 try-catch，清理失败不影响删除主流程
 *
 * @author YESPDF-plus
 */
object CoverCleaner {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    /**
     * @param removed 刚刚从数据库删除的 PDF 记录（封面路径从这些记录里取）
     */
    fun purge(removed: Collection<PDF>?) {
        if (removed.isNullOrEmpty()) return
        try {
            val app = App.getContext()
            val filesDir = app.filesDir ?: return
            val dataDir = filesDir.parentFile ?: return
            val dataPath = dataDir.absolutePath
            val filesPath = filesDir.absolutePath

            // 注意：调用时记录已被删除，这里的查询结果是"剩余仍被引用"的封面
            val stillUsed = HashSet<String>()
            for (pdf in DBHelper.queryAllPDF()) {
                val cover = pdf.cover
                if (!cover.isNullOrEmpty()) stillUsed.add(cover)
            }

            for (pdf in removed) {
                val cover = pdf.cover
                if (cover.isNullOrEmpty() || stillUsed.contains(cover)) continue

                val file = File(cover)
                if (!file.isFile) continue

                val path = file.absolutePath
                if (!path.startsWith(dataPath) && !path.startsWith(filesPath)) continue

                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in IMAGE_EXT) continue

                if (file.delete()) {
                    LogUtils.d("CoverCleaner: removed cover $path")
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("CoverCleaner: " + t.message)
        }
    }
}
