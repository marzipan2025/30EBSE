package com.artbrain.ebse.store

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.artbrain.ebse.text.Convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 *
 * 형식마다 글을 푸는 일은 [Convert] 가 한다 — 29EBWO 와 같은 파일이다.
 */
object Import {

    class ImportError(msg: String) : Exception(msg)

    /** 고른 파일 하나를 들여온다. 목록에 넣은 칸을 돌려준다. */
    suspend fun one(ctx: Context, store: DocStore, uri: Uri, onProgress: (Int) -> Unit): Doc =
        withContext(Dispatchers.IO) {
            val cr = ctx.contentResolver
            val meta = meta(ctx, uri)
            val type = cr.getType(uri).orEmpty()
            val title = Convert.title(meta.name)

            val index = store.loadIndex(all = true)
            val id = index.firstOrNull { it.name == title }?.id ?: newId()

            val work = File(ctx.cacheDir, "import").apply { mkdirs() }
            val file = File(work, "$id.bin")
            try {
                val kind: String
                if (meta.virtual || type.startsWith(GOOGLE_APPS)) {
                    // 구글 문서 — 드라이브가 내주는 꼴 가운데 PDF 를 받는다.
                    val offered = runCatching { cr.getStreamTypes(uri, "*/*")?.toList() }.getOrNull().orEmpty()
                    if (Convert.PDF !in offered) throw ImportError("이 구글 파일은 글로 가져올 수 없습니다.")
                    copy(cr.openTypedAssetFileDescriptor(uri, Convert.PDF, null)?.createInputStream(), file)
                    kind = Convert.PDF
                } else {
                    kind = Convert.kindOf(type, meta.name)
                        ?: throw ImportError("지원하지 않는 형식입니다.\npdf · docx · txt · epub · md · srt 를 열 수 있습니다.")
                    copy(cr.openInputStream(uri), file)
                }
                onProgress(PREPARE_SHARE)

                when (kind) {
                    Convert.EPUB -> Fetch.epub(ctx, store, id, file) { f ->
                        onProgress(PREPARE_SHARE + (f * (100 - PREPARE_SHARE)).toInt())
                    }
                    else -> {
                        val text = Convert.text(kind, file)
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
            } catch (e: Convert.Unsupported) {
                throw ImportError(e.message.orEmpty())
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

    private fun copy(input: java.io.InputStream?, to: File) {
        (input ?: throw ImportError("파일을 열 수 없습니다.")).use { i ->
            to.outputStream().use { o -> i.copyTo(o) }
        }
    }

    private fun newId() = "f" + System.currentTimeMillis().toString(36) +
        (0 until 4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    private const val GOOGLE_APPS = "application/vnd.google-apps"

    /** 파일을 받아 오기까지가 차지하는 퍼센트 — 구글 문서는 망을 타므로 여기가 길다 */
    private const val PREPARE_SHARE = 60
}
