package com.artbrain.ebse.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub 릴리스를 보고 새 판이 있으면 기기에서 바로 받아 설치한다.
 * 25HAK3·26HAKC 와 같은 구조 — 스토어를 거치지 않는 배포라 갱신도 직접 챙긴다.
 *
 * 두 가지를 전제한다.
 *  1. 릴리스가 익명으로 읽히는 곳(공개 저장소)에 있어야 한다. APK 에 넣은 토큰은
 *     꺼내 쓸 수 있으므로 넣지 않는다.
 *  2. 새 APK 가 지금 깔린 것과 **같은 키**로 서명돼 있어야 한다. 이 앱은 30EBSE
 *     전용 릴리스 키(SHA-1 E8:2B:BB…6E:1C, CLAUDE.md 참고)로 서명한다. 다르면
 *     안드로이드가 덮어쓰기를 거부하고, 지우고 다시 깔면 가져온 글과 읽던 자리가 사라진다.
 *
 * 설치가 끝난 앱은 스스로 다시 뜰 수 없다(안드로이드가 뒤에서 화면 띄우기를 막는다).
 * 시스템 설치 화면의 "열기" 로 다시 연다.
 */
object Updater {

    private const val API = "https://api.github.com/repos/marzipan2025/30EBSE/releases/latest"

    data class Release(val version: String, val apkUrl: String)

    /** 새 판이 있으면 그 릴리스, 없거나 알 수 없으면 null. 조용히 실패한다. */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) {
        val r = fetch() ?: return@withContext null
        if (isNewer(r.version, current)) r else null
    }

    private fun fetch(): Release? = runCatching {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.optJSONArray("assets") ?: return@runCatching null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            Release(json.optString("tag_name").removePrefix("v"), apk ?: return@runCatching null)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 0.2.10 이 0.2.9 보다 새것이도록 마디마다 숫자로 견준다. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * APK 를 받아 캐시에 둔다. [onProgress] 에 퍼센트를 알린다. 코루틴을 취소하면
     * 다음 토막에서 멈추고 받던 파일을 지운다. 실패하면 null.
     */
    suspend fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // 지난번 것은 치운다
            val out = File(dir, "30EBSE-${r.version}.apk")
            var ok = false
            try {
                val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return@withContext null
                    val total = conn.contentLengthLong
                    var read = 0L
                    conn.inputStream.use { input ->
                        out.outputStream().use { sink ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                sink.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress((read * 100 / total).toInt())
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                ok = true
                out
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                if (!ok) out.delete()
            }
        }

    /** 이 앱이 다른 앱을 설치해도 되는가("출처를 알 수 없는 앱" 허용). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** 설치 허용을 켜는 설정 화면을 연다. */
    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ))
    }

    /** 시스템 설치 화면을 띄운다. 설치 여부와 "열기" 는 사용자가 그 화면에서 정한다. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
