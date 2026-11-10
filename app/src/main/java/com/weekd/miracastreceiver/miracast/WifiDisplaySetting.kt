package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.provider.Settings
import timber.log.Timber

/** The only adapter accessing wifi_display_on. Its value does not describe media ownership. */
object WifiDisplaySetting {
    private const val KEY = "wifi_display_on"
    const val SYSTEM_P2P_ENABLED = 1
    fun current(ctx: Context): Int? = try { Settings.Global.getInt(ctx.contentResolver, KEY) }
        catch (_: Settings.SettingNotFoundException) { null }
    fun writeValue(ctx: Context, target: Int?): Boolean {
        if (current(ctx) == target) return true
        return try {
            val written = if (target == null) Settings.Global.putString(ctx.contentResolver, KEY, null)
                else Settings.Global.putInt(ctx.contentResolver, KEY, target)
            written && current(ctx) == target
        } catch (e: SecurityException) {
            Timber.w(e, "Miracast: cannot change system WFD setting")
            false
        }
    }
}
