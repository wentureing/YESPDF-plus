package com.aaron.yespdf.data

import android.content.Context
import android.os.Environment
import com.aaron.yespdf.R
import com.aaron.yespdf.common.AppConfig
import com.aaron.yespdf.common.DataManager
import com.aaron.yespdf.common.bean.PDF
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 应用私有数据的一个条目（文件或目录）。
 */
data class DataItem(
        val file: File,
        val isDir: Boolean,
        val size: Long,
        val childCount: Int,
        val title: String,
        val note: String,
        val subNote: String,
        val iconRes: Int,
        val canRename: Boolean,
        val canExport: Boolean,
        val isDatabase: Boolean = false
) {
    val canDelete: Boolean get() = true
}

/** 一个可进入的根目录；[filesOnly] 为 true 时只展示直属文件（不递归子目录） */
data class DataRoot(val label: String, val dir: File, val note: String, val filesOnly: Boolean = false)

/**
 * 应用内剪贴板（复制 / 剪切）。
 *
 * 只在进程内保存绝对路径，不复制任何字节 —— 与"文件引用原则"一致。
 */
object AppClipboard {

    private var sources: List<File> = emptyList()
    private var move = false

    val isEmpty: Boolean get() = sources.isEmpty()
    val count: Int get() = sources.size
    val isMove: Boolean get() = move

    fun put(files: List<File>, move: Boolean) {
        sources = files.toList()
        this.move = move
    }

    fun items(): List<File> = sources

    fun clear() {
        sources = emptyList()
        move = false
    }
}

/**
 * 内部目录扫描 + 中文用途注释。
 *
 * 注释分两层：
 * 1. 静态表：文件名/目录名固定且确定（数据库、SP、缓存…）
 * 2. 动态匹配：封面文件名 == `PDF.name`，据此标注"《书名》的封面"
 */
object InternalDataScanner {

    //region 根目录

    fun roots(context: Context): List<DataRoot> {
        val result = ArrayList<DataRoot>()
        val files = context.filesDir

        // 四个精确 root 互不包含，避免列表重复与概览重复统计
        result.add(DataRoot("files", files, "封面图片"))

        context.getDatabasePath(AppConfig.DB_NAME).parentFile?.let {
            result.add(DataRoot("databases", it, "书库数据库"))
        }

        val prefs = File(files.parentFile, "shared_prefs")
        if (prefs.exists()) {
            result.add(DataRoot("shared_prefs", prefs, "应用设置"))
        }

        result.add(DataRoot("cache", context.cacheDir, "Glide 图片缓存，可清空"))

        // 旧封面：历史上封面写在 dataDir 根（PDF.COVER 存的是绝对路径）。
        // 这里只列 dataDir 的**直属文件**，不递归子目录 —— 否则会和上面的
        // files/cache/databases/shared_prefs 重复出现（曾经的 app data 根问题）。
        val dataDir = files.parentFile
        if (dataDir != null && dataDir.exists()) {
            result.add(DataRoot("旧封面", dataDir, "旧封面（建议一键整理）", filesOnly = true))
        }
        return result
    }

    //endregion

    //region 列表与注释

