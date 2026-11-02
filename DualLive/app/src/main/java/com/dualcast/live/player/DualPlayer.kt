package com.dualcast.live.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dualcast.live.data.model.PlaySource
import com.dualcast.live.data.net.LiveHttp

/**
 * 双路播放器：
 * - videoPlayer 播放 B 的画面 + 声音
 * - audioPlayer 播放 A 的声音；平时禁用视频轨道省解码，
 *   打开解说小窗时才解 A 的视频给那个浮动小窗用
 *
 * 两个设计取舍：
 *
 * 1. **声音对齐靠暂停 / 快放，不碰 AudioProcessor。** 在 AudioSink 里插处理器
 *    依赖解码链路走 PCM，很多机型会走 offload 直接绕过去，外部根本验证不了。
 *    改成直接控制播放器：
 *      - A 要更晚 → 暂停 A 若干毫秒，A 就永久落后这么多；
 *      - A 要更早 → A 用 1.5 倍速追一段（Sonic 变速不变调）。
 *    每次操作都有实打实的听感变化，不存在「调了没反应」。
 *
 * 2. **拉流必须用 [LiveHttp.mediaClient]**（没有整通超时的那个），否则每 25 秒
 *    被 OkHttp 掐断；看门狗负责发现「位置不动」并切换线路。
 */
