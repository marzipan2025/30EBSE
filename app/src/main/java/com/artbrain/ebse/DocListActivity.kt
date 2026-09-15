package com.artbrain.ebse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebse.net.Net
import com.artbrain.ebse.net.PublicDrive
import com.artbrain.ebse.net.Updater
import com.artbrain.ebse.store.Doc
import com.artbrain.ebse.store.DocStore
import com.artbrain.ebse.store.Library
import com.artbrain.ebse.store.Settings
import com.artbrain.ebse.ui.Chime
import com.artbrain.ebse.ui.Eink
import com.artbrain.ebse.ui.Fonts
import com.artbrain.ebse.ui.Glyph
import com.artbrain.ebse.ui.Ink
import com.artbrain.ebse.ui.Popup
import com.artbrain.ebse.ui.LineShade
import com.artbrain.ebse.ui.Shade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 문서 목록 — 드라이브 폴더 하나(설정의 링크) 아래의 문서들.
 *
 * 스크롤을 쓰지 않는다 — 화면에 들어갈 만큼만 놓고 나머지는 이전/다음
 * 단추로 넘긴다. 쪽 수는 화면 높이에서 구하므로 기기가 바뀌어도 맞는다.
 *
 * 오른쪽 위에 `○`(설정)와 `↩`(새로고침 + 새 판 확인)가 있다.
 */
class DocListActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: DocStore
    private lateinit var settings: Settings

    private lateinit var root: FrameLayout
    private lateinit var box: View
    private lateinit var title: TextView
    private lateinit var rows: LinearLayout
    private lateinit var number: TextView
    private lateinit var empty: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnRefresh: TextView

    private var docs: List<Doc> = emptyList()
    private var page = 0
    private var perPage = 1
    private var busy = false

    private val popup by lazy { Popup(root) }
    private lateinit var chime: Chime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doclist)
        Eink.applyTheme(this)
        store = DocStore(this)
        settings = Settings(this)

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
        btnSettings = findViewById(R.id.settings)
        btnRefresh = findViewById(R.id.refresh)
        chime = Chime(this, root)

        btnPrev.setOnClickListener { if (page > 0) { page--; render() } }
        btnNext.setOnClickListener { if (page < lastPage()) { page++; render() } }
        btnSettings.setOnClickListener { showSettings() }
        btnRefresh.setOnClickListener { refresh() }

        // 이 화면의 글자는 모두 geist — 가는 이탤릭 Geist Mono.
        // 제목만 기울이지 않은 보통 굵기로.
        title.typeface = Fonts.of(this, Fonts.UI_UPRIGHT)
        title.fontVariationSettings = Fonts.REGULAR
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.includeFontPadding = false
        geist(number, 14f)
        geist(btnSettings, 24f)
        geist(btnRefresh, 24f)
        geist(btnPrev, 24f)
        geist(btnNext, 24f)
        root.post { sizeBox() }
        // ↩ 가 끝에 붙은 뒤에(geist 가 건 post 다음) ○ 를 ↩ 쪽으로 당긴다.
        btnRefresh.post { btnRefresh.post { placeSettingsButton() } }

        // 지난번에 치워 둔 것은 되돌릴 기회가 지났다. 여기서 쓸어 낸다.
        store.purgeAll()
        // 지난번 업데이트로 받은 APK 도 설치가 끝났으면 치운다.
        Updater.cleanup(this, BuildConfig.VERSION_NAME)
        docs = store.loadIndex()

        // 한 쪽에 몇 칸이 들어가는지는 자리를 잡은 뒤에야 안다.
        // 통의 높이는 활용공간이 정해진 **뒤에야** 확정된다. 한 번만 재면
        // 상자가 줄기 전의 큰 값을 잡아 칸이 커지고 아래 줄을 침범한다.
        // 높이가 바뀔 때마다 다시 센다.
        rows.viewTreeObserver.addOnGlobalLayoutListener {
            val h = rows.height
            if (h <= 0 || h == measuredRowsH) return@addOnGlobalLayoutListener
            measuredRowsH = h
            val fit = max(1, h / Ink.dp(this, ROW_DP).toInt())
            rowH = h / fit
            perPage = fit
            render()
            // 처음 켰는데 폴더 링크가 없으면 설정부터 연다.
            if (!askedOnce && settings.folder == null) { askedOnce = true; showSettings() }
        }
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        chime.resume()
        // 읽고 돌아오면 받아 둔 표시가 바뀔 수 있다.
        if (::store.isInitialized && perPage > 0) {
            docs = store.loadIndex()
            render()
        }
    }

    /** 팝업이 떠 있으면 뒤로 가기는 팝업만 닫는다. */
    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onBackPressed() {
        if (popup.isShowing) { popup.dismiss(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
            when (v.id) {
                // ○ 는 ↩ 옆에 서므로 끝에 붙이지 않고 세로만 맞춘다(가로는 placeSettingsButton).
                R.id.settings -> v.post { Glyph.centerVertical(v) }
                // 오른쪽 것들은 활용공간의 오른쪽 끝에, 왼쪽 것은 왼쪽 끝에 세운다.
                else -> v.post { Glyph.alignEdge(v, toStart = v.id == R.id.prev) }
            }
        }
    }

    /**
     * 활용공간 — 폭은 화면의 60%. 세로는 **리더에 맞춘다**: 머리(이름·○·↩)의
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

    /**
     * ○ 를 ↩ 쪽으로 옮긴다 — 두 먹 사이가 제자리일 때의 [SETTINGS_GAP] 이 되도록.
     *
     * 두 단추는 손가락 자리(56dp) 폭으로 나란히 서서, 먹 사이가 멀어 보인다. 먹의 실제
     * 끝을 재어 옮기므로 글꼴·화면 밀도가 달라도 같은 비율이 된다. 단추째 옮기므로 글리프가
     * 잘리지 않는다.
     */
    private fun placeSettingsButton() {
        val a = inkX(btnSettings) ?: return
        val b = inkX(btnRefresh) ?: return
        val gap = b.first - a.second
        if (gap <= 0f) return
        btnSettings.translationX += gap * (1f - SETTINGS_GAP)
    }

    /** 단추 글리프 먹의 가로 범위 — 화면 좌표(옮김 포함) */
    private fun inkX(v: TextView): Pair<Float, Float>? {
        val t = v.text?.toString().orEmpty()
        if (t.isEmpty() || v.width == 0) return null
        val r = android.graphics.Rect()
        v.paint.getTextBounds(t, 0, t.length, r)
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        val start = loc[0] + (v.width - v.paint.measureText(t)) / 2f
        return (start + r.left) to (start + r.right)
    }

    /** 칸 하나의 높이. 놓는 칸 수를 줄여도 이 값은 그대로다 — 간격이 안 변한다. */
    private var rowH = 0
    private var measuredRowsH = 0
    private var askedOnce = false

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
            // 받아 둔 것만 진하게 — 흑백뿐이라 색 대신 농도로 가른다.
            alpha = if (doc.cached) 1f else UNCACHED_ALPHA
        }
        // 누르는 동안 제목 뒤를 ░ 한 줄로 채운다 — 제목보다 1dp 작게.
        LineShade.applyTo(row, title, TITLE_DP - 1f)

        val mark = row.findViewById<TextView>(R.id.mark)
        val del = row.findViewById<TextView>(R.id.del)
        geist(mark, 14f)
        geist(del, 20f)

        if (doc.cached) {
            // 10KB 를 한 단위로 센 정수 — 130KB 는 13, 6,230KB 는 623. 단위는 붙이지 않는다.
            // MB 소수점 한 자리로는 글만 든 문서가 모두 0.0·0.1 로 뭉쳐 가를 수 없었다.
            mark.text = sizeMark(store.bodyBytes(doc.id))
            del.visibility = View.VISIBLE
            del.setOnClickListener {
                if (!store.deleteBody(doc.id)) return@setOnClickListener
                docs = store.loadIndex()
                render()
                popup.show(
                    // 폴더에서 사라진 것은 목록에서도 빠진다. 폴더에 있는 것은 받지 않은 칸으로 남는다.
                    msg = if (doc.gone) "지웠습니다." else "받아 둔 글을 지웠습니다.",
                    name = doc.name,
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

    /** 무게 표시 — 10KB(10,240바이트) 단위로 반올림. 조금이라도 있으면 0 이 아니라 1 이다. */
    private fun sizeMark(bytes: Long): String =
        if (bytes <= 0) "0" else maxOf(1L, (bytes + SIZE_UNIT / 2) / SIZE_UNIT).toString()

    /** 문서를 연다. 받아 둔 것이 있으면 망 없이도 열리고, 없으면 받아서 연다. */
    private fun open(doc: Doc) {
        if (store.hasBody(doc.id)) {
            startActivity(Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_ID, doc.id)
                putExtra(ReaderActivity.EXTRA_NAME, doc.name)
            })
            return
        }
        if (busy) return
        if (!Net.online(this)) { say(Net.OFFLINE); return }
        var job: kotlinx.coroutines.Job? = null
        val progress = popup.progress("받고 있습니다", name = doc.name) { job?.cancel() }
        job = scope.launch {
            busy = true
            try {
                Library.body(this@DocListActivity, store, doc, progress)
                popup.dismiss()
                docs = store.loadIndex()
                render()
                open(doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(Net.explain(this@DocListActivity, e, "글을 받지"))
            } finally {
                busy = false
            }
        }
    }

    // ── 설정 ─────────────────────────────────────────────

    /**
     * 설정 화면(○)을 연다([SettingsActivity]). 저장하고 돌아오면 [onActivityResult] 가 목록을
     * 다시 앉히고 폴더를 읽는다 — 링크가 바뀌었으면 설정 화면이 이미 이전 폴더의 것을 지웠다.
     */
    private fun showSettings() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
    }

    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SETTINGS) {
            if (resultCode == RESULT_OK) {
                docs = store.loadIndex()
                page = 0
                render()
                if (settings.folder != null) refresh()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ── 새로고침 ─────────────────────────────────────────

    /**
     * ↩ — 폴더를 다시 읽어 목록을 맞추고([Library.sync]), 앱의 새 판도 본다.
     * 목록이 실패했으면 그 까닭을 알리는 팝업을 덮지 않도록 새 판은 보지 않는다.
     */
    private fun refresh() {
        if (busy || updating?.isActive == true) return
        val folder = settings.folder ?: run { showSettings(); return }
        if (!Net.online(this)) { say(Net.OFFLINE); return }
        scope.launch {
            busy = true
            render()
            popup.progress("폴더를 읽고 있습니다") { }
            try {
                Library.sync(this@DocListActivity, store, folder)
                popup.dismiss()
                docs = store.loadIndex()
                page = 0
                checkUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(Net.explain(this@DocListActivity, e, "폴더를 읽지"))
            } finally {
                busy = false
                render()
            }
        }
    }

    /** 새 판을 받는 중인 일. 받는 동안에는 새로고침을 막는다 — 팝업이 덮인다. */
    private var updating: kotlinx.coroutines.Job? = null

    /**
     * GitHub 릴리스에 새 판이 있으면 설치할지 묻는다. 없거나 알 수 없으면 조용히 넘어간다.
     * 왼쪽 단추 자리에 Install, 닫기는 늘 오른쪽.
     */
    private fun checkUpdate() {
        if (!Net.online(this)) return
        scope.launch {
            val r = Updater.check(BuildConfig.VERSION_NAME) ?: return@launch
            if (isFinishing || busy) return@launch
            popup.show(
                msg = "새 버전 ${r.version}${subjectParticle(r.version)} 있습니다.\n지금 설치할까요?",
                undoLabel = INSTALL,
                onUndo = { startUpdate(r) },
                ms = UPDATE_MS,
            )
        }
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
                msg = "설치하려면 이 앱에 설치 권한이 필요합니다.\n켜고 돌아와 ↩ 를 다시 눌러 주세요.",
                undoLabel = SETTINGS,
                onUndo = { Updater.openInstallSettings(this) },
                ms = UPDATE_MS,
            )
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

    private fun say(msg: String) = popup.show(msg)

    private companion object {
        /** ○ 와 ↩ 사이(먹과 먹 사이)를 기본 자리의 이만큼으로 좁힌다 */
        const val SETTINGS_GAP = 0.6f
        /** 목록 제목 글자 크기 */
        const val TITLE_DP = 14f

        /** 무게 표시 한 단위 — 10KB */
        const val SIZE_UNIT = 10_240L

        /** 받지 않은 칸 제목의 옅기 */
        const val UNCACHED_ALPHA = 0.55f

        /** 업데이트 팝업의 왼쪽 단추 */
        const val INSTALL = "Install"
        const val SETTINGS = "Settings"

        /** 업데이트를 묻는 팝업이 머무는 시간 — 읽고 누를 틈 */
        const val UPDATE_MS = 10_000L

        /** 누를 수 없는 화살표의 옅기 */
        const val DIM = 0.5f

        /** 칸 높이 — 손가락 자리(56dp)의 80% */
        const val ROW_DP = Ink.TOUCH_DP * 0.8f

        /** 설정 화면을 연 요청 */
        const val REQ_SETTINGS = 1
    }

    private fun showStatus() {
        val msg = when {
            // 목록이 있으면 받는 중인 것은 팝업이 알린다. 칸 위에 겹쳐 쓰지 않는다.
            busy && docs.isEmpty() -> "폴더를 읽고 있습니다…"
            settings.folder == null -> "○ 를 눌러 구글 드라이브 폴더 링크를 넣어 주세요."
            docs.isEmpty() -> "↩ 를 눌러 폴더의 문서를 받아오세요.\npdf · docx · txt · epub · md · srt · 구글 문서"
            else -> null
        }
        empty.text = msg.orEmpty()
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
