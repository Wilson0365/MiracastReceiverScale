package com.weekd.miracastreceiver.miracast

/** Every owned resource must be released before the durable dirty marker is cleared. */
internal class CleanupReport {
    private val failed = mutableListOf<String>()
    val failures: List<String> get() = failed.toList()
    val clean: Boolean get() = failed.isEmpty()

    suspend fun attempt(name: String, operation: suspend () -> Boolean) {
        if (!runCatching { operation() }.getOrDefault(false)) failed.add(name)
    }
}
