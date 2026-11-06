package com.weekd.miracastreceiver.miracast

import android.content.Context

enum class MiracastMode { SYSTEM, APP }

/**
 * @param keepsIntent 失败后是否保留用户选择的 APP 意图。
 *  瞬时问题（没权限、Wi-Fi 没开、P2P 忙）保留，等条件满足后自动重试；
 *  永久问题（硬件不支持、写不了系统设置、被系统覆盖）退回 SYSTEM 并写回意图，不反复折腾。
 */
enum class MiracastError(val keepsIntent: Boolean) {
    UNSUPPORTED(false),
    CANNOT_WRITE_SETTING(false),
    SETTING_OVERRIDDEN(false),
    UNCERTAIN_STATE(false),
    PERMISSION_MISSING(true),
    WIFI_OFF(true),
    GROUP_FAILED(true),
    TIMEOUT(true),
    UNKNOWN(true)
}

class MiracastException(val error: MiracastError, cause: Throwable? = null) :
    Exception(error.name, cause)

sealed interface MiracastState {
    object Initializing : MiracastState
    object SystemOwned : MiracastState
    data class Switching(val target: MiracastMode) : MiracastState
    data class AppActive(val groupReady: Boolean, val wfdIeAdvertised: Boolean) : MiracastState
    data class Failed(val error: MiracastError) : MiracastState
}

object MiracastModeStore {
    private const val PREFS = "miracast_mode"
    private const val KEY_MODE = "mode"
    private const val KEY_ROOT_IE = "root_ie_injected"
    private const val KEY_CLEAN_PENDING = "clean_pending"
    private const val KEY_SESSION_OPEN = "session_open"
    private const val KEY_LAST_WRITTEN = "last_written"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): MiracastMode {
        val name = prefs(ctx).getString(KEY_MODE, null)
        return MiracastMode.values().firstOrNull { it.name == name } ?: MiracastMode.SYSTEM
    }

    fun set(ctx: Context, mode: MiracastMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isRootIeInjected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ROOT_IE, false)

    fun setRootIeInjected(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ROOT_IE, value).commit()
    }

    fun isSessionOpen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SESSION_OPEN, false)

    fun setSessionOpen(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SESSION_OPEN, value).commit()
    }

    fun lastWritten(ctx: Context): Int? =
        prefs(ctx).getInt(KEY_LAST_WRITTEN, -1).takeIf { it >= 0 }

    fun setLastWritten(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_LAST_WRITTEN, value).commit()
    }

    fun markCleanPending(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_CLEAN_PENDING, true).apply()
    }

    @Synchronized
    fun takeCleanPending(ctx: Context): Boolean {
        val p = prefs(ctx)
        val pending = p.getBoolean(KEY_CLEAN_PENDING, false)
        if (pending) p.edit().putBoolean(KEY_CLEAN_PENDING, false).apply()
        return pending
    }
}
