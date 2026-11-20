package com.aaron.yespdf.data

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aaron.yespdf.R
import kotlinx.android.synthetic.main.app_recycler_item_filepicker.view.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 内部数据列表适配器。
 *
 * 复用「浏览全部」的条目布局 app_recycler_item_filepicker（图标 + 标题 + 描述 + 箭头 + 复选框），
 * 因此多选视觉与导入页完全一致。
 *
 * @author YESPDF-plus
 */
internal class DataFileAdapter(
        private val callback: Callback
) : RecyclerView.Adapter<DataFileAdapter.Holder>() {

    interface Callback {
        fun onClick(item: DataItem)
        fun onSelectionModeChanged(inSelection: Boolean, count: Int)
    }

    private val items: MutableList<DataItem> = ArrayList()
    private val selected: MutableSet<String> = LinkedHashSet()

    var selectionMode: Boolean = false
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<DataItem>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedFiles(): List<File> =
            items.filter { selected.contains(it.file.absolutePath) }.map { it.file }

    fun selectedCount(): Int = selected.size

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(select: Boolean) {
        selected.clear()
        if (select) {
            for (item in items) selected.add(item.file.absolutePath)
        }
        notifyDataSetChanged()
        callback.onSelectionModeChanged(selectionMode, selected.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun exitSelection() {
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        callback.onSelectionModeChanged(false, 0)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun enterSelection(item: DataItem) {
        selectionMode = true
        selected.add(item.file.absolutePath)
        notifyDataSetChanged()
        callback.onSelectionModeChanged(true, selected.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggle(item: DataItem) {
        val path = item.file.absolutePath
        if (!selected.remove(path)) selected.add(path)
        if (selected.isEmpty()) {
            selectionMode = false
        }
        notifyDataSetChanged()
        callback.onSelectionModeChanged(selectionMode, selected.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.app_recycler_item_filepicker, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("SetTextI18n", "SimpleDateFormat")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val view = holder.itemView

        view.app_iv_icon.setImageResource(item.iconRes)
        view.app_tv_title.text = item.title

        val size = InternalDataScanner.formatSize(item.size)
        view.app_tv_description.text = if (item.isDir) {
            "${item.note} · ${item.childCount} 项 · $size"
        } else {
            val time = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
                    .format(Date(item.file.lastModified()))
            "${item.note} · $size · $time"
        }

        view.app_iv_next.visibility = if (item.isDir && !selectionMode) View.VISIBLE else View.GONE
        // 选择模式下目录也可勾选（目录同样支持删除/复制/导出/重命名）
        view.app_cb.visibility = if (selectionMode) View.VISIBLE else View.GONE
        view.app_cb.isChecked = selected.contains(item.file.absolutePath)

        view.setOnClickListener {
            if (selectionMode) {
                toggle(item)
            } else {
                callback.onClick(item)
            }
        }
        // 长按文件或目录都可进入选择模式
        view.setOnLongClickListener {
            enterSelection(item)
            true
        }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
