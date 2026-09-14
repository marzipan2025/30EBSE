package com.artbrain.ebse.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.artbrain.ebse.text.Epub
import com.artbrain.ebse.text.PageBuilder
import com.artbrain.ebse.text.Sentences
import com.artbrain.ebse.text.WordWrap
import kotlin.math.floor

/**
 * 한 쪽에 문장 하나를 그린다.
 *
 * 글은 **화면 가운데 60% 상자** 안에만 놓이고, 그 안에서 가로·세로 모두
 * 가운데로 맞춘다. 상자에 들어가지 않는 문장은 [PageBuilder] 가 쪼갠다.
 *
 * 줄바꿈은 [WordWrap] 이 **띄어쓰기에서만** 한다. 안드로이드에 맡기면 한글이
 * 음절 단위로 끊기고, 그걸 막는 `LineBreakConfig` 는 API 33 부터다.
 *
 * 글자에는 앤티에일리어싱을 쓴다 — 212dpi 에서 계단이 보이면 읽기 힘들다.
 * 회색 무늬를 쓰는 [Halftone] 과는 반대로 가는 것이 맞다.
 *
 * **사진도 한 쪽이다.** 본문에 `￼0001.png` 로 적힌 쪽은 [imageDir] 의 사진을
 * 그린다. 사진은 받을 때 이미 상자 폭의 정사각형에 맞춰 두었으므로 늘리지 않고
 * 한 픽셀씩 그대로 옮긴다 — 보간이 끼면 16단계 회색이 흐려진다.
 */
class PageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint().apply {
        isAntiAlias = true
        color = Ink.BLACK
        textSize = Ink.dp(context, Ink.TEXT_DP)
    }

    private var raw: String = ""
    private var pages: List<String> = emptyList()
    private var layout: StaticLayout? = null
    private var imageDir: java.io.File? = null
    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        // e-ink 가 아니면 창이 통째로 뒤집히므로 사진만 미리 한 번 뒤집어 둔다.
        colorFilter = Eink.photoFilter
    }
    private var pendingRestore = 0

    /** 글 상자의 크기 — 화면 가운데 [Ink.BOX_FRACTION] 만큼 */
    private var boxW = 1
    private var boxH = 1

    /** 한 쪽에 들어가는 최대 줄 수 */
    var maxLines: Int = 1
        private set

    var page: Int = 0
        set(value) {
            val v = value.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (v == field && layout != null) return
            field = v
            rebuildLayout()
            invalidate()
        }

    val pageCount: Int get() = pages.size

    /** 쪽이 새로 나뉘면 알린다 — 진행 표시와 타임라인을 다시 그려야 한다. */
    var onPaginated: ((count: Int) -> Unit)? = null

    fun setFont(tf: Typeface) {
        paint.typeface = tf
        repaginate()
    }

    /** 문서 본문을 앉힌다. [restore] 쪽부터 보여 준다. 사진은 [images] 에서 찾는다. */
    fun setDocument(text: String, restore: Int, images: java.io.File? = null) {
        raw = text
        imageDir = images
        pendingRestore = restore
        repaginate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        repaginate()
    }

    private fun lineHeight(): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * Ink.LINE_SPACING + spacingAdd
    }

    private val spacingAdd = Ink.dp(context, Ink.LINE_SPACING_ADD_DP)

    /** 띄어쓰기에서만 끊어 나눈 줄들 */
    private fun wrapLines(s: String): List<String> =
        WordWrap.wrap(s, boxW.toFloat()) { paint.measureText(it) }

    private fun repaginate() {
        if (width <= 0 || height <= 0) return

        boxW = (width * Ink.BOX_FRACTION).toInt().coerceAtLeast(1)
        boxH = (height * Ink.BOX_FRACTION).toInt().coerceAtLeast(1)
        // 상자 높이가 허락하는 줄 수와 [Ink.MAX_LINES] 가운데 작은 쪽.
        val roomy = floor(boxH / lineHeight()).toInt()
        maxLines = minOf(Ink.MAX_LINES, roomy).coerceAtLeast(1)

        val fits: (String) -> Boolean = { s ->
            s.isEmpty() || wrapLines(s).size <= maxLines
        }

        // 짧은 꼬리를 붙일 때만 한 줄 더. 상자 높이가 허락하는 만큼만.
        val tailLines = minOf(Ink.MAX_LINES_TAIL, roomy).coerceAtLeast(maxLines)
        val fitsTail: (String) -> Boolean = { s -> wrapLines(s).size <= tailLines }

        pages = if (raw.isBlank()) emptyList()
        else PageBuilder.build(Sentences.split(raw), fits, fitsTail)

        android.util.Log.i("EBWO", "box=${boxW}x${boxH} lineH=${"%.1f".format(lineHeight())} " +
            "maxLines=$maxLines 한줄글자=${(boxW / paint.measureText("가")).toInt()} " +
            "쪽최대글자=${(maxLines * boxW / paint.measureText("가")).toInt()} " +
            "원문=${raw.length}자 쪽=${pages.size}")

        page = pendingRestore.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        rebuildLayout()
        onPaginated?.invoke(pages.size)
        invalidate()
    }

    private fun rebuildLayout() {
        val s = pages.getOrNull(page)
        bitmap?.recycle()
        bitmap = null
        if (s == null || width <= 0) { layout = null; return }
        val img = imageFor(s)
        if (img != null) {
            bitmap = BitmapFactory.decodeFile(img.path)
            if (bitmap != null) { layout = null; return }
        }
        // 우리가 끊은 줄을 그대로 그린다. 줄바꿈이 이미 박혀 있으므로
        // StaticLayout 이 따로 끊을 일이 없다.
        val wrapped = wrapLines(s).joinToString("\n")
        layout = StaticLayout.Builder
            .obtain(wrapped, 0, wrapped.length, paint, boxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(spacingAdd, Ink.LINE_SPACING)
            .setIncludePad(false)
            .build()
    }

    /** 이 쪽이 사진이면 그 파일. 표가 있어도 파일이 없으면 글로 그린다. */
    private fun imageFor(s: String): java.io.File? {
        if (s.firstOrNull() != Epub.IMAGE_MARK) return null
        val name = s.substring(1).trim()
        if (name.isEmpty() || '/' in name) return null
        return java.io.File(imageDir ?: return null, name).takeIf { it.isFile }
    }


    /** 다음 쪽으로. 마지막이면 false. */
    fun next(): Boolean {
        if (page >= pages.size - 1) return false
        page += 1
        return true
    }

    /** 앞 쪽으로. 처음이면 false. */
    fun prev(): Boolean {
        if (page <= 0) return false
        page -= 1
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // 바탕은 칠하지 않는다 — 루트가 흰 종이이고, 그 사이에 [Chime] 이
        // 글자 뒤로 깔린다.
        bitmap?.let {
            // 정수 픽셀에 놓아야 한 픽셀씩 그대로 옮겨진다.
            canvas.drawBitmap(it, ((width - it.width) / 2).toFloat(), ((height - it.height) / 2).toFloat(), bitmapPaint)
            return
        }
        val l = layout ?: return
        canvas.save()
        canvas.translate((width - boxW) / 2f, (height - l.height) / 2f)
        l.draw(canvas)
        canvas.restore()
    }
}
