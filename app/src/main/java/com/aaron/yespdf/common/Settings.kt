package com.aaron.yespdf.common

import com.blankj.utilcode.util.SPStaticUtils

/**
 * 全局设置
 * <p>
 * @author aaronzzxup@gmail.com
 * @since 2020/6/13
 */
object Settings {

    private const val SP_LOCK_LANDSCAPE = "SP_LOCK_LANDSCAPE"
    private const val SP_MAX_RECENT_COUNT = "SP_MAX_RECENT_COUNT"
    private const val SP_SWIPE_HORIZONTAL = "SP_SWIPE_HORIZONTAL"
    private const val SP_NIGHT_MODE = "SP_NIGHT_MODE"
    private const val SP_NIGHT_DISPLAY = "SP_NIGHT_DISPLAY"
    private const val SP_NIGHT_DISPLAY_LAST = "SP_NIGHT_DISPLAY_LAST"
    private const val SP_NIGHT_TIP_SHOWN = "SP_NIGHT_TIP_SHOWN"
    private const val SP_NIGHT_MASK_ALPHA = "SP_NIGHT_MASK_ALPHA"
    private const val SP_VOLUME_CONTROL = "SP_VOLUME_CONTROL"
    private const val SP_SHOW_STATUS_BAR = "SP_SHOW_STATUS_BAR"
    private const val SP_SCROLL_LEVEL = "SP_SCROLL_LEVEL"
    private const val SP_CLICK_FLIP_PAGE = "SP_CLICK_FLIP_PAGE"
    private const val SP_KEEP_SCREEN_ON = "SP_KEEP_SCREEN_ON"
    private const val SP_LINEAR_LAYOUT = "SP_LINEAR_LAYOUT"
    private const val SP_SCROLL_SHORTCUT = "SP_SCROLL_SHORTCUT"
    private const val SP_AUTO_SCROLL_TIPS = "SP_AUTO_SCROLL_TIPS"
    private const val SP_FIRST_CREATE_SHORTCUT = "SP_FIRST_CREATE_SHORTCUT"
    private const val SP_GLOBAL_GREY = "SP_GLOBAL_GREY"
    private const val SP_HIDE_SCROLL_LEVEL_BAR = "SP_HIDE_SCROLL_LEVEL_BAR"

    /** 夜间显示：关闭 */
    const val NIGHT_DISPLAY_OFF = 0

    /** 夜间显示：反色（负片），适合黑白文档 */
    const val NIGHT_DISPLAY_INVERT = 1

    /** 夜间显示：蒙版（半透明黑覆盖），适合彩色文档 */
    const val NIGHT_DISPLAY_MASK = 2

    var lockLandscape: Boolean
        get() = SPStaticUtils.getBoolean(SP_LOCK_LANDSCAPE, false)
        set(value) = SPStaticUtils.put(SP_LOCK_LANDSCAPE, value)

    var maxRecentCount: String
        get() = SPStaticUtils.getString(SP_MAX_RECENT_COUNT, "9")
        set(value) = SPStaticUtils.put(SP_MAX_RECENT_COUNT, value)

    var swipeHorizontal: Boolean
        get() = SPStaticUtils.getBoolean(SP_SWIPE_HORIZONTAL, true)
        set(value) = SPStaticUtils.put(SP_SWIPE_HORIZONTAL, value)

    /**
     * 旧版单一夜间模式开关（反色）。保留读写以兼容老数据，
     * 新逻辑一律使用 [nightDisplay]。
     */
    @Deprecated("use nightDisplay")
    var nightMode: Boolean
        get() = SPStaticUtils.getBoolean(SP_NIGHT_MODE, false)
        set(value) = SPStaticUtils.put(SP_NIGHT_MODE, value)

    /**
     * 夜间显示模式：二选一
     * - [NIGHT_DISPLAY_OFF] 关闭
     * - [NIGHT_DISPLAY_INVERT] 反色（负片），适合黑白文档
     * - [NIGHT_DISPLAY_MASK] 蒙版（叠半透明黑），适合彩色文档，保色只压亮度
     */
    var nightDisplay: Int
        get() = SPStaticUtils.getInt(SP_NIGHT_DISPLAY, NIGHT_DISPLAY_OFF)
        set(value) {
            SPStaticUtils.put(SP_NIGHT_DISPLAY, value)
            if (value != NIGHT_DISPLAY_OFF) {
                SPStaticUtils.put(SP_NIGHT_DISPLAY_LAST, value)
            }
        }

