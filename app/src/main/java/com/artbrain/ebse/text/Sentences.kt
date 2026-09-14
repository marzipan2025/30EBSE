package com.artbrain.ebse.text

/**
 * 글을 문장으로 가른다.
 *
 * 안드로이드에 기대지 않는 순수 코틀린이다 — JVM 에서 그대로 시험할 수 있다.
 *
 * 문장 끝으로 보는 것: `.` `!` `?` `…` `。`
 * 끝 부호 뒤에 닫는 따옴표나 괄호가 붙어 있으면 **그것까지 문장에 넣는다**.
 * `"가자."` 는 따옴표를 떼지 않고 한 문장이다.
 */
object Sentences {

    /** 연.월.일을 맺는 마지막 점 — 문장 끝이 아니다. */
    private val DATE_TAIL =
        Regex("""\d{1,4}\s*\.\s*\d{1,2}\s*\.\s*\d{1,2}\s*\.$""")

    /** 문장을 끝내는 부호 */
    private const val ENDERS = ".!?…。！？"

    /** 끝 부호 뒤에 따라붙어도 되는 닫는 부호들 */
    private const val CLOSERS = "\"'”’»›)]}〉》」』】"

    /**
     * 마침표 뒤라도 자르지 않는 줄임말.
     *
     * 소문자로 맞춰 견준다. 마침표 앞의 낱말이 이 안에 있으면 문장이 끝난 것이
     * 아니다.
     */
    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc",
        "e.g", "i.e", "a.m", "p.m", "no", "vol", "fig", "ch", "ed",
    )

    fun split(raw: String): List<String> {
        val text = normalize(raw)
        val out = ArrayList<String>()

        // 빈 줄로 갈린 토막(문단)마다 따로 가른다. 문단 끝은 끝 부호가 없어도
        // 문장 끝이다 — 제목이나 목록 줄이 다음 문단에 들러붙지 않게.
        for (para in text.split(Regex("\n\\s*\n"))) {
            val block = para.trim()
            if (block.isEmpty()) continue
            for (unit in mendWraps(block)) out += splitLine(unit)
        }
        return out
    }

    /**
     * 문단 안에서 종이 폭에 끊긴 줄을 도로 잇는다.
     *
     * 구글 문서로 옮겨 온 글에는 원본의 하드 줄바꿈이 그대로 들어 있는 일이
     * 많다. 실측한 한 문서는 빈 줄 아닌 줄 1521개 가운데 **끝 부호로 끝나는
     * 것이 23%뿐**이었다 — 나머지는 문장 중간에서 끊긴 자리다. 그걸 문장
     * 경계로 세면 서른 몇 자짜리 토막이 한 쪽씩 차지한다.
     *
     * 그래서 끝 부호 없이 끝난 줄은 다음 줄과 잇는다. 끊긴 자리에는 빈칸을
     * 하나 둔다 — 같은 문서에서 그런 줄의 99%가 이미 빈칸으로 끝나 있었으니
     * (낱말 사이에서 끊긴 것이다) 빈칸이 맞는 자리다.
     *
     * 다만 **짧은 줄은 잇지 않는다.** 끊긴 줄은 폭에 닿아 있으므로 문단에서
     * 가장 긴 줄과 길이가 비슷하다. 그보다 훨씬 짧으면 제목이나 목록처럼
     * 일부러 짧게 둔 줄이므로, 이으면 다음 문단에 들러붙는다.
     */
    private fun mendWraps(block: String): List<String> {
        val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return lines

        val longest = lines.maxOf { it.length }
        val out = ArrayList<String>()
        val buf = StringBuilder()

        for (l in lines) {
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l)
            val ended = l.last() in ENDERS || l.last() in CLOSERS
            val short = l.length < longest * 0.6
            if (ended || short) { out += buf.toString(); buf.clear() }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun splitLine(line: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c !in ENDERS) { i++; continue }

            // 같은 끝 부호가 잇달아 오면(`...`, `!!`, `?!`) 한 덩이로 본다.
            var end = i
            while (end + 1 < line.length && line[end + 1] in ENDERS) end++

            if (!isBoundary(line, i, end)) { i = end + 1; continue }

            // 닫는 따옴표·괄호까지 문장에 넣는다.
            var stop = end
            while (stop + 1 < line.length && line[stop + 1] in CLOSERS) stop++

            val piece = line.substring(start, stop + 1).trim()
            if (piece.isNotEmpty()) out += piece
            start = stop + 1
            // 다음 문장 앞의 공백은 버린다.
            while (start < line.length && line[start].isWhitespace()) start++
            i = start
        }
        val tail = line.substring(start.coerceAtMost(line.length)).trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    /** [from]..[to] 의 끝 부호가 정말 문장 끝인가 */
    private fun isBoundary(s: String, from: Int, to: Int): Boolean {
        // 물음표·느낌표·줄임표는 군말 없이 문장 끝이다.
        if (s[from] != '.' || to != from) return true

        val prev = s.getOrNull(from - 1)
        val next = s.getOrNull(to + 1)

        // 3.14 — 숫자 사이의 점
        if (prev != null && prev.isDigit() && next != null && next.isDigit()) return false

        if (prev != null && prev.isDigit()) {
            // "2026. 9. 12." 처럼 숫자 + 점 + 빈칸 + 숫자
            var k = to + 1
            while (k < s.length && s[k] == ' ') k++
            if (k < s.length && s[k].isDigit()) return false

            // 이 점이 날짜(연.월.일)를 맺는 자리면 문장 끝이 아니다.
            // "2026. 9. 12. 에 만났다" 의 마지막 점이 여기 걸린다.
            if (DATE_TAIL.containsMatchIn(s.substring(0, to + 1))) return false

            // "1. 첫째" 처럼 줄머리의 번호 매김. 앞이 빈칸뿐이면 목록 표지다.
            val digits = s.substring(0, from).takeLastWhile { it.isDigit() }
            val head = s.substring(0, from - digits.length)
            if (head.isBlank() || head.endsWith("\n")) return false
        }

        // 줄임말 뒤
        if (prev != null && (prev.isLetter() || prev == '.')) {
            val head = s.substring(0, from)
            val word = head.takeLastWhile { it.isLetter() || it == '.' }
                .trimStart('.')
                .lowercase()
            if (word in ABBREV) return false
            // 이름 머리글자 한 자 (`J. R. R.`)
            if (word.length == 1 && head.isNotEmpty() && head.last().isUpperCase()) return false
        }

        // 끝 부호 뒤에 아무것도 없으면 문장 끝이다.
        if (next == null) return true
        // 닫는 부호나 빈칸이 와야 문장 끝이다. `ebwo.kt` 같은 건 자르지 않는다.
        return next.isWhitespace() || next in CLOSERS
    }

    private fun normalize(s: String): String = s
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(' ', ' ')      // 줄바꿈 없는 빈칸
        .replace('​', ' ')      // 폭 없는 빈칸
        .replace("﻿", "")       // BOM
        .replace(Regex("[ \t]+"), " ")
}
