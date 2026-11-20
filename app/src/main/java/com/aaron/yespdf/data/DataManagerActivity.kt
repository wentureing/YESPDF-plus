package com.aaron.yespdf.data

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.aaron.yespdf.R
import com.aaron.yespdf.common.AppConfig
import com.aaron.yespdf.common.CommonActivity
import com.aaron.yespdf.common.DBHelper
import com.aaron.yespdf.common.DataManager
import com.aaron.yespdf.common.DialogManager
import com.aaron.yespdf.common.UiManager
import com.aaron.yespdf.common.security.PasswordVault
import com.aaron.yespdf.common.utils.DialogUtils
import com.blankj.utilcode.util.SPStaticUtils
import com.github.anzewei.parallaxbacklayout.ParallaxBack
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.android.synthetic.main.app_activity_data_manager.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 应用数据管理（F0）。
 *
 * 目标：让用户看得见、看得懂、能动手，同时守住两条底线：
 * 1. 绝不复制用户文件（只有导出/粘贴这类用户显式发起的动作才会写外部目录）
 * 2. 数据库与设置文件不做原地编辑，只提供只读信息 / 导出 / 高危删除
 *
 * @author YESPDF-plus
 */
@ParallaxBack
class DataManagerActivity : CommonActivity() {

    private lateinit var adapter: DataFileAdapter
    private var roots: List<DataRoot> = emptyList()
    private var currentDir: File? = null
    private var pendingExportFiles: List<File> = emptyList()
    private var pendingReplaceFile: File? = null

    override fun layoutId(): Int = R.layout.app_activity_data_manager

