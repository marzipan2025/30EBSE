package com.artbrain.ebse.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.artbrain.ebse.text.Epub
import com.artbrain.ebse.ui.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * epub 을 **글과 사진으로 풀어 둔다** — 읽을 때마다 압축을 풀지 않도록, 그리고
 * 리더가 다른 형식과 똑같이 글 한 벌만 보면 되도록.
 *
 * 사진은 본문 속 한 줄 `￼0001.png` 로 적는다([Epub.IMAGE_MARK]). 한 문단이라
 * 문장 가르기를 거쳐도 제 쪽 하나를 차지한다.
 */
object Fetch {

    /** 기기에 옮겨 둔 epub [file] 을 풀어 [id] 칸에 저장한다. [onProgress] 에 0‥1 을 알린다. */
    suspend fun epub(
        ctx: Context, store: DocStore, id: String, file: File,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val staged = File(ctx.cacheDir, "epub/$id-img")
        try {
            staged.deleteRecursively(); staged.mkdirs()
            val text = unpack(ctx, file, staged) { f -> ensureActive(); onProgress(f) }
            store.writeEpub(id, text, staged)
            text
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun unpack(ctx: Context, file: File, outDir: File, onProgress: (Float) -> Unit): String = ZipFile(file).use { zip ->
        fun bytes(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val blocks = Epub(::bytes).blocks()
        // 사진은 글 상자 폭의 정사각형에 차도록 — 리더의 상자와 같은 셈이다.
        val side = (ctx.resources.displayMetrics.widthPixels * Ink.BOX_FRACTION).toInt()
        val names = HashMap<String, String?>()   // zip 경로 → 저장한 이름 (못 쓰면 null)
        val sb = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            if (b is Epub.Image) onProgress(i.toFloat() / blocks.size)
            val line = when (b) {
                is Epub.Para -> b.text.replace(Epub.IMAGE_MARK.toString(), "")
                is Epub.Image -> names.getOrPut(b.path) {
                    val name = "%04d.png".format(names.size + 1)
                    val raw = bytes(b.path)
                    if (raw != null && saveGray(raw, side, File(outDir, name))) name else null
                }?.let { "${Epub.IMAGE_MARK}$it" }
            } ?: continue
            if (line.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(line)
        }
        if (sb.isEmpty()) throw IllegalArgumentException("epub 에서 읽을 글을 찾지 못했습니다.")
        sb.toString()
    }

    /**
     * 사진을 [side] 정사각형에 차게 맞추고 **16단계 회색**으로 저장한다.
     *
     * 점무늬(베이어·오차 확산)로 내면 기기의 화면 격자와 어긋나 물결무늬가
     * 선다. 기기가 16단계 회색을 제대로 내므로 그 단계에 맞춰 둔다. 작은
     * 사진도 비율대로 키워 상자에 채운다. 너무 작은 그림(여백용 점 따위)은
     * 버린다.
     */
    private fun saveGray(raw: ByteArray, side: Int, to: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return false
        if (w0 < TINY && h0 < TINY) return false

        // 필요한 크기의 두 배를 넘는 만큼만 줄여 읽는다 — 메모리를 아낀다.
        var sample = 1
        while (max(w0, h0) / (sample * 2) >= side * 2) sample *= 2
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false

        val scale = side.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)   // 투명한 곳은 종이색
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
        }
        src.recycle()

        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val g = ((px[i] shr 16) and 0xFF)
            val q = ((g + 8) / 17) * 17
            px[i] = Color.rgb(q, q, q)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        to.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        return true
    }

    /** 이보다 작은 그림은 가로·세로 모두 작으면 버린다 (px) */
    private const val TINY = 32
}