    /**
     * 上一次启用过的夜间显示模式，用于"单击在 关闭 ↔ 上次模式 之间切换"
     */
    var lastNightDisplay: Int
        get() = SPStaticUtils.getInt(SP_NIGHT_DISPLAY_LAST, NIGHT_DISPLAY_INVERT)
        set(value) = SPStaticUtils.put(SP_NIGHT_DISPLAY_LAST, value)

    /**
     * 是否已提示过"长按可切换 反色/蒙版"
     */
    var nightTipShown: Boolean
        get() = SPStaticUtils.getBoolean(SP_NIGHT_TIP_SHOWN, false)
        set(value) = SPStaticUtils.put(SP_NIGHT_TIP_SHOWN, value)

    /**
     * 蒙版强度（百分比，10~90），默认 65
     */
    var nightMaskAlpha: Int
        get() = SPStaticUtils.getInt(SP_NIGHT_MASK_ALPHA, 65)
        set(value) = SPStaticUtils.put(SP_NIGHT_MASK_ALPHA, value.coerceIn(10, 90))

    /**
     * 从旧版单开关迁移：老用户若开着夜间模式，等价于"反色"。
     * 仅在首次读取时执行一次（用 -1 作为"未初始化"哨兵）。
     */
    fun migrateNightDisplayIfNeeded() {
        if (SPStaticUtils.getInt(SP_NIGHT_DISPLAY, -1) != -1) return
        val legacy = SPStaticUtils.getBoolean(SP_NIGHT_MODE, false)
        nightDisplay = if (legacy) NIGHT_DISPLAY_INVERT else NIGHT_DISPLAY_OFF
    }

    var volumeControl: Boolean
        get() = SPStaticUtils.getBoolean(SP_VOLUME_CONTROL, true)
        set(value) = SPStaticUtils.put(SP_VOLUME_CONTROL, value)

    var showStatusBar: Boolean
        get() = SPStaticUtils.getBoolean(SP_SHOW_STATUS_BAR, false)
        set(value) = SPStaticUtils.put(SP_SHOW_STATUS_BAR, value)

    var scrollLevel: Long
        get() = SPStaticUtils.getLong(SP_SCROLL_LEVEL, 8L)
        set(value) = SPStaticUtils.put(SP_SCROLL_LEVEL, value)

    var clickFlipPage: Boolean
        get() = SPStaticUtils.getBoolean(SP_CLICK_FLIP_PAGE, true)
        set(value) = SPStaticUtils.put(SP_CLICK_FLIP_PAGE, value)

    var keepScreenOn: Boolean
        get() = SPStaticUtils.getBoolean(SP_KEEP_SCREEN_ON, false)
        set(value) = SPStaticUtils.put(SP_KEEP_SCREEN_ON, value)

    var linearLayout: Boolean
        get() = SPStaticUtils.getBoolean(SP_LINEAR_LAYOUT, false)
        set(value) = SPStaticUtils.put(SP_LINEAR_LAYOUT, value)

    var scrollShortCut: Boolean
        get() = SPStaticUtils.getBoolean(SP_SCROLL_SHORTCUT, false)
        set(value) = SPStaticUtils.put(SP_SCROLL_SHORTCUT, value)

    /**
     * 用于第一次打开自动滚动时的教程弹窗
     */
    var autoScrollTipsHasShown: Boolean
        get() = SPStaticUtils.getBoolean(SP_AUTO_SCROLL_TIPS, false)
        set(value) = SPStaticUtils.put(SP_AUTO_SCROLL_TIPS, value)

    /**
     * 用于第一次创建桌面快捷方式
     */
    var firstCreateShortcut: Boolean
        get() = SPStaticUtils.getBoolean(SP_FIRST_CREATE_SHORTCUT, true)
        set(value) = SPStaticUtils.put(SP_FIRST_CREATE_SHORTCUT, value)

    /**
     * 全局灰度开关
     */
    var globalGrey: Boolean
        get() = SPStaticUtils.getBoolean(SP_GLOBAL_GREY, false)
        set(value) = SPStaticUtils.put(SP_GLOBAL_GREY, value)

    /**
     * 隐藏自动滚动速度调节 Bar
     */
    var hideScrollLevelBar: Boolean
        get() = SPStaticUtils.getBoolean(SP_HIDE_SCROLL_LEVEL_BAR, false)
        set(value) = SPStaticUtils.put(SP_HIDE_SCROLL_LEVEL_BAR, value)
}