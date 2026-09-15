package com.artbrain.ebse.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 한가운데 뜨는 알림. 흰 면에 검은 테두리만 두른 네모다.
 *
 * 아래쪽에 슬쩍 뜨는 토스트는 e-ink 에서 잘 안 보인다 — 화면이 늦게 갱신되는
 * 사이 이미 사라져 있기도 하다. 가운데에 테두리를 두르고 세워 두면 놓치지
 * 않는다.
 *
 * 단추는 아래에 한 줄로 놓는다. **닫기는 늘 오른쪽**, 되돌리기가 있으면
 * **왼쪽**이다. 되돌리기는 되돌리지 않아도 그만인 일이라 옅게 두고, 닫기는
 * 검게 둔다.
 *
 * 받는 동안은 같은 자리를 쓴다([progress]) — 왼쪽에 퍼센트, 오른쪽에 Cancel.
 * 되물을 때는 [ask] — 두 단추 모두 할 일이 있고, 저절로 닫히지 않는다.
 */
class Popup(private val root: FrameLayout) {

    private val ctx = root.context
    private val hand = Handler(Looper.getMainLooper())
    private val hide = Runnable { dismiss() }

    private var box: LinearLayout? = null

    /** 저절로 닫히거나 닫기를 눌렀을 때 할 일 — 되돌리지 않은 것으로 친다. */
    private var onExpire: (() -> Unit)? = null

