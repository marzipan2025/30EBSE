package com.artbrain.ebse.store

import android.content.Context
import com.artbrain.ebse.BuildConfig
import java.util.Locale

/**
 * 앱에 든 사용 안내 — 한글판과 영문판 둘이 목록 맨 뒤에 늘 있고 지울 수 없다.
 *
 * 본문은 `assets/guide/ko.txt`·`en.txt`. 기기 언어의 것이 먼저 선다(둘 사이에서).
 * 여느 문서처럼 `files/docs/` 에 본문을 앉혀 리더가 그대로 연다. 앱 판이 바뀌면 다시
 * 앉힌다(안내 문구가 고쳐졌을 수 있다). 링크를 바꿔 모두 지워도 다시 앉힌다.
 *
 * 드라이브 폴더의 것이 아니므로 `index.json` 에 적지 않는다 — [DocStore.loadIndex] 가 늘 맨 뒤에
 * 끼워 넣고, [DocStore.saveIndex] 는 걸러 낸다.
 */
object Guide {

    private class Lang(val code: String, val name: String) {
        /** 드라이브 ID 는 영숫자·`-`·`_` 20자 이상이라 겹치지 않는다. */
        val id = "_guide_$code"
    }

    private val KO = Lang("ko", "GUIDE(KOR)")
    private val EN = Lang("en", "GUIDE(ENG)")

    const val MIME = "text/plain"

    /** 기기 언어의 것이 먼저 */
    private fun langs(): List<Lang> =
        if (Locale.getDefault().language == "ko") listOf(KO, EN) else listOf(EN, KO)

    fun isGuide(id: String) = id == KO.id || id == EN.id

    private fun doc(l: Lang) = Doc(l.id, l.name, "", cached = true, mimeType = MIME, builtin = true)

    /** 목록 맨 뒤에 설 안내들 — 기기 언어의 것이 먼저 */
    fun docs(): List<Doc> = langs().map { doc(it) }

    /** 기기 언어의 안내 — 처음 켤 때 여는 것 */
    fun primary(): Doc = docs().first()

    /**
     * 본문이 없거나 assets 의 문구가 바뀌었으면 다시 앉힌다. 판 번호가 아니라 **문구 자체**를 견준다 —
     * 판을 올리지 않고 문구만 고쳐 깔아도 반영된다. 앱 판이 같으면 assets 를 다시 읽지 않는다.
     */
    fun ensure(ctx: Context, store: DocStore) {
        val prefs = ctx.getSharedPreferences("guide", Context.MODE_PRIVATE)
        val build = "${BuildConfig.VERSION_CODE}/${ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime}"
        for (l in langs()) {
            val buildKey = "build_${l.code}"
            if (store.hasBody(l.id) && prefs.getString(buildKey, null) == build) continue
            val text = runCatching {
                ctx.assets.open("guide/${l.code}.txt").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val hashKey = "hash_${l.code}"
            val hash = text.hashCode()
            if (!store.hasBody(l.id) || prefs.getInt(hashKey, 0) != hash) store.writeBody(l.id, text)
            prefs.edit().putString(buildKey, build).putInt(hashKey, hash).apply()
        }
    }

    /** 처음 켤 때 한 번 저절로 연다. 연 적이 있으면 false. */
    fun firstOpen(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("guide", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        return true
    }

    private const val KEY_SHOWN = "shown"
}
