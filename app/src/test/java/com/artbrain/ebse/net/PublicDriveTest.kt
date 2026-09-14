package com.artbrain.ebse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 사용자가 붙여 넣는 여러 꼴의 폴더 링크를 알아보는지 본다. */
class PublicDriveTest {
    private val id = "1q_lNHqVNGJOJS309PMYSGcmpPkYzMAxF"

    @Test fun shareLink() =
        assertEquals(PublicDrive.Folder(id, null), PublicDrive.parse("https://drive.google.com/drive/folders/$id?usp=sharing"))

    @Test fun accountPathAndResourceKey() =
        assertEquals(PublicDrive.Folder(id, "0-abcDEF"), PublicDrive.parse(" https://drive.google.com/drive/u/1/folders/$id?resourcekey=0-abcDEF&usp=drive_link\n"))

    @Test fun openLinkAndBareId() {
        assertEquals(id, PublicDrive.parse("https://drive.google.com/open?id=$id")?.id)
        assertEquals(id, PublicDrive.parse(id)?.id)
    }

    @Test fun notALink() {
        assertNull(PublicDrive.parse(""))
        assertNull(PublicDrive.parse("https://example.com/hello"))
    }
}
