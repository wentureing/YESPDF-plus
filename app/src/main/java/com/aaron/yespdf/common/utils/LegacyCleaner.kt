package com.aaron.yespdf.common.utils

import android.content.Context
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPStaticUtils
import java.io.File

/**
 * 一次性残留清理。
 *
 * 背景：本项目已移除友盟（UMeng）统计 SDK，但老用户的数据目录里会留下它写过的
 * 数据库与首选项文件（ua.db、umeng_zero_cache.db、umeng_*.xml 等）。
 * 这些文件不再被任何代码读写，属于纯垃圾，且会出现在「应用数据管理」里造成困惑。
 *
 * 策略（对应"安装后检查一次并删除"）：
 * - 只在首次清除版本时执行一次，用 SP 标记；标记写入失败或删除抛异常时不会重复打扰
 * - 只删**明确的友盟文件**，绝不按目录整体删除，避免误伤我们的 yespdf.db / spUtils.xml
 * - 全程 try-catch，任何异常都不影响应用启动
 *
 * @author YESPDF-plus
 */
object LegacyCleaner {

    /** 清理标记（带版本号后缀，将来若需再清理可换新 key） */
    private const val SP_UMENG_CLEANED = "SP_CLEANED_UMENG_RESIDUE_V1"

    /** 友盟写在 databases/ 下的文件 */
    private val DB_RESIDUE = listOf(
            "ua.db",
            "ua.db-journal",
            "ua.db-wal",
            "ua.db-shm",
            "umeng_zero_cache.db",
            "umeng_zero_cache.db-journal"
    )

    /** 友盟写在 shared_prefs/ 下的文件名前缀（um_pri.xml / UM_PROBE_DATA.xml 等） */
    private val PREF_RESIDUE_PREFIX = listOf("umeng_", "um_", "UM_")

    /** 友盟可能写在 files/ 下的文件名前缀 */
    private val FILE_RESIDUE_PREFIX = listOf("umeng", "ua_")

    fun cleanOnce(context: Context) {
        if (SPStaticUtils.getBoolean(SP_UMENG_CLEANED, false)) return

        val removed = ArrayList<String>()
        try {
            // 1) databases/：友盟的统计库
            val dbDir = context.getDatabasePath("yespdf.db").parentFile
            if (dbDir != null && dbDir.isDirectory) {
                for (name in DB_RESIDUE) {
                    deleteQuietly(File(dbDir, name), removed)
                }
            }

            // 2) shared_prefs/：友盟的首选项
            val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
            prefsDir.listFiles()?.forEach { file ->
                val name = file.name
                if (PREF_RESIDUE_PREFIX.any { name.startsWith(it, ignoreCase = true) }) {
                    deleteQuietly(file, removed)
                }
            }

            // 3) files/：偶发残留
            context.filesDir?.listFiles()?.forEach { file ->
                if (FILE_RESIDUE_PREFIX.any { file.name.startsWith(it, ignoreCase = true) }) {
                    deleteQuietly(file, removed)
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("LegacyCleaner: ${t.message}")
        }

        // 无论是否有残留都打标记 —— 这是"检查一次"的语义，避免每次启动重复扫描
        SPStaticUtils.put(SP_UMENG_CLEANED, true)
        if (removed.isNotEmpty()) {
            LogUtils.i("LegacyCleaner: removed ${removed.size} umeng residue file(s): $removed")
        }
    }

    private fun deleteQuietly(file: File, removed: MutableList<String>) {
        try {
            if (file.exists() && file.isFile && file.delete()) {
                removed.add(file.name)
            }
        } catch (t: Throwable) {
            // 单个文件删不掉不影响其余
        }
    }
}
