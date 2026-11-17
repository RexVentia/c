package com.robot.simulator

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robot.simulator.ui.MapSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on the isolated publicqa build; never erase a user's saved configuration. */
@RunWith(AndroidJUnit4::class)
class MapSettingsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireIsolatedPackage() {
        check(context.packageName == "com.robot.simulator.publicqa")
        clearTestPreferences()
    }

    @After
    fun cleanup() {
        if (context.packageName == "com.robot.simulator.publicqa") clearTestPreferences()
    }

    private fun clearTestPreferences() {
        for (name in listOf("map-settings", "amap-consent")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun publicBuildDoesNotEmbedMapKey() {
        val value = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA,
        ).metaData?.getString("com.amap.api.v2.apikey").orEmpty()
        assertEquals("", value)
        assertEquals("", MapSettings.apiKey(context))
    }

    @Test
    fun personalKeyIsTrimmedAndSavedLocally() {
        MapSettings.saveApiKey(context, "  local-qa-placeholder  ")
        assertEquals("local-qa-placeholder", MapSettings.apiKey(context))
        val independentContext = context.createPackageContext(context.packageName, 0)
        assertEquals("local-qa-placeholder", MapSettings.apiKey(independentContext))
    }

    @Test
    fun mapSdkIsNotInitializedBeforeConsent() {
        MapSettings.saveApiKey(context, "local-qa-placeholder")
        assertFalse(MapSettings.initialize(context))
    }

    @Test
    fun signingFingerprintHasTwentyBytes() {
        assertTrue(MapSettings.certificateSha1(context).matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){19}")))
    }
}
