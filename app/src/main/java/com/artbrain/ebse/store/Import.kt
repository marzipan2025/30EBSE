package com.artbrain.ebse.store

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.artbrain.ebse.text.Markup
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * 파일 선택창(SAF)에서 고른 것을 기기에 앉힌다.
 *
 * **원본을 붙들지 않는다.** 고른 순간 글로 풀어 앱 폴더에 두고 끝낸다. 그래서
 * 원본이 지워지거나 망이 끊겨도 읽을 수 있고, 권한을 계속 쥐고 있을 필요도 없다.
 * 같은 이름을 다시 고르면 같은 칸을 새 글로 바꾼다 — 읽던 자리는 그대로다.
 *
 * 구글 드라이브 앱이 깔려 있으면 선택창에 드라이브가 함께 뜬다. 구글 문서는
 * 기기에 바이트로 있는 파일이 아니라(가상 파일) 드라이브에 "이 꼴로 내 달라"
 * 청해야 하는데, 드라이브가 내주는 꼴은 **PDF 하나뿐**이다(22SUTO-A 에서
 * getStreamTypes 로 확인). 그래서 PDF 로 받아 글을 뽑는다.
 */
object Import {

    class ImportError(msg: String) : Exception(msg)

    /** 고른 파일 하나를 들여온다. 목록에 넣은 칸을 돌려준다. */
    suspend fun one(ctx: Context, store: DocStore, uri: Uri, onProgress: (Int) -> Unit): Doc =
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            val meta = meta(ctx, uri)
            val type = cr.getType(uri).orEmpty()
            val ext = meta.name.substringAfterLast('.', "").lowercase()
            val title = if (ext.isNotEmpty() && ext.length <= 5) meta.name.substringBeforeLast('.') else meta.name

            val index = store.loadIndex(all = true)
            val id = index.firstOrNull { it.name == title }?.id ?: newId()

