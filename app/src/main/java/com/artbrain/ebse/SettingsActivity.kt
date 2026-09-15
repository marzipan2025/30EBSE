package com.artbrain.ebse

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebse.net.PublicDrive
import com.artbrain.ebse.store.DocStore
import com.artbrain.ebse.store.Settings
import com.artbrain.ebse.ui.Chime
import com.artbrain.ebse.ui.Eink
import com.artbrain.ebse.ui.Fonts
import com.artbrain.ebse.ui.Glyph
import com.artbrain.ebse.ui.Ink
import com.artbrain.ebse.ui.Popup
import com.artbrain.ebse.ui.Shade

/**
 * 설정 화면 — 드라이브 공유 폴더 링크 하나를 넣는다.
 *
 * 머리 줄은 리더와 같다: 왼쪽 위 `↰`(나가기), 가운데 `Settings`(문장 번호와 같은 꼴),
 * 오른쪽 위 `Save`. 입력칸 위에 `Google Drive URL` 레이블, 입력칸은 네모 없이 글 상자 폭(화면
 * 60%)만큼의 아랫선이고 두 줄까지 감긴다. 비었을 때는 흐린 안내 글. 맨 아래에 저작권·라이선스.
 *
 * - `↰` 나 뒤로 가기는 **저장하지 않고** 나간다.
 * - `Save` 는 링크를 저장하고 목록으로 돌아간다. 목록이 폴더를 다시 읽는다.
 *   **링크가 바뀌면 이전 폴더의 것은 모두 지운다** — 목록·받아 둔 글·사진·읽던 자리. 그래서
 *   바뀌었으면 먼저 되묻는다(왼쪽 `Revert` 는 원래 링크로 되돌리고 머문다, 오른쪽 `Save`).
 *   같은 링크면 지우지 않고 다시 읽기만 한다. 알아볼 수 없는 링크면 머문 채로 알린다.
 */
class SettingsActivity : Activity() {

    private val popup by lazy { Popup(root) }
    private lateinit var chime: Chime
    private lateinit var settings: Settings

    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var save: TextView
    private lateinit var label: TextView
    private lateinit var link: EditText
    private lateinit var footer: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Eink.applyTheme(this)
        settings = Settings(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        root = findViewById(R.id.root)
        title = findViewById(R.id.title)
        back = findViewById(R.id.back)
        save = findViewById(R.id.save)
        label = findViewById(R.id.label)
        link = findViewById(R.id.link)
        footer = findViewById(R.id.footer)
        chime = Chime(this, root)

        // 제목은 리더의 문장 번호와 같은 꼴 — 가는 이탤릭 Geist Mono, 옅기 0.8(레이아웃).
        title.typeface = Fonts.of(this, Fonts.UI)
        title.fontVariationSettings = Fonts.THIN
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, TITLE_DP)
        title.post { Glyph.centerVertical(title) }

