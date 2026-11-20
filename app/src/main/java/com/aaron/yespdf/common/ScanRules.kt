package com.aaron.yespdf.common

import android.os.Environment
import com.blankj.utilcode.util.SPStaticUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 自动扫描的"默认扫描路径集合"与"默认排除集合"。
 *
 * 之所以用 SP + JSON 而不是新增 GreenDAO 表：
 * 规则总量极小（数十条）、无任何查询/关联需求，加表还得配 schema 迁移，收益为负。
 *
 * 语义（对应需求 5）：
 * - 默认关闭：多数用户全盘扫描即可，规则"通常用不上"
 * - 勾选后生效：扫描只从 [includeDirs] 出发，并跳过 [excludeDirs] 与 [excludeFiles]
 * - 全部可编辑：见 ScanRuleActivity
 *
 * @author YESPDF-plus
 */
object ScanRules {

    private const val SP_ENABLED = "SP_SCAN_RULES_ENABLED"
    private const val SP_BROWSER_ENABLED = "SP_SCAN_RULES_BROWSER_ENABLED"
    private const val SP_INCLUDE_DIRS = "SP_SCAN_INCLUDE_DIRS"
    private const val SP_EXCLUDE_DIRS = "SP_SCAN_EXCLUDE_DIRS"
    private const val SP_EXCLUDE_FILES = "SP_SCAN_EXCLUDE_FILES"

    /** 规则种类，用于 UI 分组与增删时分派 */
    const val KIND_INCLUDE_DIR = 0
    const val KIND_EXCLUDE_DIR = 1
    const val KIND_EXCLUDE_FILE = 2

    /**
     * 规则总开关。关闭时扫描行为与旧版完全一致（遍历所有已挂载存储卷、只认 .pdf）。
     */
    var enabled: Boolean
        get() = SPStaticUtils.getBoolean(SP_ENABLED, false)
        set(value) = SPStaticUtils.put(SP_ENABLED, value)

    /**
     * 「浏览全部」页是否应用同一份排除规则（需求 6）。
     *
     * 与 [enabled] 独立：
     * - 扫描时可以关掉规则，同时浏览页仍按排除集合过滤；
     * - 反之亦然。两个开关都默认关闭（"通常用不上，勾选时用上"）。
     */
    var browserEnabled: Boolean
        get() = SPStaticUtils.getBoolean(SP_BROWSER_ENABLED, false)
        set(value) = SPStaticUtils.put(SP_BROWSER_ENABLED, value)

    /**
     * 默认扫描目录。为空表示"所有已挂载存储卷"（含外置 SD 卡）。
     */
    var includeDirs: List<String>
        get() = readList(SP_INCLUDE_DIRS)
        set(value) = writeList(SP_INCLUDE_DIRS, value)

    /**
     * 排除的目录（整棵子树跳过），按路径前缀匹配。
     */
    var excludeDirs: List<String>
        get() = readList(SP_EXCLUDE_DIRS)
        set(value) = writeList(SP_EXCLUDE_DIRS, value)

    /**
     * 排除的文件名或通配模式，如 `*.nomedia`、`~$*`、`*.tmp`。
     */
    var excludeFiles: List<String>
        get() = readList(SP_EXCLUDE_FILES)
        set(value) = writeList(SP_EXCLUDE_FILES, value)

    /**
     * 实际生效的扫描根。
     *
     * @param fallbackRoots 未配置 includeDirs 时要遍历的根（通常是所有已挂载卷）
     */
    fun effectiveRoots(fallbackRoots: List<String>): List<String> {
        if (!enabled) return fallbackRoots
        val includes = includeDirs.filter { it.isNotBlank() }
        return if (includes.isEmpty()) fallbackRoots else includes
    }

    /**
     * 目录是否被排除。
     *
     * 注意：这里**不再判断** [enabled] / [browserEnabled]，由调用方按场景（扫描 / 浏览）
     * 决定规则是否生效，避免两套开关互相干扰。
     */
    fun isDirExcluded(path: String): Boolean {
        val target = normalize(path)
        if (target.isEmpty()) return false
        for (dir in excludeDirs) {
            val excluded = normalize(dir)
            if (excluded.isEmpty()) continue
            if (target == excluded || target.startsWith("$excluded/")) return true
        }
        return false
    }

    /** 文件名是否被排除（支持 `*` / `?` 通配，不区分大小写）；是否生效由调用方决定 */
    fun isFileExcluded(name: String): Boolean {
        if (name.isBlank()) return false
        for (pattern in excludeFiles) {
            if (pattern.isBlank()) continue
            if (wildcardToRegex(pattern).matches(name)) return true
        }
        return false
    }

    /** 按种类取当前列表 */
    fun listOf(kind: Int): List<String> = when (kind) {
        KIND_INCLUDE_DIR -> includeDirs
        KIND_EXCLUDE_DIR -> excludeDirs
        KIND_EXCLUDE_FILE -> excludeFiles
        else -> emptyList()
    }

    /** 追加一条规则（自动去重、去首尾空白） */
    fun add(kind: Int, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        val current = listOf(kind).toMutableList()
        if (current.any { it.equals(v, ignoreCase = true) }) return false
        current.add(v)
        save(kind, current)
        return true
    }

    /** 删除一条规则 */
    fun remove(kind: Int, value: String) {
        val current = listOf(kind).toMutableList()
        if (current.remove(value)) save(kind, current)
    }

    /** 原地重命名（索引不变，避免列表跳动） */
    fun replace(kind: Int, oldValue: String, newValue: String): Boolean {
        val v = newValue.trim()
        if (v.isEmpty()) return false
        val current = listOf(kind).toMutableList()
        val index = current.indexOf(oldValue)
        if (index < 0) return false
        current[index] = v
        save(kind, current)
        return true
    }

    /** 建议的默认扫描根（设备外部存储根） */
    fun suggestDefaultRoot(): String =
            Environment.getExternalStorageDirectory()?.absolutePath ?: ""

    //region 内部实现

    private fun save(kind: Int, list: MutableList<String>) {
        when (kind) {
            KIND_INCLUDE_DIR -> includeDirs = list
            KIND_EXCLUDE_DIR -> excludeDirs = list
            KIND_EXCLUDE_FILE -> excludeFiles = list
        }
    }

    private fun normalize(path: String): String {
        var p = path.trim()
        while (p.length > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length - 1)
        }
        return p
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE))
    }

    private fun readList(key: String): List<String> {
        val json = SPStaticUtils.getString(key, "")
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<MutableList<String>>() {}.type
            val list: MutableList<String>? = Gson().fromJson(json, type)
            list ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, value: List<String>) {
        SPStaticUtils.put(key, Gson().toJson(value))
    }
    //endregion
}