            val work = File(ctx.cacheDir, "import").apply { mkdirs() }
            val file = File(work, "$id.bin")
            try {
                val kind: String
                if (meta.virtual || type.startsWith(GOOGLE_APPS)) {
                    // 구글 문서 — 드라이브가 내주는 꼴 가운데 PDF 를 받는다.
                    val offered = runCatching { cr.getStreamTypes(uri, "*/*")?.toList() }.getOrNull().orEmpty()
                    if (MIME_PDF !in offered) throw ImportError("이 구글 파일은 글로 가져올 수 없습니다.")
                    copy(cr.openTypedAssetFileDescriptor(uri, MIME_PDF, null)?.createInputStream(), file)
                    kind = MIME_PDF
                } else {
                    copy(cr.openInputStream(uri), file)
                    kind = kindOf(type, ext)
                }
                onProgress(PREPARE_SHARE)

                when (kind) {
                    Doc.EPUB -> Fetch.epub(ctx, store, id, file) { f ->
                        onProgress(PREPARE_SHARE + (f * (100 - PREPARE_SHARE)).toInt())
                    }
                    else -> {
                        val text = tidy(when (kind) {
                            MIME_PDF -> pdf(file)
                            MIME_DOCX -> docx(file)
                            MIME_MD -> markdown(decode(file.readBytes()))
                            MIME_SRT -> subtitles(decode(file.readBytes()))
                            else -> decode(file.readBytes())
                        })
                        if (text.isBlank()) throw ImportError("읽을 글을 찾지 못했습니다.")
                        store.imageDir(id).deleteRecursively()
                        store.writeBody(id, text)
                    }
                }
                onProgress(100)

                val doc = Doc(id, title, System.currentTimeMillis().toString(), true, kind)
                // 새로 들인 것이 맨 앞에 선다.
                store.saveIndex(listOf(doc) + index.filter { it.id != id })
                doc
            } finally {
                file.delete()
            }
        }

    // ── 파일의 겉 ─────────────────────────────────────────

    /** 알림에 쓸 파일 이름 */
    fun displayName(ctx: Context, uri: Uri): String = meta(ctx, uri).name

    private class Meta(val name: String, val virtual: Boolean)

    private fun meta(ctx: Context, uri: Uri): Meta {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "문서"
        var virtual = false
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getString(it) }?.takeIf { it.isNotBlank() }?.let { name = it }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { virtual = c.getInt(it) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0 }
            }
        }
        return Meta(name, virtual)
    }

    /** 무엇으로 풀지 — 선택창이 알려 준 종류보다 확장자를 먼저 믿는다(md·srt 는 대개 octet-stream 으로 온다). */
    private fun kindOf(type: String, ext: String): String = when {
        ext == "epub" || type == Doc.EPUB -> Doc.EPUB
        ext == "pdf" || type == MIME_PDF -> MIME_PDF
        ext == "docx" || type == MIME_DOCX -> MIME_DOCX
        ext == "md" || ext == "markdown" || type == "text/markdown" -> MIME_MD
        ext == "srt" || type == "application/x-subrip" -> MIME_SRT
        ext == "txt" || type.startsWith("text/") -> MIME_TEXT
        else -> throw ImportError("지원하지 않는 형식입니다.\npdf · docx · txt · epub · md · srt 를 열 수 있습니다.")
    }

    private fun copy(input: java.io.InputStream?, to: File) {
        (input ?: throw ImportError("파일을 열 수 없습니다.")).use { i ->
            to.outputStream().use { o -> i.copyTo(o) }
        }
    }

    private fun newId() = "f" + System.currentTimeMillis().toString(36) +
        (0 until 4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    // ── PDF ──────────────────────────────────────────────

    /**
     * PDF 에서 글을 뽑는다.
     *
     * PDF 의 줄바꿈은 종이 폭에서 끊긴 자리일 뿐이다. 문단이 바뀌는 자리에만
     * 표를 세워 두고, 문단 안의 줄은 도로 잇는다([joinWrapped]).
     */
    internal fun pdf(file: File): String {
        val raw = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                PDFTextStripper().apply {
                    sortByPosition = true
                    setAddMoreFormatting(true)
                    paragraphStart = PARA_MARK
                    lineSeparator = "\n"
                }.getText(doc)
            }
        } catch (e: Exception) {
            throw ImportError("PDF 에서 글을 읽지 못했습니다.")
        }
        return raw.replace("\r", "").split(PARA_MARK)
            .joinToString("\n\n") { joinWrapped(it.split('\n')) }
    }

    /**
     * 종이 폭에서 끊긴 줄을 잇는다.
     *
     * 줄 끝의 띄어쓰기는 PDF 를 만든 프로그램에 따라 남기도 하고 사라지기도 한다
     * (크롬이 뽑은 PDF 는 지운다 — 2026-09 기기에서 실측). 그래서 남아 있기를
     * 기대하지 않고 **사이에 빈칸을 하나 둔다.** 영어와 한국어는 낱말 사이에서
     * 줄을 바꾸기 때문이다. 빈칸 없이 붙여야 하는 것은 둘뿐이다 — 낱말을 쪼갠
     * 붙임표(`inter-` / `national`)와, 띄어 쓰지 않는 중국어·일본어 글자끼리.
     */
    internal fun joinWrapped(lines: List<String>): String {
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
    private fun cjk(c: Char) = c in '\u3040'..'\u30FF' || c in '\u3400'..'\u9FFF' ||
        c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

    // ── docx ─────────────────────────────────────────────

    /** zip 속 word/document.xml 의 문단(w:p)마다 글 토막(w:t)을 모은다. */
    internal fun docx(file: File): String {
        val xml = try {
            ZipFile(file).use { zip ->
                val e = zip.getEntry("word/document.xml") ?: throw ImportError("Word 문서를 열 수 없습니다.")
                zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
            }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError("Word 문서를 열 수 없습니다. 손상되었을 수 있습니다.")
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
                "p" -> sb.toString().trim().takeIf { it.isNotEmpty() }?.let { paras += it }
            }
            is Markup.Text -> if (inT) sb.append(t.text)
        }
        return paras.joinToString("\n\n")
    }

    // ── md ───────────────────────────────────────────────

    /** 마크다운 문법을 걷고 글만 남긴다. 제목·목록 줄은 제 문단이 된다. */
    internal fun markdown(src: String): String {
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
            Regex("""^\s*([-*+]|\d+[.)])\s+(\[[ xX]]\s+)?""").find(l)?.let { l = l.substring(it.range.last + 1); block = true }
            l = l.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")                 // 그림
                .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")               // 링크
                .replace(Regex("""\[([^\]]*)]\[[^\]]*]"""), "$1")
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

    private val CUE_TIME = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{3})""")

    /**
     * 자막에서 번호와 시각을 걷고 대사만 남긴다.
     *
     * 한 문장이 자막 여러 칸에 걸쳐 나오는 일이 흔해 칸마다 끊으면 토막이 된다.
     * 그래서 칸을 이어 붙이되, **끝 부호로 끝났거나 말 사이가 뜬 곳**(다음 칸까지
     * [CUE_GAP_MS] 넘게 쉼)에서 문단을 가른다. 끝 부호를 잘 안 쓰는 한국어
     * 자막도 쉼에서 갈린다.
     */
    internal fun subtitles(src: String): String {
        val paras = ArrayList<String>()
        val buf = StringBuilder()
        var lastEnd = -1L
        for (block in src.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))) {
            val lines = block.trim().split('\n')
            val ti = lines.indexOfFirst { CUE_TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = CUE_TIME.find(lines[ti])!!.groupValues
            fun ms(o: Int) = m[o].toLong() * 3_600_000 + m[o + 1].toLong() * 60_000 + m[o + 2].toLong() * 1000 + m[o + 3].toLong()
            val start = ms(1)
            val text = lines.drop(ti + 1)
                .map { it.replace(Regex("""<[^>]+>|\{[^}]*}"""), "").trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (text.isEmpty()) continue
            if (buf.isNotEmpty() && lastEnd >= 0 && start - lastEnd > CUE_GAP_MS) {
                paras += buf.toString(); buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(text)
            lastEnd = ms(5)
            if (text.last() in ".!?…。！？\"'”’)") { paras += buf.toString(); buf.clear() }
        }
        if (buf.isNotEmpty()) paras += buf.toString()
        return paras.joinToString("\n\n")
    }

    // ── 인코딩 ───────────────────────────────────────────

    /** 한글 글은 UTF-8 이 아닌 일이 흔해 차례로 시도한다 (22SUTO-A 의 decodeBestEffort). */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // 1) 파일이 스스로 밝힌 표(BOM)
        bom(bytes)?.let { (cs, skip) -> return String(bytes, skip, bytes.size - skip, cs) }
        // 2) UTF-8 로 흠 없이 풀리면 UTF-8
        strict(bytes, Charsets.UTF_8)?.let { if (readable(it)) return it }
        // 3) 표 없는 UTF-16 — NUL 이 몰린 자리로 바이트 차례를 안다
        utf16NoBom(bytes)?.let { cs ->
            runCatching { String(bytes, cs) }.getOrNull()?.let { if (readable(it)) return it }
        }
        // 4) 글자 쓰임새로 알아맞힌다 — 문법만 보면 Shift_JIS 가 MS949 로 조용히 읽힌다
        runCatching {
            val d = UniversalDetector(null)
            d.handleData(bytes, 0, bytes.size)
            d.dataEnd()
            d.detectedCharset?.let { Charset.forName(it) }?.let { String(bytes, it) }
        }.getOrNull()?.let { if (readable(it)) return it }
        // 5) 한국어 옛 인코딩
        for (name in listOf("MS949", "EUC-KR")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            strict(bytes, cs)?.let { if (readable(it)) return it }
        }
        return String(bytes, Charsets.UTF_8)
    }

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

    internal fun tidy(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private const val GOOGLE_APPS = "application/vnd.google-apps"
    const val MIME_PDF = "application/pdf"
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_TEXT = "text/plain"
    const val MIME_MD = "text/markdown"
    const val MIME_SRT = "application/x-subrip"

    /** PDF 에서 문단이 바뀌는 자리에 잠깐 세워 두는 표. 글에 나올 리 없는 글자다. */
    private const val PARA_MARK = ""

    /** 자막 칸 사이가 이보다 뜨면 말이 끊긴 것으로 본다 */
    private const val CUE_GAP_MS = 1500L

    /** 파일을 받아 오기까지가 차지하는 퍼센트 — 구글 문서는 망을 타므로 여기가 길다 */
    private const val PREPARE_SHARE = 60
}
