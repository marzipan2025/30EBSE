package com.artbrain.ebse.ui

import android.graphics.Rect
import android.widget.TextView

/**
 * 글리프를 제 네모 한가운데로 민다.
 *
 * TextView 는 글자를 **글자 상자**(ascent~descent) 기준으로 가운데 두는데,
 * 화살표는 그 상자 안에서 위아래·좌우로 치우쳐 있다. 게다가 이탤릭이라
 * 가로로도 밀린다. 그래서 글리프의 **실제 먹 자리**를 재어 그 차이만큼
 * 되민다 — 글꼴이나 크기를 바꿔도 저절로 맞는다.
 */
object Glyph {
    private val r = Rect()

    /**
     * 세로는 가운데, 가로는 제 자리의 **끝에 딱 맞춘다**.
     *
     * 단추 안에서 가운데로 두면 글리프가 활용공간 가장자리에서 안쪽으로
     * 들어가, 가장자리에 붙은 글(제목 따위)과 줄이 안 맞는다. 그러면 그 줄만
     * 한쪽으로 밀린 것처럼 보인다. 먹의 실제 끝을 재어 가장자리에 세운다.
     */
    fun alignEdge(b: TextView, toStart: Boolean) {
        val t = b.text?.toString().orEmpty()
        if (t.isEmpty() || b.width == 0) return
        b.paint.getTextBounds(t, 0, t.length, r)
        val fm = b.paint.fontMetrics
        b.translationY = (fm.ascent + fm.descent) / 2f - (r.top + r.bottom) / 2f
        val advance = b.paint.measureText(t)
        // gravity 가 가운데이므로 글자는 (width-advance)/2 에서 시작한다.
        val inkLeft = (b.width - advance) / 2f + r.left
        val inkRight = (b.width - advance) / 2f + r.right
        b.translationX = if (toStart) -inkLeft else b.width - inkRight
    }

    /** 세로만 먹 기준으로 맞춘다. 가로는 뷰의 gravity 에 맡길 때 쓴다. */
    fun centerVertical(b: TextView) {
        val t = b.text?.toString().orEmpty()
        if (t.isEmpty()) return
        b.paint.getTextBounds(t, 0, t.length, r)
        val fm = b.paint.fontMetrics
        b.translationY = (fm.ascent + fm.descent) / 2f - (r.top + r.bottom) / 2f
    }

    fun center(b: TextView) {
        val t = b.text?.toString().orEmpty()
        if (t.isEmpty()) return
        b.paint.getTextBounds(t, 0, t.length, r)
        val fm = b.paint.fontMetrics
        b.translationY = (fm.ascent + fm.descent) / 2f - (r.top + r.bottom) / 2f
        b.translationX = b.paint.measureText(t) / 2f - (r.left + r.right) / 2f
    }
}
