package com.artbrain.ebse

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Gravity
import android.view.TouchDelegate
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebse.net.Net
import com.artbrain.ebse.net.Updater
import com.artbrain.ebse.store.Doc
import com.artbrain.ebse.store.DocStore
import com.artbrain.ebse.store.Import
import com.artbrain.ebse.ui.Chime
import com.artbrain.ebse.ui.Eink
import com.artbrain.ebse.ui.Fonts
import com.artbrain.ebse.ui.Glyph
import com.artbrain.ebse.ui.Ink
import com.artbrain.ebse.ui.Popup
import com.artbrain.ebse.ui.LineShade
import com.artbrain.ebse.ui.Shade
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 문서 목록.
 *
 * 스크롤을 쓰지 않는다 — 화면에 들어갈 만큼만 놓고 나머지는 이전/다음
 * 단추로 넘긴다. 쪽 수는 화면 높이에서 구하므로 기기가 바뀌어도 맞는다.
 */
class DocListActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: DocStore

    private lateinit var root: FrameLayout
    private lateinit var box: View
    private lateinit var title: TextView
    private lateinit var rows: LinearLayout
    private lateinit var number: TextView
    private lateinit var empty: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnPick: TextView
    private lateinit var about: TextView

    private var docs: List<Doc> = emptyList()
    private var page = 0
    private var perPage = 1
    private var busy = false

    /** 아래 상태줄에 띄울 말. null 이면 형편에 맞는 기본 말이 나온다. */
    private var status: String? = null
    private val popup by lazy { Popup(root) }
    private lateinit var chime: Chime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doclist)
        Eink.applyTheme(this)
        store = DocStore(this)

        // 리더처럼 시스템 막대를 걷는다. 쓸어내릴 때만 잠깐 나온다. 활용공간은
        // 화면 비율로 잡으므로 막대 높이만큼 위가 비는 일이 없다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        root = findViewById(R.id.root)
        rows = findViewById(R.id.rows)
        number = findViewById(R.id.number)
        empty = findViewById(R.id.empty)
        box = findViewById(R.id.box)
        title = findViewById(R.id.title)
        btnPrev = findViewById(R.id.prev)
        btnNext = findViewById(R.id.next)
        btnPick = findViewById(R.id.pick)
        about = findViewById(R.id.about)
        chime = Chime(this, root)

        btnPrev.setOnClickListener { if (page > 0) { page--; render() } }
        btnNext.setOnClickListener { if (page < lastPage()) { page++; render() } }
        btnPick.setOnClickListener { pick() }
        about.setOnClickListener { showAbout() }

        // 이 화면의 글자는 모두 geist — 가는 이탤릭 Geist Mono.
        // 제목만 기울이지 않은 보통 굵기로.
        title.typeface = Fonts.of(this, Fonts.UI_UPRIGHT)
        title.fontVariationSettings = Fonts.REGULAR
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.includeFontPadding = false
        geist(number, 14f)
        geist(btnPick, 24f)
        setUpAbout()
        geist(btnPrev, 24f)
        geist(btnNext, 24f)
        root.post { sizeBox() }

        // 지난번에 치워 둔 것은 되돌릴 기회가 지났다. 여기서 쓸어 낸다.
        store.purgeAll()
        docs = store.loadIndex()

        // 한 쪽에 몇 칸이 들어가는지는 자리를 잡은 뒤에야 안다.
        // 통의 높이는 활용공간이 정해진 **뒤에야** 확정된다. 한 번만 재면
        // 상자가 줄기 전의 큰 값을 잡아 칸이 커지고 아래 줄을 침범한다.
        // 높이가 바뀔 때마다 다시 센다.
        rows.viewTreeObserver.addOnGlobalLayoutListener {
            val h = rows.height
            if (h <= 0 || h == measuredRowsH) return@addOnGlobalLayoutListener
            measuredRowsH = h
            // 들어가는 만큼을 세어 칸 높이를 정하고(간격은 이 값으로 고정),
            // 놓기는 하나 적게 한다 — 마지막 칸과 아래 줄 사이가 그만큼 뜬다.
            val fit = max(1, h / Ink.dp(this, ROW_DP).toInt())
            rowH = h / fit
            perPage = fit
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // 파일 선택창 따위에서 돌아오면 막대가 다시 나와 있을 수 있다.
        hideBars()
        chime.resume()
        // 읽고 돌아오면 받아 둔 표시가 바뀔 수 있다.
        if (::store.isInitialized && perPage > 0) {
            docs = store.loadIndex()
            render()
        }
        showHeldUpdate()
    }

    override fun onPause() {
        super.onPause()
        chime.pause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 이 화면의 모든 글자에 쓰는 꼴 — 가는 이탤릭 Geist Mono. */
    private fun geist(v: TextView, dp: Float) {
        v.typeface = Fonts.of(this, Fonts.UI)
        v.fontVariationSettings = Fonts.THIN
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp)
        v.includeFontPadding = false
        // TextView 는 가로를 gravity 에 맡기고 세로만 먹 기준으로 맞춘다.
        // translationX 를 걸면 그만큼 반대쪽 여백이 잘려 글리프 끝이 날아간다.
        if (v !is android.widget.Button) v.post { Glyph.centerVertical(v) }
        if (v is android.widget.Button) {
            v.gravity = Gravity.CENTER
            Shade.applyTo(v)
            // 오른쪽 것들은 활용공간의 오른쪽 끝에, 왼쪽 것은 왼쪽 끝에 세운다.
            val toStart = v.id == R.id.prev
            v.post { Glyph.alignEdge(v, toStart) }
        }
    }

    /**
     * 이름 바로 뒤의 © — 윗첨자 크기의 가는 이탤릭.
     *
     * 글자의 윗선을 이름의 윗선(대문자 높이)에 맞춘다. 글자가 작아 누르기
     * 어려우므로 누르는 자리는 머리 높이 전체와 오른쪽으로 넉넉히 넓힌다.
     */
    private fun setUpAbout() {
        geist(about, ABOUT_DP)
        about.gravity = Gravity.CENTER
        about.setPadding(Ink.dp(this, 1f).toInt(), 0, 0, 0)
        Shade.applyTo(about)
        about.post {
            val r = Rect()
            title.paint.getTextBounds(title.text.toString(), 0, title.text.length, r)
            val titleTop = title.baseline + r.top
            about.paint.getTextBounds("\u00A9", 0, 1, r)
            about.translationY = (titleTop - (about.baseline + r.top)).toFloat()
            // 누르는 자리 — 글자 둘레로 넓힌다.
            val hit = Rect()
            about.getHitRect(hit)
            hit.inset(-Ink.dp(this, 12f).toInt(), 0)
            hit.top = 0
            hit.bottom = (about.parent as View).height
            (about.parent as View).touchDelegate = TouchDelegate(hit, about)
        }
    }

    /** 앱 정보와 라이선스 */
    private fun showAbout() {
        popup.show(
            msg = "30EBSE ${BuildConfig.VERSION_NAME}\n" +
                "한 쪽에 한 문장씩 읽는 e-ink 리더\n\n" +
                "에이투지체 — SIL Open Font License 1.1\n" +
                "Geist Mono — SIL Open Font License 1.1\n" +
                "PdfBox-Android — Apache License 2.0\n" +
                "juniversalchardet — Mozilla Public License 1.1\n" +
                "OkHttp — Apache License 2.0\n" +
                "AndroidX · Kotlin Coroutines — Apache License 2.0\n\n" +
                "github.com/marzipan2025/30EBSE",
            ms = ABOUT_MS,
        )
    }

    /**
     * 활용공간 — 폭은 화면의 60%. 세로는 **리더에 맞춘다**: 머리(이름·*)의
     * 가운데가 리더의 문장 번호 줄(위에서 15%)에, 발(화살표·쪽 번호)의 가운데가
     * 리더의 시계 줄(아래에서 15%)에 선다. 두 화면을 오갈 때 위아래 줄이 제자리에
     * 있다. 막대를 걷어 화면을 끝까지 쓰므로 비율이 곧 화면 자리다.
     */
    private fun sizeBox() {
        if (root.width <= 0) return
        val h = root.height
        val touch = Ink.dp(this, Ink.TOUCH_DP).toInt()
        val top = (h * Ink.EDGE_Y).toInt() - touch / 2
        val bottom = (h * (1f - Ink.EDGE_Y)).toInt() + touch / 2
        (box.layoutParams as FrameLayout.LayoutParams).let {
            it.width = (root.width * Ink.BOX_FRACTION).toInt()
            it.height = bottom - top
            it.topMargin = top
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            box.layoutParams = it
        }
    }

    /** 칸 하나의 높이. 놓는 칸 수를 줄여도 이 값은 그대로다 — 간격이 안 변한다. */
    private var rowH = 0
    private var measuredRowsH = 0

    private fun rowPx(): Int =
        if (rowH > 0) rowH else Ink.dp(this, ROW_DP).toInt()

    private fun lastPage() = max(0, (docs.size - 1) / perPage)

    private fun render() {
        rows.removeAllViews()
        page = page.coerceIn(0, lastPage())

        showStatus()

        if (docs.isEmpty()) {
            number.text = ""
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
            btnPrev.alpha = DIM
            btnNext.alpha = DIM
            return
        }

        val from = page * perPage
        val to = minOf(from + perPage, docs.size)
        val inflater = LayoutInflater.from(this)
        for (i in from until to) addRow(inflater, rows, docs[i])

        number.text = "${page + 1}/${lastPage() + 1}"
        btnPrev.isEnabled = page > 0
        btnNext.isEnabled = page < lastPage()
        // 누를 수 없어도 지운 자리처럼 보이지 않게 옅게 남긴다 — 줄의 균형이
        // 무너지지 않는다.
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else DIM
        btnNext.alpha = if (btnNext.isEnabled) 1f else DIM
    }

    private fun addRow(inflater: LayoutInflater, parent: ViewGroup, doc: Doc) {
        val row = inflater.inflate(R.layout.row_doc, parent, false)
        // 칸이 통을 빈틈없이 나눠 갖게 한다 — 칸 사이에 죽은 자리가 남으면
        // 거기를 눌러도 아무 일이 없어 "안 눌린다" 로 느껴진다.
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, rowPx())
        val title = row.findViewById<TextView>(R.id.name).apply {
            text = doc.name
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, TITLE_DP)
        }
        // 누르는 동안 제목 뒤를 ░ 한 줄로 채운다 — 제목보다 1dp 작게.
        LineShade.applyTo(row, title, TITLE_DP - 1f)

        val mark = row.findViewById<TextView>(R.id.mark)
        val del = row.findViewById<TextView>(R.id.del)
        geist(mark, 14f)
        geist(del, 20f)

        if (doc.cached) {
            // 메가바이트, 소수점 한 자리. 단위는 붙이지 않는다.
            mark.text = String.format(Locale.US, "%.1f", store.bodyBytes(doc.id) / 1_048_576.0)
            del.visibility = View.VISIBLE
            del.setOnClickListener {
                if (!store.deleteBody(doc.id)) return@setOnClickListener
                docs = store.loadIndex()
                render()
                popup.show(
                    msg = "${doc.name}\n지웠습니다.",
                    undoLabel = "Undo",
                    onUndo = {
                        store.restoreBody(doc.id)
                        docs = store.loadIndex()
                        render()
                    },
                    // 되돌리지 않고 시간이 다 되면 그때 정말 지운다.
                    onExpire = { store.purge(doc.id) },
                    ms = Popup.UNDO_MS,
                )
            }
        } else {
            mark.text = ""
            del.visibility = View.INVISIBLE
        }

        row.setOnClickListener { open(doc) }
        parent.addView(row)
    }

    /** 문서를 연다. 들일 때 글로 풀어 두었으므로 망이 필요 없다. */
    private fun open(doc: Doc) {
        if (!store.hasBody(doc.id)) return
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_ID, doc.id)
            putExtra(ReaderActivity.EXTRA_NAME, doc.name)
        })
    }

    /**
     * 파일 선택창을 연다. 여러 개를 한꺼번에 고를 수 있다.
     *
     * 종류로 거르지 않는다(모든 종류) — md·srt 는 대개 종류 없이(octet-stream) 오기
     * 때문이다. CATEGORY_OPENABLE 도 붙이지 않는다. 붙이면 바이트로 열 수 있는
     * 파일만 뜨고, **구글 문서 같은 가상 파일이 빠진다.** 형식은 고른 뒤에 가린다.
     *
     * 드라이브 앱이 깔려 있으면 선택창 옆 서랍에 드라이브가 뜬다. 없으면 기기
     * 안의 파일만 보인다. 우리 쪽에서 할 일은 없다.
     *
     * 고르는 동안 뒤에서 새 판을 본다([checkUpdateDaily]). 알림은 선택창을
     * 덮지 않도록 돌아온 뒤에 띄운다.
     */
    private fun pick() {
        if (busy || updating?.isActive == true) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
            picking = true
        } catch (_: ActivityNotFoundException) {
            say("이 기기에는 파일 선택창이 없습니다.")
            return
        }
        checkUpdateDaily()
    }

    /** 선택창이 떠 있나 — 그동안 도착한 새 판 알림은 미뤄 둔다. */
    private var picking = false

    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        picking = false
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
            else data.data?.let { uris += it }
        }
        if (uris.isEmpty()) showHeldUpdate() else importAll(uris)
    }

    /**
     * 고른 파일을 차례로 들인다. 하나만 골랐으면 들이자마자 연다.
     * 안 되는 파일이 있어도 나머지는 계속 들이고, 끝에 모아서 알린다.
     */
    private fun importAll(uris: List<Uri>) {
        if (busy) return
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            busy = true
            val failed = ArrayList<String>()
            var last: Doc? = null
            try {
                for ((i, uri) in uris.withIndex()) {
                    val head = if (uris.size > 1) "${i + 1}/${uris.size} " else ""
                    val progress = popup.progress("${head}가져오고 있습니다") { job?.cancel() }
                    try {
                        last = Import.one(this@DocListActivity, store, uri, progress)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val name = Import.displayName(this@DocListActivity, uri)
                        failed += "$name — " + when (e) {
                            is Import.ImportError, is IllegalArgumentException -> e.message.orEmpty()
                            else -> Net.explain(this@DocListActivity, e, "가져오지")
                        }
                    }
                }
            } finally {
                busy = false
                popup.dismiss()
                docs = store.loadIndex()
                page = 0
                render()
            }
            when {
                failed.isNotEmpty() -> popup.show(failed.joinToString("\n\n"), ms = FAIL_MS,
                    onExpire = { showHeldUpdate() })
                // 새 판 알림은 리더에서 돌아온 뒤 onResume 이 띄운다.
                uris.size == 1 && last != null -> open(last)
                else -> showHeldUpdate()
            }
        }
    }

    /** 새 판을 받는 중인 일. 받는 동안에는 * 를 막는다 — 팝업이 덮인다. */
    private var updating: kotlinx.coroutines.Job? = null

    /**
     * GitHub 릴리스에 새 판이 있는지 **하루에 한 번** 본다. * 를 누를 때 뒤에서 돈다.
     * 있으면 설치할지 묻는다(왼쪽에 Install, 닫기는 늘 오른쪽). 없거나 알 수 없으면
     * 조용히 넘어간다.
     */
    private fun checkUpdateDaily() {
        if (!Net.online(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < DAY_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        scope.launch {
            heldRelease = Updater.check(BuildConfig.VERSION_NAME) ?: return@launch
            if (!picking && !busy) showHeldUpdate()
        }
    }

    /** 선택창·가져오기가 끝나기를 기다리는 새 판 */
    private var heldRelease: Updater.Release? = null

    private fun showHeldUpdate() {
        val r = heldRelease ?: return
        if (isFinishing || picking || busy) return
        heldRelease = null
        popup.show(
            msg = "새 버전 ${r.version}${subjectParticle(r.version)} 있습니다.\n지금 설치할까요?",
            undoLabel = INSTALL,
            onUndo = { startUpdate(r) },
            ms = UPDATE_MS,
        )
    }

    /**
     * 버전 뒤의 조사 — `0.2.5가`, `0.2.6이`. 끝 숫자를 읽는 소리에 받침이 있으면
     * 이(영·일·삼·육·칠·팔), 없으면 가(이·사·오·구).
     */
    private fun subjectParticle(version: String): String =
        if (version.lastOrNull() in listOf('2', '4', '5', '9')) "가" else "이"

    /** 받아서 시스템 설치 화면으로 넘긴다. 설치가 끝나면 그 화면의 "열기" 로 다시 연다. */
    private fun startUpdate(r: Updater.Release) {
        if (!Updater.canInstall(this)) {
            popup.show(
                msg = "설치하려면 이 앱에 설치 권한이 필요합니다.\n켜고 돌아와 다시 설치해 주세요.",
                undoLabel = SETTINGS,
                onUndo = { Updater.openInstallSettings(this) },
                ms = UPDATE_MS,
            )
            // 켜고 돌아오면 다시 물을 수 있게 남겨 둔다.
            heldRelease = r
            return
        }
        var job: kotlinx.coroutines.Job? = null
        val progress = popup.progress("새 버전 ${r.version} 받고 있습니다") { job?.cancel() }
        job = scope.launch {
            val apk = Updater.download(this@DocListActivity, r, progress)
            popup.dismiss()
            if (apk == null) { say("새 버전을 받지 못했습니다.\n잠시 뒤에 다시 시도해 주세요."); return@launch }
            Updater.install(this@DocListActivity, apk)
        }
        updating = job
    }

    private fun say(msg: String?) {
        if (msg == null) { status = null; showStatus(); return }
        popup.show(msg)
    }

    private companion object {
        /** 목록 제목 글자 크기 */
        const val TITLE_DP = 14f

        const val REQ_PICK = 30

        const val PREFS = "ebse"
        const val KEY_LAST_CHECK = "lastUpdateCheck"
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** 가져오지 못한 까닭을 읽을 틈 */
        const val FAIL_MS = 10_000L

        /** © 글자 크기 — 이름(20dp)의 윗첨자 */
        const val ABOUT_DP = 12f

        /** 앱 정보는 천천히 읽는다 — Close 로 닫는다 */
        const val ABOUT_MS = 60_000L

        /** 업데이트 팝업의 왼쪽 단추 */
        const val INSTALL = "Install"
        const val SETTINGS = "Settings"

        /** 업데이트를 묻는 팝업이 머무는 시간 — 읽고 누를 틈 */
        const val UPDATE_MS = 10_000L


        /** 누를 수 없는 화살표의 옅기 */
        const val DIM = 0.5f

        /** 칸 높이 — 손가락 자리(56dp)의 80% */
        const val ROW_DP = Ink.TOUCH_DP * 0.8f
    }

    private fun showStatus() {
        val msg = when {
            // 목록이 있으면 받는 중인 것은 팝업이 알린다. 칸 위에 겹쳐 쓰지 않는다.
            busy && docs.isEmpty() -> "가져오고 있습니다…"
            docs.isEmpty() -> "* 을 눌러 읽을 파일을 고르세요.\npdf · docx · txt · epub · md · srt"
            else -> null
        }
        empty.text = msg.orEmpty()
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
