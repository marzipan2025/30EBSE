package com.artbrain.ebse.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 망 사정을 사람 말로 옮긴다.
 *
 * 전자책 기기는 대개 Wi-Fi 를 꺼 둔 채 쓴다. 그래서 "연결 안 됨" 이 사고가
 * 아니라 **보통 상태**다. `Unable to resolve host ...` 같은 말을 그대로 띄우면
 * 무엇이 잘못된 줄 알고 놀라게 된다. 받아 둔 글은 그대로 읽을 수 있다는 것을
 * 함께 알린다.
 */
object Net {

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 망이 끊겨서 난 일인가 */
    fun isOffline(e: Throwable): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> true
        is IOException -> e.message?.contains("Unable to resolve host", true) == true ||
            e.message?.contains("Network is unreachable", true) == true
        else -> false
    }

    const val OFFLINE = "인터넷에 연결되어 있지 않습니다.\n가져온 글은 그대로 읽을 수 있습니다."

    /** 무엇을 하려다 난 일인지 붙여 사람 말로 돌려준다. */
    fun explain(ctx: Context, e: Throwable, doing: String): String = when {
        !online(ctx) || isOffline(e) -> OFFLINE
        // 드라이브가 알려 준 까닭(권한·링크)과 파일을 풀지 못한 까닭은 그대로 알린다.
        e is java.io.IOException && e.message?.contains('\n') == true -> e.message!!
        e is IllegalArgumentException && !e.message.isNullOrBlank() -> e.message!!
        else -> "$doing 못했습니다.\n잠시 뒤에 다시 시도해 주세요."
    }
}
