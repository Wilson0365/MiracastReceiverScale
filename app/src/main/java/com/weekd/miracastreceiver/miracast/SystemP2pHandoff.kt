package com.weekd.miracastreceiver.miracast

import android.content.Context

/** Enables the platform WFD setting; never assumes ownership of an existing P2P group. */
class SystemP2pHandoff(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("system_p2p_handoff", Context.MODE_PRIVATE)
    val needsRestore: Boolean get() = prefs.contains("previous_value")

    fun prepare(): Boolean {
        val current = WifiDisplaySetting.current(ctx)
        if (current == WifiDisplaySetting.SYSTEM_P2P_ENABLED) return true
        if (!prefs.edit().putInt("previous_value", current ?: -1).commit()) return false
        return WifiDisplaySetting.writeValue(ctx, WifiDisplaySetting.SYSTEM_P2P_ENABLED)
    }

    fun restore(): Boolean {
        if (!needsRestore) return true
        val previous = prefs.getInt("previous_value", -1).takeIf { it >= 0 }
        // External changes win; do not fight the system/user on exit.
        if (WifiDisplaySetting.current(ctx) == WifiDisplaySetting.SYSTEM_P2P_ENABLED &&
            !WifiDisplaySetting.writeValue(ctx, previous)) return false
        return prefs.edit().remove("previous_value").commit()
    }
}
