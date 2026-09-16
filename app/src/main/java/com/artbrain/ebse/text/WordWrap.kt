package com.artbrain.ebse.text

/**
 * 띄어쓰기에서만 줄을 바꾼다.
 *
 * 안드로이드에 맡기면 한글을 음절 단위로 아무 데서나 끊는다. 어절 단위로
 * 끊게 하는 `LineBreakConfig` 는 API 33 부터라 이 기기(API 30)에서는 쓸 수
 * 없다. 그래서 우리가 직접 나눈다 — 쪽을 나누는 일도 이미 우리가 하고 있어,
 * 여기서 나눈 줄을 그대로 세면 몇 줄인지도 정확해진다.
 *
 * 재는 일은 [measure] 에 맡긴다. 안드로이드에 기대지 않으므로 JVM 에서
 * 그대로 시험할 수 있다.
 */
object WordWrap {

    /**
     * [maxWidth] 안에 들어가도록 줄을 나눈다.
     *
     * 낱말 하나가 한 줄보다 길면 그 낱말만 글자 단위로 끊는다 — 그러지
     * 않으면 줄이 넘쳐 잘려 보인다.
     */
    /**
     * 글 앞에서 [maxWidth] 안에 드는 **글자 수**를 한 번에 내주는 자.
     *
     * 안드로이드의 `Paint.breakText` 가 이 일을 한 번에 한다. 그것이 있으면 한 줄을
     * 재는 값이 그 줄 길이만큼으로 끝난다 — 없으면 낱말을 하나씩 붙여 가며 재는데,
     * 그러면 한 줄을 만드는 데 그 줄을 몇 번씩 다시 재게 된다.
     */
    fun interface Breaker {
        fun count(text: String, maxWidth: Float): Int
    }

    fun wrap(
        text: String,
        maxWidth: Float,
        breaker: Breaker? = null,
        measure: (String) -> Float,
    ): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return listOf(text)
        val lines = ArrayList<String>()
        walk(text, maxWidth, Int.MAX_VALUE, breaker, measure, lines)
        return lines
    }

    /**
     * [maxLines] 줄 안에 들어가는가.
     *
     * [wrap] 을 불러 줄 수를 세도 답은 같지만, 그러면 **들어가지 않는 글도 끝까지
     * 재게 된다.** 쪽을 나눌 때는 "여기까지 들어가나" 를 수없이 묻고 그 대부분이
     * 아니다 — 넘치는 순간 그만두면 한 번 묻는 값이 글 길이와 상관없어진다.
     */
    fun fits(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        breaker: Breaker? = null,
        measure: (String) -> Float,
    ): Boolean {
        if (text.isEmpty() || maxWidth <= 0f) return true
        return walk(text, maxWidth, maxLines, breaker, measure, null) <= maxLines
    }

    /**
     * 줄을 나누며 센다. [out] 이 있으면 담고, 줄 수가 [limit] 을 넘어서면 곧바로
     * 멈춘다(그때의 값은 [limit] + 1 이다).
     */
    private fun walk(
        text: String,
        maxWidth: Float,
        limit: Int,
        breaker: Breaker?,
        measure: (String) -> Float,
        out: MutableList<String>?,
    ): Int {
        var count = 0
        // 줄 하나가 끝났다. 넘쳤으면 true — 부르는 쪽이 멈춘다.
        fun put(line: String): Boolean {
            out?.add(line)
            count++
            return count > limit
        }

        // 글 안의 줄바꿈은 그대로 지킨다.
        for (para in text.split('\n')) {
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) { if (put("")) return count; continue }

            if (breaker != null) {
                if (walkFast(para, maxWidth, breaker, ::put)) return count
                continue
            }

            var line = StringBuilder()
            for (w in words) {
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (measure(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                    continue
                }
                // 들어가지 않는다. 쌓아 둔 줄이 있으면 먼저 낸다.
                if (line.isNotEmpty()) {
                    if (put(line.toString())) return count
                    line = StringBuilder()
                }
                // 낱말 하나가 한 줄보다 길면 글자로 끊는다.
                if (measure(w) <= maxWidth) {
                    line = StringBuilder(w)
                } else {
                    var rest = w
                    while (rest.isNotEmpty() && measure(rest) > maxWidth) {
                        val cut = fitChars(rest, maxWidth, measure)
                        if (put(rest.substring(0, cut))) return count
                        rest = rest.substring(cut)
                    }
                    if (rest.isNotEmpty()) line = StringBuilder(rest)
                }
            }
            if (line.isNotEmpty() && put(line.toString())) return count
        }
        return count
    }

    /**
     * [Breaker] 가 있을 때의 줄 나누기. 들어가는 만큼을 한 번에 받아 그 안의
     * 마지막 빈칸에서 끊는다 — 낱말을 하나씩 붙여 가며 재는 것과 같은 줄이 나온다.
     * 낱말 하나가 한 줄보다 길면 그 낱말만 글자 단위로 끊는다.
     */
    private fun walkFast(
        para: String,
        maxWidth: Float,
        breaker: Breaker,
        put: (String) -> Boolean,
    ): Boolean {
        var pos = 0
        while (pos < para.length) {
            while (pos < para.length && para[pos] == ' ') pos++
            if (pos >= para.length) break
            val rest = para.substring(pos)
            val n = breaker.count(rest, maxWidth)
            if (n >= rest.length) return put(rest)
            val cut = rest.lastIndexOf(' ', n)
            if (cut <= 0) {
                // 낱말 하나가 한 줄보다 길다 — 들어가는 만큼 글자로 끊는다.
                val take = n.coerceAtLeast(1)
                if (put(rest.substring(0, take))) return true
                pos += take
            } else {
                if (put(rest.substring(0, cut))) return true
                pos += cut
            }
        }
        return false
    }

    /** [s] 의 앞에서 [maxWidth] 안에 드는 글자 수. 적어도 한 자는 낸다. */
    private fun fitChars(s: String, maxWidth: Float, measure: (String) -> Float): Int {
        var lo = 1
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(s.substring(0, mid)) <= maxWidth) lo = mid else hi = mid - 1
        }
        return lo
    }
}
