package com.dualcast.live.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.dualcast.live.data.DanmakuStyle
import com.dualcast.live.data.model.DanmakuMessage
import com.dualcast.live.data.model.DanmakuType
import kotlin.math.max
import kotlin.math.min

/**
 * 弹幕层：自己算轨道、自己算速度，用 Canvas 直接画。
 * 之所以不用 Compose 的 Text，是因为直播弹幕密度高，Compose 组合/布局开销顶不住。
 */
class DanmakuOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var danmakuEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) clear()
            invalidate()
        }

    var textScale: Float = 1f
        set(value) {
            field = value.coerceIn(0.6f, 2f)
            applyTextSize()
        }

    var alphaScale: Float = 0.94f
        set(value) {
            field = value.coerceIn(0.15f, 1f)
        }

    var speedScale: Float = 1f
        set(value) {
            field = value.coerceIn(0.4f, 2.5f)
        }

    var displayArea: Float = 0.62f
        set(value) {
            field = value.coerceIn(0.2f, 1f)
            resetLanes()
        }

    var showUserName: Boolean = false
    var bold: Boolean = true
        set(value) {
            field = value
            applyTextSize()
        }

    var style: DanmakuStyle = DanmakuStyle.OUTLINE
    var blockedWords: List<String> = emptyList()

    // 点选高亮：点中一条弹幕时闪一下，告诉用户点的是哪条
    private var flashItem: Item? = null
    private var flashUntil = 0L

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    // 点中弹幕后的高亮圈，900ms 内闪一下，告诉用户点的是哪条
    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFFFFD166.toInt()
    }

    private val items = mutableListOf<Item>()
    private val pending = ArrayDeque<DanmakuMessage>()

    private var textSizePx = 16f * scaledDensity
    private var laneHeight = textSizePx * 1.5f
    private var laneCount = 1
    private var laneAvailable = LongArray(0)
    private val occupiedUntil = HashMap<Int, Long>()
    private var lastFrameTime = 0L
    private var running = true

    private val highlightRect = RectF()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        applyTextSize()
    }

    fun submit(message: DanmakuMessage) {
        if (!danmakuEnabled) return
        if (message.text.isBlank()) return
        if (blockedWords.any { it.isNotBlank() && message.text.contains(it, ignoreCase = true) }) return
        if (pending.size >= 200) pending.removeFirst()
        pending.addLast(message)
        if (items.size > 160) items.removeAt(0)
        postInvalidateOnAnimation()
    }

    fun clear() {
        items.clear()
        pending.clear()
        resetLanes()
        flashItem = null
        invalidate()
    }

    fun setRunning(value: Boolean) {
        running = value
        lastFrameTime = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetLanes()
    }

    /**
     * 命中检测：从后往前找（后画的盖在上面），纵向放宽一点，手指没那么准也能点中。
     *
     * 触摸不在这里处理 —— 上层用 Compose 的手势统一接，再换算成这个 View 的坐标来问
     * 「点到哪条了」，这样点空白处仍然能开关控制条，不会因为 View 吃了 DOWN 就断掉。
     */
    private fun findItemAt(x: Float, y: Float): Item? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            val top = item.top
            if (y < top - 6f || y > top + laneHeight + 6f) continue
            if (x < item.x - 12f || x > item.x + item.width + 12f) continue
            return item
        }
        return null
    }

    /** 返回坐标处的弹幕；命中时闪一圈作为反馈。 */
    fun findMessageAt(x: Float, y: Float): DanmakuMessage? {
        val item = findItemAt(x, y) ?: return null
        flashItem = item
        flashUntil = SystemClock.uptimeMillis() + 900L
        invalidate()
        return item.message
    }

    private fun applyTextSize() {
        textSizePx = 16f * scaledDensity * textScale
        laneHeight = textSizePx * 1.52f
        textPaint.textSize = textSizePx
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        strokePaint.textSize = textSizePx
        strokePaint.typeface = textPaint.typeface
        strokePaint.strokeWidth = max(2f, textSizePx * 0.14f)
        resetLanes()
        invalidate()
    }

    private fun resetLanes() {
        val usable = (height * displayArea).coerceAtLeast(laneHeight)
        laneCount = max(1, (usable / laneHeight).toInt())
        laneAvailable = LongArray(laneCount)
        occupiedUntil.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuEnabled) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTime == 0L) 0f else min(48L, now - lastFrameTime).toFloat()
        lastFrameTime = now

        drainPending(now)
        if (running) {
            advance(dt, now)
        }
        drawItems(canvas)

        if (running && (items.isNotEmpty() || pending.isNotEmpty())) {
            postInvalidateOnAnimation()
        }
    }

    private fun drainPending(now: Long) {
        var budget = 6
        while (pending.isNotEmpty() && budget-- > 0) {
            val message = pending.removeFirst()
            spawn(message, now)
        }
    }

    private fun advance(dt: Float, now: Long) {
        val iterator = items.listIterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.mode == MODE_SCROLL) {
                item.x -= item.speed * dt / 1000f
                if (item.x + item.width < -8f) iterator.remove()
            } else if (now - item.bornAt > TOP_DURATION_MS) {
                iterator.remove()
            }
        }
    }

    private fun spawn(message: DanmakuMessage, now: Long) {
        val raw = buildText(message)
        if (raw.isBlank()) return
        val text = if (raw.length > 88) raw.take(88) + "…" else raw
        val width = textPaint.measureText(text)
        val screenWidth = widthPx().toFloat()
        val speed = (width + screenWidth) / (BASE_DURATION_MS / 1000f * speedScale)

        if (message.type == DanmakuType.SUPER_CHAT || message.type == DanmakuType.GIFT) {
            spawnTop(text, width, message, now)
            return
        }

        val lane = pickScrollLane(now)
        if (lane < 0) return
        laneAvailable[lane] = now + ((width + LANE_GAP) / speed * 1000f).toLong()

        items.add(
            Item(
                text = text,
                message = message,
                color = resolveColor(message.color),
                width = width,
                x = widthPx().toFloat(),
                top = lane * laneHeight + paddingTop,
                mode = MODE_SCROLL,
                speed = speed,
                bornAt = now,
                highlight = false,
                self = message.isSelf,
            )
        )
    }

    private fun spawnTop(text: String, width: Float, message: DanmakuMessage, now: Long) {
        var lane = -1
        for (i in 0 until laneCount) {
            if ((occupiedUntil[i] ?: 0L) <= now) {
                lane = i
                break
            }
        }
        if (lane < 0) return
        occupiedUntil[lane] = now + TOP_DURATION_MS
        items.add(
            Item(
                text = text,
                message = message,
                color = resolveColor(message.color),
                width = width,
                x = ((widthPx() - width) / 2f).coerceAtLeast(8f),
                top = lane * laneHeight + paddingTop,
                mode = MODE_TOP,
                speed = 0f,
                bornAt = now,
                highlight = message.type != DanmakuType.CHAT,
                self = message.isSelf,
            )
        )
    }

    private fun pickScrollLane(now: Long): Int {
        var best = -1
        var bestTime = Long.MAX_VALUE
        for (i in 0 until laneCount) {
            val available = laneAvailable[i]
            if (available <= now && available < bestTime) {
                best = i
                bestTime = available
            }
        }
        return best
    }

    private fun drawItems(canvas: Canvas) {
        textPaint.alpha = (255 * alphaScale).toInt()
        strokePaint.alpha = (255 * alphaScale).toInt()
        val baselineOffset = textSizePx

        items.forEach { item ->
            val baseline = item.top + baselineOffset
            if (item.self) {
                // 自己发的那条：金色描边底，一眼能从满屏弹幕里认出来
                highlightRect.set(
                    item.x - 10f,
                    item.top + 2f,
                    item.x + item.width + 10f,
                    item.top + laneHeight - 6f,
                )
                textPaint.color = 0xCCFFD166.toInt()
                canvas.drawRoundRect(highlightRect, 12f, 12f, textPaint)
            } else if (item.highlight) {
                highlightRect.set(
                    item.x - 10f,
                    item.top + 2f,
                    item.x + item.width + 10f,
                    item.top + laneHeight - 6f,
                )
                textPaint.color = 0x66000000
                canvas.drawRoundRect(highlightRect, 12f, 12f, textPaint)
            }
            when (style) {
                DanmakuStyle.OUTLINE -> {
                    strokePaint.color = 0xCC000000.toInt()
                    canvas.drawText(item.text, item.x, baseline, strokePaint)
                    textPaint.color = item.color
                    canvas.drawText(item.text, item.x, baseline, textPaint)
                }
                DanmakuStyle.SHADOW -> {
                    textPaint.color = item.color
                    textPaint.setShadowLayer(textSizePx * 0.22f, 0f, 0f, Color.BLACK)
                    canvas.drawText(item.text, item.x, baseline, textPaint)
                    textPaint.clearShadowLayer()
                }
                DanmakuStyle.PLAIN -> {
                    textPaint.color = item.color
                    canvas.drawText(item.text, item.x, baseline, textPaint)
                }
            }
        }

        // 点选高亮：只在短时间内画一圈，别一直挂在弹幕上
        val flash = flashItem
        if (flash != null) {
            val now = SystemClock.uptimeMillis()
            if (now >= flashUntil || !items.contains(flash)) {
                flashItem = null
            } else {
                highlightRect.set(
                    flash.x - 12f,
                    flash.top + 1f,
                    flash.x + flash.width + 12f,
                    flash.top + laneHeight - 5f,
                )
                canvas.drawRoundRect(highlightRect, 12f, 12f, tapPaint)
            }
        }
    }

    private fun buildText(message: DanmakuMessage): String {
        if (!showUserName || message.user.isBlank()) return message.text
        return "${message.user}: ${message.text}"
    }

    private fun resolveColor(color: Int): Int {
        val luminance = ColorUtils.calculateLuminance(color)
        return if (luminance < 0.18) {
            ColorUtils.blendARGB(color, Color.WHITE, 0.72f)
        } else {
            color
        }
    }

    private fun widthPx(): Int = if (width > 0) width else resources.displayMetrics.widthPixels

    private class Item(
        val text: String,
        val message: DanmakuMessage,
        val color: Int,
        val width: Float,
        var x: Float,
        val top: Float,
        val mode: Int,
        val speed: Float,
        val bornAt: Long,
        val highlight: Boolean,
        val self: Boolean = false,
    )

    companion object {
        private const val MODE_SCROLL = 0
        private const val MODE_TOP = 1
        private const val LANE_GAP = 48f
        private const val BASE_DURATION_MS = 8_500f
        private const val TOP_DURATION_MS = 4_200L
    }
}
