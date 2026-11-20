package com.aaron.yespdf.data

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/**
 * Storage Access Framework 的最小封装（零新增依赖）。
 *
 * 刻意不引入 `androidx.documentfile`：其本质就是这几个 `DocumentsContract` 调用的语法糖
 * （当前依赖树里也没有它），手写 40 行即可完全替代。
 *
 * 关键约定：
 * - 选**文件**用 `ACTION_OPEN_DOCUMENT` + 通配符 type，**绝不设 `EXTRA_MIME_TYPES`**
 *   （一旦按类型过滤，系统侧边栏会被裁剪，只剩"最近/下载"这类入口）
 * - 选**文件夹**只能 `ACTION_OPEN_DOCUMENT_TREE`，此时侧边栏只列出支持 createDocument 的根
 * - Android 11+ 不允许选 Download 根 / 存储根 / Android/data，需引导用户选子目录
 *
 * @author YESPDF-plus
 */
object DocumentTreeHelper {

    private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR
    private const val MAX_IMAGE_SIDE = 512
    private const val MAX_IMAGE_BYTES = 2L * 1024 * 1024

    data class DocEntry(val uri: Uri, val name: String, val isDir: Boolean, val size: Long)

    //region 权限

    fun takePermissions(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Throwable) {
            // 部分 provider 不支持持久化授权，忽略即可（本次会话内仍可用）
        }
    }

    //endregion

    //region 读取（导入方向）

    fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val result = ArrayList<DocEntry>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            while (cursor != null && cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                result.add(DocEntry(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name,
                        mime == MIME_DIR,
                        size))
            }
        } catch (e: Throwable) {
            // provider 异常时返回已读到的部分
        } finally {
            cursor?.close()
        }
        return result
    }

    /** 递归收集一棵目录树下的所有文件（不含目录本身），用于"自动落位恢复" */
    fun collectFiles(resolver: ContentResolver, treeUri: Uri, maxFiles: Int = 3000): List<DocEntry> {
        val result = ArrayList<DocEntry>()
        fun walk(parentDocId: String) {
            if (result.size >= maxFiles) return
            for (entry in listChildren(resolver, treeUri, parentDocId)) {
                if (entry.isDir) {
                    try {
                        walk(DocumentsContract.getDocumentId(entry.uri))
                    } catch (e: Throwable) {
                        // 个别 provider 不支持继续下钻，跳过该子树
                    }
                } else {
                    result.add(entry)
                    if (result.size >= maxFiles) return
                }
            }
        }
        try {
            walk(DocumentsContract.getTreeDocumentId(treeUri))
        } catch (e: Throwable) {
        }
        return result
    }

    /** 递归复制整个目录树到 [targetDir] */
    fun copyTreeToDir(resolver: ContentResolver, treeUri: Uri, targetDir: File): Int {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        return copyChildren(resolver, treeUri, rootDocId, targetDir)
    }

    private fun copyChildren(resolver: ContentResolver,
                             treeUri: Uri,
                             parentDocId: String,
                             targetDir: File): Int {
        if (!targetDir.exists() && !targetDir.mkdirs()) return 0
        var count = 0
        for (entry in listChildren(resolver, treeUri, parentDocId)) {
            try {
                if (entry.isDir) {
                    count += copyChildren(resolver, treeUri,
                            DocumentsContract.getDocumentId(entry.uri),
                            File(targetDir, uniqueName(targetDir, entry.name)))
                } else {
                    // 文件按原名写入；同名则先备份再覆盖（否则会变成 "yespdf (1).db"，
                    // 应用仍读旧文件，用户看到的是"导入成功但没有效果"）
                    val dst = File(targetDir, entry.name)
                    if (dst.exists()) InternalDataScanner.backupBeforeOverwrite(dst)
                    resolver.openInputStream(entry.uri)?.use { input ->
                        FileOutputStream(dst).use { output -> input.copyTo(output) }
                    }
                    if (dst.exists()) count++
                }
            } catch (e: Throwable) {
                // 单个条目失败不影响其余
            }
        }
        return count
    }

    /** 复制单个文档（多选导入时逐个调用） */
    fun copyDocumentToDir(resolver: ContentResolver, uri: Uri, name: String, targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) targetDir.mkdirs()
            val dst = File(targetDir, name)
            if (dst.exists()) InternalDataScanner.backupBeforeOverwrite(dst)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
            dst.exists()
        } catch (e: Throwable) {
            false
        }
    }

    //endregion

    //region 写入（导出方向）

    /** 把单个文件写入用户选中的目录（同一个 filename 冲突时由 provider 自动改名） */
    fun writeFileToTree(resolver: ContentResolver, treeUri: Uri, src: File): Boolean {
        return try {
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val newUri = DocumentsContract.createDocument(resolver, parentUri, guessMime(src.name), src.name)
                    ?: return false
            resolver.openOutputStream(newUri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 把目录写入用户选中的目录（递归建子目录） */
    fun writeDirToTree(resolver: ContentResolver, treeUri: Uri, srcDir: File): Boolean {
        return try {
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            writeDirInternal(resolver, parentDocUri, srcDir)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 递归写入。注意这里用 **document uri** 作为父节点（`createDocument` 接受任意 document uri），
     * 不能把 createDocument 返回的 uri 当作 tree uri 再传给 getTreeDocumentId。
     */
    private fun writeDirInternal(resolver: ContentResolver, parentDocUri: Uri, srcDir: File): Boolean {
        val created = DocumentsContract.createDocument(resolver, parentDocUri, MIME_DIR, srcDir.name)
                ?: return false
        val children = srcDir.listFiles() ?: return true
        for (child in children) {
            try {
                if (child.isDirectory) {
                    writeDirInternal(resolver, created, child)
                } else {
                    val newUri = DocumentsContract.createDocument(
                            resolver, created, guessMime(child.name), child.name) ?: continue
                    resolver.openOutputStream(newUri)?.use { out ->
                        child.inputStream().use { it.copyTo(out) }
                    }
                }
            } catch (e: Throwable) {
                // 单个条目失败不影响其余
            }
        }
        return true
    }

    //endregion

    //region 图片（封面替换）

    /**
     * 解码并按最长边 ≤512px 缩放，符合"封面唯一派生文件"的体积约束。
     */
    fun loadScaledImage(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_SIDE &&
                    bounds.outHeight / (sample * 2) >= MAX_IMAGE_SIDE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide <= MAX_IMAGE_SIDE) {
                bitmap
            } else {
                val scale = MAX_IMAGE_SIDE.toFloat() / maxSide
                Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 以 JPEG 保存，逐级降质直到 ≤2MB */
    fun saveJpeg(bitmap: Bitmap, target: File): Boolean {
        var quality = 85
        while (quality >= 35) {
            try {
                FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (target.length() in 1..MAX_IMAGE_BYTES) return true
            } catch (e: Throwable) {
                return false
            }
            quality -= 15
        }
        return target.exists() && target.length() <= MAX_IMAGE_BYTES
    }

    //endregion

    //region 工具

    fun guessMime(name: String): String {
        val lower = name.toLowerCase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".xml") -> "text/xml"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".db") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    fun uniqueName(dir: File, name: String): String {
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
