package com.nutomic.syncthingandroid.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRouteTest {

    @Test
    fun `every route name used in intents maps to its screen`() {
        val expected =
            mapOf(
                "Root" to SettingsRoute.Root,
                "RunConditions" to SettingsRoute.RunConditions,
                "UserInterface" to SettingsRoute.UserInterface,
                "Behavior" to SettingsRoute.Behavior,
                "SyncthingOptions" to SettingsRoute.SyncthingOptions,
                "CustomCertificate" to SettingsRoute.CustomCertificate,
                "ImportExport" to SettingsRoute.ImportExport,
                "Troubleshooting" to SettingsRoute.Troubleshooting,
                "Experimental" to SettingsRoute.Experimental,
                "About" to SettingsRoute.About,
                "Licenses" to SettingsRoute.Licenses,
            )
        expected.forEach { (name, route) ->
            assertEquals(name, route, SettingsRoute.fromString(name))
        }
    }

    @Test
    fun `unknown or missing names fall back to the root screen`() {
        assertEquals(SettingsRoute.Root, SettingsRoute.fromString(null))
        assertEquals(SettingsRoute.Root, SettingsRoute.fromString(""))
        assertEquals(SettingsRoute.Root, SettingsRoute.fromString("about"))
    }
}