    fun list(dir: File, filesOnly: Boolean = false): List<DataItem> {
        val children = dir.listFiles() ?: return emptyList()
        val covers = coverIndex()
        val items = ArrayList<DataItem>()
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (filesOnly && f.isDirectory) continue
            val isDir = f.isDirectory
            val size = if (isDir) sizeOf(f) else f.length()
            val notePair = noteOf(f, covers)
            items.add(DataItem(
                    file = f,
                    isDir = isDir,
                    size = size,
                    childCount = if (isDir) (f.list()?.size ?: 0) else 0,
                    title = f.name,
                    note = notePair.first,
                    subNote = notePair.second,
                    iconRes = iconOf(f, isDir),
                    canRename = canRename(f, isDir),
                    canExport = true,
                    isDatabase = !isDir && f.name.startsWith(AppConfig.DB_NAME)
            ))
        }
        return items.sortedWith(compareBy({ !it.isDir }, { it.title.toLowerCase() }))
    }

    private fun canRename(file: File, isDir: Boolean): Boolean {
        // 目录也允许改名（含 files/databases/shared_prefs 这类标准目录）。
        // 改名后应用会重建同名空目录，旧数据仍留在改名后的目录里、可在管理页看到并操作，
        // 具体风险由 UI 的二次确认提示承担。
        if (isDir) return true
        val name = file.name
        // 数据库与设置文件的文件名被代码硬编码，改名会直接打不开
        if (name.startsWith(AppConfig.DB_NAME)) return false
        if (file.parentFile?.name == "shared_prefs" && name.endsWith(".xml")) return false
        return true
    }

    private fun iconOf(file: File, isDir: Boolean): Int {
        if (isDir) return R.drawable.app_ic_folder_yellow_24dp
        val name = file.name.toLowerCase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") ->
                R.drawable.app_ic_image_white_24dp
            name.startsWith(AppConfig.DB_NAME) -> R.drawable.app_ic_pdf_red_24dp
            name.endsWith(".xml") -> R.drawable.app_ic_action_settings_black
            else -> R.drawable.app_img_file
        }
    }

    /** 封面路径 → PDF，用于把封面文件标注成《书名》 */
    private fun coverIndex(): Map<String, PDF> {
        val map = HashMap<String, PDF>()
        for (pdf in DataManager.getPdfList()) {
            val cover = pdf.cover
            if (!cover.isNullOrEmpty()) map[cover] = pdf
        }
        return map
    }

    private fun noteOf(file: File, covers: Map<String, PDF>): Pair<String, String> {
        val name = file.name

        if (file.isDirectory) {
            // 列表里只放短标签；完整说明在"查看信息"里给出
            return when (name) {
                "databases" -> "书库数据库" to ""
                "shared_prefs" -> "应用设置" to ""
                "files" -> "封面图片" to ""
                "cache" -> "Glide 图片缓存，可清空" to ""
                else -> "子目录" to ""
            }
        }

        when {
            name == AppConfig.DB_NAME ->
                return "书库数据库：书名·路径·进度·书签·分组" to
                        "删除后应用会立即退出，下次启动重建空库，历史数据永久丢失"

            name.startsWith("${AppConfig.DB_NAME}-") ->
                return "数据库临时日志（-wal/-shm/-journal）" to "退出应用后可安全删除"

            name.startsWith("umeng", ignoreCase = true) ||
                    name.startsWith("um_", ignoreCase = true) ||
                    name.equals("UA.db", ignoreCase = true) ||
                    name.equals("umeng_zero_cache.db", ignoreCase = true) ->
                return "友盟 SDK 残留（SDK 已移除，新版本启动时会自动清理一次）" to
                        "若仍存在，说明删除被占用，可在此手动删除"

            // SharedPreferences：不假设文件名（blankj 默认是 spUtils.xml，但可能随版本变化）
            file.parentFile?.name == "shared_prefs" && name.endsWith(".xml") ->
                return "应用设置（SharedPreferences）：夜间显示、扫描规则、已保存密码密文等" to
                        "删除后相关设置恢复默认"

            name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") -> {
                val pdf = covers[file.absolutePath]
                        ?: covers.values.firstOrNull { "${it.name}.jpg" == name }
                return if (pdf != null) {
                    "《${pdf.name}》的封面缩略图（唯一派生文件）" to
                            "关联原文件：${pdf.path} · 进度 ${pdf.progress}"
                } else {
                    "未被书库引用的图片（可能是残留，可安全删除）" to ""
                }
            }
        }
        return "未知文件" to ""
    }

    //endregion

    //region 文件系统操作

    fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            total += if (child.isFile) child.length() else sizeOf(child)
        }
        return total
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    fun countFiles(dir: File): Int {
        var count = 0
        val children = dir.listFiles() ?: return 0
        for (child in children) {
            count += if (child.isFile) 1 else countFiles(child)
        }
        return count
    }

    /**
     * 导出到 Download/YESPDF_data/<时间戳>/。
     *
     * @return 导出目录；失败返回 null
     */
    fun export(files: List<File>): File? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val target = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "YESPDF_data/$stamp")
            if (!target.exists() && !target.mkdirs()) return null
            for (src in files) {
                if (!src.exists()) continue
                val dst = File(target, src.name)
                if (src.isDirectory) {
                    src.copyRecursively(dst, overwrite = true)
                } else {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }
            }
            target
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 把 [AppClipboard] 里的内容复制/剪切到目标目录。
     *
     * @return 成功处理的条目数
     */
    fun paste(target: File): Int {
        val sources = AppClipboard.items()
        if (sources.isEmpty()) return 0
        var ok = 0
        for (src in sources) {
            if (!src.exists()) continue
            // 文件保持原名（同名则先备份旧文件再覆盖），否则会生成 "yespdf (1).db"
            // 这类名字，应用读的还是旧文件，表现为"粘贴成功但没生效"。
            // 目录仍用自动改名，避免两棵目录树被悄悄合并。
            val dst = if (src.isDirectory) File(target, uniqueName(target, src.name))
            else File(target, src.name)
            try {
                if (src.isDirectory) {
                    src.copyRecursively(dst, overwrite = false)
                } else {
                    if (dst.exists()) backupBeforeOverwrite(dst)
                    src.copyTo(dst, overwrite = true)
                }
                if (AppClipboard.isMove) src.deleteRecursively()
                ok++
            } catch (e: Throwable) {
                // 单个失败不影响其余条目
            }
        }
        if (ok > 0) AppClipboard.clear()
        return ok
    }

    /**
     * 覆盖同名文件前先留档：`yespdf.db` → `yespdf.db.bak-<时间戳>`。
     * 这样"粘贴/导入覆盖"既能立刻生效，又能在出错时回滚（文件仍在本目录可见）。
     */
    fun backupBeforeOverwrite(dst: File) {
        try {
            if (!dst.exists()) return
            val backup = File(dst.parentFile, dst.name + ".bak-" + System.currentTimeMillis())
            if (!dst.renameTo(backup)) {
                dst.delete()
            }
        } catch (t: Throwable) {
            // 备份失败就让它走后面的覆盖逻辑
        }
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    //endregion
}
