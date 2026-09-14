package com.artbrain.ebse.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 목록에 뜨는 문서 한 칸 */
data class Doc(
    val id: String,
    val name: String,
    val modifiedTime: String,
    /** 본문이 기기에 있나. 30EBSE 는 들일 때 곧바로 풀어 두므로 목록에 뜨는 것은 늘 참이다. */
    val cached: Boolean = false,
    /** 무엇에서 풀었나 — pdf·docx·epub… ([Import] 의 MIME_*) */
    val mimeType: String = "text/plain",
) {
    val isEpub: Boolean get() = mimeType == EPUB

    companion object {
        const val EPUB = "application/epub+zip"
    }
}

/**
 * 문서와 읽던 자리를 기기에 둔다.
 *
 * 앱 전용 폴더에만 쓴다 — 권한이 필요 없고, 앱을 지우면 함께 사라진다.
 *
 *   files/index.json     들여온 문서 목록 (새로 들인 것이 앞)
 *   files/pos.json       문서마다 읽던 쪽
 *   files/docs/<id>.txt  본문
 *   files/docs/<id>/     본문에 든 사진 (epub 만). 이름은 본문의 표가 가리킨다.
 *   files/trash/         지운 뒤 Undo 를 기다리는 본문
 */
class DocStore(ctx: Context) {

    private val root: File = ctx.filesDir
    private val docsDir = File(root, "docs").apply { mkdirs() }
    private val trashDir = File(root, "trash")
    private val indexFile = File(root, "index.json")
    private val posFile = File(root, "pos.json")

    // ── 목록 ──────────────────────────────────────────────

    /**
     * 목록을 읽는다. **본문이 있는 것만** 뜬다.
     *
     * 지운 문서는 본문이 휴지통으로 가므로 곧바로 목록에서 빠지고, Undo 로
     * 되돌리면 제자리(들인 차례)로 돌아온다. 목록에서 따로 빼고 넣지 않는다 —
     * 본문이 있느냐 하나에서 매번 셈하므로 둘이 어긋날 여지가 없다.
     *
     * 목록 파일을 고쳐 쓸 때는 [all] 로 읽는다 — Undo 를 기다리는 칸을 잃지 않게.
     */
    fun loadIndex(all: Boolean = false): List<Doc> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                Doc(id, o.getString("name"), o.optString("modifiedTime"), bodyFile(id).exists(),
                    o.optString("mimeType", "text/plain"))
            }.filter { all || it.cached }
        }.getOrDefault(emptyList())
    }

    fun saveIndex(docs: List<Doc>) {
        val arr = JSONArray()
        for (d in docs) arr.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("modifiedTime", d.modifiedTime)
            put("mimeType", d.mimeType)
        })
        indexFile.writeText(arr.toString())
    }

    // ── 본문 ──────────────────────────────────────────────

    private fun bodyFile(id: String) = File(docsDir, "${safe(id)}.txt")
    private fun trashFile(id: String) = File(trashDir, "${safe(id)}.txt")

    /** 이 문서의 사진 폴더. 본문과 함께 옮기고 지운다. */
    fun imageDir(id: String) = File(docsDir, safe(id))
    private fun trashImageDir(id: String) = File(trashDir, safe(id))

    /** 본문과 사진을 한꺼번에 바꿔 넣는다. 사진은 [staged] 폴더째 옮긴다. */
    fun writeEpub(id: String, text: String, staged: File) {
        val dir = imageDir(id)
        dir.deleteRecursively()
        if (!staged.renameTo(dir)) { staged.copyRecursively(dir, overwrite = true); staged.deleteRecursively() }
        writeBody(id, text)
    }

    fun hasBody(id: String) = bodyFile(id).exists()

    fun readBody(id: String): String? =
        bodyFile(id).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun writeBody(id: String, text: String) = bodyFile(id).writeText(text)

    /**
     * 받아 둔 본문을 **치운다**. 지우는 것이 아니라 [trashDir] 로 옮긴다.
     *
     * 되돌릴 수 있어야 하기 때문이다. 읽던 자리도 지우지 않고 남겨 둔다 —
     * 되돌리면 읽던 데서 이어야 한다. 정말 지우는 것은 [purge] 가 한다.
     */
    fun deleteBody(id: String): Boolean {
        val f = bodyFile(id)
        if (!f.exists()) return false
        trashDir.mkdirs()
        imageDir(id).takeIf { it.exists() }?.renameTo(trashImageDir(id))
        return f.renameTo(trashFile(id))
    }

    /** 치워 둔 것을 되돌린다. */
    fun restoreBody(id: String): Boolean {
        val t = trashFile(id)
        trashImageDir(id).takeIf { it.exists() }?.renameTo(imageDir(id))
        return t.exists() && t.renameTo(bodyFile(id))
    }

    /** 치워 둔 것을 정말 지운다. 되돌릴 기회가 지난 뒤에 부른다. */
    fun purge(id: String) {
        trashFile(id).delete()
        trashImageDir(id).deleteRecursively()
        savePos(id, 0)
        dropFromIndex(setOf(id))
    }

    /** 남아 있는 치운 것들을 모두 지운다. 앱을 다시 켤 때 한 번 쓸어 낸다. */
    fun purgeAll() {
        val gone = HashSet<String>()
        trashDir.listFiles()?.forEach { f ->
            f.name.removeSuffix(".txt").let { savePos(it, 0); gone += it }
            f.deleteRecursively()
        }
        if (gone.isNotEmpty()) dropFromIndex(gone)
    }

    /** 본문까지 사라진 칸을 목록 파일에서도 뺀다. */
    private fun dropFromIndex(ids: Set<String>) {
        if (!indexFile.exists()) return
        runCatching {
            val arr = JSONArray(indexFile.readText())
            val keep = JSONArray()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (safe(it.getString("id")) !in ids) keep.put(it) }
            indexFile.writeText(keep.toString())
        }
    }

    /** 받아 둔 크기 — 사진까지 */
    fun bodyBytes(id: String): Long {
        val f = bodyFile(id)
        if (!f.exists()) return 0L
        val images = imageDir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return f.length() + images
    }

    // ── 읽던 자리 ─────────────────────────────────────────

    private fun positions(): JSONObject =
        if (posFile.exists()) runCatching { JSONObject(posFile.readText()) }.getOrDefault(JSONObject())
        else JSONObject()

    fun loadPos(id: String): Int = positions().optInt(id, 0)

    fun savePos(id: String, page: Int) {
        val o = positions()
        if (page <= 0) o.remove(id) else o.put(id, page)
        posFile.writeText(o.toString())
    }

    /** 파일 이름에 쓸 수 없는 글자를 막는다. 우리가 만든 id 는 안전하지만 만약을 위해. */
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
