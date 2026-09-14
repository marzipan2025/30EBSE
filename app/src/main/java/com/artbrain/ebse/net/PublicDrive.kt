package com.artbrain.ebse.net

import android.content.Context
import android.content.pm.PackageManager
import com.artbrain.ebse.BuildConfig
import com.artbrain.ebse.store.Doc
import com.artbrain.ebse.text.Convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * **링크로 공유된** 구글 드라이브 폴더를 로그인 없이 읽는다.
 *
 * 29EBWO 는 OAuth 로 사용자 드라이브 전체를 읽었다. 그 길은 구글의 앱 검증(심사)을
 * 거쳐야 일반에 배포할 수 있다. 여기서는 사용자가 폴더를 "링크가 있는 모든 사용자" 로
 * 공유하고 그 링크를 넣는다 — 공개된 파일은 **API 키** 만으로 Drive API 가 읽어 준다.
 * API 키에는 동의 화면도 심사도 없다.
 *
 * 키는 APK 에 들어가므로 꺼낼 수 있다. 콘솔에서 "Android 앱" 제한(패키지 + 서명 SHA-1)과
 * Drive API 전용 제한을 걸고, 요청마다 그 제한이 보는 머리(X-Android-Package·Cert)를 싣는다.
 * 새어 나가도 요금이 드는 API 가 아니므로 최악은 할당량 소진이다.
 *
 * 2021 년 보안 갱신 전에 링크 공유된 일부 항목은 **리소스 키**가 있어야 열린다. 링크의
 * `resourcekey=` 와 목록이 주는 `resourceKey` 를 `X-Goog-Drive-Resource-Keys` 로 싣는다.
 */
