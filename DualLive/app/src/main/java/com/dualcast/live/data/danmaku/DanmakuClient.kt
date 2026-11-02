package com.dualcast.live.data.danmaku

import com.dualcast.live.data.model.DanmakuMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface DanmakuStatus {
    data object Idle : DanmakuStatus
    data object Connecting : DanmakuStatus
    data object Connected : DanmakuStatus

    /** 断线重连中，attempt 是第几次。 */
    data class Reconnecting(val attempt: Int, val reason: String? = null) : DanmakuStatus

    /** 彻底失败，不再自动重试；retryable 为 true 时可以手动点重试。 */
    data class Failed(val reason: String, val retryable: Boolean = true) : DanmakuStatus
}

interface DanmakuClient {
    val messages: SharedFlow<DanmakuMessage>
    val status: StateFlow<DanmakuStatus>
    fun start()
    fun stop()

    /** 手动重试：重置重试计数后重新连接。 */
    fun retry() = start()
}
