package com.weekd.miracastreceiver.miracast
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
/** Enables system WFD without claiming ownership of any existing P2P group. */
class SystemP2pHandoff(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("system_p2p_handoff", Context.MODE_PRIVATE)
    private var observer: ContentObserver? = null
    val needsRestore: Boolean get() = prefs.contains("previous_value")
    private val lease = SystemSettingLease(
        read = { WifiDisplaySetting.current(ctx) },
        write = { WifiDisplaySetting.writeValue(ctx, it) },
        load = { if (needsRestore) SettingSnapshot(prefs.getInt("previous_value", -1).takeIf { it >= 0 }) else null },
        save = { snapshot ->
            val edit = prefs.edit()
            if (snapshot == null) edit.remove("previous_value")
            else edit.putInt("previous_value", snapshot.value ?: -1)
            edit.commit()
        }
    )
    fun prepare(): Boolean {
        val ok = lease.prepare()
        if (ok) watchExternalChanges()
        return ok
    }
    private fun watchExternalChanges() {
        if (!lease.owned || observer != null) return
        // Register after our own write. Any subsequent change relinquishes ownership,
        // even when off/on notifications are coalesced and the final value is 1 again.
        val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { lease.onExternalChange() }
        }
        ctx.contentResolver.registerContentObserver(WifiDisplaySetting.uri, false, watcher)
        observer = watcher
    }
    fun restore(): Boolean {
        observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
        observer = null
        val ok = lease.restore()
        if (!ok) watchExternalChanges()
        return ok
    }
}
