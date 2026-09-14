package com.artbrain.ebse.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PdfBox-Android 는 JVM 에서 돌지 않아 기기에서 본다. 시험 PDF 는 크롬으로 뽑은
 * 것으로, 긴 문단이 종이 폭에서 여러 줄로 끊겨 있다 — 그 줄이 도로 이어지는지 본다.
 */
@RunWith(AndroidJUnit4::class)
class PdfTest {
    @Test fun wrappedLinesRejoin() {
        val ins = InstrumentationRegistry.getInstrumentation()
        PDFBoxResourceLoader.init(ins.targetContext)
        val f = File(ins.targetContext.cacheDir, "t.pdf")
        ins.context.assets.open("doc.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val t = Import.tidy(Import.pdf(f))
        android.util.Log.i("PdfTest", t)
        val long = ("종이 폭에서 줄이 끊기는 긴 문장을 시험합니다. ".repeat(6)).trim()
        assertEquals("$long\n\n둘째 문단은 짧습니다.", t)
    }
}
