package com.artbrain.ebse.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/**
 * 누름 표시 — 기호 아래에 `░` 를 **한 글자** 겹친다.
 *
 * 면을 칠하거나 무늬를 깔지 않는다. 기호도 `░` 도 결국 같은 글꼴의 한 글자
 * 이므로, 같은 크기·같은 자리에 겹쳐 놓으면 글자 뒤에 성긴 점무늬가 깔린
 * 꼴이 된다. e-ink 에서 네모가 나타났다 사라지는 것보다 조용하다.
 *
 * [owner] 의 붓을 그대로 베껴 쓰므로 글꼴·크기·굵기가 저절로 맞는다.
 * 자리 계산도 TextView 가 가운데 정렬할 때 쓰는 것과 같은 셈이라
 * **글자 상자가 정확히 포개진다.**
 *
 * [block] 을 바꾸면 짙기가 달라진다 — 시계 자리의 Offline 은 `▓` 위에 흰
 * 글자를 얹는다.
 */
class Shade(
    private val owner: TextView,
    private val block: String = BLOCK,
) : Drawable() {

    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.set(owner.paint)
        paint.isAntiAlias = true
        paint.color = Ink.BLACK
        paint.alpha = 255

        val blocks = block.repeat(owner.text?.length ?: 0)
        if (blocks.isEmpty()) return
        val advance = paint.measureText(blocks)
        val fm = paint.fontMetrics
        val x = b.left + (b.width() - advance) / 2f
        val y = b.top + (b.height() - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(blocks, x, y, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val BLOCK = "░"

        /** 짙은 무늬 — 늘 깔려 있는 표시(Offline)에 쓴다. */
        const val DENSE = "▓"

        /** 누를 때만 뒤에 깔리게 붙인다. */
        fun applyTo(v: TextView) {
            v.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Shade(v))
                addState(intArrayOf(), null)
            }
        }
    }
}

/**
 * 목록 칸의 누름 표시 — 제목 글자가 끝난 자리부터 칸의 오른쪽 끝까지 `░` 를
 * **한 줄 타이핑하듯** 깐다.
 *
 * 제목은 가리지 않는다. 무게와 지움표는 이 줄 위에 그대로 얹힌다(배경이다).
 * 크기는 제목보다 1dp 작게 — 글자로 친 줄이라 제목보다 한 발 물러나 보이게.
 * 기울이지 않은 Geist Mono 를 쓴다. 고정폭이라 한 칸씩 빈틈없이 이어진다.
 */
class LineShade(
    private val title: TextView,
    sizeDp: Float,
) : Drawable() {

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Ink.BLACK
        typeface = Fonts.of(title.context, Fonts.UI_UPRIGHT)
        fontVariationSettings = Fonts.THIN
        textSize = Ink.dp(title.context, sizeDp)
    }
    private val advance = paint.measureText(BLOCK)
    private val fm = paint.fontMetrics

    override fun draw(canvas: Canvas) {
        val b = bounds
        val layout = title.layout ?: return
        if (b.isEmpty || advance <= 0f) return
        // 제목 글자의 오른쪽 끝(말줄임표까지) — 칸 안에서의 자리
        val start = title.left + title.paddingLeft + layout.getLineRight(0)
        // 끝까지 채우려고 올림으로 센다. 넘친 마지막 한 글자는 칸 경계에서 잘린다.
        val n = kotlin.math.ceil((b.right - start) / advance).toInt()
        if (n <= 0) return
        val y = b.top + (b.height() - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.save()
        canvas.clipRect(b)
        canvas.drawText(BLOCK.repeat(n), start, y, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val BLOCK = "░"

        /** 칸을 누를 때만 깔리게 붙인다. */
        fun applyTo(row: android.view.View, title: TextView, sizeDp: Float) {
            row.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), LineShade(title, sizeDp))
                addState(intArrayOf(), null)
            }
        }
    }
}
