package com.weekd.miracastreceiver.miracast
internal data class SettingSnapshot(val value: Int?)
/** Conservatively retain a write only while this process can observe external changes. */
internal class SystemSettingLease(
    private val read: () -> Int?, private val write: (Int?) -> Boolean,
    private val load: () -> SettingSnapshot?, private val save: (SettingSnapshot?) -> Boolean
) {
    @Volatile var owned = false
        private set
    @Synchronized fun prepare(): Boolean {
        if (read() == 1) return true
        if (!save(SettingSnapshot(read()))) return false
        owned = true
        return write(1)
    }
    @Synchronized fun onExternalChange() {
        owned = false
        save(null)
    }
    @Synchronized fun restore(): Boolean {
        val original = load() ?: return true
        if (owned && read() == 1 && !write(original.value)) return false
        owned = false
        return save(null)
    }
}
