package com.artbrain.ebse.text

/**
 * 문장을 페이지로 앉힌다.
 *
 * **문장 하나가 페이지 하나다.** "가자." 도 "헉" 도 한 페이지를 다 쓴다.
 * 다만 화면에 들어가지 않는 긴 문장은 쪼갠다.
 *
 * 들어가는지 여부는 [fits] 가 판단한다 — 글자 수가 아니라 실제로 그려 본
 * 줄 수로 재야 정확해서, 재는 일은 안드로이드 쪽에 맡기고 여기서는 어디서
 * 자를지만 고른다. 덕분에 이 갈래는 JVM 에서 그대로 시험할 수 있다.
 *
 * **꼬리가 짧으면 앞 쪽에 붙인다.** 쪼갠 마지막 조각이 [SHORT_TAIL] 자보다
 * 짧으면(`있었다.` 따위) 한 쪽을 혼자 쓰기엔 허전하다. 앞 조각과 합쳐
 * [fitsTail] 에 들어가면(한 줄 더, 다섯 줄까지) 그 쪽만 예외로 길게 둔다.
 * 들어가지 않으면 나눈 채로 둔다. 글자 수는 띄어쓰기와 부호까지 센다.
 */
object PageBuilder {

    /** 끊기 좋은 자리. 숫자가 작을수록 좋은 자리다. */
    private fun tierAfter(s: String, i: Int): Int {
        val c = s[i]
        return when {
            // 쉼표·세미콜론·콜론·줄표 뒤 — 뜻이 끊기는 자리
            c in ",;:，、" -> 1
            c in "—–" -> 1
            // 닫는 따옴표·괄호 뒤
            c in "\"'”’»›)]}〉》」』】" -> 1
            // 그 밖에는 빈칸 앞에서만 끊는다
            c == ' ' -> 2
            else -> 0     // 0 = 끊을 자리가 아님
        }
    }

    /** 쪼갠 마지막 조각이 이 글자 수보다 짧으면 앞 조각에 붙여 본다 */
    const val SHORT_TAIL = 7

    fun build(
        sentences: List<String>,
        fits: (String) -> Boolean,
        fitsTail: ((String) -> Boolean)? = null,
    ): List<String> {
        val out = ArrayList<String>(sentences.size)
        for (s in sentences) out += chop(s, fits, fitsTail)
        return out
    }

    /** 한 문장을 들어가는 크기로 쪼갠다. 들어가면 그대로 하나다. */
    fun chop(
        sentence: String,
        fits: (String) -> Boolean,
        fitsTail: ((String) -> Boolean)? = null,
    ): List<String> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        if (fits(s)) return listOf(s)

        // 조각과 그 조각이 원문에서 시작하는 자리
        val pieces = ArrayList<Pair<Int, String>>()
        var start = 0
        while (start < s.length) {
            val cut = findCut(s, start, fits)
            val piece = s.substring(start, cut).trim()
            if (piece.isNotEmpty()) pieces += start to piece
            start = cut
            while (start < s.length && s[start] == ' ') start++
        }

        if (fitsTail != null && pieces.size >= 2 && pieces.last().second.length < SHORT_TAIL) {
            // 앞 조각의 시작부터 원문 끝까지 — 잘렸던 자리의 띄어쓰기도 그대로 살린다.
            val merged = s.substring(pieces[pieces.size - 2].first).trim()
            if (fitsTail(merged)) {
                pieces.removeAt(pieces.size - 1)
                pieces[pieces.size - 1] = pieces.last().first to merged
            }
        }
        return pieces.map { it.second }
    }

    /**
     * [start] 에서 시작해 들어가는 만큼 가장 멀리 끊는 자리를 고른다.
     *
     * 들어가는 자리 가운데 가장 먼 곳을 잡되, 좋은 자리(쉼표 따위)가 그
     * 6할 뒤에 있으면 그쪽을 쓴다. 빈칸에서 아슬아슬하게 끊는 것보다
     * 쉼표에서 끊는 편이 읽기 낫고, 6할이면 자리를 크게 버리지도 않는다.
     *
     * **재는 일은 절반씩 좁혀 찾는다.** [fits] 는 글자를 실제로 그려 재므로
     * 한 글자씩 늘려 가며 물으면 한 쪽을 나누는 데 글자 수만큼 재게 된다
     * (80만 자짜리 책에서 원문의 아홉 배를 쟀다). 길수록 안 들어가는 것은
     * 뻔하므로 들어가는 가장 긴 자리를 이분 탐색으로 한 번 찾고, 끊을 자리는
     * 재지 않고 글자만 훑어 고른다. 고르는 결과는 같다.
     */
    private fun findCut(s: String, start: Int, fits: (String) -> Boolean): Int {
        // 문장 끝까지 들어가면 거기서 끝낸다.
        if (fits(s.substring(start).trim())) return s.length

        // 들어가는 가장 긴 조각의 끝
        var lo = start
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(s.substring(start, mid).trim())) lo = mid else hi = mid - 1
        }
        val limit = lo

        // 그 안에서 가장 먼 끊을 자리와, 가장 먼 좋은 자리(tier 1)
        var best = -1
        var bestGood = -1
        for (i in limit - 1 downTo start) {
            val t = tierAfter(s, i)
            if (t == 0) continue
            if (best < 0) best = i + 1
            if (t == 1) { bestGood = i + 1; break }
            if (i + 1 < (best * 0.6).toInt()) break   // 더 뒤로 가도 6할에 못 미친다
        }

        if (bestGood >= 0 && bestGood >= (best * 0.6).toInt()) return bestGood
        if (best >= 0) return best

        // 끊을 자리가 아예 없다 — 낱말 하나가 화면보다 길다. 들어가는 만큼 자른다.
        return limit.coerceAtLeast(start + 1)
    }
}