class DualPlayer(
    context: Context,
    private val http: LiveHttp,
) {

    enum class Slot { A, B }

    var onError: ((Slot, String) -> Unit)? = null
    var onPlayingChanged: ((Slot, Boolean) -> Unit)? = null

    /** 所有线路都卡住时回调，上层重新解析播放地址后调用 [reload]。 */
    var onStalled: ((Slot) -> Unit)? = null

    /**
     * A 的画面解不了时回调（小窗打开后才可能发生）。
     * 关掉视频轨道、把声音重新拉起来，不能因为看不了小窗把整路声音弄没。
     */
    var onCommentaryVideoUnavailable: ((String) -> Unit)? = null

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var sourceA: PlaySource? = null
    private var sourceB: PlaySource? = null
    private var indexA = 0
    private var indexB = 0

    var videoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(loadControl())
        .build()
        private set

    var audioPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLoadControl(loadControl())
        .build()
        private set

    private val watchdog = Handler(Looper.getMainLooper())
    private val watchA = Watchdog()
    private val watchB = Watchdog()
    private var watching = false
    private var sessionActive = false

    /** A 路是否在解视频（解说小窗开着的时候才需要）。 */
    private var commentaryVideoEnabled = false

    init {
        applyCommentaryVideoTrack()

        videoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                recover(Slot.B, friendly(error))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startedB = true
                    maybeApplyInitialOffset()
                }
                onPlayingChanged?.invoke(Slot.B, isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // 直播流被服务端正常关闭时不会报错，只会「播完」，这里直接重连
                    recover(Slot.B, "B 路直播流中断")
                }
            }
        })

        audioPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 小窗解不了视频时，先保声音：关掉 A 的视频轨道重新 prepare，
                // 而不是按整路失败去切线路 / 报错。
                if (commentaryVideoEnabled && isDecoderError(error)) {
                    commentaryVideoEnabled = false
                    applyCommentaryVideoTrack()
                    onCommentaryVideoUnavailable?.invoke("A 的画面解不了，已关闭解说小窗，声音继续")
                    start(Slot.A)
                    return
                }
                recover(Slot.A, friendly(error))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startedA = true
                    maybeApplyInitialOffset()
                }
                // 调延迟时会主动暂停 A，这个瞬时状态不要反映到 UI 上
                if (pausedForNudge) return
                onPlayingChanged?.invoke(Slot.A, isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) recover(Slot.A, "A 路直播流中断")
            }
        })
    }

    fun play(a: PlaySource, b: PlaySource, initialOffsetMs: Int = 0) {
        sourceA = a
        sourceB = b
        indexA = 0
        indexB = 0
        sessionActive = true
        offsetEpoch++
        currentOffset = initialOffsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        // 两路都真正出声之后再执行，保证恢复出来的偏移跟当初手调的一致
        pendingInitialOffset = currentOffset
        startedA = false
        startedB = false
        offsetApplyJob?.let { handler.removeCallbacks(it) }
        offsetApplyJob = null
        watchA.reset()
        watchB.reset()
        start(Slot.A)
        start(Slot.B)
        startWatchdog()
    }

    /**
     * 直播流不需要「先攒 30 秒」：连上就播，缓冲水位保持小一点，
     * 延迟更低，看比赛也不容易被拉开。抗抖交给看门狗和换线。
     */
    private fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 3_000,
            /* maxBufferMs = */ 15_000,
            /* bufferForPlaybackMs = */ 800,
            /* bufferForPlaybackAfterRebufferMs = */ 2_500,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    fun setVolumes(a: Float, b: Float) {
        audioPlayer.volume = a.coerceIn(0f, 1f)
        videoPlayer.volume = b.coerceIn(0f, 1f)
    }

    /** 只替换 B 的播放地址（切换清晰度用），A 的声音不受影响。 */
    fun switchSourceB(source: PlaySource) {
        sourceB = source
        indexB = 0
        watchB.reset()
        start(Slot.B)
    }

    /** 看门狗把线路耗光后，上层重新解析到的新地址从这里塞回来。 */
    fun reload(slot: Slot, source: PlaySource) {
        if (slot == Slot.A) {
            sourceA = source
            indexA = 0
            watchA.reset()
        } else {
            sourceB = source
            indexB = 0
            watchB.reset()
        }
        start(slot)
    }

    /**
     * 一键互换 A / B 的画面与声音。
     *
     * 播放器实例不重建（PlayerView 还挂在同一个 videoPlayer 上），只把两路的
     * 媒体源对调：videoPlayer 改播原来的 A，audioPlayer 改播原来的 B。
     */
    fun swapSources() {
        val a = sourceA
        sourceA = sourceB
        sourceB = a
        val ia = indexA
        indexA = indexB
        indexB = ia
        watchA.reset()
        watchB.reset()

        // 偏移是「A 相对 B」的量，换了边之后方向要反过来。
        // 但如果开播偏移还停在待执行队列里，实际上一次都没挪过，直接清零更准确。
        val pending = pendingInitialOffset != 0 || offsetApplyJob != null
        currentOffset = if (pending) 0 else -currentOffset
        // 还没执行的开播偏移直接作废，避免换边后按老方向再推一次
        pendingInitialOffset = 0
        offsetApplyJob?.let { handler.removeCallbacks(it) }
        offsetApplyJob = null

        start(Slot.A)
        start(Slot.B)
        // 换边后两路都重新 prepare，把镜像后的偏移重新落一次
        reapplyOffset()
    }

    // ---- 声音对齐 ----

    /** 当前手动偏移：正值表示 A 比 B 晚，负值表示 A 比 B 早。 */
    var currentOffset: Int = 0
        private set

    private var startedA = false
    private var startedB = false

    /**
     * 开播时要恢复的偏移。等两路都真正出过声再执行，
     * 避免在起播瞬间暂停 / 快放，把开播拖慢。
     */
    private var pendingInitialOffset = 0
    private var offsetApplyJob: Runnable? = null

    /** 每次用户手动调偏移 / 归零都 +1，用来作废还没执行的 reapply。 */
    private var offsetEpoch = 0

    private fun maybeApplyInitialOffset() {
        if (pendingInitialOffset == 0 || !startedA || !startedB) return
        val ms = pendingInitialOffset
        pendingInitialOffset = 0
        offsetApplyJob?.let { handler.removeCallbacks(it) }
        // 稍等一下，避开起播瞬间的缓冲抖动
        val job = object : Runnable {
            override fun run() {
                offsetApplyJob = null
                if (sessionActive) shiftAudio(ms)
            }
        }
        offsetApplyJob = job
        handler.postDelayed(job, 700)
    }

    /** 往右推：A 晚一点；往左推：A 早一点。单位毫秒。 */
    fun nudgeOffset(deltaMs: Int) {
        offsetEpoch++
        val next = (currentOffset + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val applied = next - currentOffset
        if (applied == 0) return
        currentOffset = next
        shiftAudio(applied)
    }

    /** 清除手动偏移：把之前挪过去的量再挪回来，听感上会明确地快放或停顿一下。 */
    fun resetOffset() {
        offsetEpoch++        // 开播偏移还没真正执行：直接取消，别再反向补偿一次
        if (pendingInitialOffset != 0 || offsetApplyJob != null) {
            pendingInitialOffset = 0
            offsetApplyJob?.let { handler.removeCallbacks(it) }
            offsetApplyJob = null
            currentOffset = 0
            return
        }
        val applied = -currentOffset
        if (applied == 0) return
        currentOffset = 0
        shiftAudio(applied)
    }

    /**
     * 重连 / 换边后重新补上手动偏移。
     *
     * 重新 prepare 会把 A 拉回直播边缘，之前暂停 / 快放攒出来的偏差就没了，
     * 但 [currentOffset] 还记着用户调的数值。这里先清零，等播放器重新出声
     * 再把同样的量推一次，避免下次调的时候数字和听感对不上。
     */
    fun reapplyOffset() {
        val ms = currentOffset
        if (ms == 0) return
        currentOffset = 0
        val epoch = ++offsetEpoch
        handler.postDelayed({
            // 这 1.5 秒里用户可能自己归零或又调了一次，以新操作为准
            if (sessionActive && epoch == offsetEpoch) nudgeOffset(ms)
        }, 1_500)
    }

    /**
     * @param applied 正值：A 需要往后挪（暂停一段时间）；
     *                负值：A 需要往前赶（变速快放一段时间）。
     */
    private fun shiftAudio(applied: Int) {
        if (!sessionActive) return
        if (applied > 0) pauseAudioFor(applied.toLong()) else speedUpAudio(-applied)
    }

    private var pausedForNudge = false
    private var pauseEndAt = 0L
    private var pauseResumeJob: Runnable? = null

    /** 暂停 A 一段时间。多次点击会累加，不会互相打断。 */
    private fun pauseAudioFor(ms: Long) {
        val now = SystemClock.elapsedRealtime()
        pauseEndAt = maxOf(pauseEndAt, now) + ms
        if (!pausedForNudge) {
            pausedForNudge = true
            audioPlayer.pause()
        }
        pauseResumeJob?.let { handler.removeCallbacks(it) }
        val job = object : Runnable {
            override fun run() {
                val left = pauseEndAt - SystemClock.elapsedRealtime()
                if (left > 0) {
                    handler.postDelayed(this, left)
                    return
                }
                pausedForNudge = false
                if (sessionActive && !userPaused) audioPlayer.play()
            }
        }
        pauseResumeJob = job
        handler.postDelayed(job, ms)
    }

    private var speedUntil = 0L
    private var speedResetJob: Runnable? = null

    /**
     * A 用 1.5 倍速追一段，追回 [ms] 毫秒的差距。
     *
     * 走的是 ExoPlayer 自带的 Sonic，变速不变调，听感是「语速快了一点」，
     * 不会变成尖细的怪声。1.5 倍时每播放 1 秒能追回 0.5 秒。
     */
    private fun speedUpAudio(ms: Int) {
        val now = SystemClock.elapsedRealtime()
        val duration = (ms / (CATCH_UP_SPEED - 1f)).toLong().coerceIn(120L, 20_000L)
        speedUntil = maxOf(speedUntil, now) + duration
        audioPlayer.setPlaybackSpeed(CATCH_UP_SPEED)
        speedResetJob?.let { handler.removeCallbacks(it) }
        val job = object : Runnable {
            override fun run() {
                val left = speedUntil - SystemClock.elapsedRealtime()
                if (left > 0) {
                    handler.postDelayed(this, left)
                    return
                }
                if (sessionActive) audioPlayer.setPlaybackSpeed(1f)
            }
        }
        speedResetJob = job
        handler.postDelayed(job, duration)
    }

    /**
     * 后台播放时把视频轨道关掉，只留音频解码。
     * 画面本来也看不见，继续解 4K 只是白白耗电发热。
     */
    fun setVideoEnabled(enabled: Boolean) {
        videoPlayer.trackSelectionParameters = videoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /**
     * A 路平时只解音频。打开解说小窗时才解视频，
     * 关掉小窗立刻释放视频解码器——两路同时解 1080p 很费电。
     */
    fun setCommentaryVideoEnabled(enabled: Boolean) {
        if (commentaryVideoEnabled == enabled) return
        commentaryVideoEnabled = enabled
        applyCommentaryVideoTrack()
    }

    private fun applyCommentaryVideoTrack() {
        audioPlayer.trackSelectionParameters = audioPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !commentaryVideoEnabled)
            .build()
    }

    private var userPaused = false

    fun pause() {
        userPaused = true
        videoPlayer.pause()
        if (!pausedForNudge) audioPlayer.pause()
    }

    /**
     * 只在「用户自己暂停过」时恢复。
     *
     * 播放页的 DisposableEffect 一进来就会调这个方法，如果无条件 play()，
     * 会把用户手动暂停的状态顶掉。
     */
    fun resume() {
        if (!userPaused) return
        userPaused = false
        audioPlayer.play()
        videoPlayer.play()
    }

    fun release() {
        sessionActive = false
        stopWatchdog()
        pauseResumeJob?.let { handler.removeCallbacks(it) }
        speedResetJob?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        videoPlayer.release()
        audioPlayer.release()
    }

    private fun playerFor(slot: Slot): ExoPlayer = if (slot == Slot.A) audioPlayer else videoPlayer

    private fun sourceFor(slot: Slot): PlaySource? = if (slot == Slot.A) sourceA else sourceB

    private fun start(slot: Slot) {
        val source = sourceFor(slot) ?: return
        val index = if (slot == Slot.A) indexA else indexB
        if (index !in source.urls.indices) return

        val dataSourceFactory = OkHttpDataSource.Factory(http.mediaClient)
            .setDefaultRequestProperties(source.headers)
            .setUserAgent(source.headers["User-Agent"])

        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(
            // 不要给 FLV 直播挂 LiveConfiguration：时间戳是流内绝对时间，
            // 播放器拿它算「直播延迟」会算出一个巨大的值，然后不停追帧。
            MediaItem.Builder().setUri(source.urls[index]).build()
        )

        val player = playerFor(slot)
        player.setMediaSource(mediaSource)
        player.prepare()
        // 用户手动暂停时，切清晰度 / 自动换线不该偷偷把声音放出来
        player.playWhenReady = !userPaused
    }

    /** 出错 / 播完 / 卡死 的统一处理：先换线路，线路没了再让上层重新解析。 */
    private fun recover(slot: Slot, message: String?) {
        if (!sessionActive) return
        val watch = if (slot == Slot.A) watchA else watchB
        val now = SystemClock.elapsedRealtime()
        if (now - watch.lastRecoverAt < RECOVER_INTERVAL_MS) return
        watch.lastRecoverAt = now

        if (tryNext(slot)) return
        if (watch.rescues < MAX_RESCUES && onStalled != null) {
            watch.rescues++
            onStalled?.invoke(slot)
            return
        }
        if (message != null) onError?.invoke(slot, message)
    }

    private fun tryNext(slot: Slot): Boolean {
        val source = sourceFor(slot) ?: return false
        val next = (if (slot == Slot.A) indexA else indexB) + 1
        if (next >= source.urls.size) return false
        if (slot == Slot.A) indexA = next else indexB = next
        start(slot)
        return true
    }

    // ---- 看门狗 ----

    private inner class Watchdog {
        var lastPosition = C.TIME_UNSET
        var lastProgressAt = 0L
        var bufferingSince = 0L
        var lastRecoverAt = 0L
        var rescues = 0

        fun reset() {
            lastPosition = C.TIME_UNSET
            lastProgressAt = SystemClock.elapsedRealtime()
            bufferingSince = 0L
            lastRecoverAt = 0L
            rescues = 0
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!watching) return
            inspect(Slot.A)
            inspect(Slot.B)
            watchdog.postDelayed(this, TICK_MS)
        }
    }

    private fun startWatchdog() {
        if (watching) return
        watching = true
        watchA.reset()
        watchB.reset()
        watchdog.postDelayed(tick, TICK_MS)
    }

    private fun stopWatchdog() {
        watching = false
        watchdog.removeCallbacksAndMessages(null)
    }

    private fun inspect(slot: Slot) {
        val player = playerFor(slot)
        // 暂停时本来就不播，别把它当成卡死
        if (!player.playWhenReady) {
            bufferingSinceReset(slot)
            return
        }
        val watch = if (slot == Slot.A) watchA else watchB
        val now = SystemClock.elapsedRealtime()

        val buffering = player.playbackState == Player.STATE_BUFFERING
        if (buffering) {
            if (watch.bufferingSince == 0L) watch.bufferingSince = now
        } else {
            watch.bufferingSince = 0L
        }

        // 调延迟时 A 会暂停，位置不动属于正常
        if (slot == Slot.A && pausedForNudge) {
            watch.lastProgressAt = now
            watch.bufferingSince = 0L
            return
        }

        val position = player.currentPosition
        if (position != watch.lastPosition) {
            watch.lastPosition = position
            watch.lastProgressAt = now
        }

        val stalled = when {
            buffering -> now - watch.bufferingSince > STALL_MS
            player.playbackState == Player.STATE_READY -> now - watch.lastProgressAt > STALL_MS
            else -> false
        }
        if (stalled) {
            recover(slot, "${if (slot == Slot.A) "A" else "B"} 路长时间无数据，正在切换线路")
            watch.lastProgressAt = now
            watch.bufferingSince = 0L
        }
    }

    private fun bufferingSinceReset(slot: Slot) {
        val watch = if (slot == Slot.A) watchA else watchB
        watch.bufferingSince = 0L
        watch.lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun isDecoderError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> true
        else -> false
    }

    private fun friendly(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "拉流失败（HTTP ${error.cause?.message.orEmpty().take(24)}），线路可能已过期"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接超时"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
        PlaybackException.ERROR_CODE_UNSPECIFIED -> "播放失败"
        else -> "播放失败（${error.errorCodeName}）"
    }

    private companion object {
        const val TICK_MS = 2_000L

        /**
         * 多久没进展才算「真卡死」。
         *
         * 之前是 9 秒，结果网络抖一下就被判死，切线路、重连、再抖、再切……
         * 用户看到的就是「播一秒、卡十几秒」。现在有缓冲兜底，放宽到 25 秒。
         */
        const val STALL_MS = 25_000L

        /** 两次自动恢复之间至少隔 15 秒，避免重连风暴。 */
        const val RECOVER_INTERVAL_MS = 15_000L
        const val MAX_RESCUES = 3

        /** 追赶时用 1.5 倍速，音调由 Sonic 保持。 */
        const val CATCH_UP_SPEED = 1.5f

        /** 手动偏移上限，跟 UI 的 ±6 秒一致。 */
        const val MAX_OFFSET_MS = 6_000
    }
}