    override fun createToolbar(): Toolbar? = findViewById(R.id.app_toolbar)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initToolbar()
        roots = InternalDataScanner.roots(this)
        adapter = DataFileAdapter(object : DataFileAdapter.Callback {
            override fun onClick(item: DataItem) {
                if (item.isDir) enter(item.file) else showOpsMenu(item)
            }

            override fun onSelectionModeChanged(inSelection: Boolean, count: Int) {
                updateSelectionBar(inSelection, count)
            }
        })
        app_rv_data.layoutManager = LinearLayoutManager(this)
        app_rv_data.adapter = adapter
        app_tv_path.setOnClickListener { goUp() }
        app_btn_copy.setOnClickListener { copySelected() }
        app_btn_export.setOnClickListener { exportSelected() }
        app_btn_delete_selected.setOnClickListener { confirmDelete(adapter.selectedFiles()) }
        app_btn_cancel_selection.setOnClickListener { adapter.exitSelection() }
        app_btn_paste.setOnClickListener { pasteIntoCurrentDir() }
        showRoots()
        showIntroTipIfNeeded()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    //region 系统文件选择器（S3）

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_menu_data_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.app_menu_import_files -> pickImportFiles()
            R.id.app_menu_import_dir -> pickImportTree()
            R.id.app_menu_export_dir -> pickExportTree()
            R.id.app_menu_refresh -> refresh()
        }
        return true
    }

    /**
     * 选文件：故意用 `*/*` 且**不设 EXTRA_MIME_TYPES**。
     * 一旦按类型过滤，系统侧边栏会被裁剪成"可能含该类型"的少数入口，
     * 图片/视频/音频/下载/手机型号/第三方 App 目录都会消失。
     */
    private fun pickImportFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQ_IMPORT_FILES)
    }

    /** 选文件夹：只有 OPEN_DOCUMENT_TREE 能选目录，侧边栏也只会列出支持建目录的根 */
    private fun pickImportTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, REQ_IMPORT_TREE)
    }

    private fun pickExportTree() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) {
            UiManager.showShort(R.string.app_data_select_first)
            return
        }
        pendingExportFiles = files
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, REQ_EXPORT_TREE)
    }

    private fun pickReplaceImage(target: File) {
        pendingReplaceFile = target
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQ_REPLACE_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMPORT_FILES -> handleImportFiles(data)
            REQ_IMPORT_TREE -> data.data?.let { handleImportTree(it) }
            REQ_EXPORT_TREE -> data.data?.let { handleExportTree(it) }
            REQ_REPLACE_IMAGE -> data.data?.let { handleReplaceImage(it) }
        }
    }

    private fun handleImportFiles(data: Intent) {
        val target = currentDir ?: filesDir
        val items = ArrayList<Pair<Uri, String>>()
        val clip = data.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                val uri = clip.getItemAt(index).uri ?: continue
                items.add(uri to displayName(uri))
            }
        } else {
            data.data?.let { items.add(it to displayName(it)) }
        }
        if (items.isEmpty()) return

        Thread {
            var ok = 0
            var touchedDb = false
            for ((uri, name) in items) {
                if (DocumentTreeHelper.copyDocumentToDir(contentResolver, uri, name, target)) {
                    ok++
                    if (name.startsWith(AppConfig.DB_NAME)) touchedDb = true
                }
            }
            val count = ok
            runOnUiThread {
                UiManager.showShort(getString(R.string.app_data_imported, count))
                refresh()
                if (touchedDb) promptRestartAfterDbImport()
            }
        }.start()
    }

    private fun handleImportTree(treeUri: Uri) {
        DocumentTreeHelper.takePermissions(contentResolver, treeUri)
        val target = currentDir ?: filesDir
        UiManager.showShort(R.string.app_data_importing)
        Thread {
            val count = DocumentTreeHelper.copyTreeToDir(contentResolver, treeUri, target)
            val touchedDb = File(target, AppConfig.DB_NAME).exists()
            runOnUiThread {
                UiManager.showShort(getString(R.string.app_data_imported, count))
                refresh()
                if (touchedDb) promptRestartAfterDbImport()
            }
        }.start()
    }

    private fun handleExportTree(treeUri: Uri) {
        DocumentTreeHelper.takePermissions(contentResolver, treeUri)
        val files = pendingExportFiles
        pendingExportFiles = emptyList()
        if (files.isEmpty()) return

        Thread {
            var ok = 0
            for (file in files) {
                val done = if (file.isDirectory) {
                    DocumentTreeHelper.writeDirToTree(contentResolver, treeUri, file)
                } else {
                    DocumentTreeHelper.writeFileToTree(contentResolver, treeUri, file)
                }
                if (done) ok++
            }
            val count = ok
            runOnUiThread {
                adapter.exitSelection()
                UiManager.showShort(getString(R.string.app_data_exported_count, count))
            }
        }.start()
    }

    private fun handleReplaceImage(uri: Uri) {
        val target = pendingReplaceFile ?: return
        pendingReplaceFile = null
        Thread {
            val bitmap = DocumentTreeHelper.loadScaledImage(contentResolver, uri)
            val saved = bitmap != null && DocumentTreeHelper.saveJpeg(bitmap, target)
            runOnUiThread {
                if (saved) {
                    UiManager.showShort(R.string.app_data_image_replaced)
                    refresh()
                } else {
                    UiManager.showShort(R.string.app_data_op_failed)
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "imported"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
            }
        } catch (e: Throwable) {
            // 拿不到名字就用 uri 末段
        }
        return name
    }

    /** 导入的目录/文件覆盖了数据库 → 必须重启，热重连会落到已被替换的 inode 上 */
    private fun promptRestartAfterDbImport() {
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_data_restart_title)
            tvContent.setText(R.string.app_data_restart_content)
            btnLeft.setText(R.string.app_data_intro_skip)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_data_restart_now)
            btnRight.setOnClickListener {
                dialog?.dismiss()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            }
        }
        dialog?.show()
    }

    //endregion

    override fun onBackPressed() {
        when {
            adapter.selectionMode -> adapter.exitSelection()
            currentDir != null -> goUp()
            else -> super.onBackPressed()
        }
    }

    //region 导航

    private fun initToolbar() {
        supportActionBar?.run {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.app_ic_action_back_black)
        }
        toolbar?.title = getString(R.string.app_data_manage)
    }

    private fun showRoots() {
        currentDir = null
        adapter.exitSelection()
        val items = roots.map { root ->
            DataItem(
                    file = root.dir,
                    isDir = true,
                    size = 0L,
                    childCount = root.dir.list()?.size ?: 0,
                    title = root.label,
                    note = root.note,
                    subNote = root.dir.absolutePath,
                    iconRes = R.drawable.app_ic_folder_yellow_24dp,
                    canRename = false,
                    canExport = true
            )
        }
        adapter.submit(items)
        app_tv_path.text = getString(R.string.app_data_root)
        toolbar?.title = getString(R.string.app_data_manage)
        updateSelectionBar(false, 0)
        loadOverview()
    }

    private fun enter(dir: File) {
        currentDir = dir
        adapter.exitSelection()
        Thread {
            val items = InternalDataScanner.list(dir)
            runOnUiThread {
                adapter.submit(items)
                app_tv_path.text = dir.absolutePath
                updateSelectionBar(false, 0)
            }
        }.start()
    }

    private fun goUp() {
        val dir = currentDir ?: return
        if (roots.any { it.dir.absolutePath == dir.absolutePath } || dir.parentFile == null) {
            showRoots()
        } else {
            enter(dir.parentFile!!)
        }
    }

    private fun refresh() {
        val dir = currentDir
        if (dir == null) showRoots() else enter(dir)
    }

    //endregion

    //region 概览与选择栏

    private fun loadOverview() {
        Thread {
            var total = 0L
            var files = 0
            for (root in roots) {
                // 兼容根可能与 files 重复，跳过避免重复计算
                if (root.label == "app data") continue
                total += InternalDataScanner.sizeOf(root.dir)
                files += InternalDataScanner.countFiles(root.dir)
            }
            val text = getString(R.string.app_data_overview,
                    InternalDataScanner.formatSize(total), files, PasswordVault.count())
            runOnUiThread { app_tv_overview.text = text }
        }.start()
    }

    private fun updateSelectionBar(inSelection: Boolean, count: Int) {
        app_ll_selection.visibility = if (inSelection) View.VISIBLE else View.GONE
        app_btn_paste.visibility =
                if (!inSelection && !AppClipboard.isEmpty && currentDir != null) View.VISIBLE else View.GONE
        if (!inSelection && !AppClipboard.isEmpty) {
            app_btn_paste.text = getString(R.string.app_data_paste, AppClipboard.count)
        }
        toolbar?.title =
                if (inSelection) getString(R.string.app_data_selected, count)
                else getString(R.string.app_data_manage)
    }

    //endregion

    //region 条目操作

    private fun showOpsMenu(item: DataItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.app_bottomdialog_ops, null)
        view.findViewById<TextView>(R.id.app_tv_ops_title).text = item.title
        view.findViewById<TextView>(R.id.app_tv_ops_note).text = buildNote(item)
        val container = view.findViewById<LinearLayout>(R.id.app_ll_ops)
        val dialog = DialogUtils.createBottomSheetDialog(this, view)

        val actions = ArrayList<Pair<String, Boolean>>()   // 文案 + 是否危险
        val handlers = HashMap<Int, () -> Unit>()
        var index = 0

        fun add(label: String, danger: Boolean = false, action: () -> Unit) {
            actions.add(label to danger)
            handlers[index++] = action
        }

        add(getString(R.string.app_data_info)) {
            dialog.dismiss()
            showInfo(item)
        }
        if (item.canRename) {
            add(getString(R.string.app_data_rename)) {
                dialog.dismiss()
                rename(item)
            }
        }
        add(getString(R.string.app_data_copy_path)) {
            dialog.dismiss()
            copyPathToClipboard(item.file.absolutePath)
            UiManager.showShort(R.string.app_data_path_copied)
        }
        if (item.canExport) {
            add(getString(R.string.app_data_export)) {
                dialog.dismiss()
                export(listOf(item.file))
            }
        }
        if (isImage(item.title)) {
            add(getString(R.string.app_data_replace_image)) {
                dialog.dismiss()
                pickReplaceImage(item.file)
            }
        }
        if (item.isDatabase) {
            add(getString(R.string.app_data_delete_db), true) {
                dialog.dismiss()
                confirmDeleteDatabase()
            }
        } else {
            add(getString(R.string.app_delete), true) {
                dialog.dismiss()
                confirmDelete(listOf(item.file))
            }
        }

        for (i in actions.indices) {
            val (label, danger) = actions[i]
            container.addView(buildOpsRow(label, danger, handlers[i] ?: {}))
        }
        view.findViewById<Button>(R.id.app_btn_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun isImage(name: String): Boolean {
        val lower = name.toLowerCase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun buildNote(item: DataItem): String {        val size = InternalDataScanner.formatSize(item.size)
        val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(item.file.lastModified()))
        val extra = if (item.subNote.isNotEmpty()) "\n${item.subNote}" else ""
        return "${item.note}\n$size · $time$extra"
    }

    private fun buildOpsRow(label: String, danger: Boolean, action: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.base_text_large))
        tv.setTextColor(resources.getColor(if (danger) R.color.app_color_accent else R.color.base_black))
        val pad = (resources.displayMetrics.density * 16).toInt()
        tv.setPadding(pad, pad, pad, pad)
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        tv.setBackgroundResource(out.resourceId)
        tv.isClickable = true
        tv.setOnClickListener { action() }
        return tv
    }

    /**
     * 复制到系统剪贴板。
     *
     * 不用 blankj 的 ClipboardUtils：utilcodex 1.23.7 里并没有这个类
     * （154 个 util 类中无剪贴板相关），直接用系统 API 更可靠且零依赖。
     */
    private fun copyPathToClipboard(path: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("YESPDF path", path))
        } catch (e: Throwable) {
            UiManager.showShort(R.string.app_data_op_failed)
        }
    }

    private fun showInfo(item: DataItem) {
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.text = item.title
            tvContent.text = buildNote(item)
            btnLeft.setText(R.string.app_data_copy_path)
            btnLeft.setOnClickListener {
                copyPathToClipboard(item.file.absolutePath)
                UiManager.showShort(R.string.app_data_path_copied)
                dialog?.dismiss()
            }
            btnRight.setText(R.string.app_cancel)
            btnRight.setOnClickListener { dialog?.dismiss() }
        }
        dialog?.show()
    }

    private fun rename(item: DataItem) {
        var dialog: Dialog? = null
        dialog = DialogManager.createInputDialog(this) { tvTitle, etInput, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_data_rename)
            etInput.setText(item.title)
            etInput.setSelection(item.title.length)
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_confirm)
            btnRight.setOnClickListener {
                val newName = etInput.text.toString().trim()
                if (newName.isEmpty()) {
                    UiManager.showShort(R.string.app_data_name_invalid)
                    return@setOnClickListener
                }
                val target = File(item.file.parentFile, newName)
                if (target.exists()) {
                    UiManager.showShort(R.string.app_data_name_exists)
                    return@setOnClickListener
                }
                val moved = item.file.renameTo(target)
                if (moved) {
                    syncCoverPath(item.file, target)
                    dialog?.dismiss()
                    refresh()
                } else {
                    UiManager.showShort(R.string.app_data_op_failed)
                }
            }
        }
        dialog?.show()
    }

    /** 封面改名后同步数据库中的 cover 路径，避免列表回落到占位图 */
    private fun syncCoverPath(oldFile: File, newFile: File) {
        val pdf = DataManager.getPdfList().firstOrNull { it.cover == oldFile.absolutePath } ?: return
        pdf.cover = newFile.absolutePath
        DBHelper.updatePDF(pdf)
        DataManager.updatePDFs()
    }

    private fun copySelected() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) return
        AppClipboard.put(files, false)
        adapter.exitSelection()
        UiManager.showShort(getString(R.string.app_data_copied, files.size))
        updateSelectionBar(false, 0)
    }

    private fun exportSelected() {
        val files = adapter.selectedFiles()
        if (files.isEmpty()) return
        adapter.exitSelection()
        export(files)
    }

    private fun export(files: List<File>) {
        UiManager.showShort(R.string.app_data_exporting)
        Thread {
            val dir = InternalDataScanner.export(files)
            runOnUiThread {
                if (dir == null) {
                    UiManager.showShort(R.string.app_data_op_failed)
                } else {
                    UiManager.showCenterShort(getString(R.string.app_data_exported, dir.absolutePath))
                }
            }
        }.start()
    }

    private fun pasteIntoCurrentDir() {
        val target = currentDir ?: return
        Thread {
            val count = InternalDataScanner.paste(target)
            runOnUiThread {
                UiManager.showShort(getString(R.string.app_data_pasted, count))
                refresh()
            }
        }.start()
    }

    //endregion

    //region 删除（含数据库高危流程）

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        var dialog: BottomSheetDialog? = null
        dialog = DialogManager.createDeleteDialog(this) { tvDescription, _, btnCancel, btnDelete ->
            tvDescription.text = getString(R.string.app_data_delete_desc, files.size)
            btnCancel.setText(R.string.app_cancel)
            btnCancel.setOnClickListener { dialog?.dismiss() }
            btnDelete.setText(R.string.app_delete)
            btnDelete.setOnClickListener {
                var ok = 0
                for (f in files) {
                    val owner = DataManager.getPdfList().firstOrNull { it.cover == f.absolutePath }
                    val deleted = f.deleteRecursively()
                    if (deleted) {
                        ok++
                        // 封面被删 → 清掉引用，书库列表回落到占位图
                        if (owner != null) {
                            owner.cover = ""
                            DBHelper.updatePDF(owner)
                        }
                    }
                }
                if (ok > 0) DataManager.updatePDFs()
                dialog?.dismiss()
                adapter.exitSelection()
                refresh()
                UiManager.showShort(getString(R.string.app_data_deleted, ok))
            }
        }
        dialog?.show()
    }

    private fun confirmDeleteDatabase() {
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_data_delete_db_title)
            tvContent.setText(R.string.app_data_delete_db_content)
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_data_delete_db_confirm)
            btnRight.setOnClickListener {
                dialog?.dismiss()
                deleteDatabaseAndExit()
            }
        }
        dialog?.show()
    }

    /**
     * 高危操作：删除书库数据库并立即退出进程。
     *
     * 刻意**不做热重连**：GreenDAO 已打开的连接仍指向被删除的 inode，
     * 继续读写会落到"幽灵文件"上。交给下次冷启动的 DBHelper.init() 重建空库。
     */
    private fun deleteDatabaseAndExit() {
        try {
            deleteDatabase(AppConfig.DB_NAME)
        } catch (e: Throwable) {
            // 即使删除失败也要退出，避免状态不一致
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    //endregion

    //region 首次提示

    private fun showIntroTipIfNeeded() {
        if (SPStaticUtils.getBoolean(SP_TIP_SHOWN, false)) return
        SPStaticUtils.put(SP_TIP_SHOWN, true)
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_data_intro_title)
            tvContent.setText(R.string.app_data_intro_content)
            btnLeft.setText(R.string.app_data_intro_skip)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_data_intro_ok)
            btnRight.setOnClickListener { dialog?.dismiss() }
        }
        dialog?.show()
    }

    //endregion

    companion object {
        private const val SP_TIP_SHOWN = "SP_DATA_MANAGER_TIP_SHOWN"

        private const val REQ_IMPORT_FILES = 2001
        private const val REQ_IMPORT_TREE = 2002
        private const val REQ_EXPORT_TREE = 2003
        private const val REQ_REPLACE_IMAGE = 2004

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, DataManagerActivity::class.java))
        }
    }
}
