package com.aaron.yespdf.common

import android.content.Context
import java.io.File

/**
 * 封面文件的路径约定与解析。
 *
 * 现状（历史遗留）：上游把封面写成 `PathUtils.getInternalAppDataPath() + "/书名.jpg"`，
 * 而该 API 返回的其实是 **dataDir 根**（`/data/user/0/<pkg>`），导致封面散落在私有根目录、
 * 与 files/cache/databases 混在一起，既难管理又让备份体积虚高。
 *
 * 新约定：封面统一放 `files/covers/<书名>.jpg`（≤512px JPEG q80）。
 *
 * [resolve] 负责兼容三种状态，保证"整理前 / 整理中断 / 整理完成"都能显示封面：
 * 1. DB 里记录的路径存在 → 直接用
 * 2. 否则按文件名回退到 `files/covers/`
 * 3. 再回退到 dataDir 根（旧位置）
 *
 * @author YESPDF-plus
 */
object CoverPaths {

    private const val DIR_NAME = "covers"

    /** 新位置目录（不保证已存在） */
    fun coversDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 新封面文件的完整路径；调用方负责 mkdirs */
    fun fileFor(context: Context, bookName: String): File =
            File(coversDir(context), "$bookName.jpg")

    /** 旧位置：dataDir 根 */
    fun legacyDir(context: Context): File? = context.filesDir?.parentFile

    /**
     * 解析实际可用的封面文件。
     *
     * @return 存在的文件；都找不到时返回 null（调用方显示占位图）
     */
    fun resolve(context: Context, cover: String?): File? {
        if (cover.isNullOrEmpty()) return null
        val recorded = File(cover)
        if (recorded.isFile) return recorded

        val name = recorded.name
        if (name.isEmpty()) return null

        val inCovers = File(coversDir(context), name)
        if (inCovers.isFile) return inCovers

        val legacy = legacyDir(context)
        if (legacy != null) {
            val inRoot = File(legacy, name)
            if (inRoot.isFile) return inRoot
        }
        return null
    }

    /** 便捷方法：给图片加载器用的绝对路径 */
    fun resolvePath(context: Context, cover: String?): String? = resolve(context, cover)?.absolutePath
}
