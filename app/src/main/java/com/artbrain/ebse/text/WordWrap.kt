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
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return listOf(text)

        val lines = ArrayList<String>()
        // 글 안의 줄바꿈은 그대로 지킨다.
        for (para in text.split('\n')) {
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) { lines += ""; continue }

            var line = StringBuilder()
            for (w in words) {
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (measure(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                    continue
                }
                // 들어가지 않는다. 쌓아 둔 줄이 있으면 먼저 낸다.
                if (line.isNotEmpty()) {
                    lines += line.toString()
                    line = StringBuilder()
                }
                // 낱말 하나가 한 줄보다 길면 글자로 끊는다.
                if (measure(w) <= maxWidth) {
                    line = StringBuilder(w)
                } else {
                    var rest = w
                    while (rest.isNotEmpty() && measure(rest) > maxWidth) {
                        val cut = fitChars(rest, maxWidth, measure)
                        lines += rest.substring(0, cut)
                        rest = rest.substring(cut)
                    }
                    if (rest.isNotEmpty()) line = StringBuilder(rest)
                }
            }
            if (line.isNotEmpty()) lines += line.toString()
        }
        return lines
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
