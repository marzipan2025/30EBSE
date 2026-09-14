package com.artbrain.ebse.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artbrain.ebse.text.Convert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 단위 시험(ConvertTest)과 같은 파일을 **기기에서** 한 번 더 푼다.
 * 안드로이드의 정규식(ICU)과 문자셋은 JVM 과 달라, JVM 에서 통과해도 기기에서
 * 넘어질 수 있다(srt 의 `{[^}]*}` 가 그랬다).
 */
@RunWith(AndroidJUnit4::class)
class ConvertDeviceTest {
    private fun asset(name: String): File {
        val ins = InstrumentationRegistry.getInstrumentation()
        val f = File(ins.targetContext.cacheDir, name)
        ins.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test fun allFormats() {
        assertTrue(Convert.text(Convert.TEXT, asset("euckr.txt")).startsWith("첫 문단입니다."))
        assertEquals("UTF-16 텍스트입니다.", Convert.text(Convert.TEXT, asset("utf16le.txt")))
        assertTrue(Convert.decodeMarkup(asset("euckr.xhtml").readBytes()).contains("옛 epub 문단입니다."))
        val md = Convert.text(Convert.MD, asset("note.md"))
        assertTrue(md, md.startsWith("마크다운 제목\n\n") && md.contains("그리고 링크가 섞인") && md.contains("둘째 항목, 코드 포함."))
        assertEquals("안녕하세요,\n\n반갑습니다.\n\n오랜만이네\n\n정말로", Convert.text(Convert.SRT, asset("sub.srt")))
        assertTrue(Convert.text(Convert.DOCX, asset("word.docx")).startsWith("워드 문서의 첫 문장입니다."))
        assertEquals("international", Convert.joinWrapped(listOf("inter-", "national")))
    }
}
