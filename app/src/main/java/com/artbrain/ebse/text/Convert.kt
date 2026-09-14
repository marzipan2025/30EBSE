package com.artbrain.ebse.text

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * 파일 형식마다 글만 남긴다 — pdf([Pdf])·docx·txt·md·srt, 그리고 글의 인코딩.
 *
 * 안드로이드에 기대지 않는 순수 코틀린이다. JVM 에서 그대로 시험한다(ImportTest).
 * **29EBWO 와 30EBSE 가 같은 파일을 쓴다** — 한쪽을 고치면 다른 쪽에도 옮긴다
 * (패키지 이름만 다르다).
 */
object Convert {

    class Unsupported(msg: String) : Exception(msg)

    const val EPUB = "application/epub+zip"
    const val PDF = "application/pdf"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val TEXT = "text/plain"
    const val MD = "text/markdown"
    const val SRT = "application/x-subrip"

    /**
     * 무엇으로 풀지 정한다. 모르는 형식이면 null.
     *
     * **확장자를 종류보다 먼저 믿는다.** md·srt 는 드라이브든 선택창이든 대개
     * 종류 없이(application/octet-stream) 오거나 text/plain 으로 온다.
     */
    fun kindOf(mime: String, name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "epub" || mime == EPUB -> EPUB
            ext == "pdf" || mime == PDF -> PDF
            ext == "docx" || mime == DOCX -> DOCX
            ext == "md" || ext == "markdown" || mime == MD || mime == "text/x-markdown" -> MD
            ext == "srt" || mime == SRT || mime == "text/srt" || mime == "application/srt" -> SRT
            ext == "txt" || mime == TEXT -> TEXT
            else -> null
        }
    }

    /**
     * 목록에 보일 제목. **epub 만 확장자를 뗀다** — 드라이브에는 같은 이름의 구글 문서와
     * docx 가 나란히 있는 일이 흔해(구글 문서를 docx 로 내려받아 둔 것) 확장자가
     * 없으면 둘을 가를 수 없다.
     */
    fun title(name: String): String =
        if (name.endsWith(".epub", ignoreCase = true)) name.dropLast(5) else name

    /** 파일 하나를 [kind] 에 맞게 글로 푼다 (epub 은 사진이 있어 따로 푼다 — Fetch). */
    fun text(kind: String, file: File): String = tidy(when (kind) {
        PDF -> Pdf.text(file)
        DOCX -> docx(file)
        MD -> markdown(decode(file.readBytes()))
        SRT -> subtitles(decode(file.readBytes()))
        TEXT -> decode(file.readBytes())
        else -> throw Unsupported("지원하지 않는 형식입니다.")
    })

    // ── docx ─────────────────────────────────────────────

    /**
     * zip 속 word/document.xml 의 문단(w:p)마다 글 토막(w:t)을 모은다. docx 는 늘 UTF-8 이다.
     *
     * 문단은 **줄 하나**로 두고 빈 문단은 빈 줄로 둔다 — 구글 문서를 글로 받을 때와 같은
     * 꼴이다. 옮겨 적은 책처럼 종이 폭에서 줄마다 Enter 를 친 문서가 많은데(좀머 씨 이야기
     * docx 에서 실측), 그 줄은 리더의 문장 가르기가 도로 잇는다([Sentences]).
     */
    fun docx(file: File): String {
        val xml = try {
            ZipFile(file).use { zip ->
                val e = zip.getEntry("word/document.xml") ?: throw Unsupported("Word 문서를 열 수 없습니다.")
                zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
            }
        } catch (e: Unsupported) {
            throw e
        } catch (e: Exception) {
            throw Unsupported("Word 문서를 열 수 없습니다. 손상되었을 수 있습니다.")
        }
        val paras = ArrayList<String>()
        val sb = StringBuilder()
        var inT = false
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Open -> when (t.name) {
                "p" -> if (!t.selfClosing) sb.setLength(0)
                "t" -> inT = !t.selfClosing
                "br", "cr" -> sb.append('\n')
                // 문단 머리의 탭 설정(w:tabs 속 w:tab)은 글이 아니다 — 글이 나온 뒤의 탭만 띄운다.
                "tab" -> if (sb.isNotEmpty()) sb.append(' ')
            }
            is Markup.Close -> when (t.name) {
                "t" -> inT = false
                "p" -> paras += sb.toString().trim()
            }
            is Markup.Text -> if (inT) sb.append(t.text)
        }
        return paras.joinToString("\n")
    }

    // ── md ───────────────────────────────────────────────

    /** 마크다운 문법을 걷고 글만 남긴다. 제목·목록 줄은 제 문단이 된다. */
    // 정규식의 `]` `}` 는 글자로 쓸 때도 모두 `\` 로 막는다. JVM 은 너그럽게 받지만
    // 안드로이드(ICU)는 `{[^}]*}` 같은 것을 문법 오류로 던진다 — 기기에서 srt 가 실패했다.
    fun markdown(src: String): String {
        val out = ArrayList<String>()
        var fence = false
        for (line0 in src.replace("\r\n", "\n").split('\n')) {
            var l = line0
            if (l.trimStart().startsWith("```") || l.trimStart().startsWith("~~~")) { fence = !fence; continue }
            if (fence) { out += l; continue }
            if (Regex("""^\s*([-*_])(\s*\1){2,}\s*$""").matches(l)) { out += ""; continue }   // 가로줄
            if (Regex("""^\s*\|?\s*:?-{3,}""").containsMatchIn(l) && l.contains('|')) continue  // 표 머리 구분선
            var block = false
            Regex("""^\s{0,3}#{1,6}\s+""").find(l)?.let { l = l.substring(it.range.last + 1).trimEnd('#', ' '); block = true }
            Regex("""^\s*(>\s*)+""").find(l)?.let { l = l.substring(it.range.last + 1) }
            Regex("""^\s*([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?""").find(l)?.let { l = l.substring(it.range.last + 1); block = true }
            l = l.replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "")                 // 그림
                .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")               // 링크
                .replace(Regex("""\[([^\]]*)\]\[[^\]]*\]"""), "$1")
                .replace(Regex("""<[^>]+>"""), "")                              // html 태그
                .replace(Regex("""(\*\*|__|~~)(.+?)\1"""), "$2")
                .replace(Regex("""(?<![\w*])[*_](?!\s)(.+?)(?<!\s)[*_](?![\w*])"""), "$1")
                .replace(Regex("""`+([^`]*)`+"""), "$1")
                .replace(Regex("""\\([\\`*_{}\[\]()#+\-.!|>])"""), "$1")
            if (l.contains('|') && l.trim().startsWith('|')) l = l.trim().trim('|').split('|').joinToString("  ") { it.trim() }
            if (block) { out += ""; out += l.trim(); out += "" } else out += l
        }
        return out.joinToString("\n")
    }

    // ── srt ──────────────────────────────────────────────

    private val CUE_TIME = Regex("""\d+:\d{2}:\d{2}[,.]\d{3}\s*-->""")

    /**
     * 자막에서 번호와 시각을 걷고 대사만 남긴다.
     *
     * **자막 한 칸(시간 한 토막)이 한 문단이다.** 칸끼리는 잇지 않는다. 한 칸 안에
     * 문장이 여럿이면 리더의 문장 가르기([Sentences])가 그 안에서 나눈다. 한국어
     * 자막은 마침표를 거의 쓰지 않아, 칸을 이어 붙이고 끝 부호로 가르면 대사 여럿이
     * 한 문장으로 붙는다(넷플릭스 자막에서 실측).
     */
    fun subtitles(src: String): String {
        val cues = ArrayList<String>()
        for (block in src.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))) {
            val lines = block.trim().split('\n')
            val ti = lines.indexOfFirst { CUE_TIME.containsMatchIn(it) }
            if (ti < 0) continue
            // 한 칸 안의 줄바꿈은 화면 폭에서 끊은 것이라 잇는다.
            val text = lines.drop(ti + 1)
                .map { it.replace(Regex("""<[^>]+>|\{[^}]*\}"""), "").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (text.isNotEmpty()) cues += text
        }
        return cues.joinToString("\n\n")
    }

    // ── 줄 잇기 ──────────────────────────────────────────

    /**
     * 종이 폭에서 끊긴 줄을 잇는다 (PDF).
     *
     * 줄 끝의 띄어쓰기는 PDF 를 만든 프로그램에 따라 남기도 하고 사라지기도 한다
     * (크롬이 뽑은 PDF 는 지운다 — 2026-09 기기에서 실측). 그래서 남아 있기를
     * 기대하지 않고 **사이에 빈칸을 하나 둔다.** 영어와 한국어는 낱말 사이에서
     * 줄을 바꾸기 때문이다. 빈칸 없이 붙여야 하는 것은 둘뿐이다 — 낱말을 쪼갠
     * 붙임표(`inter-` / `national`)와, 띄어 쓰지 않는 중국어·일본어 글자끼리.
     */
    fun joinWrapped(lines: List<String>): String {
        val sb = StringBuilder()
        for (l0 in lines) {
            val l = l0.trim()
            if (l.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val a = sb.last()
                val b = l.first()
                val hyphen = a == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && b.isLetter()
                when {
                    // 소문자로 이어지면 낱말을 쪼갠 붙임표라 떼고, 대문자면 원래 있던 것이라 둔다.
                    hyphen -> if (b.isLowerCase()) sb.setLength(sb.length - 1)
                    cjk(a) && cjk(b) -> Unit
                    else -> sb.append(' ')
                }
            }
            sb.append(l)
        }
        return sb.toString()
    }

    /** 띄어 쓰지 않는 글자 — 한자·가나·전각 부호. 한글은 들지 않는다. */
    private fun cjk(c: Char) = c in '぀'..'ヿ' || c in '㐀'..'鿿' ||
        c in '　'..'〿' || c in '＀'..'￯'

    // ── 인코딩 ───────────────────────────────────────────

    /**
     * 한글 글은 UTF-8 이 아닌 일이 흔해 차례로 시도한다 (22SUTO-A 의 decodeBestEffort).
     *
     * 1) 파일이 스스로 밝힌 표(BOM) 2) 흠 없는 UTF-8 3) 표 없는 UTF-16
     * 4) 글자 쓰임새로 알아맞히기 5) 한국어 옛 인코딩(MS949·EUC-KR).
     * 문법만 보면 안 된다 — Shift_JIS 바이트가 MS949 문법도 통과해 '륺궼귏' 같은
     * 한글로 조용히 읽힌다. 풀린 글이 사람 글로 보이는지까지 본다([readable]).
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        strict(bytes, Charsets.UTF_8)?.let { if (readable(it)) return it }
        utf16NoBom(bytes)?.let { cs ->
            runCatching { String(bytes, cs) }.getOrNull()?.let { if (readable(it)) return it }
        }
        runCatching {
            val d = UniversalDetector(null)
            d.handleData(bytes, 0, bytes.size)
            d.dataEnd()
            d.detectedCharset?.let { Charset.forName(it) }?.let { String(bytes, it) }
        }.getOrNull()?.let { if (readable(it)) return it }
        for (name in listOf("MS949", "EUC-KR")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            strict(bytes, cs)?.let { if (readable(it)) return it }
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * XHTML·XML 을 푼다 (epub 속 글).
     *
     * epub 은 UTF-8 이 원칙이지만, 옛 한국 epub 에는 `<?xml encoding="euc-kr"?>` 나
     * `<meta charset="euc-kr">` 로 밝힌 것이 있다. 밝힌 것이 있고 흠 없이 풀리면
     * 그것을 믿고, 아니면(밝힌 것이 틀린 파일도 있다) [decode] 로 알아맞힌다.
     */
    fun decodeMarkup(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val declared = DECLARED.find(head)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (declared != null) strict(bytes, declared)?.let { if (readable(it)) return it }
        return decode(bytes)
    }

    private val DECLARED = Regex("""(?i)(?:encoding|charset)\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""")

    private fun bom(b: ByteArray): Pair<Charset, Int>? {
        fun at(i: Int, v: Int) = b.size > i && b[i] == v.toByte()
        return when {
            at(0, 0xEF) && at(1, 0xBB) && at(2, 0xBF) -> Charsets.UTF_8 to 3
            at(0, 0xFF) && at(1, 0xFE) && at(2, 0x00) && at(3, 0x00) -> Charset.forName("UTF-32LE") to 4
            at(0, 0x00) && at(1, 0x00) && at(2, 0xFE) && at(3, 0xFF) -> Charset.forName("UTF-32BE") to 4
            at(0, 0xFF) && at(1, 0xFE) -> Charsets.UTF_16LE to 2
            at(0, 0xFE) && at(1, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
    }

    /** 표 없는 UTF-16 — 한 바이트 인코딩에는 NUL 이 없으므로, NUL 이 몰린 자리로 바이트 차례를 안다. */
    private fun utf16NoBom(bytes: ByteArray): Charset? {
        val n = minOf(bytes.size, 4096)
        if (n < 4) return null
        var even = 0; var odd = 0
        for (i in 0 until n) if (bytes[i] == 0.toByte()) { if (i % 2 == 0) even++ else odd++ }
        if ((even + odd) * 10 < n) return null
        return when {
            odd > even * 4 -> Charsets.UTF_16LE
            even > odd * 4 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** 한 바이트라도 어긋나면 null. "그럭저럭 풀렸다" 를 성공으로 치지 않는다. */
    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { null }

    /** 풀린 글이 사람 글로 보이나 — 아무 바이트나 받아 주는 인코딩을 걸러 낸다. */
    private fun readable(text: String): Boolean {
        if (text.isEmpty()) return false
        var odd = 0
        for (ch in text) {
            if (ch == '�' || ch == ' ') return false
            if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') odd++
            else if (ch in ''..'') odd++
        }
        return odd * 20 <= text.length
    }

    fun tidy(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
