package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 全工程唯一读写 Settings.Global.wifi_display_on 的地方。
 *
 * 语义（小米电视实测）：
 *   0 = 电视自带投屏服务负责 (VALUE_SYSTEM)
 *   1 = 本 app 负责（手机能搜到本 app）(VALUE_APP)
 *
 * 旧代码语义是反的（写 0 当"app 接管"），这里已修正。
 */
object WifiDisplaySetting {
    private const val KEY = "wifi_display_on"
    const val VALUE_SYSTEM = 0
    const val VALUE_APP = 1

    val uri: Uri get() = Settings.Global.getUriFor(KEY)

    fun current(ctx: Context): Int? = try {
        Settings.Global.getInt(ctx.contentResolver, KEY)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    fun isAppOwned(ctx: Context): Boolean = current(ctx) == VALUE_APP

    fun canWrite(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 写入并读回确认。返回"现在系统里的值是否等于目标值"。
     * 失败返回 false → Controller 抛 MiracastException(CANNOT_WRITE_SETTING)。
     */
    fun write(ctx: Context, appOwned: Boolean): Boolean {
        val target = if (appOwned) VALUE_APP else VALUE_SYSTEM
        if (current(ctx) == target) {
            MiracastModeStore.setLastWritten(ctx, target)
            return true
        }
        val direct = try {
            Settings.Global.putInt(ctx.contentResolver, KEY, target)
        } catch (e: SecurityException) {
            false
        }
        val ok = direct && current(ctx) == target
        if (ok) MiracastModeStore.setLastWritten(ctx, target)
        if (!ok) Timber.w("wifi_display_on write not effective, wanted $target, got ${current(ctx)}")
        return ok
    }
}
