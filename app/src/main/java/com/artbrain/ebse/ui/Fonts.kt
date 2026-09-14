package com.artbrain.ebse.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * 글꼴을 **이름으로** 찾는다.
 *
 * `R.font.xxx` 로 직접 부르면 글꼴 파일이 없을 때 리소스 ID 가 아예 없어
 * 컴파일이 깨진다. 이 저장소는 글꼴을 재배포하지 않아 파일이 빠진 채로
 * 받는 것이 정상이므로, 이름으로 찾아 없으면 기기 기본 글꼴로 물러난다.
 * 그래야 받자마자 빌드가 된다.
 */
object Fonts {
    /** 본문 — 한글. 에이투지체 Regular. 없으면 기기 기본 글꼴. */
    const val BODY = "a2z_regular"

    /** 번호·화살표 — Geist Mono 의 가는 이탤릭. */
    const val UI = "geist_mono_italic"

    /** 제목 — 같은 Geist Mono 이되 기울이지 않은 것. */
    const val UI_UPRIGHT = "geist_mono"

    /** 가는 굵기 — 번호·화살표에 쓴다. */
    const val THIN = "'wght' 100"

    /** 보통 굵기 — 제목에 쓴다. */
    const val REGULAR = "'wght' 400"

    private val cache = HashMap<String, Typeface>()

    fun of(ctx: Context, name: String): Typeface = cache.getOrPut(name) {
        val id = ctx.resources.getIdentifier(name, "font", ctx.packageName)
        (if (id == 0) null else ResourcesCompat.getFont(ctx, id)) ?: Typeface.DEFAULT
    }
}
