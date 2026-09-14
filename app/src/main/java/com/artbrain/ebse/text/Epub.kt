package com.artbrain.ebse.text

import java.net.URLDecoder

/**
 * epub 에서 **읽을 차례대로** 글과 사진을 뽑는다.
 *
 * 순수 코틀린이다. 압축을 푸는 일은 [read] 에 맡긴다 — 기기에서는 ZipFile,
 * 시험에서는 무엇이든 된다.
 *
 * 뽑는 규칙:
 *
 * - **차례(spine)를 따른다.** `linear="no"` 는 건너뛰되 표지는 남긴다.
 * - **차례 쪽은 뺀다.** 모든 epub 에 공통된 표시가 없어서 두 겹으로 가린다.
 *   EPUB 3 의 `nav`, EPUB 2 의 `<guide type="toc">` 가 있으면 그것을 믿고,
 *   없으면 **줄의 7할 이상이 목차(toc.ncx·nav) 제목과 똑같은 쪽**을 차례로
 *   본다. 부분 일치로 재면 짧은 본문 장까지 걸려서 똑같을 때만 센다.
 * - **사진은 한 쪽이다.** 사진 설명은 뒤따르는 글로 남아 제 문장이 된다.
 *   책 안에서 두 번 넘게 쓰인 그림은 장식(소제목 무늬 따위)으로 보고 뺀다.
 * - **각주는 번호도 본문도 뺀다.** 번호를 빼면 본문이 어디 붙는 말인지
 *   알 수 없어서다.
 * - 문단 안의 `<br>` 은 빈칸이다. 두 줄로 조판한 제목이 한 쪽에 모인다.
 */
class Epub(private val read: (path: String) -> ByteArray?) {

    sealed interface Block
    data class Para(val text: String) : Block
    /** [path] 는 zip 안의 경로 */
    data class Image(val path: String) : Block

    private class Item(val href: String, val type: String, val props: String)

    fun blocks(): List<Block> {
        val opfPath = rootfile() ?: throw IllegalArgumentException("epub 목차 파일(OPF)을 찾지 못했습니다.")
        val base = dir(opfPath)
        val opf = text(opfPath) ?: throw IllegalArgumentException("OPF 를 읽지 못했습니다.")

        val items = HashMap<String, Item>()
        val spine = ArrayList<Pair<String, Boolean>>()  // idref, linear
        val guide = HashMap<String, String>()           // type → 경로
        var coverId: String? = null
        for (t in Markup.tokens(opf)) {
            if (t !is Markup.Open) continue
            when (t.name) {
                "item" -> {
                    val id = t.attrs["id"] ?: continue
                    val href = t.attrs["href"] ?: continue
                    items[id] = Item(resolve(base, href), t.attrs["media-type"].orEmpty(), t.attrs["properties"].orEmpty())
                }
                "itemref" -> t.attrs["idref"]?.let { spine += it to (t.attrs["linear"] != "no") }
                "reference" -> {
                    val type = t.attrs["type"]?.lowercase() ?: continue
                    val href = t.attrs["href"] ?: continue
                    guide[type] = resolve(base, href)
                }
                "meta" -> if (t.attrs["name"] == "cover") coverId = t.attrs["content"]
            }
        }

        val coverImage = items.values.firstOrNull { "cover-image" in it.props.split(' ') }?.href
            ?: coverId?.let { items[it]?.href }
        val navDocs = items.values.filter { "nav" in it.props.split(' ') }.map { it.href }.toSet()
        val tocLabels = tocLabels(items.values)

        // 쪽마다 뽑아 두고, 그림이 몇 번 쓰였는지 센다.
        data class Doc(val path: String, val blocks: List<Block>)
        val docs = ArrayList<Doc>()
        for ((idref, linear) in spine) {
            val item = items[idref] ?: continue
            val path = item.href
            if (path in navDocs || path == guide["toc"]) continue
            val src = text(path) ?: continue
            val blocks = extract(src, dir(path))
            val isCover = path == guide["cover"] ||
                (coverImage != null && blocks.any { it is Image && it.path == coverImage })
            if (!linear && !isCover) continue
            if (!isCover && looksLikeToc(blocks, tocLabels)) continue
            docs += Doc(path, blocks)
        }

        val uses = HashMap<String, Int>()
        for (d in docs) for (b in d.blocks) if (b is Image) uses[b.path] = (uses[b.path] ?: 0) + 1

        val out = ArrayList<Block>()
        // 표지 그림이 차례 어디에도 없으면 맨 앞에 둔다.
        if (coverImage != null && uses[coverImage] == null && read(coverImage) != null) out += Image(coverImage)
        for (d in docs) for (b in d.blocks) {
            if (b is Image && (uses[b.path] ?: 0) > 1 && b.path != coverImage) continue
            out += b
        }
        return out
    }

