package com.aaron.yespdf.common.statistic

import android.content.Context

/**
 * 统计埋点占位实现。
 *
 * 上游使用友盟（UMeng）SDK；本项目已移除该 SDK（隐私与体积考虑），
 * 这里保留同名方法作为 no-op，避免改动所有调用点。
 *
 * 用户数据目录中的 umeng 残留（ua.db、umeng_*.xml）可在
 * 「应用数据管理 → 清理已移除 SDK 的残留」里检查和删除。
 *
 * @author aaronzzxup@gmail.com
 */
object Statistic {

    fun init(context: Context) {
        // no-op：不再初始化任何统计 SDK
    }

    fun setLogEnabled(value: Boolean) {
        // no-op
    }

    fun onResume(context: Context) {
        // no-op
    }

    fun onPause(context: Context) {
        // no-op
    }
}
