package com.artbrain.ebse.store

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artbrain.ebse.text.Epub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 사진이 든 epub 을 선택창에서 고른 것처럼 들인다([Import.one]) — 글과 사진이
 * 앱 폴더에 풀리고 본문에 사진 표가 들어가는지 본다.
 *
 * pictures.epub 은 시험용으로 만든 것이다: 장 둘, 가로 PNG 600×400 하나, 세로 JPEG
 * 300×500 하나. 들인 칸은 지우지 않고 남겨 두어 리더에서 눈으로도 볼 수 있게 한다.
 */
@RunWith(AndroidJUnit4::class)
class EpubImportTest {
    @Test fun picturesSurvive() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val f = File(ctx.cacheDir, "사진책.epub")
        ins.context.assets.open("pictures.epub").use { i -> f.outputStream().use { i.copyTo(it) } }

        val store = DocStore(ctx)
        val doc = Import.one(ctx, store, Uri.fromFile(f)) {}
        assertEquals("사진책", doc.name)
        assertTrue(doc.isEpub)

        val text = store.readBody(doc.id)!!
        android.util.Log.i("EpubImportTest", text)
        val marks = text.split("\n\n").filter { it.startsWith(Epub.IMAGE_MARK) }
        assertEquals(2, marks.size)
        val images = store.imageDir(doc.id).listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("0001.png", "0002.png"), images)
        assertTrue(text.indexOf("사진 앞의 문장입니다.") < text.indexOf(marks[0]))
        assertTrue(text.indexOf(marks[0]) < text.indexOf("넓은 사진 뒤의 문장입니다."))
        assertTrue(text.contains("세로 사진 뒤의 문장입니다."))
    }
}
