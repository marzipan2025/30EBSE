package com.artbrain.ebse.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader

/**
 * 중간 밝기를 점무늬로 낸다.
 *
 * **무늬는 dp 가 아니라 화면 픽셀로 짠다.** dp 로 잡으면 212dpi 기기에서
 * 1.325 배로 늘어나며 보간이 끼고, 무늬가 흐린 회색으로 뭉개진다. 하프톤을
 * 쓰는 뜻이 없어진다. 그래서 비트맵을 화면 픽셀 그대로 깔고
 * [Paint.isFilterBitmap] 을 꺼 보간을 막는다.
 */
object Halftone {

    /** 열여섯 칸 가운데 몇 칸을 검게 둘지로 밝기를 낸다. */
    enum class Tone(val cells: Int) {
        /** 16칸 중 2칸 — 아주 옅게. 비활성 글자나 얇은 구분선에. */
        LIGHT(2),
        /** 16칸 중 4칸 — 옅게. 아직 차례가 아닌 칸에. */
        PALE(4),
        /** 16칸 중 8칸 — 절반. 격자무늬. 눌린 상태나 테두리에. */
        HALF(8),
        /** 16칸 중 12칸 — 짙게. */
        DARK(12),
    }

    /**
     * 4×4 짜리 한 조각. 어느 칸을 검게 둘지 미리 정해 둔다.
     *
     * 칸을 고르는 자리가 중요하다. 한쪽에 몰리면 무늬가 줄무늬로 보이고,
     * 고르게 퍼뜨리면 밝기로 읽힌다. 아래는 4×4 베이어(Bayer) 순서를 따른다.
     */
    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    private val cache = HashMap<Tone, Bitmap>()

    private fun tile(tone: Tone): Bitmap = cache.getOrPut(tone) {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) for (x in 0 until 4) {
            val on = BAYER[y * 4 + x] < tone.cells
            bmp.setPixel(x, y, if (on) Ink.BLACK else Ink.WHITE)
        }
        bmp
    }

    /** 무늬로 칠하는 붓. 보간과 앤티에일리어싱을 모두 끈다. */
    fun paint(tone: Tone): Paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
        shader = BitmapShader(tile(tone), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** 무늬 없는 검정 붓 */
    fun solid(): Paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
        color = Ink.BLACK
    }
}