    // ── 목차 ─────────────────────────────────────────────

    private fun rootfile(): String? {
        val c = text("META-INF/container.xml") ?: return null
        return Markup.tokens(c).filterIsInstance<Markup.Open>()
            .firstOrNull { it.name == "rootfile" }?.attrs?.get("full-path")
    }

    /** toc.ncx 와 nav 문서에 적힌 제목들 — 견주기 좋게 다듬어서 */
    private fun tocLabels(items: Collection<Item>): Set<String> {
        val out = HashSet<String>()
        for (it in items) {
            val isNcx = it.type == "application/x-dtbncx+xml"
            val isNav = "nav" in it.props.split(' ')
            if (!isNcx && !isNav) continue
            val src = text(it.href) ?: continue
            var inLabel = false
            val buf = StringBuilder()
            for (t in Markup.tokens(src)) when (t) {
                is Markup.Open -> if ((isNcx && t.name == "text") || (isNav && t.name == "a")) { inLabel = true; buf.clear() }
                is Markup.Close -> if (inLabel && ((isNcx && t.name == "text") || (isNav && t.name == "a"))) {
                    inLabel = false
                    norm(buf.toString()).takeIf { s -> s.isNotEmpty() }?.let { s -> out += s }
                }
                is Markup.Text -> if (inLabel) buf.append(t.text)
            }
        }
        return out
    }

    private fun looksLikeToc(blocks: List<Block>, labels: Set<String>): Boolean {
        if (labels.isEmpty()) return false
        val lines = blocks.filterIsInstance<Para>().map { norm(it.text) }.filter { it.isNotEmpty() }
        if (lines.size < TOC_MIN_LINES) return false
        return lines.count { it in labels } >= lines.size * TOC_RATIO
    }

    // ── 본문 ─────────────────────────────────────────────

