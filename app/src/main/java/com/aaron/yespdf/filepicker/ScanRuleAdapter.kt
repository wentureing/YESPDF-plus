package com.aaron.yespdf.filepicker

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aaron.yespdf.R
import com.aaron.yespdf.common.ScanRules
import kotlinx.android.synthetic.main.app_recycler_item_filepicker.view.*

/**
 * 扫描规则列表适配器。
 *
 * 复用两类现成行布局，**不新增任何 item 布局**：
 * - 开关行：app_recycler_item_settings_switch（总开关）
 * - 标题/条目/添加行：app_recycler_item_filepicker（图标 + 标题 + 描述 + 箭头）
 *
 * @author YESPDF-plus
 */
internal class ScanRuleAdapter(
        private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onToggle(enabled: Boolean)
        fun onAdd(kind: Int)
        fun onEdit(kind: Int, value: String)
        fun onDelete(kind: Int, value: String)
    }

    private val rows: MutableList<Row> = ArrayList()

    /**
     * 按当前规则重建列表。规则始终可编辑（即使总开关关闭），
     * 便于用户先配置好再启用 —— 符合"勾选时用上"的语义。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        rows.clear()
        rows.add(Row(TYPE_SWITCH, titleRes = R.string.app_scan_rule_enable, checked = ScanRules.enabled))

        addGroup(ScanRules.KIND_INCLUDE_DIR,
                R.string.app_scan_rule_include,
                R.string.app_scan_rule_include_desc,
                R.string.app_scan_rule_add_include,
                R.drawable.app_ic_folder_yellow_24dp)

        addGroup(ScanRules.KIND_EXCLUDE_DIR,
                R.string.app_scan_rule_exclude_dir,
                R.string.app_scan_rule_exclude_dir_desc,
                R.string.app_scan_rule_add_exclude_dir,
                R.drawable.app_ic_folder_yellow_24dp)

        addGroup(ScanRules.KIND_EXCLUDE_FILE,
                R.string.app_scan_rule_exclude_file,
                R.string.app_scan_rule_exclude_file_desc,
                R.string.app_scan_rule_add_exclude_file,
                R.drawable.app_ic_pdf_red_24dp)

        notifyDataSetChanged()
    }

    private fun addGroup(kind: Int, titleRes: Int, descRes: Int, addRes: Int, iconRes: Int) {
        rows.add(Row(TYPE_HEADER, kind = kind, titleRes = titleRes, descRes = descRes))
        for (value in ScanRules.listOf(kind)) {
            rows.add(Row(TYPE_ITEM, kind = kind, value = value, iconRes = iconRes))
        }
        rows.add(Row(TYPE_ADD, kind = kind, titleRes = addRes, iconRes = R.drawable.app_ic_action_import_black))
    }

    override fun getItemViewType(position: Int): Int = rows[position].type

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SWITCH) {
            SwitchHolder(inflater.inflate(R.layout.app_recycler_item_settings_switch, parent, false))
        } else {
            RowHolder(inflater.inflate(R.layout.app_recycler_item_filepicker, parent, false))
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is SwitchHolder -> {
                holder.title.setText(row.titleRes)
                holder.switch.isChecked = row.checked
                holder.itemView.setOnClickListener {
                    val next = !holder.switch.isChecked
                    holder.switch.isChecked = next
                    listener.onToggle(next)
                }
            }
            is RowHolder -> {
                val item = holder.itemView
                item.app_cb.visibility = View.GONE
                item.app_iv_next.visibility = View.GONE
                item.app_tv_description.visibility =
                        if (row.type == TYPE_HEADER) View.VISIBLE else View.GONE
                val pad = dp(item, 16)
                val padV = if (row.type == TYPE_HEADER) dp(item, 24) else pad
                item.setPadding(pad, padV, pad, padV)

                when (row.type) {
                    TYPE_HEADER -> {
                        item.app_tv_title.setText(row.titleRes)
                        item.app_tv_title.setTextColor(item.resources.getColor(R.color.base_black))
                        item.app_tv_description.setText(row.descRes)
                        item.app_iv_icon.visibility = View.GONE
                        item.isClickable = false
                        item.setOnClickListener(null)
                        item.setOnLongClickListener(null)
                    }
                    TYPE_ADD -> {
                        item.app_tv_title.setText(row.titleRes)
                        item.app_iv_icon.visibility = View.VISIBLE
                        item.app_iv_icon.setImageResource(row.iconRes)
                        item.setOnClickListener { listener.onAdd(row.kind) }
                        item.setOnLongClickListener(null)
                    }
                    else -> { // TYPE_ITEM
                        item.app_tv_title.text = row.value
                        item.app_iv_icon.visibility = View.VISIBLE
                        item.app_iv_icon.setImageResource(row.iconRes)
                        item.setOnClickListener { listener.onEdit(row.kind, row.value) }
                        item.setOnLongClickListener {
                            listener.onDelete(row.kind, row.value)
                            true
                        }
                    }
                }
            }
        }
    }

    private fun dp(view: View, value: Int): Int =
            (view.resources.displayMetrics.density * value + 0.5f).toInt()

    internal class SwitchHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // 刻意用 findViewById 而不是合成视图：本文件同时引用两种 item 布局，
        // 而它们都有 app_tv_title（id 相同），合成属性会歧义。
        val title: TextView = itemView.findViewById(R.id.app_tv_title)
        val switch: Switch = itemView.findViewById(R.id.app_switch)
    }

    internal class RowHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private class Row(
            val type: Int,
            val kind: Int = 0,
            val titleRes: Int = 0,
            val descRes: Int = 0,
            val value: String = "",
            val iconRes: Int = 0,
            val checked: Boolean = false
    )

    companion object {
        private const val TYPE_SWITCH = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_ITEM = 2
        private const val TYPE_ADD = 3
    }
}
