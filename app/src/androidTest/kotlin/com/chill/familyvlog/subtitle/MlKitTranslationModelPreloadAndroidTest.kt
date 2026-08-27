package com.chill.familyvlog.subtitle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTranslationModelPreloadAndroidTest {
    @Test
    fun preloadChineseTranslationModelOnlyWhenExplicitlyEnabled() {
        val arguments = InstrumentationRegistry.getArguments()
        val allowDownload = arguments.getString(ALLOW_MODEL_DOWNLOAD_ARGUMENT) == "true"
        val requirePreloaded = arguments.getString(REQUIRE_PRELOADED_ARGUMENT) == "true"
        if (!allowDownload && !requirePreloaded) return
        require(allowDownload != requirePreloaded)

        runBlocking {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                val translator = MlKitCaptionTranslatorFactory().create()
                try {
                    translator.prepare {
                        check(allowDownload) { "translation_model_not_preloaded" }
                    }
                } finally {
                    translator.close()
                }
            }
        }
    }
}

private const val ALLOW_MODEL_DOWNLOAD_ARGUMENT = "allow_mlkit_model_download"
private const val REQUIRE_PRELOADED_ARGUMENT = "require_mlkit_model_preloaded"
private const val MODEL_DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L
