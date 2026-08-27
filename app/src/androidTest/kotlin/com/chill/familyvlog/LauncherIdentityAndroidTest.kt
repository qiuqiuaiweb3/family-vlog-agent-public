package com.chill.familyvlog

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIdentityAndroidTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedApplicationUsesLauncherLabelAndAdaptiveIconLayers() {
        val packageManager = targetContext.packageManager
        val applicationInfo = packageManager.getApplicationInfo(
            targetContext.packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )

        assertEquals(R.string.launcher_name, applicationInfo.labelRes)
        assertEquals("family-vlog", applicationInfo.loadLabel(packageManager).toString())
        assertEquals("ic_launcher", targetContext.resources.getResourceEntryName(applicationInfo.icon))
        assertAdaptiveIcon(applicationInfo.icon)

        val roundIcon = targetContext.resources.getIdentifier(
            "ic_launcher_round",
            "mipmap",
            targetContext.packageName,
        )
        assertNotEquals(0, roundIcon)
        assertAdaptiveIcon(roundIcon)
    }

    @Test
    fun launcherNameIsExactInEnglishAndChineseWhileAppNameStaysUnchanged() {
        assertEquals("family-vlog", localizedString("en", R.string.launcher_name))
        assertEquals("family-vlog", localizedString("zh-CN", R.string.launcher_name))
        assertEquals("Family Vlog", localizedString("en", R.string.app_name))
        assertEquals("家庭 Vlog", localizedString("zh-CN", R.string.app_name))
    }

    private fun assertAdaptiveIcon(resourceId: Int) {
        val drawable = targetContext.getDrawable(resourceId)
        assertTrue(drawable is AdaptiveIconDrawable)
        drawable as AdaptiveIconDrawable
        assertNotNull(drawable.background)
        assertNotNull(drawable.foreground)
        assertNotNull(drawable.monochrome)
    }

    private fun localizedString(languageTag: String, resourceId: Int): String {
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        return targetContext.createConfigurationContext(configuration).getString(resourceId)
    }
}
