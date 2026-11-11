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
 *
 * **폭은 늘 [WIDTH_DP](260dp)다.** 글이 길면 그 안에서 줄을 바꾼다. 화면이 그보다 좁으면 화면
 * 폭까지만. 파일 이름처럼 길이를 알 수 없는 것은 [show]·[progress] 의 `name` 으로 따로 넘긴다
 * — 이름과 글을 합쳐 네 줄 이상이 되면 이름을 말줄임(…)으로 줄여 모두 세 줄 안에 둔다.
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
        name: String? = null,
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

        val v = frame(pad).apply {
            addView(message(msg, pad, name))
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(v, boxLp())
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
        root.addView(v, boxLp())
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
    fun progress(msg: String, name: String? = null, onCancel: () -> Unit): (Int) -> Unit {
        dismiss(runExpire = true)
        val pad = Ink.dp(ctx, 20f).toInt()
        val percent = label("0%")
        val row = FrameLayout(ctx).apply {
            addView(edge(percent, Gravity.START), lp(Gravity.START))
            addView(edge(button(CANCEL, 1f, Fonts.REGULAR) { dismiss(); onCancel() }, Gravity.END), lp(Gravity.END))
        }
        val v = frame(pad).apply {
            addView(message(msg, pad, name))
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(v, boxLp())
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

    /**
     * 긴 글(라이선스 원문 따위)을 띄운다. **폭은 여느 팝업과 같고([WIDTH_DP]) 높이는 활용공간만큼**
     * — 위아래 끝이 목록 머리·발 줄([Ink.EDGE_Y])의 손가락 자리 끝과 같다.
     * 머리 줄 왼쪽에 [title], 오른쪽에 Close. 제목은 글 상자 폭의 [DOC_TITLE_FRACTION] 를 넘으면
     * 두 줄로 감고, **첫 줄 글자의 윗끝(먹)을 Close 글자의 윗끝에** 맞춘다. Close 는 제목이 몇 줄이든
     * 팝업 윗면에서 늘 같은 거리에 서고, 두 줄 제목은 아래로 자란다.
     *
     * 스크롤은 쓰지 않는다 — e-ink 에서 끌어 올리는 동안 화면이 번진다. 대신 글 상자 높이에
     * 맞춰 쪽으로 나누고, 한 쪽에 다 들지 않으면 아래 줄에 `←` 쪽번호 `→` 를 둔다(목록과 같은 꼴).
     * 쪽은 실제로 그릴 붓과 폭으로 줄을 끊어 나누므로 쪽 끝에서 글이 잘리지 않는다.
     * 저절로 닫히지 않는다.
     */
    fun document(title: String, text: String, textDp: Float = DOC_DP) {
        dismiss(runExpire = true)
        val pad = Ink.dp(ctx, 20f).toInt()
        val touch = Ink.dp(ctx, Ink.TOUCH_DP).toInt()
        val boxW = boxWidth()
        val boxH = ((root.height * (1f - 2 * Ink.EDGE_Y)).toInt() + touch)
            .coerceAtMost(root.height).coerceAtLeast(Ink.dp(ctx, 200f).toInt())
        val rowH = Ink.dp(ctx, 44f).toInt()
        val gap = Ink.dp(ctx, DOC_GAP_DP).toInt()
        val textW = boxW - pad * 2

        // 머리 줄 — 제목(넘치면 두 줄)과 Close.
        val titleView = TextView(ctx).apply {
            this.text = title
            setTextColor(Color.BLACK)
            typeface = Fonts.of(ctx, Fonts.UI)
            fontVariationSettings = Fonts.REGULAR
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, DOC_TITLE_DP)
            includeFontPadding = false
            maxWidth = (textW * DOC_TITLE_FRACTION).toInt()
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val close = edge(button(CLOSE, 1f, Fonts.REGULAR) { dismiss() }, Gravity.END)
        val head = FrameLayout(ctx).apply {
            addView(titleView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP))
            addView(close, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP))
        }
        // 제목 첫 줄 먹의 윗끝을 Close 먹의 윗끝에 맞춘다. Close 는 제자리, 제목만 옮긴다.
        // 글자 크기가 달라 글자 상자로 맞추면 어긋나 보이므로 실제 먹(getTextBounds)으로 잰다.
        head.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val tl = titleView.layout ?: return@addOnLayoutChangeListener
            val r = android.graphics.Rect()
            close.paint.getTextBounds(CLOSE, 0, CLOSE.length, r)
            val want = close.top + close.baseline + r.top
            val first = title.substring(tl.getLineStart(0), tl.getLineEnd(0)).trimEnd()
            titleView.paint.getTextBounds(first, 0, first.length, r)
            val have = titleView.top + titleView.paddingTop + tl.getLineBaseline(0) + r.top
            if (want != have) titleView.translationY = (want - have).toFloat()
        }
        // 머리 줄 높이 — 한 줄이면 단추 높이, 두 줄 제목이 그보다 길게 내려가면 그만큼.
        head.measure(
            View.MeasureSpec.makeMeasureSpec(textW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val closeTopInk = run {
            val r = android.graphics.Rect()
            close.paint.getTextBounds(CLOSE, 0, CLOSE.length, r)
            close.paddingTop + (close.measuredHeight - close.paddingTop - close.paddingBottom -
                (close.paint.fontMetricsInt.descent - close.paint.fontMetricsInt.ascent)) / 2 -
                close.paint.fontMetricsInt.ascent + r.top
        }
        val headH = maxOf(rowH, closeTopInk + titleView.measuredHeight)
        // 윗 여백을 여느 팝업과 맞춘다 — 여느 팝업의 첫 글자 먹 윗끝이 서는 자리에 Close·제목의 먹
        // 윗끝이 오도록, 단추의 안쪽 여백만큼 팝업 위 여백을 줄인다.
        val msgTopInk = run {
            val t = text("가")
            t.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val r = android.graphics.Rect()
            t.paint.getTextBounds("가", 0, 1, r)
            t.baseline + r.top
        }
        val topPad = (pad + msgTopInk - closeTopInk).coerceAtLeast(0)

        val body = TextView(ctx).apply {
            setTextColor(Color.BLACK)
            typeface = Fonts.of(ctx, Fonts.BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp)
            setLineSpacing(Ink.dp(ctx, MSG_LINE_ADD_DP), 1f)
            includeFontPadding = false
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            gravity = Gravity.TOP or Gravity.START
        }

        // 머리 줄과 (있으면) 쪽 줄을 뺀 높이에 들어가는 만큼씩 나눈다.
        val oneH = boxH - topPad - pad / 2 - headH - gap
        var pages = paginate(text, body, textW, oneH)
        if (pages.size > 1) pages = paginate(text, body, textW, oneH - rowH - gap)
        val textH = if (pages.size > 1) oneH - rowH - gap else oneH
        var at = 0

        val number = label("")
        val prev = button(PREV, 1f, Fonts.THIN) {}
        val next = button(NEXT, 1f, Fonts.THIN) {}
        fun turn(to: Int) {
            at = to.coerceIn(0, pages.size - 1)
            body.text = pages[at]
            number.text = "${at + 1}/${pages.size}"
            // 넘길 수 없는 쪽 화살표는 목록처럼 옅게 남긴다 — 줄의 균형이 무너지지 않는다.
            prev.alpha = if (at > 0) 1f else DIM
            next.alpha = if (at < pages.size - 1) 1f else DIM
        }
        prev.setOnClickListener { if (at > 0) turn(at - 1) }
        next.setOnClickListener { if (at < pages.size - 1) turn(at + 1) }
        for (b in listOf(prev, next)) b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
        number.fontVariationSettings = Fonts.THIN
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)

        val v = frame(pad).apply {
            setPadding(pad, topPad, pad, pad / 2)
            clipChildren = false
            gravity = Gravity.TOP
            addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headH))
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, textH).apply {
                topMargin = gap
            })
            if (pages.size > 1) addView(FrameLayout(ctx).apply {
                addView(edge(prev, Gravity.START), lp(Gravity.START))
                addView(number, lp(Gravity.CENTER_HORIZONTAL))
                addView(edge(next, Gravity.END), lp(Gravity.END))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH).apply {
                topMargin = gap
            })
        }
        turn(0)
        root.addView(v, FrameLayout.LayoutParams(boxW, boxH, Gravity.CENTER))
        box = v
        hand.removeCallbacks(hide)
    }

    /**
     * [text] 를 [width] 폭·[height] 높이의 쪽들로 나눈다. [v] 의 붓과 줄 간격 그대로 줄을 끊고,
     * 쪽마다 줄 머리에서 시작하므로 쪽을 따로 그려도 같은 자리에서 끊긴다. 쪽 머리의 빈 줄은 뗀다.
     */
    private fun paginate(text: String, v: TextView, width: Int, height: Int): List<String> {
        if (width <= 0 || height <= 0) return listOf(text)
        val l = android.text.StaticLayout.Builder.obtain(text, 0, text.length, v.paint, width)
            .setLineSpacing(v.lineSpacingExtra, v.lineSpacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        val pages = ArrayList<String>()
        var first = 0
        while (first < l.lineCount) {
            // 쪽 머리의 빈 줄(문단 사이)은 건너뛴다.
            while (first < l.lineCount && text.substring(l.getLineStart(first), l.getLineEnd(first)).isBlank()) first++
            if (first >= l.lineCount) break
            val top = l.getLineTop(first)
            var last = first
            while (last + 1 < l.lineCount && l.getLineBottom(last + 1) - top <= height) last++
            pages += text.substring(l.getLineStart(first), l.getLineEnd(last)).trimEnd()
            first = last + 1
        }
        return pages.ifEmpty { listOf(text) }
    }

    private fun frame(pad: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(pad, pad, pad, pad / 2)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(Ink.dp(ctx, 1.5f).toInt(), Color.BLACK)
        }
        // 뒤쪽으로 누름이 새어 나가지 않게 한다.
        isClickable = true
    }

    /** 팝업 네모의 폭 — [WIDTH_DP], 화면이 더 좁으면 화면 폭 */
    private fun boxWidth(): Int {
        val w = Ink.dp(ctx, WIDTH_DP).toInt()
        return if (root.width in 1 until w) root.width else w
    }

    private fun boxLp() = FrameLayout.LayoutParams(boxWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)

    /** 알림 글 한 토막. 단추보다 한 단 작게, 줄 사이는 조금 넉넉히. */
    private fun text(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        typeface = Fonts.of(ctx, Fonts.BODY)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, MSG_DP)
        setLineSpacing(Ink.dp(ctx, MSG_LINE_ADD_DP), 1f)
    }

    /**
     * 알림 글. [name](파일 이름 따위)이 있으면 그 줄을 위에 따로 두고, 이름과 [msg] 를 합쳐
     * [MAX_LINES] 줄을 넘으면 이름을 말줄임으로 줄인다 — 글([msg])은 자르지 않는다.
     */
    private fun message(msg: String, pad: Int, name: String? = null): View {
        val body = text(msg)
        if (name.isNullOrEmpty()) return body.apply { setPadding(0, 0, 0, pad) }

        val head = text(name)
        val width = boxWidth() - pad * 2
        val nameLines = linesOf(head, width)
        val bodyLines = linesOf(body, width)
        if (nameLines + bodyLines > MAX_LINES) {
            head.maxLines = (MAX_LINES - bodyLines).coerceAtLeast(1)
            head.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
            addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** [v] 의 글이 [width] 폭에서 몇 줄이 되는가 — 그려지는 붓 그대로 잰다. */
    private fun linesOf(v: TextView, width: Int): Int {
        if (width <= 0) return 1
        val t = v.text
        return android.text.StaticLayout.Builder.obtain(t, 0, t.length, v.paint, width)
            .setLineSpacing(v.lineSpacingExtra, v.lineSpacingMultiplier)
            .setIncludePad(v.includeFontPadding)
            .build().lineCount
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

        /** 팝업 네모의 폭 — 모든 팝업이 같다 */
        const val WIDTH_DP = 260f

        /** 이름과 글을 합친 줄 수가 이보다 많으면 이름을 말줄임한다 */
        private const val MAX_LINES = 3
        private const val CANCEL = "Cancel"

        /** 알림 글 크기와 더하는 줄 간격 */
        private const val MSG_DP = 12f
        private const val MSG_LINE_ADD_DP = 2f

        /** 긴 글 팝업 — 글 크기, 제목 크기, 제목이 차지할 수 있는 폭(글 상자의 비율), 줄 사이 */
        private const val DOC_DP = 10f
        private const val DOC_TITLE_DP = 12f
        private const val DOC_TITLE_FRACTION = 0.6f
        private const val DOC_GAP_DP = 8f
        private const val PREV = "←"
        private const val NEXT = "→"
        /** 넘길 수 없는 화살표의 옅기 — 목록과 같다 */
        private const val DIM = 0.5f

        const val PLAIN_MS = 4_000L
        const val UNDO_MS = 6_000L
    }
}
