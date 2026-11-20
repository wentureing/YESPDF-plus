package com.aaron.yespdf.filepicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import com.aaron.yespdf.R
import com.aaron.yespdf.common.CommonActivity
import com.aaron.yespdf.common.DialogManager
import com.aaron.yespdf.common.ScanRules
import com.aaron.yespdf.common.UiManager
import com.github.anzewei.parallaxbacklayout.ParallaxBack
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.android.synthetic.main.app_activity_scan_rule.*

/**
 * 自动扫描的「扫描范围与排除规则」编辑页。
 *
 * 交互全部复用现有 dialog：
 * - 新增/编辑：DialogManager.createInputDialog（settings 里同款输入框）
 * - 删除：DialogManager.createDeleteDialog（带红色确认按钮的白底 BottomSheet）
 *
 * @author YESPDF-plus
 */
@ParallaxBack
class ScanRuleActivity : CommonActivity() {

    private lateinit var adapter: ScanRuleAdapter

    override fun layoutId(): Int = R.layout.app_activity_scan_rule

    override fun createToolbar(): Toolbar? = findViewById(R.id.app_toolbar)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initToolbar()
        adapter = ScanRuleAdapter(object : ScanRuleAdapter.Listener {
            override fun onToggle(enabled: Boolean) {
                ScanRules.enabled = enabled
                adapter.refresh()
            }

            override fun onAdd(kind: Int) {
                showInputDialog(kind, null)
            }

            override fun onEdit(kind: Int, value: String) {
                showInputDialog(kind, value)
            }

            override fun onDelete(kind: Int, value: String) {
                confirmDelete(kind, value)
            }
        })
        app_rv_rule.layoutManager = LinearLayoutManager(this)
        app_rv_rule.adapter = adapter
        adapter.refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initToolbar() {
        supportActionBar?.run {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.app_ic_action_back_black)
        }
        toolbar?.setTitle(R.string.app_scan_rule)
    }

    private fun showInputDialog(kind: Int, oldValue: String?) {
        val isFilePattern = kind == ScanRules.KIND_EXCLUDE_FILE
        var dialog: android.app.Dialog? = null
        dialog = DialogManager.createInputDialog(this) { tvTitle, etInput, btnLeft, btnRight ->
            tvTitle.setText(if (oldValue == null) titleForAdd(kind) else R.string.app_scan_rule_edit)
            etInput.hint = getString(
                    if (isFilePattern) R.string.app_scan_rule_hint_file
                    else R.string.app_scan_rule_hint_dir)

            val initText = oldValue
                    ?: if (isFilePattern) "" else ScanRules.suggestDefaultRoot()
            etInput.setText(initText)
            if (initText.isNotEmpty()) {
                etInput.setSelection(initText.length)
            }

            btnLeft.setText(R.string.app_cancel)
            btnLeft.setOnClickListener { dialog?.dismiss() }

            btnRight.setText(R.string.app_confirm)
            btnRight.setOnClickListener {
                val text = etInput.text.toString()
                val saved = if (oldValue == null) {
                    ScanRules.add(kind, text)
                } else {
                    ScanRules.replace(kind, oldValue, text)
                }
                if (saved) {
                    adapter.refresh()
                    UiManager.showShort(R.string.app_scan_rule_saved)
                    dialog?.dismiss()
                } else {
                    UiManager.showShort(R.string.app_scan_rule_invalid)
                }
            }
        }
        dialog?.show()
    }

    private fun confirmDelete(kind: Int, value: String) {
        var dialog: BottomSheetDialog? = null
        dialog = DialogManager.createDeleteDialog(this) { tvDescription, _, btnCancel, btnDelete ->
            tvDescription.text = getString(R.string.app_scan_rule_delete_desc, value)
            btnCancel.setText(R.string.app_cancel)
            btnCancel.setOnClickListener { dialog?.dismiss() }
            btnDelete.setText(R.string.app_delete)
            btnDelete.setOnClickListener {
                ScanRules.remove(kind, value)
                adapter.refresh()
                dialog?.dismiss()
            }
        }
        dialog?.show()
    }

    private fun titleForAdd(kind: Int): Int = when (kind) {
        ScanRules.KIND_INCLUDE_DIR -> R.string.app_scan_rule_add_include
        ScanRules.KIND_EXCLUDE_DIR -> R.string.app_scan_rule_add_exclude_dir
        else -> R.string.app_scan_rule_add_exclude_file
    }

    companion object {
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, ScanRuleActivity::class.java))
        }

        @JvmStatic
        fun startForResult(activity: Activity, requestCode: Int) {
            activity.startActivityForResult(
                    Intent(activity, ScanRuleActivity::class.java), requestCode)
        }
    }
}
