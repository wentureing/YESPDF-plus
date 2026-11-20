package com.aaron.yespdf.filepicker

import com.aaron.yespdf.common.ScanRules
import java.io.File
import java.io.FileFilter

/**
 * 扫描/浏览时应用的规则过滤器。
 *
 * 设计要点：只做"是否接受该节点"的判断，不负责递归 —— 被拒绝的目录自然不会被
 * [ByNameListable] 返回，从而整棵子树都不会被进入，避免逐层剪枝的复杂度。
 *
 * 规则未启用时行为与 [FileFilterImpl] 完全一致（零行为变化）。
 *
 * @author YESPDF-plus
 */
internal class ScanRuleFilter(
        private val delegate: FileFilter = FileFilterImpl()
) : FileFilter {

    override fun accept(file: File): Boolean {
        if (!ScanRules.enabled) return delegate.accept(file)

        return if (file.isDirectory) {
            !ScanRules.isDirExcluded(file.absolutePath) && delegate.accept(file)
        } else {
            !ScanRules.isFileExcluded(file.name) && delegate.accept(file)
        }
    }
}
