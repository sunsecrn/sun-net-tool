package dev.nettool.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedFlow(
    val source: String,
    val sourcePort: Int,
    val destination: String,
    val destinationPort: Int,
    val protocol: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val packets: Long = 0,
    val uid: Int? = null,
    val packages: List<String> = emptyList(),
)

data class CaptureSnapshot(
    val running: Boolean = false,
    val status: String = "Captura parada",
    val sessionId: String = "",
    val startedAt: Long = 0,
    val packets: Long = 0,
    val bytes: Long = 0,
    val forwardingErrors: Long = 0,
    val omittedFlows: Long = 0,
    val flows: List<CapturedFlow> = emptyList(),
    val pcapPath: String? = null,
    val jsonPath: String? = null,
    val csvPath: String? = null,
    val unsupportedPackets: Long = 0,
)

object CaptureState {
    internal val mutable = MutableStateFlow(CaptureSnapshot())
    val state = mutable.asStateFlow()
}