        // ↰ 는 리더의 ↰ 와 같다. Save 는 팝업 단추와 같은 꼴(보통 굵기 — 검게 보이게).
        back.typeface = Fonts.of(this, Fonts.UI)
        back.fontVariationSettings = Fonts.THIN
        back.setTextSize(TypedValue.COMPLEX_UNIT_DIP, GLYPH_DP)
        save.typeface = Fonts.of(this, Fonts.UI)
        save.fontVariationSettings = Fonts.REGULAR
        save.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SAVE_DP)
        for (b in listOf(back, save)) {
            b.includeFontPadding = false
            b.gravity = Gravity.CENTER
            Shade.applyTo(b)
            // 먹의 바깥 끝을 글 상자(화면 폭 60%)의 끝에 세운다 — 목록·리더와 같은 폭.
            b.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                Glyph.alignEdge(v as TextView, toStart = v === back)
            }
        }
        back.setOnClickListener { leave() }
        save.setOnClickListener { save() }

        // 레이블 — 입력칸과 같은 꼴, 한 단 옅게.
        label.typeface = Fonts.of(this, Fonts.UI_UPRIGHT)
        label.fontVariationSettings = Fonts.REGULAR
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, INPUT_DP)

        link.setText(settings.folderLink)
        link.typeface = Fonts.of(this, Fonts.UI_UPRIGHT)
        link.fontVariationSettings = Fonts.REGULAR
        link.setTextSize(TypedValue.COMPLEX_UNIT_DIP, INPUT_DP)
        // 링크는 길다 — 여러 줄 입력으로 두고 두 줄까지 감아 보인다. 넘치면 칸 안에서 밀린다.
        // 줄바꿈 글자는 받지 않는다 — Enter 는 저장이다(아래). 칸은 여러 줄이지만 키보드에는
        // 여러 줄 표시를 뺀 종류를 알려 '줄바꿈' 대신 '완료' 가 뜨게 한다.
        link.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        link.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        link.setHorizontallyScrolling(false)
        link.minLines = 1
        link.maxLines = INPUT_MAX_LINES
        link.filters = arrayOf(android.text.InputFilter { src, s, e, _, _, _ ->
            val t = src.subSequence(s, e)
            if (t.contains('\n')) t.toString().replace("\n", "") else null
        })
        link.imeOptions = EditorInfo.IME_ACTION_DONE
        // 글과 아랫선 사이만 조금 띄운다. 좌우는 붙여 선이 글 상자 폭과 같다.
        link.setPadding(0, 0, 0, Ink.dp(this, INPUT_GAP_DP).toInt())
        // 화면 키보드의 완료와 실제 Enter 둘 다 저장. Enter 는 **뗄 때** 저장한다 — 누를 때 나가면
        // 뒤따르는 떼기가 목록의 머리 단추(○)로 가서 설정이 다시 열린다(팝업 시절 실측).
        link.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER) {
                if (ev.action == KeyEvent.ACTION_UP) save()
                true
            } else false
        }
        link.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }

        footer.text = FOOTER.format(BuildConfig.VERSION_NAME)
        footer.typeface = Fonts.of(this, Fonts.BODY)
        footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, FOOTER_DP)
        footer.setLineSpacing(Ink.dp(this, 2f), 1f)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        chime.resume()
    }

    override fun onPause() {
        super.onPause()
        chime.pause()
    }

    /** 팝업이 떠 있으면 뒤로 가기는 팝업만 닫는다. 아니면 저장하지 않고 나간다. */
    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onBackPressed() {
        if (popup.isShowing) { popup.dismiss(); return }
        leave()
    }

    /**
     * 자리를 화면 비율로 잡는다 — 머리 줄 가운데는 위에서 [Ink.EDGE_Y], 폭은 [Ink.BOX_FRACTION].
     * 입력칸은 머리 줄 바로 아래(목록의 첫 칸 자리), 저작권은 발 줄([Ink.EDGE_Y] 아래)에 바닥을 붙인다.
     */
    private fun place() {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        val side = ((w - w * Ink.BOX_FRACTION) / 2).toInt()
        val boxW = w - side * 2
        val lineY = (h * Ink.EDGE_Y).toInt()
        val touch = Ink.dp(this, Ink.TOUCH_DP).toInt()

        for (b in listOf(back, save)) setLp(b) {
            topMargin = lineY - touch / 2
            marginStart = side
            marginEnd = side
        }
        setLp(title) { topMargin = lineY - title.height / 2 }
        val labelTop = lineY + touch / 2 + Ink.dp(this, INPUT_TOP_DP).toInt()
        setLp(label) {
            width = boxW
            topMargin = labelTop
        }
        setLp(link) {
            width = boxW
            topMargin = labelTop + label.height + Ink.dp(this@SettingsActivity, LABEL_GAP_DP).toInt()
        }
        setLp(footer) {
            width = boxW
            topMargin = (h * (1f - Ink.EDGE_Y)).toInt() + touch / 2 - footer.height
        }
    }

    private inline fun setLp(v: android.view.View, block: FrameLayout.LayoutParams.() -> Unit) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val before = FrameLayout.LayoutParams(lp)
        lp.block()
        // 같으면 다시 레이아웃하지 않는다 — 레이아웃 변화마다 불리므로 되풀이를 막는다.
        if (lp.width != before.width || lp.topMargin != before.topMargin ||
            lp.marginStart != before.marginStart || lp.marginEnd != before.marginEnd
        ) v.layoutParams = lp
    }

    private fun save() {
        hideKeyboard()
        val value = link.text.toString().replace("\n", "").trim()
        if (value == settings.folderLink) { done(); return }
        if (value.isNotEmpty() && PublicDrive.parse(value) == null) {
            popup.show("폴더 링크를 알아볼 수 없습니다.\n드라이브에서 복사한 링크를 그대로 넣어 주세요.")
            return
        }
        // 링크가 바뀌면 이전 폴더의 것을 모두 지우므로 먼저 되묻는다.
        popup.ask(
            msg = "연결된 링크값이 변경되었습니다.\n저장하시겠습니까?",
            leftLabel = REVERT,
            onLeft = {
                link.setText(settings.folderLink)
                link.setSelection(link.text.length)
            },
            rightLabel = SAVE,
            onRight = {
                DocStore(this).wipe()
                settings.folderLink = value
                done()
            },
        )
    }

    /** 저장했다 — 목록이 다시 읽도록 알리고 나간다. */
    private fun done() {
        setResult(RESULT_OK)
        finish()
    }

    /** 저장하지 않고 나간다. */
    private fun leave() {
        hideKeyboard()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(link.windowToken, 0)
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        /** 제목 — 리더의 문장 번호와 같은 크기 */
        const val TITLE_DP = 14f

        /** ↰ — 리더의 ↰ 와 같은 크기 */
        const val GLYPH_DP = 32f

        /** Save — 팝업 단추와 같은 크기 */
        const val SAVE_DP = 16f

        const val INPUT_DP = 12f
        const val INPUT_MAX_LINES = 2
        const val LABEL_GAP_DP = 8f

        /** 링크가 바뀌었을 때 되묻는 팝업의 단추 */
        const val REVERT = "Revert"
        const val SAVE = "Save"

        /** 머리 줄 아래에서 입력칸까지, 글과 아랫선 사이 */
        const val INPUT_TOP_DP = 24f
        const val INPUT_GAP_DP = 6f

        /** 저작권 글 크기 — 팝업 시절(10dp)보다 2dp 작게 */
        const val FOOTER_DP = 8f

        const val FOOTER = "30EBSE %s · © artbrain\n" +
            "에이투지체 · Geist Mono — SIL Open Font License 1.1\n" +
            "PdfBox-Android · OkHttp · AndroidX — Apache License 2.0\n" +
            "juniversalchardet — Mozilla Public License 1.1\n" +
            "github.com/marzipan2025/30EBSE"
    }
}
