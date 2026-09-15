package com.artbrain.ebse.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 쪽을 네모로 늘어놓은 띠. 22SUTO-A 의 `MadeStrip` 을 옮겼다.
 *
 * 칸 하나가 한 쪽이다. 쪽이 많아 한 줄에 못 놓으면 여러 쪽을 한 칸에 묶고,
 * 칸을 누르면 그 칸이 맡은 첫 쪽으로 간다 — 어림자리로 옮기는 것이다.
 * 맨 앞 칸은 첫 쪽, 맨 뒤 칸은 마지막 쪽이다.
 *
 * **띠는 늘 제 폭을 100% 쓴다.** 쪽이 적으면 칸을 길이 비례로 나눠 넓힌다.
 * 구석에만 짧게 붙은 띠는 게이지로 읽히지 않는다.
 *
 * 지금 쪽이 아닌 칸은 **높이 2dp** 로 눕혀 점선처럼 보이게 하고, 지금 쪽만
 * 칸 너비만큼의 **정사각형**으로 세운다. 점선 위에 네모 하나가 놓인 꼴이라
 * 어디쯤인지가 한눈에 들어온다 — 흑백뿐이라 모양으로 가른다.
 *
 * **끌면 손가락을 따라 바로 옮긴다.** 누르는 순간 그 칸으로 가고, 끄는 동안
 * 칸이 바뀔 때마다 본문까지 그 쪽으로 넘긴다. 칸 사이의 중간값은 쓰지 않으니
 * 다시 그리는 횟수는 칸 수를 넘지 않는다. e-ink 의 잔상은 그대로 둔다.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 칸의 최소 너비. 이보다 좁아지면 여러 쪽을 한 칸에 묶는다. */
    private val cellMin = Ink.dp(context, 5f).roundToInt().coerceAtLeast(3)

    /** 지금 쪽이 아닌 칸의 높이 — 눕혀서 점선으로 보이게 한다. */
    private val trackH = Ink.dp(context, 1.2f).roundToInt().coerceAtLeast(1)

    /** 마커가 이만큼은 되게 칸을 잡는다. 너무 작으면 눈에 띄지 않는다. */
    private val markerTarget = Ink.dp(context, 7.5f).roundToInt()

    /**
     * 마커 높이의 한도.
     *
     * 쪽이 적으면 칸이 아주 넓어진다(목록 여섯 쪽이면 한 칸이 55px). 그대로
     * 정사각형을 그리면 띠가 아니라 큰 네모 하나가 된다. 높이를 여기서 묶어
     * 두면 그럴 때는 가로로 긴 직사각형이 되어 띠의 꼴을 지킨다.
     */
    private val markerMaxH = Ink.dp(context, 10f).roundToInt()

    private val gap = Ink.dp(context, 1.5f).roundToInt().coerceAtLeast(1)

    private val done = Halftone.solid()                       // 지나온 쪽
    private val todo = Halftone.paint(Halftone.Tone.PALE)     // 아직 안 본 쪽

    /** 지금 쪽 — 흰 바탕에 검은 테두리만. */
    private val markerFill = Paint().apply {
        isAntiAlias = false; style = Paint.Style.FILL; color = Ink.WHITE
    }
    private val markerLine = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        color = Ink.BLACK
        strokeWidth = Ink.dp(context, 1.5f)
    }
    private val r = RectF()

    private var count = 0
    private var current = 0

    /** 칸을 누르면 그 칸의 첫 쪽 번호가 온다. */
    var onSeek: ((page: Int) -> Unit)? = null

    fun set(count: Int, current: Int) {
        this.count = count
        this.current = current
        invalidate()
    }

    /** 놓을 칸 수 — 마커가 [markerTarget] 만해지도록 잡고, 넘치는 쪽은 묶는다. */
    private fun cells(): Int {
        if (count <= 0 || width <= 0) return 0
        val maxCells = floor((width + gap).toDouble() / (markerTarget + gap)).toInt().coerceAtLeast(1)
        return if (count < maxCells) count else maxCells
    }

    /**
     * [k] 번 칸의 왼쪽·오른쪽 자리.
     *
     * 폭을 [n] 로 비례해 나눈 경계를 정수로 맞춘다 — 칸마다 같은 너비를
     * 쓰고 남는 픽셀을 버리면 띠가 폭을 다 못 채운다. 경계를 먼저 정하면
     * 남는 픽셀이 칸들에 저절로 흩어지고 오른쪽 끝이 정확히 맞는다.
     */
    private fun bounds(k: Int, n: Int): Pair<Float, Float> {
        val step = width.toDouble() / n
        val left = (k * step).roundToInt()
        val right = ((k + 1) * step).roundToInt() - if (k < n - 1) gap else 0
        return left.toFloat() to right.coerceAtLeast(left + 1).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val n = cells()
        if (n <= 0) return

        val mid = height / 2f
        val nowCell = (current.toLong() * n / count).toInt().coerceIn(0, n - 1)

        // 지금 쪽이 아닌 칸 — 2dp 로 누운 점선
        val top = mid - trackH / 2f
        for (k in 0 until n) {
            if (k == nowCell) continue
            val (left, right) = bounds(k, n)
            r.set(left, top, right, top + trackH)
            canvas.drawRect(r, if (k < nowCell) done else todo)
        }

        // 지금 쪽 — 칸 너비만큼의 정사각형. 흰 면에 검은 테두리만.
        val (l, rr) = bounds(nowCell, n)
        val side = (rr - l).coerceAtMost(markerMaxH.toFloat())
        val inset = markerLine.strokeWidth / 2f
        r.set(l + inset, mid - side / 2f + inset, rr - inset, mid + side / 2f - inset)
        canvas.drawRect(r, markerFill)
        canvas.drawRect(r, markerLine)
    }

    /** 이번 누름에서 마지막으로 옮겨 간 칸. 같은 칸 안에서 움직이면 다시 옮기지 않는다. */
    private var seekCell = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val n = cells()
        if (n <= 0 || count <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 띠 밖으로 끌려 나가도 부모가 가로채지 않게 한다.
                parent?.requestDisallowInterceptTouchEvent(true)
                seekCell = -1
                seekTo(event.x, n)
            }
            MotionEvent.ACTION_MOVE -> seekTo(event.x, n)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> seekCell = -1
        }
        return true
    }

    /**
     * 손가락이 놓인 칸의 첫 쪽으로 간다. 칸이 바뀔 때만 알린다.
     * 맨 뒤 칸만은 그 칸의 첫 쪽이 아니라 **글의 마지막 쪽**으로 간다.
     */
    private fun seekTo(x: Float, n: Int) {
        val k = floor(x * n / width).toInt().coerceIn(0, n - 1)
        if (k == seekCell) return
        seekCell = k
        val page = if (k == n - 1) count - 1
            else (k.toLong() * count / n).toInt().coerceIn(0, count - 1)
        onSeek?.invoke(page)
    }
}
