package com.chill.familyvlog

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeHeapAndroidTest {
    @Test
    fun targetAppUsesExpectedLargeHeapTier() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = targetContext.getSystemService(ActivityManager::class.java)

        assertTrue(
            targetContext.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0,
        )
        assertEquals(512, activityManager.largeMemoryClass)
    }
}
