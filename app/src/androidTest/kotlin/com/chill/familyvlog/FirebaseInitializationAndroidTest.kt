package com.chill.familyvlog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseInitializationAndroidTest {
    @Test
    fun googleServicesResourceInitializesDefaultFirebaseApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hasGoogleAppId = context.resources.getIdentifier(
            "google_app_id",
            "string",
            context.packageName,
        ) != 0
        val hasDefaultFirebaseApp = FirebaseApp.getApps(context)
            .any { it.name == FirebaseApp.DEFAULT_APP_NAME }

        assertTrue(hasGoogleAppId)
        assertTrue(hasDefaultFirebaseApp)
    }
}
