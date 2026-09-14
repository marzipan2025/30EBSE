package com.artbrain.ebse.store

import android.content.Context
import com.artbrain.ebse.net.PublicDrive
import com.artbrain.ebse.text.Convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 드라이브 폴더와 기기의 목록을 맞추고, 문서 본문을 받아 앉힌다.
 *
 * 폴더 링크는 하나다([Settings]). 새로고침([sync])은 폴더 아래 전부를 다시 읽어 **이름만**
 * 목록에 올린다 — 본문은 문서를 열 때 받는다([body]).
 */
object Library {

    /**
     * 폴더를 다시 읽어 목록을 맞춘다.
     *
     * - 새로 생긴 파일 → 받지 않은 칸으로 들어온다.
     * - 이름·고친 때가 바뀐 파일 → 칸을 고친다. 받아 둔 본문은 그대로다(리더의 새로고침으로 다시 받는다).
     * - 사라진 파일 → 받아 둔 본문이 없으면 빠지고, 있으면 [Doc.gone] 으로 남는다.
     *   그 칸을 지우면(×) 목록에서도 빠진다.
     */
    suspend fun sync(ctx: Context, store: DocStore, folder: PublicDrive.Folder) = withContext(Dispatchers.IO) {
        val remote = PublicDrive(ctx).listAll(folder)
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        val left = store.loadIndex(all = true)
            .filter { it.id !in remoteIds && store.hasBody(it.id) }
            .map { it.copy(gone = true) }
        store.saveIndex(remote + left)
    }

    /**
     * 문서 본문을 받아 앉히고 돌려준다. [onProgress] 에 퍼센트를 알린다.
     *
     * 구글 문서는 글로 내보내 받는다. 파일은 그대로 받아 기기에서 푼다 — epub 은 글과
     * 사진으로([Fetch.epub]), 나머지는 [Convert]. 받기가 9할이다.
     */
    suspend fun body(ctx: Context, store: DocStore, doc: Doc, onProgress: (Int) -> Unit = {}): String {
        val drive = PublicDrive(ctx)
        if (doc.mimeType == Doc.GOOGLE_DOC) return drive.exportText(doc).also {
            withContext(Dispatchers.IO) { store.writeBody(doc.id, it) }
            onProgress(100)
        }
        val file = File(File(ctx.cacheDir, "file").apply { mkdirs() }, "${doc.id}.bin")
        try {
            drive.download(doc, file) { onProgress((it * DOWNLOAD_SHARE).toInt()) }
            return withContext(Dispatchers.IO) {
                val text = if (doc.isEpub) {
                    Fetch.epub(ctx, store, doc.id, file) { f ->
                        onProgress(DOWNLOAD_SHARE + (f * (100 - DOWNLOAD_SHARE)).toInt())
                    }
                } else {
                    val t = try {
                        Convert.text(doc.mimeType, file)
                    } catch (e: Convert.Unsupported) {
                        throw IllegalArgumentException(e.message)
                    }
                    if (t.isBlank()) throw IllegalArgumentException("읽을 글을 찾지 못했습니다.")
                    store.imageDir(doc.id).deleteRecursively()
                    store.writeBody(doc.id, t)
                    t
                }
                onProgress(100)
                text
            }
        } finally {
            file.delete()
        }
    }

    /** 받기가 차지하는 퍼센트 — 나머지는 풀기 */
    private const val DOWNLOAD_SHARE = 90
}
