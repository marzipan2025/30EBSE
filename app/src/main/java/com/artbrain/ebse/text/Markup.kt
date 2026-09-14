package com.artbrain.ebse.text

/**
 * XHTML·XML 을 태그와 글 토막으로 흘려 읽는다.
 *
 * 안드로이드의 XmlPullParser 를 쓰지 않는다 — epub 속 XHTML 에는 `&nbsp;` 같은
 * HTML 이름 문자가 선언 없이 들어 있는 일이 흔해 엄격한 XML 파서는 거기서
 * 멈춘다. 우리가 알고 싶은 것은 태그 이름·속성·글뿐이라 너그럽게 훑는다.
 * 순수 코틀린이라 JVM 에서 그대로 시험할 수 있다.
 */
object Markup {

    sealed interface Token
    /** 여는 태그. [selfClosing] 이면 닫는 태그가 따로 오지 않는다. */
    data class Open(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Token
    data class Close(val name: String) : Token
    data class Text(val text: String) : Token

    fun tokens(src: String): Sequence<Token> = sequence {
        var i = 0
        val n = src.length
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) { yield(Text(decode(src.substring(i)))); break }
            if (lt > i) yield(Text(decode(src.substring(i, lt))))
            when {
                src.startsWith("<!--", lt) -> {
                    val e = src.indexOf("-->", lt + 4)
                    i = if (e < 0) n else e + 3
                }
                src.startsWith("<![CDATA[", lt) -> {
                    val e = src.indexOf("]]>", lt + 9)
                    yield(Text(src.substring(lt + 9, if (e < 0) n else e)))
                    i = if (e < 0) n else e + 3
                }
                src.startsWith("<!", lt) || src.startsWith("<?", lt) -> {
                    val e = src.indexOf('>', lt)
                    i = if (e < 0) n else e + 1
                }
                else -> {
                    val e = tagEnd(src, lt)
                    val body = src.substring(lt + 1, e).trim()
                    i = e + 1
                    if (body.startsWith("/")) {
                        yield(Close(local(body.substring(1).trim())))
                    } else if (body.isNotEmpty()) {
                        val self = body.endsWith("/")
                        val inner = if (self) body.dropLast(1) else body
                        val nameEnd = inner.indexOfFirst { it.isWhitespace() }.let { if (it < 0) inner.length else it }
                        yield(Open(local(inner.substring(0, nameEnd)), attrs(inner.substring(nameEnd)), self))
                    }
                }
            }
        }
    }

    /** 따옴표 안의 `>` 는 태그 끝이 아니다. */
    private fun tagEnd(s: String, from: Int): Int {
        var q = 0.toChar()
        for (k in from + 1 until s.length) {
            val c = s[k]
            if (q != 0.toChar()) { if (c == q) q = 0.toChar() }
            else if (c == '"' || c == '\'') q = c
            else if (c == '>') return k
        }
        return s.length - 1
    }

    /** `xhtml:p` 처럼 이름공간이 붙어 와도 `p` 로 본다. */
    private fun local(name: String) = name.substringAfter(':').lowercase()

    private val ATTR = Regex("""([^\s=/]+)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

    /** 속성 이름은 소문자로, `epub:type` 같은 이름공간은 그대로 둔다. */
    private fun attrs(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            out[m.groupValues[1].lowercase()] = decode(v)
        }
        return out
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "shy" to "", "zwnj" to "", "zwj" to "",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "deg" to "°", "sup2" to "²", "sup3" to "³", "frac12" to "½",
    )

    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY.replace(s) { m ->
            val k = m.groupValues[1]
            when {
                k.startsWith("#x") || k.startsWith("#X") ->
                    k.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                k.startsWith("#") ->
                    k.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[k] ?: m.value
            }
        }
    }
}
