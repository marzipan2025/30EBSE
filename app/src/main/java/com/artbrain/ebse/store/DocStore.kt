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
    /** 본문을 받아 뒀나 */
    val cached: Boolean = false,
    /** 무엇으로 푸나 — 구글 문서 또는 [com.artbrain.ebse.text.Convert] 의 종류 */
    val mimeType: String = GOOGLE_DOC,
    /** 드라이브의 리소스 키 — 오래전에 링크 공유된 항목은 이것이 있어야 열린다 */
    val resourceKey: String? = null,
    /**
     * 드라이브 폴더에서 사라졌지만 받아 둔 본문이 있어 남겨 둔 칸. 새로고침으로 다시
     * 받을 곳이 없고, 지우면(×) 목록에서도 빠진다.
     */
    val gone: Boolean = false,
) {
    val isEpub: Boolean get() = mimeType == EPUB

    companion object {
        const val GOOGLE_DOC = "application/vnd.google-apps.document"
        const val EPUB = "application/epub+zip"
    }
}

/**
 * 문서와 읽던 자리를 기기에 둔다.
 *
 * 앱 전용 폴더에만 쓴다 — 권한이 필요 없고, 앱을 지우면 함께 사라진다.
 *
 *   files/index.json     드라이브 폴더의 문서 목록 (받아 둔 것이 앞)
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
    private val sizeFile = File(root, "sizes.json")
    private val pagesDir = File(root, "pages")

    // ── 목록 ──────────────────────────────────────────────

    /**
     * 목록을 읽는다. **받아 둔 문서가 앞에 온다** — 같은 무리 안에서는 드라이브가 준
     * 차례(최근 고친 순)를 지킨다.
     *
     * 폴더에서 사라진 칸([Doc.gone])은 본문이 있을 때만 뜬다. 그런 칸을 지우면 본문이
     * 휴지통으로 가며 곧바로 빠지고, Undo 로 되돌리면 돌아온다.
     */
    fun loadIndex(all: Boolean = false): List<Doc> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                Doc(id, o.getString("name"), o.optString("modifiedTime"), bodyFile(id).exists(),
                    o.optString("mimeType", Doc.GOOGLE_DOC), o.optString("resourceKey").ifEmpty { null },
                    o.optBoolean("gone", false))
            }.filter { all || it.cached || !it.gone }.sortedByDescending { it.cached }
        }.getOrDefault(emptyList())
    }

    fun saveIndex(docs: List<Doc>) {
        val arr = JSONArray()
        for (d in docs) arr.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("modifiedTime", d.modifiedTime)
            put("mimeType", d.mimeType)
            d.resourceKey?.let { put("resourceKey", it) }
            if (d.gone) put("gone", true)
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

    fun writeBody(id: String, text: String) {
        bodyFile(id).writeText(text)
        forgetSize(id)
        pagesFile(id).delete()
    }

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
        forgetSize(id)
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
        forgetSize(id)
        pagesFile(id).delete()
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

    /** 본문까지 지운 칸 가운데 폴더에서 이미 사라진 것을 목록 파일에서도 뺀다. */
    private fun dropFromIndex(ids: Set<String>) {
        if (!indexFile.exists()) return
        runCatching {
            val arr = JSONArray(indexFile.readText())
            val keep = JSONArray()
            for (i in 0 until arr.length()) arr.getJSONObject(i).let {
                // 폴더에서 사라진 칸만 목록에서도 뺀다. 폴더에 남아 있는 칸은 받지 않은 채로 남는다.
                if (!(safe(it.getString("id")) in ids && it.optBoolean("gone", false))) keep.put(it)
            }
            indexFile.writeText(keep.toString())
        }
    }

    /**
     * 받아 둔 크기 — 사진까지. **한 번 재고 [sizeFile] 에 적어 둔다.**
     *
     * 목록은 칸마다 이 값을 보이고, 쪽을 넘길 때마다 다시 그린다. 그때마다 사진
     * 폴더를 걸어 다니면 사진이 든 글이 많을수록 화면이 멈춘다 — 받거나 지울 때만
     * 바뀌는 값이므로 그때 지우고 다음에 한 번 다시 잰다.
     */
    fun bodyBytes(id: String): Long {
        val f = bodyFile(id)
        if (!f.exists()) return 0L
        sizes()[id]?.let { return it }
        val images = imageDir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val total = f.length() + images
        sizes()[id] = total
        saveSizes()
        return total
    }

    private var sizeCache: MutableMap<String, Long>? = null

    private fun sizes(): MutableMap<String, Long> = sizeCache ?: run {
        val m = HashMap<String, Long>()
        if (sizeFile.exists()) runCatching {
            val o = JSONObject(sizeFile.readText())
            for (k in o.keys()) m[k] = o.getLong(k)
        }
        sizeCache = m
        m
    }

    private fun saveSizes() {
        val o = JSONObject()
        for ((k, v) in sizes()) o.put(k, v)
        runCatching { sizeFile.writeText(o.toString()) }
    }

    /** 본문이나 사진이 바뀌었다 — 다음에 다시 잰다. */
    private fun forgetSize(id: String) {
        if (sizes().remove(id) != null) saveSizes()
    }

    /** 모두 지운다 — 폴더 링크를 바꿨을 때. 이전 폴더의 목록·본문·사진·읽던 자리가 사라진다. */
    fun wipe() {
        docsDir.listFiles()?.forEach { it.deleteRecursively() }
        trashDir.deleteRecursively()
        pagesDir.deleteRecursively()
        indexFile.delete()
        posFile.delete()
        sizeFile.delete()
        sizeCache = null
    }

    /**
     * 쪽을 나눠 둔 파일. 같은 글을 다시 열 때 처음부터 다시 나누지 않는다
     * ([com.artbrain.ebse.ui.PageView]). 본문이 바뀌거나 지워지면 함께 지운다.
     */
    fun pagesFile(id: String): File {
        pagesDir.mkdirs()
        return File(pagesDir, "${safe(id)}.pg")
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
