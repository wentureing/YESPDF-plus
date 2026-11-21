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
import com.aaron.yespdf.common.utils.AppRestarter
import com.aaron.yespdf.common.utils.DialogUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPStaticUtils
import com.github.anzewei.parallaxbacklayout.ParallaxBack
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.android.synthetic.main.app_activity_data_manager.*
import java.io.File
import java.io.FileOutputStream
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
    /** 阻塞式进度弹窗：导入/恢复大文件时一直显示，直到流程结束（如"立即重启"提示出现） */
    private var blockingDialog: Dialog? = null
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
        app_btn_rename.setOnClickListener { renameSelected() }
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
            R.id.app_menu_clean_covers -> confirmCoverAction(CoverAction.CLEAN)
            R.id.app_menu_compress_covers -> confirmCoverAction(CoverAction.COMPRESS)
            R.id.app_menu_reorganize_covers -> confirmCoverAction(CoverAction.REORG)
            R.id.app_menu_auto_restore -> pickAutoRestore()
            R.id.app_menu_import_files -> pickImportFiles()
            R.id.app_menu_import_dir -> pickImportTree()
            R.id.app_menu_export_dir -> pickExportTree()
            R.id.app_menu_refresh -> refresh()
        }
        return true
    }

    /**
     * 选文件：故意用通配符 type 且**不设 EXTRA_MIME_TYPES**。
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

    //region 封面维护（清理 / 压缩 / 整理）

    private enum class CoverAction { CLEAN, COMPRESS, REORG }

    private fun confirmCoverAction(action: CoverAction) {
        val titleRes: Int
        val contentRes: Int
        when (action) {
            CoverAction.CLEAN -> {
                titleRes = R.string.app_data_cover_clean
                contentRes = R.string.app_data_cover_clean_desc
            }
            CoverAction.COMPRESS -> {
                titleRes = R.string.app_data_cover_compress
                contentRes = R.string.app_data_cover_compress_desc
            }
            CoverAction.REORG -> {
                titleRes = R.string.app_data_cover_reorg
                contentRes = R.string.app_data_cover_reorg_desc
            }
        }
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.setText(titleRes)
            tvContent.setText(contentRes)
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_data_cover_action_go)
            btnRight.setOnClickListener {
                dialog?.dismiss()
                runCoverAction(action)
            }
        }
        dialog?.show()
    }

    private fun runCoverAction(action: CoverAction) {
        showBlocking(when (action) {
            CoverAction.CLEAN -> R.string.app_data_cover_cleaning
            CoverAction.COMPRESS -> R.string.app_data_cover_compressing
            CoverAction.REORG -> R.string.app_data_cover_organizing
        })
        Thread {
            try {
                val ctx = applicationContext
                val summary = when (action) {
                    CoverAction.CLEAN -> {
                        val r = CoverOps.deleteOrphans(ctx)
                        getString(R.string.app_data_cover_clean_done,
                                r.changed, InternalDataScanner.formatSize(r.beforeBytes))
                    }
                    CoverAction.COMPRESS -> {
                        val r = CoverOps.compress(ctx)
                        getString(R.string.app_data_cover_compress_done,
                                r.changed, r.skipped,
                                InternalDataScanner.formatSize(r.beforeBytes),
                                InternalDataScanner.formatSize(r.afterBytes))
                    }
                    CoverAction.REORG -> {
                        val r = CoverOps.reorganize(ctx)
                        getString(R.string.app_data_cover_reorg_done, r.changed, r.skipped)
                    }
                }
                runOnUiThread {
                    dismissBlocking()
                    refresh()
                    loadOverview()
                    UiManager.showCenterShort(summary)
                }
            } catch (t: Throwable) {
                LogUtils.w("cover action failed: " + t.message)
                runOnUiThread {
                    dismissBlocking()
                    UiManager.showShort(R.string.app_data_op_failed)
                }
            }
        }.start()
    }

    //endregion

    /**
     * 「从备份自动恢复」：只需选一次备份目录，应用按文件类型各自归位，不再依赖"当前在哪个目录"。
     *
     * - yespdf.db            → databases/（覆盖前自动备份成 yespdf.db.bak-<时间戳>）
     * - spUtils.xml 等设置   → shared_prefs/（跳过 umeng 残留）
     * - .jpg / .png          → 应用数据根目录（PDF.COVER 记录的绝对路径就在这里）
     *
     * 这样即使用户选了 YESPDF_restore 这种"包含 db/ sp/ covers/ 的备份根目录"也不会放错。
     */
    private fun pickAutoRestore() {
        showAutoRestoreTipIfNeeded {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(intent, REQ_AUTO_RESTORE)
        }
    }

    private fun showAutoRestoreTipIfNeeded(action: () -> Unit) {
        if (SPStaticUtils.getBoolean(SP_AUTO_RESTORE_TIP, false)) {
            action()
            return
        }
        SPStaticUtils.put(SP_AUTO_RESTORE_TIP, true)
        var dialog: Dialog? = null
        dialog = DialogManager.createDoubleBtnDialog(this) { tvTitle, tvContent, btnLeft, btnRight ->
            tvTitle.setText(R.string.app_data_auto_restore_tip_title)
            tvContent.setText(R.string.app_data_auto_restore_tip_content)
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_data_auto_restore_go)
            btnRight.setOnClickListener {
                dialog?.dismiss()
                action()
            }
        }
        dialog?.show()
    }

    private fun handleAutoRestore(treeUri: Uri) {
        DocumentTreeHelper.takePermissions(contentResolver, treeUri)
        val dataDir = filesDir.parentFile
        val dbDir = File(dataDir, "databases")
        val spDir = File(dataDir, "shared_prefs")
        showBlocking(R.string.app_data_auto_restore_running)
        Thread {
            val docs = DocumentTreeHelper.collectFiles(contentResolver, treeUri)
            var db = 0
            var sp = 0
            var img = 0
            for (doc in docs) {
                val name = doc.name
                val lower = name.toLowerCase()
                try {
                    when {
                        name.equals(AppConfig.DB_NAME, ignoreCase = true) -> {
                            if (!dbDir.exists()) dbDir.mkdirs()
                            val dst = File(dbDir, AppConfig.DB_NAME)
                            if (dst.exists()) InternalDataScanner.backupBeforeOverwrite(dst)
                            contentResolver.openInputStream(doc.uri)?.use { input ->
                                FileOutputStream(dst).use { output -> input.copyTo(output) }
                            }
                            db++
                        }
                        lower.endsWith(".xml") &&
                                !lower.startsWith("umeng") && !lower.startsWith("um_") &&
                                (lower.startsWith("sp") || lower.contains("pref")) -> {
                            if (!spDir.exists()) spDir.mkdirs()
                            val dst = File(spDir, name)
                            contentResolver.openInputStream(doc.uri)?.use { input ->
                                FileOutputStream(dst).use { output -> input.copyTo(output) }
                            }
                            sp++
                        }
                        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> {
                            if (dataDir != null) {
                                val dst = File(dataDir, name)
                                contentResolver.openInputStream(doc.uri)?.use { input ->
                                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                                }
                                img++
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 单个文件失败不影响其余
                }
            }
            val countDb = db; val countSp = sp; val countImg = img
            runOnUiThread {
                dismissBlocking()
                refresh()
                if (countDb == 0 && countSp == 0 && countImg == 0) {
                    UiManager.showCenterShort(R.string.app_data_auto_restore_none)
                } else {
                    UiManager.showShort(getString(R.string.app_data_auto_restore_done,
                            countDb, countSp, countImg))
                    // 恢复数据库后需要重启才生效
                    if (countDb > 0) promptRestartAfterDbImport()
                }
            }
        }.start()
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
            REQ_AUTO_RESTORE -> data.data?.let { handleAutoRestore(it) }
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

        showBlocking(R.string.app_data_importing_wait)
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
                dismissBlocking()
                UiManager.showShort(getString(R.string.app_data_imported, count))
                refresh()
                if (touchedDb) promptRestartAfterDbImport()
            }
        }.start()
    }

    private fun handleImportTree(treeUri: Uri) {
        DocumentTreeHelper.takePermissions(contentResolver, treeUri)
        val target = currentDir ?: filesDir
        showBlocking(R.string.app_data_importing_wait)
        Thread {
            val count = DocumentTreeHelper.copyTreeToDir(contentResolver, treeUri, target)
            // 用户很容易选错层级（把包含 db/ sp/ 的备份根目录整棵导入），
            // 所以这里主动检查三种情况，而不是只看 target 本身
            val direct = File(target, AppConfig.DB_NAME).exists()
            val nested = target.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && File(dir, AppConfig.DB_NAME).exists()
            }
            runOnUiThread {
                dismissBlocking()
                refresh()
                when {
                    direct -> {
                        UiManager.showShort(getString(R.string.app_data_imported, count))
                        promptRestartAfterDbImport()
                    }
                    nested != null -> UiManager.showCenterShort(
                            getString(R.string.app_data_import_nested, nested.name))
                    else -> UiManager.showShort(
                            getString(R.string.app_data_imported_not_found, count))
                }
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
                // 真重启：AlarmManager 在进程退出后拉起 launcher，而不是只杀进程
                AppRestarter.restart(this)
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
        // "旧封面"这类 root 只列直属文件，避免与 files/cache 等重复
        val filesOnly = roots.firstOrNull { it.dir.absolutePath == dir.absolutePath }?.filesOnly ?: false
        Thread {
            val items = InternalDataScanner.list(dir, filesOnly)
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
                if (root.filesOnly) {
                    // 旧封面：只统计 dataDir 的直属文件，不递归（否则重复计算子目录）
                    root.dir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            total += f.length()
                            files++
                        }
                    }
                    continue
                }
                total += InternalDataScanner.sizeOf(root.dir)
                files += InternalDataScanner.countFiles(root.dir)
            }
            val text = getString(R.string.app_data_overview,
                    InternalDataScanner.formatSize(total), files, PasswordVault.count())
            runOnUiThread { app_tv_overview.text = text }
        }.start()
    }

    /**
     * 显示阻塞进度窗。数据量大时（如 156MB 封面）复制要几十秒，
     * 只给一个 toast 会让人以为没反应，所以这里全程阻塞并禁止返回键取消。
     */
    private fun showBlocking(messageRes: Int) {
        dismissBlocking()
        try {
            val dialog = DialogManager.createLoadingDialog(this)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            (dialog.findViewById<View>(R.id.app_tv_dialog_content) as? TextView)?.setText(messageRes)
            dialog.show()
            blockingDialog = dialog
        } catch (t: Throwable) {
            LogUtils.w("showBlocking: " + t.message)
        }
    }

    private fun dismissBlocking() {
        try {
            blockingDialog?.dismiss()
        } catch (t: Throwable) {
        }
        blockingDialog = null
    }

    override fun onDestroy() {
        dismissBlocking()
        super.onDestroy()
    }

    private fun updateSelectionBar(inSelection: Boolean, count: Int) {
        app_ll_selection.visibility = if (inSelection) View.VISIBLE else View.GONE
        app_btn_paste.visibility =
                if (!inSelection && !AppClipboard.isEmpty && currentDir != null) View.VISIBLE else View.GONE
        if (!inSelection && !AppClipboard.isEmpty) {
            app_btn_paste.text = getString(R.string.app_data_paste, AppClipboard.count)
        }
        app_btn_rename.isEnabled = count == 1
        app_btn_rename.alpha = if (count == 1) 1f else 0.4f
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
                renameFile(item.file)
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

    /** 选择模式下点「重命名」：仅支持单选（文件或目录都可以） */
    private fun renameSelected() {
        val files = adapter.selectedFiles()
        if (files.size != 1) {
            UiManager.showShort(R.string.app_data_rename_multi)
            return
        }
        val target = files[0]
        adapter.exitSelection()
        renameFile(target)
    }

    /**
     * 重命名文件或目录。
     *
     * 目录改名会改变其中文件的绝对路径，因此改完要把 PDF.COVER 的前缀一起替换；
     * 标准目录（files/databases/shared_prefs/cache）改名后应用会重建空目录，
     * 旧数据仍留在改名后的目录里、可在本页继续操作。
     */
    private fun renameFile(file: File) {
        val isRootDir = file.isDirectory && roots.any { it.dir.absolutePath == file.absolutePath }
        var dialog: Dialog? = null
        dialog = DialogManager.createInputDialog(this) { tvTitle, etInput, btnLeft, btnRight ->
            tvTitle.setText(if (isRootDir) R.string.app_data_rename_root else R.string.app_data_rename)
            etInput.setText(file.name)
            etInput.setSelection(file.name.length)
            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }
            btnRight.setText(R.string.app_confirm)
            btnRight.setOnClickListener {
                val newName = etInput.text.toString().trim()
                if (newName.isEmpty()) {
                    UiManager.showShort(R.string.app_data_name_invalid)
                    return@setOnClickListener
                }
                val target = File(file.parentFile, newName)
                if (target.exists()) {
                    UiManager.showShort(R.string.app_data_name_exists)
                    return@setOnClickListener
                }
                val moved = file.renameTo(target)
                if (moved) {
                    syncCoverPath(file, target)
                    dialog?.dismiss()
                    refresh()
                } else {
                    UiManager.showShort(R.string.app_data_op_failed)
                }
            }
        }
        dialog?.show()
    }

    /**
     * 改名后同步数据库里的封面路径。
     *
     * 用**前缀替换**而不是精确匹配，这样目录改名也能一次性修好其下所有封面路径，
     * 避免书库列表回落到占位图。
     */
    private fun syncCoverPath(oldFile: File, newFile: File) {
        val oldPrefix = oldFile.absolutePath
        val affected = DataManager.getPdfList().filter {
            !it.cover.isNullOrEmpty() && it.cover.startsWith(oldPrefix)
        }
        if (affected.isEmpty()) return
        for (pdf in affected) {
            val cover = pdf.cover ?: continue
            pdf.cover = cover.replaceFirst(oldPrefix, newFile.absolutePath)
            DBHelper.updatePDF(pdf)
        }
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
        showBlocking(R.string.app_data_exporting)
        Thread {
            val dir = InternalDataScanner.export(files)
            runOnUiThread {
                dismissBlocking()
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
        showBlocking(R.string.app_data_importing_wait)
        Thread {
            val count = InternalDataScanner.paste(target)
            val touchedDb = File(target, AppConfig.DB_NAME).exists()
            runOnUiThread {
                dismissBlocking()
                UiManager.showShort(getString(R.string.app_data_pasted, count))
                refresh()
                if (touchedDb) promptRestartAfterDbImport()
            }
        }.start()
    }

    //endregion

    //region 删除（含数据库高危流程）

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        var dialog: BottomSheetDialog? = null
        dialog = DialogManager.createDeleteDialog(this) { tvDescription, _, btnCancel, btnDelete ->
            val hasRootDir = files.any { f ->
                f.isDirectory && roots.any { it.dir.absolutePath == f.absolutePath }
            }
            tvDescription.text = getString(
                    if (hasRootDir) R.string.app_data_delete_root_desc else R.string.app_data_delete_desc,
                    files.size)
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
            // 即使删除失败也要重启，避免状态不一致
        }
        // 删除后直接重启到"空书库"，用户不需要再去桌面点一次图标
        AppRestarter.restart(this)
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
        private const val SP_AUTO_RESTORE_TIP = "SP_AUTO_RESTORE_TIP_SHOWN"

        private const val REQ_AUTO_RESTORE = 2000
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
