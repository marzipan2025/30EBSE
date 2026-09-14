package com.artbrain.ebse.text

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF 에서 글을 뽑는다 (PdfBox-Android). 구글 문서를 SAF 로 받을 때도 이 길이다.
 *
 * 문단이 바뀌는 자리에만 표를 세워 두고, 문단 안에서 종이 폭에 끊긴 줄은 도로
 * 잇는다([Convert.joinWrapped]). 앱이 뜰 때 `PDFBoxResourceLoader.init` 을 불러
 * 두어야 한다(App). JVM 에서는 돌지 않는다 — 기기 시험(PdfTest)으로 본다.
 *
 * **29EBWO 와 30EBSE 가 같은 파일을 쓴다** (패키지 이름만 다르다).
 */
object Pdf {

    fun text(file: File): String {
        val raw = try {
            // 큰 PDF 도 메모리에 다 올리지 않는다 — 기기 메모리가 1.8GB 뿐이다.
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                PDFTextStripper().apply {
                    sortByPosition = true
                    setAddMoreFormatting(true)
                    paragraphStart = PARA_MARK
                    lineSeparator = "\n"
                }.getText(doc)
            }
        } catch (e: Exception) {
            throw Convert.Unsupported("PDF 에서 글을 읽지 못했습니다.")
        }
        return raw.replace("\r", "").split(PARA_MARK)
            .joinToString("\n\n") { Convert.joinWrapped(it.split('\n')) }
    }

    /** 문단이 바뀌는 자리에 잠깐 세워 두는 표. 글에 나올 리 없는 글자다. */
    private const val PARA_MARK = ""
}
