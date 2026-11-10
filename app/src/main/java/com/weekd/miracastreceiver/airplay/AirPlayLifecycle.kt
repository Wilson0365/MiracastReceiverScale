package com.weekd.miracastreceiver.airplay

/** Discovery can change independently of a live RTSP/URL playback session. */
internal class AirPlayLifecycle(
    private val onStateChanged: (AirPlayState) -> Unit,
    private val onSessionEnded: (wasMirroring: Boolean) -> Unit
) {
    private var connected = false
    private var mirroring = false
    private var generation = 0L

    @Synchronized fun mediaGeneration(): Long = generation
    @Synchronized fun isCurrentMediaGeneration(value: Long): Boolean = generation == value
    @Synchronized fun invalidatePendingStarts() { generation++ }
    @Synchronized fun markMirroring() { mirroring = true }

    @Synchronized
    fun discoveryChanged(state: AirPlayState) {
        if (!connected) onStateChanged(state)
    }

    @Synchronized
    fun sessionConnected() {
        connected = true
        onStateChanged(AirPlayState.CONNECTED)
    }

    @Synchronized
    fun sessionEnded() {
        generation++
        val wasConnected = connected
        val wasMirroring = mirroring
        connected = false
        mirroring = false
        if (wasConnected) onSessionEnded(wasMirroring)
        onStateChanged(AirPlayState.ADVERTISING)
    }

    /** Local stop must end URL playback even after its mirror control socket has gone. */
    fun stopLocally(releaseMedia: () -> Unit, disconnectControl: () -> Unit) {
        try {
            releaseMedia()
        } finally {
            try {
                disconnectControl()
            } finally {
                sessionEnded()
            }
        }
    }
}
