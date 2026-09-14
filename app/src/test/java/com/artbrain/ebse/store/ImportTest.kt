package com.artbrain.ebse.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 형식마다 글만 제대로 남는지 본다. 시험 파일은 src/test/resources 에. */
class ImportTest {

    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name).toURI())

    @Test fun euckrText() {
        val t = Import.tidy(Import.decode(res("euckr.txt").readBytes()))
        assertTrue(t, t.startsWith("첫 문단입니다."))
        assertTrue(t.contains("\n\n두 번째 문단!"))
    }

    @Test fun markdown() {
        val t = Import.tidy(Import.markdown(res("note.md").readText()))
        println(t)
        assertTrue(t.startsWith("마크다운 제목\n\n"))
        assertTrue(t.contains("이것은 굵은 글과 기울인 글, 그리고 링크가 섞인 문단입니다."))
        assertTrue(t.contains("\n\n첫째 항목입니다.\n\n"))
        assertTrue(t.contains("둘째 항목, 코드 포함."))
        assertTrue(t.contains("인용문도 글만 남아야 합니다."))
        assertFalse(t.contains("#") || t.contains("*") || t.contains("http") || t.contains(">"))
    }

    @Test fun subtitlesCp949() {
        val t = Import.tidy(Import.subtitles(Import.decode(res("sub.srt").readBytes())))
        println(t)
        // 끝 부호 없이 이어진 두 칸은 붙고, 쉼(3초)에서 갈린다.
        assertEquals("안녕하세요, 반갑습니다.\n\n오랜만이네 정말로", t)
    }

    @Test fun docx() {
        val t = Import.tidy(Import.docx(res("word.docx")))
        println(t)
        assertTrue(t.startsWith("워드 문서의 첫 문장입니다. 두 번째 문장도 있습니다."))
        assertTrue(t.contains("새 문단은 여기서 시작합니다."))
    }

    // PDF 자체는 기기에서 본다(androidTest 의 PdfTest). 여기서는 줄 잇기만.
    @Test fun joinWrapped() {
        assertEquals("긴 문장이 끊겨 있다.", Import.joinWrapped(listOf("긴 문장이", "끊겨 있다.")))
        assertEquals("a b", Import.joinWrapped(listOf("a ", "b")))
        assertEquals("international", Import.joinWrapped(listOf("inter-", "national")))
        assertEquals("Seoul-Busan", Import.joinWrapped(listOf("Seoul-", "Busan")))
        assertEquals("日本語の文章", Import.joinWrapped(listOf("日本語の", "文章")))
    }
}
