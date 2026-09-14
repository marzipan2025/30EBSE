package com.artbrain.ebse.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 형식마다 글만 제대로 남는지, 한글이 깨지지 않는지 본다. 시험 파일은 src/test/resources 에. */
class ConvertTest {

    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name).toURI())

    @Test fun euckrText() {
        val t = Convert.text(Convert.TEXT, res("euckr.txt"))
        assertTrue(t, t.startsWith("첫 문단입니다."))
        assertTrue(t.contains("\n\n두 번째 문단!"))
    }

    @Test fun utf16WithoutBom() {
        assertEquals("UTF-16 텍스트입니다.", Convert.text(Convert.TEXT, res("utf16le.txt")))
    }

    @Test fun euckrEpubPage() {
        val s = Convert.decodeMarkup(res("euckr.xhtml").readBytes())
        assertTrue(s, s.contains("옛 epub 문단입니다."))
    }

    @Test fun markdown() {
        val t = Convert.text(Convert.MD, res("note.md"))
        assertTrue(t.startsWith("마크다운 제목\n\n"))
        assertTrue(t.contains("이것은 굵은 글과 기울인 글, 그리고 링크가 섞인 문단입니다."))
        assertTrue(t.contains("\n\n첫째 항목입니다.\n\n"))
        assertTrue(t.contains("둘째 항목, 코드 포함."))
        assertTrue(t.contains("인용문도 글만 남아야 합니다."))
        assertFalse(t.contains("#") || t.contains("*") || t.contains("http") || t.contains(">"))
    }

    @Test fun subtitlesCp949() {
        // 칸(시간 토막)마다 한 문단. 칸끼리는 잇지 않는다. CP949 도 깨지지 않는다.
        assertEquals("안녕하세요,\n\n반갑습니다.\n\n오랜만이네\n\n정말로", Convert.text(Convert.SRT, res("sub.srt")))
    }

    @Test fun oneCueTwoLines() {
        // 한 칸 안의 두 줄은 이어서 한 문단. 문장 가르기는 리더가 한다.
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n<i>가자.</i>\n{\\an8}어서!\n\n2\n00:00:04,000 --> 00:00:05,000\n어디로?\n"
        assertEquals("가자. 어서!\n\n어디로?", Convert.tidy(Convert.subtitles(srt)))
    }

    @Test fun docx() {
        val t = Convert.text(Convert.DOCX, res("word.docx"))
        assertTrue(t.startsWith("워드 문서의 첫 문장입니다. 두 번째 문장도 있습니다."))
        assertTrue(t.contains("새 문단은 여기서 시작합니다."))
    }

    @Test fun kinds() {
        assertEquals(Convert.MD, Convert.kindOf("application/octet-stream", "R6.md"))
        assertEquals(Convert.SRT, Convert.kindOf("text/plain", "영화.SRT"))
        assertEquals(null, Convert.kindOf("application/zip", "a.zip"))
        assertEquals("좀머 씨 이야기.docx", Convert.title("좀머 씨 이야기.docx"))
        assertEquals("포도에서와인으로", Convert.title("포도에서와인으로.EPUB"))
    }

    // PDF 자체는 기기에서 본다(androidTest 의 PdfTest). 여기서는 줄 잇기만.
    @Test fun joinWrapped() {
        assertEquals("긴 문장이 끊겨 있다.", Convert.joinWrapped(listOf("긴 문장이", "끊겨 있다.")))
        assertEquals("a b", Convert.joinWrapped(listOf("a ", "b")))
        assertEquals("international", Convert.joinWrapped(listOf("inter-", "national")))
        assertEquals("Seoul-Busan", Convert.joinWrapped(listOf("Seoul-", "Busan")))
        assertEquals("日本語の文章", Convert.joinWrapped(listOf("日本語の", "文章")))
    }
}
