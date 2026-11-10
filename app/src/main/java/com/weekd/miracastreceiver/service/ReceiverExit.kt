package com.weekd.miracastreceiver.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Durable intent first; all cleanup attempts before removing the foreground service. */
internal class ReceiverExit(
    private val saveSystem: suspend () -> Unit,
    private val cleanupMiracast: suspend () -> Boolean,
    private val stopReceivers: suspend () -> Unit,
    private val finish: () -> Unit
) {
    private val started = AtomicBoolean()

    suspend fun exit(): Boolean = withContext(NonCancellable) {
        if (!started.compareAndSet(false, true)) return@withContext false
        try {
            saveSystem()
            runCatching { cleanupMiracast() }.getOrDefault(false)
        } finally {
            try { stopReceivers() } finally { finish() }
        }
    }
}
