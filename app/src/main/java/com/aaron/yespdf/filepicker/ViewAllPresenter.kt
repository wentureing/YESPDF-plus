package com.aaron.yespdf.filepicker

import com.blankj.utilcode.util.StringUtils
import java.io.File

/**
 * @author Aaron aaronzzxup@gmail.com
 */
class ViewAllPresenter(view: IViewAllView) : IViewAllPresenter(view) {

    private var curPath: String? = ROOT_PATH
    private var isFirstIn = true

    override val model: IViewAllModel = ViewAllModel()

    override fun detach() {
        model.saveLastPath(curPath)
    }

    override fun canFinish(): Boolean {
        return curPath == ROOT_PATH
    }

    override fun goBack() {
        if (curPath != null && curPath?.length != 0) {
            val endIndex = curPath?.lastIndexOf("/") ?: 0
            val prePath = curPath?.substring(0, endIndex)
            listFile(prePath)
        }
    }

    override fun listStorage() {
        if (isFirstIn) {
            isFirstIn = false
            val lastPath = model.queryLastPath()
            if (StringUtils.isEmpty(lastPath) || ROOT_PATH == lastPath) {
                listStorage()
            } else {
                listFile(lastPath)
            }
            return
        }
        model.listStorage { result ->
            fileList.clear()
            if (result != null) {
                for (info in result) {
                    if (info.state == "mounted") {
                        fileList.add(File(info.path))
                    }
                }
            }
            view.onShowFileList(fileList)
        }
    }

    override fun listFile(path: String?) {
        curPath = path
        if (path == ROOT_PATH) {
            listStorage()
            return
        }
        model.listFile(path) { result ->
            path?.run {
                if (length > 18) {
                    val path1 = substring(18) // 去除 /storage/emulated/
                    val paths = listOf(*path1.split("/").toTypedArray())
                    view.onShowPath(paths)
                }
            }
            view.onShowFileList(result)
        }
    }

    /**
     * 原地重载当前目录：切换「应用排除规则」开关后调用，避免列表跳回根目录。
     */
    override fun reload() {
        val path = curPath
        if (path == null || path.isEmpty() || path == ROOT_PATH) {
            listStorage()
        } else {
            listFile(path)
        }
    }
}