package com.artbrain.ebse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebse.net.Net
import com.artbrain.ebse.store.Library
import com.artbrain.ebse.store.Settings
import com.artbrain.ebse.store.DocStore
import com.artbrain.ebse.ui.Chime
import com.artbrain.ebse.ui.Eink
import com.artbrain.ebse.ui.Fonts
import com.artbrain.ebse.ui.Glyph
import com.artbrain.ebse.ui.Ink
import com.artbrain.ebse.ui.PageView
import com.artbrain.ebse.ui.Popup
import com.artbrain.ebse.ui.Shade
import com.artbrain.ebse.ui.TimelineView
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 한 쪽에 문장 하나를 보여 준다.
 *
 * 화면을 셋으로 나눠 누른다. 쓸어 넘기기는 두지 않았다 — e-ink 에서 손가락을
 * 따라 고쳐 그리면 잔상만 남는다.
 *
 * ```
 * ┌─────────────────────────┐
 * │                         │
 * │   위 60%  ·  조작판 토글   │
 * │                         │
 * ├─────┬───────────────────┤
 * │ 20% │   80%  ·  다음     │
 * │ 이전 │  (아래 40%)        │
 * └─────┴───────────────────┘
 * ```
 *
 * 다음으로 가는 자리를 넓게 둔다. 읽는 동안 아홉 번은 다음이고 한 번이
 * 이전이라, 자주 쓰는 쪽이 넓어야 보지 않고도 누를 수 있다.
 *
 * 조작판은 5초 동안 아무 일이 없으면 스스로 숨는다. 숨을 때 화면을 한 번
 * 크게 고쳐 그리므로, 너무 짧게 두면 깜빡임이 잦아진다.
 */
class ReaderActivity : Activity() {

    private val scope = MainScope()
    private val hand = Handler(Looper.getMainLooper())
    private val popup by lazy { Popup(root) }
    private lateinit var chime: Chime
    private lateinit var store: DocStore

    private lateinit var root: FrameLayout
    private lateinit var pageView: PageView
    private lateinit var number: TextView
    private lateinit var backHint: TextView
    private lateinit var clock: TextView
    private lateinit var toList: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var timeline: TimelineView

    private lateinit var bars: WindowInsetsControllerCompat
    private var docId: String = ""
    private var docName: String = ""

    private val hideUi = Runnable { setUiVisible(false) }
    private val hideHint = Runnable { backHint.visibility = View.INVISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        Eink.applyTheme(this)
        store = DocStore(this)

        // 글만 남기려면 시스템 막대도 함께 물러나야 한다. 조작판과 같이 움직인다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bars = WindowInsetsControllerCompat(window, window.decorView).apply {
            // 쓸어올릴 때만 잠깐 나오게 둔다 — 누르는 것으로 나오면 쪽 넘김을 먹는다.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        docId = intent.getStringExtra(EXTRA_ID).orEmpty()
        docName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        root = findViewById(R.id.root)
        pageView = findViewById(R.id.page)
        number = findViewById(R.id.number)
        backHint = findViewById(R.id.backHint)
        clock = findViewById(R.id.clock)
        toList = findViewById(R.id.toList)
        refreshBtn = findViewById(R.id.refresh)
        timeline = findViewById(R.id.timeline)
        chime = Chime(this, root)

        // 번호는 Geist Mono 의 가는 이탤릭. 가변 폰트라 굵기 축을 100 으로 세운다.
        number.typeface = Fonts.of(this, Fonts.UI)
        number.fontVariationSettings = Fonts.THIN
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, NUMBER_DP)
        // 번호도 먹 자리로 가운데를 잡아야 화살표와 눈으로 줄이 맞는다.
        number.post { Glyph.centerVertical(number) }

