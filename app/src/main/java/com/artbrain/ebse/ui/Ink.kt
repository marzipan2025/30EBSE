package com.artbrain.ebse.ui

import android.content.Context
import android.util.TypedValue

/**
 * e-ink 화면의 눈금과 색.
 *
 * 색은 검정과 흰색 둘뿐이다. 중간 밝기가 필요한 자리는 [Halftone] 의 점무늬로
 * 낸다 — 회색으로 칠하면 e-ink 가 스스로 디더링하면서 얼룩이 남는다.
 */
object Ink {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** 본문 글자 크기. 시스템 글꼴 배율을 타지 않도록 sp 가 아니라 dp 다. */
    const val TEXT_DP = 17f

    /** 본문 행간 곱 */
    const val LINE_SPACING = 1.5f

    /** 행간에 더하는 몫. 곱만으로는 글자 크기에 묶여 따로 못 벌린다. */
    const val LINE_SPACING_ADD_DP = 1f

    /**
     * 글이 놓이는 상자 — 화면 가운데 60%. 가로 폭이 이 값으로 정해진다.
     *
     * 한 줄을 짧게 잡아 눈이 다음 줄 머리를 쉽게 되찾게 한다.
     */
    const val BOX_FRACTION = 0.6f

    /**
     * 한 쪽에 둘 수 있는 최대 줄 수.
     *
     * 다섯 줄이 넘으면 한눈에 들어오지 않는다. 그래서 네 줄에서 끊는다 —
     * 넘는 문장은 [com.artbrain.ebse.text.PageBuilder] 가 쪼갠다.
     *
     * 실측(59,168자 문서, 문장 1,064개)으로 본 맞바꿈이다. 60% 폭 17dp 에서는
     * 한 줄 16자, 네 줄 64자가 한도이고 **문장의 77%가 그 안에 오롯이 들어간다.**
     * 나머지 237개는 쪼개진다. 상자를 넓히면 쪼개지는 수가 줄지만(90%에서는
     * 0개) 한 줄이 24자로 길어진다 — 짧은 줄을 택한 대가다.
     */
    const val MAX_LINES = 4

    /** 잘린 문장의 짧은 꼬리를 앞 쪽에 붙일 때만 허락하는 줄 수 */
    const val MAX_LINES_TAIL = 5

    /**
     * 위아래 한 줄이 놓이는 자리 — 화면 위에서 이 비율, 아래에서도 이 비율.
     * 리더의 문장 번호·시계와 목록의 머리·발이 같은 줄에 선다.
     */
    const val EDGE_Y = 0.15f

    /** 손가락이 닿는 자리의 최소 크기. e-ink 터치는 정밀하지 않다. */
    const val TOUCH_DP = 56f

    fun dp(ctx: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    )
}
