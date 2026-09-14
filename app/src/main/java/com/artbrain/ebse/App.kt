package com.artbrain.ebse

import android.app.Application
import android.graphics.Typeface
import com.artbrain.ebse.ui.Fonts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // PdfBox 가 글꼴·인코딩 표를 APK 의 assets 에서 찾도록 알려 준다.
        PDFBoxResourceLoader.init(this)
    }

    /**
     * 본문 글꼴 — 에이투지체 Regular. 저장소에는 넣지 않는다(CLAUDE.md 의 글꼴).
     *
     * APK 에 넣어 두었으므로 기기 파일에 기대지 않는다.
     */
    val bodyFont: Typeface by lazy { Fonts.of(this, Fonts.BODY) }
}
