package com.artbrain.ebse.store

import android.content.Context
import com.artbrain.ebse.net.PublicDrive

/** 설정 — 지금은 드라이브 폴더 링크 하나다. 설정 팝업(○)이 고친다. */
class Settings(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var folderLink: String
        get() = prefs.getString(KEY_LINK, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_LINK, v).apply()

    /** 링크에서 뽑은 폴더. 링크가 없거나 알아볼 수 없으면 null. */
    val folder: PublicDrive.Folder? get() = PublicDrive.parse(folderLink)

    private companion object {
        const val KEY_LINK = "folderLink"
    }
}