    /** 한 XHTML 쪽을 문단과 그림으로 */
    private fun extract(src: String, base: String): List<Block> {
        val out = ArrayList<Block>()
        val buf = StringBuilder()
        val stack = ArrayList<String>()   // 열린 태그 이름
        var skipDepth = -1                // 이 깊이 아래는 버린다
        var inBody = src.indexOf("<body", ignoreCase = true) < 0
        // 위첨자는 닫힐 때 가린다 — 각주 번호면 버리고 `m²` 같은 것은 남긴다.
        var sup: StringBuilder? = null

        fun flush() {
            val t = buf.toString().replace(SPACES, " ").trim()
            if (t.isNotEmpty()) out += Para(t)
            buf.clear()
        }

        for (t in Markup.tokens(src)) {
            when (t) {
                is Markup.Open -> {
                    if (t.name == "body") { inBody = true; continue }
                    if (!inBody) continue
                    if (skipDepth >= 0) { if (!t.selfClosing && t.name !in VOID) stack += t.name; continue }
                    if (skips(t)) {
                        if (!t.selfClosing && t.name !in VOID) { stack += t.name; skipDepth = stack.size - 1 }
                        continue
                    }
                    when (t.name) {
                        "br" -> buf.append(' ')
                        "sup" -> if (!t.selfClosing) sup = StringBuilder()
                        "img", "image" -> {
                            val href = t.attrs["src"] ?: t.attrs["xlink:href"] ?: t.attrs["href"]
                            if (href != null && !href.startsWith("data:")) {
                                flush()
                                out += Image(resolve(base, href))
                            }
                        }
                        in BLOCKS -> flush()
                    }
                    if (!t.selfClosing && t.name !in VOID) stack += t.name
                }
                is Markup.Close -> {
                    if (!inBody) continue
                    if (t.name == "body") { flush(); inBody = false; continue }
                    val at = stack.lastIndexOf(t.name)
                    if (at < 0) continue
                    while (stack.size > at) stack.removeAt(stack.size - 1)
                    if (skipDepth >= 0) {
                        if (stack.size <= skipDepth) skipDepth = -1
                        continue
                    }
                    if (t.name == "sup") {
                        val s = sup?.toString().orEmpty()
                        sup = null
                        if (!NOTE_NUMBER.matches(s.trim())) buf.append(s)
                    }
                    if (t.name in BLOCKS) flush()
                }
                is Markup.Text -> if (inBody && skipDepth < 0) (sup ?: buf).append(t.text)
            }
        }
        flush()
        return out
    }

    /** 통째로 버릴 태그인가 — 각주 본문·각주 고리·루비 읽기·스크립트 */
    private fun skips(t: Markup.Open): Boolean {
        if (t.name in DROP) return true
        val type = (t.attrs["epub:type"].orEmpty() + " " + t.attrs["role"].orEmpty()).lowercase()
        if (NOTE_TYPES.any { it in type }) return true
        val cls = t.attrs["class"].orEmpty().lowercase()
        if ("foot" in cls || "endnote" in cls) return true
        return false
    }

    // ── 경로·글 ──────────────────────────────────────────

    private fun text(path: String): String? = read(path)?.let { bytes ->
        // UTF-8 이 원칙이지만 옛 한국 epub 은 euc-kr 로 밝혀 둔 것이 있다.
        Convert.decodeMarkup(bytes).removePrefix("﻿")
    }

    private fun dir(path: String) = path.substringBeforeLast('/', "")

    /** zip 안의 경로로 맞춘다. `../Images/a%20b.png#x` → `OEBPS/Images/a b.png` */
    private fun resolve(base: String, href: String): String {
        val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
            .getOrDefault(href.substringBefore('#'))
        val parts = ArrayList<String>()
        if (!clean.startsWith("/")) parts += base.split('/').filter { it.isNotEmpty() }
        for (p in clean.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    private fun norm(s: String) = s.replace(NOT_WORD, "").lowercase()

    companion object {
        /** 그림 쪽을 글 속에 적어 두는 표. 이 글자로 시작하는 쪽은 그림이다. */
        const val IMAGE_MARK = '￼'

        private const val TOC_MIN_LINES = 5
        private const val TOC_RATIO = 0.7

        private val SPACES = Regex("""[\s    ]+""")
        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

        private val BLOCKS = setOf(
            "p", "div", "section", "article", "header", "footer", "main",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre",
            "li", "ul", "ol", "dl", "dt", "dd", "table", "tr", "td", "th",
            "figure", "figcaption", "hr", "nav",
        )
        private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "col", "area", "source", "wbr")
        private val DROP = setOf("head", "script", "style", "rt", "rp", "noscript")

        /** 각주 번호로 보는 위첨자 — `3` `[12]` `각주7` `*` `†` `주3` */
        private val NOTE_NUMBER = Regex("""^[\[(]?(각주|주|註|note\s*)?\s*[0-9]{1,3}[\])]?$|^[*†‡]+$""", RegexOption.IGNORE_CASE)
        private val NOTE_TYPES = listOf("footnote", "endnote", "rearnote", "noteref", "doc-footnote", "doc-endnote", "doc-noteref")
    }
}
