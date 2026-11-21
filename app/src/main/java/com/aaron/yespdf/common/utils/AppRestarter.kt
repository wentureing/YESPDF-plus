package com.aaron.yespdf.common.utils

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blankj.utilcode.util.LogUtils

/**
 * 真正重启应用（而不是只把进程杀掉）。
 *
 * 之前的实现只做了 `finishAffinity() + killProcess() + exitProcess()`：
 * 进程确实被杀掉了，但**没有任何东西把它拉起来**，用户看到的就是"应用闪退/退出"。
 *
 * 这里用 AlarmManager 在进程死亡后延迟触发一个 launcher PendingIntent：
 * 即使当前进程已结束，系统闹钟仍会把它重新拉起来 —— 这才是用户感知到的"重启"。
 *
 * 刻意不引用 MainActivity 类：用 `getLaunchIntentForPackage` 拿入口，
 * 这样本工具留在 common 层也不会造成 common → main 的分层违规。
 *
 * @author YESPDF-plus
 */
object AppRestarter {

    private const val REQUEST_CODE = 1001

    /** 延迟多少毫秒后再启动（留时间让当前进程干净退出） */
    private const val DELAY_MS = 600L

    fun restart(context: Context) {
        val app = context.applicationContext
        try {
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getActivity(app, REQUEST_CODE, launch, flags)
                val alarm = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                if (alarm != null) {
                    alarm.set(AlarmManager.RTC, System.currentTimeMillis() + DELAY_MS, pending)
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("AppRestarter: " + t.message)
        }

        // 兜底：闹钟不可用时，先尝试直接拉起（部分 ROM 允许），再退出
        try {
            val fallback = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                app.startActivity(fallback)
            }
        } catch (t: Throwable) {
            // 忽略：闹钟那条路仍然有效
        }

        if (context is Activity) {
            try {
                context.finishAffinity()
            } catch (t: Throwable) {
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }
}
