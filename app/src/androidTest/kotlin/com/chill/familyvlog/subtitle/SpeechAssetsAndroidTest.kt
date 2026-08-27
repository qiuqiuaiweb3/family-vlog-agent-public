package com.chill.familyvlog.subtitle

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.text.Charsets.UTF_8
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAssetsAndroidTest {
    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledAssetsAreNonEmptyAndNativeSessionConstructsAndReleases() {
        REQUIRED_ASSETS.forEach { path ->
            targetContext.assets.open(path).use { input ->
                assertTrue("Missing or empty asset: $path", input.read() >= 0)
            }
        }

        SherpaSpeechSessionFactory(targetContext.assets).create().close()
        val fontBytes = targetContext.assets.open(SUBTITLE_FONT_ASSET).use { it.readBytes() }
        AssCanvasOverlay(
            assDocument = buildAssSubtitleDocument(
                videoWidth = 720,
                videoHeight = 1_280,
                cues = listOf(BilingualCaptionCue(0, 1_000_000, "测试", "Test")),
            ).toByteArray(UTF_8),
            fontBytes = fontBytes,
            expectedWidth = 720,
            expectedHeight = 1_280,
        ).release()
    }
}

private val REQUIRED_ASSETS = listOf(
    "silero_vad.onnx",
    "$LOCAL_ASR_MODEL_DIRECTORY/model.int8.onnx",
    "$LOCAL_ASR_MODEL_DIRECTORY/tokens.txt",
    "licenses/sherpa-onnx-Apache-2.0.txt",
    "licenses/silero-vad-MIT.txt",
    "licenses/FunASR-Model-License.txt",
    "licenses/onnxruntime-1.27.1-MIT.txt",
    "licenses/onnxruntime-1.27.1-ThirdPartyNotices.txt",
    "licenses/espeak-ng-GPL-3.0-or-later.txt",
    "licenses/piper-phonemize-MIT.txt",
    SUBTITLE_FONT_ASSET,
    "licenses/libass-android-MIT.txt",
    "licenses/libass-ISC.txt",
    "licenses/libunibreak-Zlib.txt",
    "licenses/harfbuzz-Old-MIT.txt",
    "licenses/fribidi-LGPL-2.1-or-later.txt",
    "licenses/freetype-FTL.txt",
    "licenses/fontconfig-COPYING.txt",
    "licenses/expat-MIT.txt",
    "licenses/llvm-libcxx-Apache-2.0-with-LLVM-exception.txt",
    "licenses/NotoSansCJK-SIL-OFL-1.1.txt",
)
