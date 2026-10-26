package com.inspectionbrowser.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleasesTest {
    private fun release(tag: String, vararg assets: String, draft: Boolean = false) = """
        {"tag_name":"$tag","name":"Build","draft":$draft,"assets":[${assets.joinToString(",") { """{"name":"$it","size":123,"browser_download_url":"https://example.com/$tag/$it"}""" }}]}
    """.trimIndent()

    @Test fun readsTheBuildNumberFromTheTag() {
        assertEquals(123, UpdateReleases.buildOf("v1.0.123"))
        assertEquals(7, UpdateReleases.buildOf("1.0.7"))
        assertNull(UpdateReleases.buildOf("nightly"))
    }

    @Test fun picksTheNewestBuildWithThisAppsApk() {
        val json = "[" + listOf(
            release("v1.0.130", "Other-App.apk"),
            release("v1.0.128", "Inspection-Browser.apk", "Other-App.apk"),
            release("v1.0.131", "Inspection-Browser.apk", draft = true),
            release("v1.0.125", "Inspection-Browser.apk"),
        ).joinToString(",") + "]"
        val app = UpdateReleases.newest(json, "Inspection-Browser.apk", installed = 122)!!
        assertEquals(128, app.build)
        assertEquals("https://example.com/v1.0.128/Inspection-Browser.apk", app.apkUrl)
        assertEquals(130, UpdateReleases.newest(json, "Other-App.apk", installed = 122)!!.build)
        // Nothing newer than what's installed.
        assertNull(UpdateReleases.newest(json, "Inspection-Browser.apk", installed = 128))
        assertNull(UpdateReleases.newest("not json", "Inspection-Browser.apk", installed = 1))
    }
}
