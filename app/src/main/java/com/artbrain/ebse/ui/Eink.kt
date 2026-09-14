package com.artbrain.ebse.ui

import android.app.Activity
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * e-ink 화면을 다룬다 — 전체 갱신과, e-ink 가 아닐 때의 색 반전.
 *
 * **전체 갱신.** Onyx 프레임워크의 `View.applyGCOnce()` 를 부른 뒤 다시 그리면
 * 그 한 번이 GC16 전체 갱신이 된다. 기기 로그에서 평소 `waveform_mode=1
 * update_mode=0`(빠른 부분 갱신)이 `waveform_mode=2 update_mode=1` 로 바뀌는
 * 것을 봤다. 공개 API 가 아니라 반사로 부른다 —
 * `ViewUpdateHelper.fullRefreshScreen`, `View.invalidate(int)` 은 일반 앱에
 * 막혀 있고(blacklist) 이것만 열려 있다.
 *
 * **e-ink 인지는 이 메서드가 있는지로 가린다.** 안드로이드에는 "e-ink 화면"을
 * 알려 주는 표준 API 가 없다. 제조사 이름보다 "전체 갱신을 걸 수 있는가" 가
 * 우리가 알고 싶은 바로 그것이다.
 *
 * **e-ink 가 아니면 창 전체의 색을 뒤집는다**([applyTheme]). 발광 화면에서 흰
 * 바탕은 눈부시다. 화면마다 색을 고치지 않고 창에 반전 필터를 한 겹 씌우므로
 * 목록·리더·팝업·옅기가 한꺼번에 맞게 뒤집힌다. 사진만은 [photoFilter] 로
 * 한 번 더 뒤집어 원래대로 보인다.
 */
object Eink {

    private val applyGCOnce: Method? by lazy {
        runCatching { View::class.java.getMethod("applyGCOnce") }.getOrNull()
    }

    /** 전체 갱신을 걸 수 있는 e-ink 기기인가 */
    val isEink: Boolean get() = applyGCOnce != null

    /** 다음 그리기를 전체 갱신으로 하고, 창 전체를 다시 그리게 한다. e-ink 가 아니면 아무것도 안 한다. */
    fun fullRefresh(view: View) {
        val m = applyGCOnce ?: return
        val top = view.rootView
        runCatching { m.invoke(top) }
            .onFailure { Log.i("EBWO", "applyGCOnce 실패: $it") }
        top.invalidate()
    }

    private val INVERT = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))

    /** 화면이 뒤집혀 있으면 사진을 원래대로 되돌리는 필터. 아니면 null. */
    val photoFilter: ColorMatrixColorFilter?
        get() = if (isEink) null else ColorMatrixColorFilter(INVERT)

    /** e-ink 가 아니면 이 창의 색을 뒤집는다. setContentView 뒤에 부른다. */
    fun applyTheme(activity: Activity) {
        Log.i("EBWO", "eink=$isEink")
        if (isEink) return
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(INVERT) }
        activity.window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }
}