    fun show(
        msg: String,
        undoLabel: String? = null,
        onUndo: (() -> Unit)? = null,
        onExpire: (() -> Unit)? = null,
        ms: Long = PLAIN_MS,
    ) {
        dismiss(runExpire = true)
        this.onExpire = onExpire

        val pad = Ink.dp(ctx, 20f).toInt()
        val row = FrameLayout(ctx).apply {
            if (undoLabel != null) addView(button(undoLabel, 1f, Fonts.REGULAR) {
                // 되돌렸으면 만료 처리를 하지 않는다.
                this@Popup.onExpire = null
                dismiss(runExpire = false)
                onUndo?.invoke()
            }.let { edge(it, Gravity.START) }, lp(Gravity.START))
            addView(edge(button(CLOSE, 1f, Fonts.REGULAR) { dismiss() }, Gravity.END), lp(Gravity.END))
        }

        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(Ink.dp(ctx, 1.5f).toInt(), Color.BLACK)
            }
            // 뒤쪽으로 누름이 새어 나가지 않게 한다.
            isClickable = true
            minimumWidth = Ink.dp(ctx, 260f).toInt()
            addView(message(msg, pad))
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        box = v
        hand.removeCallbacks(hide)
        hand.postDelayed(hide, ms)
    }

    /**
     * 되묻는다. 왼쪽 [leftLabel] 과 오른쪽 [rightLabel] 둘 다 할 일이 있고, 저절로 닫히지 않는다
     * — 고르기 전에 사라지면 무엇이 됐는지 알 수 없다. 누르면 닫고 그 일을 한다.
     */
    fun ask(msg: String, leftLabel: String, onLeft: () -> Unit, rightLabel: String, onRight: () -> Unit) {
        dismiss(runExpire = true)
        val pad = Ink.dp(ctx, 20f).toInt()
        val row = FrameLayout(ctx).apply {
            addView(edge(button(leftLabel, 1f, Fonts.REGULAR) { dismiss(runExpire = false); onLeft() }, Gravity.START), lp(Gravity.START))
            addView(edge(button(rightLabel, 1f, Fonts.REGULAR) { dismiss(runExpire = false); onRight() }, Gravity.END), lp(Gravity.END))
        }
        val v = frame(pad).apply {
            addView(message(msg, pad))
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        box = v
        hand.removeCallbacks(hide)
    }

    /**
     * 받는 동안 띄운다. 왼쪽 되돌리기 자리에 퍼센트를, 오른쪽에 Cancel 을 둔다.
     * 저절로 닫히지 않는다 — 끝나면 [dismiss] 로 닫는다.
     *
     * 퍼센트는 **숫자가 바뀔 때만** 고쳐 쓴다. e-ink 는 고쳐 그릴 때마다
     * 깜빡이므로 같은 값을 거듭 쓰지 않는다.
     *
     * @return 퍼센트(0~100)를 받는 함수. 아무 스레드에서 불러도 된다.
     */
    fun progress(msg: String, onCancel: () -> Unit): (Int) -> Unit {
        dismiss(runExpire = true)
        val pad = Ink.dp(ctx, 20f).toInt()
        val percent = label("0%")
        val row = FrameLayout(ctx).apply {
            addView(edge(percent, Gravity.START), lp(Gravity.START))
            addView(edge(button(CANCEL, 1f, Fonts.REGULAR) { dismiss(); onCancel() }, Gravity.END), lp(Gravity.END))
        }
        val v = frame(pad).apply {
            addView(message(msg, pad))
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        box = v
        hand.removeCallbacks(hide)

        var shown = 0
        return { p ->
            val q = p.coerceIn(0, 100)
            hand.post {
                if (box === v && q != shown) { shown = q; percent.text = "$q%" }
            }
        }
    }

    private fun frame(pad: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(pad, pad, pad, pad / 2)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(Ink.dp(ctx, 1.5f).toInt(), Color.BLACK)
        }
        isClickable = true
        minimumWidth = Ink.dp(ctx, 260f).toInt()
    }

    /** 알림 글. 단추보다 한 단 작게, 줄 사이는 조금 넉넉히. */
    private fun message(msg: String, pad: Int) = TextView(ctx).apply {
        text = msg
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        typeface = Fonts.of(ctx, Fonts.BODY)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, MSG_DP)
        setLineSpacing(Ink.dp(ctx, MSG_LINE_ADD_DP), 1f)
        setPadding(0, 0, 0, pad)
    }

    /** 누를 수 없는 단추 자리 글 — 단추와 같은 꼴·같은 자리 */
    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        typeface = Fonts.of(ctx, Fonts.UI)
        fontVariationSettings = Fonts.REGULAR
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        includeFontPadding = false
        val p = Ink.dp(ctx, 10f).toInt()
        setPadding(p, p, p, p)
        minHeight = Ink.dp(ctx, 44f).toInt()
    }

    /**
     * @param weight 가는 굵기는 획이 한 픽셀보다 얇아 **검게 지정해도 순수
     *   검정 픽셀이 생기지 않는다**(흰 바탕과 섞인 중간값만 남는다). 검게
     *   보여야 하는 닫기는 굵기를 올린다.
     */
    private fun button(label: String, alpha: Float, weight: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            setTextColor(Color.BLACK)
            this.alpha = alpha
            gravity = Gravity.CENTER
            typeface = Fonts.of(ctx, Fonts.UI)
            fontVariationSettings = weight
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            includeFontPadding = false
            val p = Ink.dp(ctx, 10f).toInt()
            setPadding(p, p, p, p)
            minHeight = Ink.dp(ctx, 44f).toInt()
            Shade.applyTo(this)
            setOnClickListener { onClick() }
        }

    /**
     * 단추 줄의 좌우 끝을 **먹으로** 맞춘다 — 왼쪽 단추는 첫 글자 먹의 왼쪽 끝을, 오른쪽 단추는
     * 끝 글자 먹의 오른쪽 끝을 팝업 글 상자(위의 글·입력칸)의 끝 선에 세운다. 모든 팝업이 따른다.
     *
     * 단추는 누르는 자리를 넓히려고 안쪽 여백을 두고, 기울인 글자는 곁(side bearing)이 있어
     * 그대로 두면 안쪽으로 들어가 보인다. 그만큼 단추째 옮긴다 — 글자가 잘리지 않는다.
     * 퍼센트처럼 글이 바뀌면 폭이 바뀔 때마다 다시 잰다.
     */
    private fun <T : TextView> edge(v: T, side: Int): T {
        fun place() {
            val t = v.text?.toString().orEmpty()
            if (t.isEmpty() || v.width == 0) return
            val r = android.graphics.Rect()
            v.paint.getTextBounds(t, 0, t.length, r)
            // 글은 wrap_content 라 안쪽 여백 바로 뒤에서 시작한다.
            v.translationX = if (side == Gravity.START) -(v.paddingLeft + r.left).toFloat()
                else (v.width - (v.paddingLeft + r.right)).toFloat()
        }
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        return v
    }

    private fun lp(gravity: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        gravity or Gravity.CENTER_VERTICAL,
    )

    /** 떠 있나 — 뒤로 가기가 앱보다 팝업을 먼저 닫게 한다. */
    val isShowing: Boolean get() = box != null

    fun dismiss(runExpire: Boolean = true) {
        hand.removeCallbacks(hide)
        box?.let { (it.parent as? FrameLayout)?.removeView(it) }
        box = null
        if (runExpire) onExpire?.invoke()
        onExpire = null
    }

    companion object {
        private const val CLOSE = "Close"
        private const val CANCEL = "Cancel"

        /** 알림 글 크기와 더하는 줄 간격 */
        private const val MSG_DP = 12f
        private const val MSG_LINE_ADD_DP = 2f

        const val PLAIN_MS = 4_000L
        const val UNDO_MS = 6_000L
    }
}