        // 이전으로 갈 때 뜨는 표 — 번호와 같은 글자·같은 크기.
        backHint.typeface = Fonts.of(this, Fonts.UI)
        backHint.fontVariationSettings = Fonts.THIN
        // 번호보다 크게 잡는다. 14dp 가는 굵기로는 이 글자의 완만한 오른쪽
        // 곡선이 한 픽셀 아래로 내려가 끊겨 보인다 — 굵기 대신 크기로 푼다.
        backHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, HINT_DP)
        backHint.post { Glyph.centerVertical(backHint) }

        // 시계는 번호와 같은 크기·굵기·색·옅기.
        clock.typeface = Fonts.of(this, Fonts.UI)
        clock.fontVariationSettings = Fonts.THIN
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, NUMBER_DP)
        clock.post { Glyph.centerVertical(clock) }

        // 화살표도 번호와 같은 가는 이탤릭으로. 누르는 자리(박스)는 그대로
        // 두고 글자만 키운다 — 손가락이 닿는 넓이는 지키면서 눈에는 크게.
        val geistItalic = Fonts.of(this, Fonts.UI)
        for (b in listOf(toList, refreshBtn)) {
            b.typeface = geistItalic
            b.fontVariationSettings = Fonts.THIN
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, GLYPH_DP)
            b.includeFontPadding = false
            b.gravity = Gravity.CENTER
            Shade.applyTo(b)
            // 먹의 바깥 끝을 글 상자(화면 폭 60%)의 끝에 세운다 — 목록의 이름과
            // ↩ 가 서는 폭과 같다. 화면이 길쭉한 기기에서도 안쪽으로 몰리지
            // 않는다. 단추는 처음에 GONE 이라 폭이 없으므로 자리가 잡힐 때마다 잰다.
            b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                Glyph.alignEdge(v as TextView, toStart = v === toList)
                // 시각 보정 — ↩ 는 먹이 위로 쏠려 보여 ↰ 보다 떠 보인다.
                if (v === refreshBtn) v.translationY += Ink.dp(this, REFRESH_NUDGE_DP)
            }
        }

        pageView.setFont((application as App).bodyFont)
        pageView.onPaginated = { updateChrome() }

        toList.setOnClickListener { finish() }
        refreshBtn.setOnClickListener { refresh(); keepUiAwake() }
        timeline.onSeek = { p -> pageView.page = p; afterTurn(); keepUiAwake() }

        // 막대를 숨기려고 화면 끝까지 쓰게 해 두었으므로(setDecorFitsSystemWindows
        // = false), 조작판이 상태바 밑으로 들어간다. 막대가 나와 있는 동안에는
        // 그 높이만큼 밀어 준다 — 그러지 않으면 첫 줄인 진행 표시가 가려진다.
        // 자리를 화면 비율로 잡는다 — 번호는 위에서 15%, 타임라인은 92%.
        // 막대를 숨기려고 화면 끝까지 쓰게 해 두었으므로 인셋은 따로 안 민다.
        // 막대를 숨기려고 화면 끝까지 쓰므로, 막대가 나와 있는 동안 위쪽 단추가
        // 상태바에 잘린다. 그만큼 내려 준다.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            barTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            placeByRatio()
            insets
        }
        root.post { placeByRatio() }

        // 왼쪽에서 오른쪽으로 쓸면 이전 문장. 화면 **왼쪽 가장자리**에서 시작한
        // 쓸기는 안드로이드의 뒤로가기 제스처가 먼저 가져가므로, 가장자리에서
        // 조금 떨어진 데서 시작해야 이 앱까지 온다.
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float,
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val far = dx > Ink.dp(this@ReaderActivity, SWIPE_MIN_DP)
                val flat = abs(dx) > abs(dy) * 2
                if (far && flat) { goPrev(); flung = true; return true }
                return false
            }
        })

        root.setOnTouchListener { _, e ->
            swipe.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                // 쓸어 넘긴 뒤 손을 뗀 것은 누름으로 세지 않는다.
                if (!flung) onTap(e.x, e.y)
                flung = false
            }
            true
        }

        val body = store.readBody(docId)
        if (body == null) {
            say("받아 둔 글이 없습니다. 목록에서 다시 열어 주세요.")
            setUiVisible(true)
        } else {
            pageView.setDocument(body, store.loadPos(docId), store.imageDir(docId))
            setUiVisible(false)
        }
        checkRefresh()
    }

    override fun onResume() {
        super.onResume()
        chime.resume()
        connectivity?.registerDefaultNetworkCallback(netWatch)
        tickClock()
    }

    override fun onPause() {
        super.onPause()
        chime.pause()
        runCatching { connectivity?.unregisterNetworkCallback(netWatch) }
        hand.removeCallbacks(tick)
        if (pageView.pageCount > 0) store.savePos(docId, pageView.page)
    }

    override fun onDestroy() {
        hand.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 누른 자리를 가린다. 자리만 본다 — 글이 어디 놓였는지는 따지지 않는다.
     *
     * ```
     * 위 60%            조작판 토글 (떠 있을 때 빈 곳을 누르면 닫힌다)
     * 아래 40% 왼쪽 20%   이전 + 조작판 끄기
     * 아래 40% 나머지     다음 + 조작판 끄기
     * ```
     *
     * 글은 화면 가운데에 놓여 대부분 위 60% 안에 든다. 읽다가 글을 짚으면
     * 조작판이 뜰 뿐 넘어가지 않는다. 조작판의 단추와 띠는 제 누름을 먼저 받는다.
     */
    private fun onTap(x: Float, y: Float) {
        if (y < root.height * TOP_ZONE) { setUiVisible(!uiShown); return }

        // 넘길 때는 조작판을 치운다 — 읽는 자리를 가리지 않게.
        setUiVisible(false)
        if (x < root.width * LEFT_ZONE) {
            goPrev()
        } else {
            if (pageView.next()) afterTurn() else sayLast()
        }
    }

    private var flung = false

    private val tick = Runnable { tickClock() }

    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /** 망이 붙거나 끊기면 다음 분을 기다리지 않고 시계 자리를 고친다. */
    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { hand.post { tickClock() } }
        override fun onLost(network: Network) { hand.post { tickClock() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            hand.post { tickClock() }
        }
    }

    /** 지금 시계 자리가 Offline 꼴인가. 같은 꼴이면 다시 칠하지 않는다. */
    private var clockOffline: Boolean? = null

    /**
     * 시계를 고쳐 쓰고 다음 분이 시작할 때 다시 부른다.
     *
     * 망이 없으면 시각 대신 **검은 네모에 흰 `Offline`** 을 둔다. 받아 둔 글은
     * 읽을 수 있으니 팝업으로 막지 않고, 늘 보이는 자리에 조용히 알린다.
     */
    private fun tickClock() {
        hand.removeCallbacks(tick)
        if (uiShown) return
        val offline = !Net.online(this)
        val now = java.util.Calendar.getInstance()
        val text = if (offline) "Offline" else "%02d:%02d".format(
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
        )
        if (offline != clockOffline || clock.text != text) {
            clock.text = text
            styleClock(offline)
        }
        // 다음 분까지 남은 만큼만 기다린다 — 쓸데없이 깨우지 않는다.
        val wait = 60_000L - (now.get(java.util.Calendar.SECOND) * 1000L +
            now.get(java.util.Calendar.MILLISECOND))
        hand.postDelayed(tick, wait)
    }

    /**
     * 시계 자리의 꼴을 정한다.
     *
     * Offline 은 누름 표시와 같은 셈이다 — 글자 수만큼 `▓` 를 깔고 그 위에 흰
     * `Offline` 을 포갠다([Shade]). 둘 다 같은 붓의 글자라 네모를 따로 재지
     * 않아도 겹친다. 흰 글자는 가는 굵기로는 무늬에 묻혀 사라지므로 굵기를
     * 올리고, 옅기(alpha)도 걷는다.
     */
    private fun styleClock(offline: Boolean) {
        clockOffline = offline
        if (offline) {
            clock.fontVariationSettings = Fonts.REGULAR
            clock.setTextColor(Color.WHITE)
            clock.background = Shade(clock, Shade.DENSE)
            clock.alpha = 1f
        } else {
            clock.fontVariationSettings = Fonts.THIN
            clock.setTextColor(Color.BLACK)
            clock.background = null
            clock.alpha = CLOCK_ALPHA
        }
        clock.post { Glyph.centerVertical(clock) }
    }

    /** 이전 문장으로. 갈 수 있었으면 표를 잠깐 띄운다. */
    private fun goPrev() {
        if (!pageView.prev()) { say("첫 문장입니다."); return }
        afterTurn()
        showBackHint()
    }

    /** 번호 바로 아래에 ↩ 를 잠깐 띄운다 — 뒤로 갔음을 알리는 표. */
    private fun showBackHint() {
        backHint.visibility = View.VISIBLE
        hand.removeCallbacks(hideHint)
        hand.postDelayed(hideHint, HINT_MS)
    }

    private fun afterTurn() {
        store.savePos(docId, pageView.page)
        updateChrome()
    }

    private var barTop = 0


    /** 번호와 타임라인을 화면 비율 자리에, 위쪽 단추를 막대 아래에 놓는다. */
    private fun placeByRatio() {
        val h = root.height
        if (h <= 0) return
        // 단추는 번호와 세로 가운데를 맞춘다. 가로는 글 상자의 양 끝이다
        // (먹의 끝을 맞추는 것은 [Glyph.alignEdge]).
        val boxH = toList.height.takeIf { it > 0 } ?: Ink.dp(this, 56f).toInt()
        val topM = ((h * NUMBER_Y).toInt() - boxH / 2).coerceAtLeast(barTop)
        val side = ((root.width - root.width * Ink.BOX_FRACTION) / 2).toInt()
        for (b in listOf(toList, refreshBtn)) {
            (b.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = topM
                it.marginStart = side
                it.marginEnd = side
                b.layoutParams = it
            }
        }
        (number.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = (h * NUMBER_Y).toInt() - number.height / 2
            number.layoutParams = it
        }
        (backHint.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = (h * NUMBER_Y).toInt() + number.height / 2 +
                Ink.dp(this, 6f).toInt()
            backHint.layoutParams = it
        }
        // 시계는 **아래에서** 번호가 위에서 떨어진 만큼 떨어진다. 글 높이를
        // 따르지 않으므로 문장을 넘겨도 제자리에 있는다.
        (clock.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = (h * (1f - NUMBER_Y)).toInt() - clock.height / 2
            clock.layoutParams = it
        }
        (timeline.layoutParams as FrameLayout.LayoutParams).let {
            it.width = (root.width * TIMELINE_W).toInt()
            it.topMargin = (h * TIMELINE_Y).toInt() - timeline.height / 2
            timeline.layoutParams = it
        }
    }

    private fun updateChrome() {
        val n = pageView.pageCount
        number.text = if (n == 0) "" else "${pageView.page + 1}"
        timeline.set(n, pageView.page)
        // 막대를 숨기려고 화면 끝까지 쓰므로, 막대가 나와 있는 동안 위쪽 단추가
        // 상태바에 잘린다. 그만큼 내려 준다.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            barTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            placeByRatio()
            insets
        }
        root.post { placeByRatio() }
    }

    /** 조작판은 두 아이콘과 타임라인이다. 번호는 여기 들지 않는다 — 늘 떠 있다. */
    private fun setUiVisible(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        toList.visibility = v
        refreshBtn.visibility = if (show && canRefresh) View.VISIBLE else View.GONE
        timeline.visibility = v
        clock.visibility = if (show) View.INVISIBLE else View.VISIBLE
        uiShown = show
        if (!show) tickClock()
        val sysBars = WindowInsetsCompat.Type.systemBars()
        if (show) bars.show(sysBars) else bars.hide(sysBars)
        hand.removeCallbacks(hideUi)
        if (show) {
            updateChrome()
            hand.postDelayed(hideUi, UI_TIMEOUT_MS)
        }
    }

    private var uiShown = false

    /** 조작판을 만지는 동안에는 숨지 않게 시계를 되감는다. */
    private fun keepUiAwake() {
        if (!uiShown) return
        hand.removeCallbacks(hideUi)
        hand.postDelayed(hideUi, UI_TIMEOUT_MS)
    }

    /** 드라이브에서 다시 받을 수 있는가. 폴더에서 사라진 문서([Doc.gone])는 받을 곳이 없다. */
    private var canRefresh = false
    private var busy = false

    private fun checkRefresh() {
        val doc = store.loadIndex().firstOrNull { it.id == docId }
        canRefresh = doc != null && !doc.gone && Settings(this).folder != null
        if (uiShown) refreshBtn.visibility = if (canRefresh) View.VISIBLE else View.GONE
    }

    /** 이 문서를 드라이브에서 다시 받는다. 읽던 자리는 지킨다. */
    private fun refresh() {
        if (busy || docId.isEmpty()) return
        if (!Net.online(this)) { say(Net.OFFLINE); return }
        val doc = store.loadIndex().firstOrNull { it.id == docId } ?: return
        var job: kotlinx.coroutines.Job? = null
        val progress = popup.progress("다시 받고 있습니다") { job?.cancel() }
        job = scope.launch {
            busy = true
            try {
                val text = Library.body(this@ReaderActivity, store, doc, progress)
                pageView.setDocument(text, pageView.page, store.imageDir(docId))
                popup.dismiss()
                say("다시 받았습니다.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(Net.explain(this@ReaderActivity, e, "글을 다시 받지"))
            } finally {
                busy = false
            }
        }
    }

    private fun say(msg: String) = popup.show(msg)

    /**
     * 끝까지 읽었다. 다음으로 갈 데가 목록뿐이므로 왼쪽 단추 자리에 `List` 를
     * 둔다 — 조작판을 열어 `↰` 를 찾지 않아도 나갈 수 있다. 닫기는 늘 오른쪽.
     * 누를 틈을 주려고 되돌리기 팝업만큼 머문다.
     */
    private fun sayLast() = popup.show(
        msg = "마지막 문장입니다.",
        undoLabel = LIST,
        onUndo = { finish() },
        ms = Popup.UNDO_MS,
    )

    companion object {
        /** 새로고침 ↩ 를 내리는 시각 보정 */
        private const val REFRESH_NUDGE_DP = 2f

        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"

        /** 마지막 문장 팝업에서 목록으로 나가는 단추 */
        private const val LIST = "List"
        /** 위에서 이만큼이 조작판 토글 자리 */
        private const val TOP_ZONE = 0.60f

        /** 그 아래에서 왼쪽 이만큼이 이전으로 가는 자리 */
        private const val LEFT_ZONE = 0.20f

        /** 문장 번호가 놓이는 자리 — 화면 위에서 이 비율 */
        private const val NUMBER_Y = Ink.EDGE_Y


        /** 화살표 글리프 크기 */
        private const val GLYPH_DP = 32f

        /** 문장 번호와 뒤로 표의 크기 */
        private const val NUMBER_DP = 14f

        /** 뒤로 표의 크기 */
        private const val HINT_DP = 20f

        /** 뒤로 표가 머무는 동안 */
        private const val HINT_MS = 1_000L

        /** 이 거리 넘게 오른쪽으로 쓸면 이전으로 본다 */
        private const val SWIPE_MIN_DP = 48f

        /** 타임라인이 놓이는 자리와 폭 */
        /** 아래에서 15% 자리 */
        /** 아래에서 18% 자리 */
        private const val TIMELINE_Y = 0.82f
        private const val TIMELINE_W = 0.45f

        private const val UI_TIMEOUT_MS = 5_000L

        /** 시계의 옅기 — 레이아웃의 alpha 와 같다 */
        private const val CLOCK_ALPHA = 0.8f
    }
}