class PublicDrive(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 폴더 링크에서 뽑은 것 */
    data class Folder(val id: String, val resourceKey: String?)

    /**
     * 폴더 **아래 전부**(하위 폴더까지)의 읽을 수 있는 파일. 최근 고친 것이 앞에 온다.
     * 구글 문서 외의 파일은 확장자로 거른다([Convert.kindOf]) — PDF·docx·txt·md·srt·epub.
     */
    suspend fun listAll(folder: Folder): List<Doc> = withContext(Dispatchers.IO) {
        val out = ArrayList<Doc>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Folder>().apply { add(folder) }
        while (queue.isNotEmpty()) {
            ensureActive()
            val f = queue.removeFirst()
            if (!seen.add(f.id)) continue       // 바로가기 따위로 고리가 생겨도 한 번만
            var pageToken: String? = null
            do {
                val url = StringBuilder(FILES)
                    .append("?q=").append(enc("'${f.id}' in parents and trashed=false"))
                    .append("&pageSize=1000")
                    .append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
                    .append("&fields=").append(enc("nextPageToken,files(id,name,mimeType,modifiedTime,resourceKey)"))
                pageToken?.let { url.append("&pageToken=").append(enc(it)) }
                val o = JSONObject(get(url.toString(), f.id, f.resourceKey))
                val files = o.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val j = files.getJSONObject(i)
                    val id = j.getString("id")
                    val mime = j.optString("mimeType")
                    val name = j.optString("name", "(제목 없음)")
                    val rk = j.optString("resourceKey").ifEmpty { null }
                    when {
                        mime == FOLDER -> queue.add(Folder(id, rk))
                        mime == Doc.GOOGLE_DOC -> out += Doc(id, name, j.optString("modifiedTime"),
                            mimeType = Doc.GOOGLE_DOC, resourceKey = rk)
                        else -> Convert.kindOf(mime, name)?.let { kind ->
                            out += Doc(id, Convert.title(name), j.optString("modifiedTime"),
                                mimeType = kind, resourceKey = rk)
                        }
                    }
                }
                pageToken = o.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null)
        }
        out.sortedByDescending { it.modifiedTime }
    }

    /** 구글 문서 본문을 글로 받는다. 내보내기는 UTF-8 이다. */
    suspend fun exportText(doc: Doc): String = withContext(Dispatchers.IO) {
        get("$FILES/${doc.id}/export?mimeType=" + enc("text/plain"), doc.id, doc.resourceKey)
    }

    /**
     * 파일을 그대로 [to] 에 받는다. 메모리에 올리지 않고 흘려 쓴다(사진 든 epub 은 백 MB 를
     * 넘기도 한다). [onProgress] 에 받은 몫(0~1)을 알린다. 취소하면 다음 토막에서 멈춘다.
     */
    suspend fun download(doc: Doc, to: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val req = request("$FILES/${doc.id}?alt=media&supportsAllDrives=true", doc.id, doc.resourceKey)
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException(explain(res.code, res.body?.string().orEmpty()))
            val body = res.body ?: throw IOException("빈 응답입니다.")
            val total = body.contentLength().takeIf { it > 0 }
                ?: runCatching {
                    JSONObject(get("$FILES/${doc.id}?fields=size&supportsAllDrives=true", doc.id, doc.resourceKey))
                        .optLong("size", -1L)
                }.getOrDefault(-1L)
            val buf = ByteArray(64 * 1024)
            var done = 0L
            body.byteStream().use { input ->
                to.outputStream().use { out ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    private fun get(url: String, id: String, resourceKey: String?): String =
        http.newCall(request(url, id, resourceKey)).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(explain(res.code, body))
            body
        }

    private fun request(url: String, id: String, resourceKey: String?): Request {
        val sep = if ('?' in url) '&' else '?'
        return Request.Builder()
            .url("$url${sep}key=${BuildConfig.DRIVE_API_KEY}")
            .header("X-Android-Package", ctx.packageName)
            .apply { signingSha1?.let { header("X-Android-Cert", it) } }
            .apply { resourceKey?.let { header("X-Goog-Drive-Resource-Keys", "$id/$it") } }
            .build()
    }

    /** 이 APK 를 서명한 인증서의 SHA-1 (콜론 없는 대문자) — API 키의 Android 앱 제한이 본다. */
    private val signingSha1: String? by lazy {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
            MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }

    /** 실패한 까닭을 사람 말로 옮긴다. */
    private fun explain(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        }.getOrNull().orEmpty()
        return when {
            code == 404 -> "폴더나 파일을 찾을 수 없습니다.\n링크 공유가 '링크가 있는 모든 사용자' 인지 확인해 주세요."
            code == 403 && reason.contains("rateLimit", true) -> "요청이 많아 잠시 막혔습니다.\n잠시 뒤에 다시 시도해 주세요."
            code == 403 && reason.contains("keyInvalid", true) -> "앱의 API 키가 올바르지 않습니다."
            code == 403 -> "읽을 권한이 없습니다.\n링크 공유가 '링크가 있는 모든 사용자' 인지 확인해 주세요."
            code == 400 && reason.contains("keyInvalid", true) -> "앱의 API 키가 올바르지 않습니다."
            else -> "드라이브 오류 $code"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val FOLDER = "application/vnd.google-apps.folder"

        /**
         * 폴더 링크에서 id 와 리소스 키를 뽑는다. 알아볼 수 없으면 null.
         *
         *   https://drive.google.com/drive/folders/<id>?usp=sharing
         *   https://drive.google.com/drive/u/0/folders/<id>?resourcekey=<key>
         *   https://drive.google.com/open?id=<id>
         *   <id> 만
         */
        fun parse(link: String): Folder? {
            val s = link.trim()
            if (s.isEmpty()) return null
            val id = Regex("""/folders/([A-Za-z0-9_-]{10,})""").find(s)?.groupValues?.get(1)
                ?: Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(s)?.groupValues?.get(1)
                ?: s.takeIf { Regex("""[A-Za-z0-9_-]{20,}""").matches(it) }
                ?: return null
            val rk = Regex("""[?&]resourcekey=([A-Za-z0-9_-]+)""").find(s)?.groupValues?.get(1)
            return Folder(id, rk)
        }
    }
}
