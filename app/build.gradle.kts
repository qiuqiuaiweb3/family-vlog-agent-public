import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.chill.familyvlog"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.chill.familyvlog"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.clear()
            assets.directories.add(rootProject.file("prompts").absolutePath)
            assets.directories.add(rootProject.file(".local-asr/assets").absolutePath)
            assets.directories.add(rootProject.file("third_party/local-asr/assets").absolutePath)
            assets.directories.add(rootProject.file(".local-subtitle/assets").absolutePath)
            assets.directories.add(rootProject.file("third_party/subtitle/assets").absolutePath)
            res.directories.add(rootProject.file(".local-subtitle/res").absolutePath)
        }
    }

    androidResources {
        noCompress += setOf("onnx", "otf")
    }

    packaging {
        resources {
            merges += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
    }
}

val localAsrAssetRoot = rootProject.file(".local-asr/assets")
val modelAssetPaths = setOf(
    "silero_vad.onnx",
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx",
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt",
)
val licenseAssetRoot = rootProject.file("third_party/local-asr/assets")
val licenseAssetPaths = setOf(
    "licenses/sherpa-onnx-Apache-2.0.txt",
    "licenses/silero-vad-MIT.txt",
    "licenses/FunASR-Model-License.txt",
    "licenses/onnxruntime-1.27.1-MIT.txt",
    "licenses/onnxruntime-1.27.1-ThirdPartyNotices.txt",
    "licenses/espeak-ng-GPL-3.0-or-later.txt",
    "licenses/piper-phonemize-MIT.txt",
)
val requiredLocalAsrAssets = modelAssetPaths.map(localAsrAssetRoot::resolve) +
    licenseAssetPaths.map(licenseAssetRoot::resolve)

val subtitleAssetRoot = rootProject.file(".local-subtitle/assets")
val subtitleAssetPaths = setOf("fonts/NotoSansCJKsc-Bold.otf")
val subtitleResourceRoot = rootProject.file(".local-subtitle/res")
val subtitleResourcePaths = setOf("drawable-xxhdpi/google_translate_attribution.png")
val subtitleLicenseAssetRoot = rootProject.file("third_party/subtitle/assets")
val subtitleLicenseAssetPaths = setOf(
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
val requiredSubtitleAssets = subtitleAssetPaths.map(subtitleAssetRoot::resolve) +
    subtitleLicenseAssetPaths.map(subtitleLicenseAssetRoot::resolve) +
    subtitleResourcePaths.map(subtitleResourceRoot::resolve)

fun verifyNoSymbolicLinkInProjectPath(file: File) {
    var current: File? = file
    while (current != null && current != rootProject.projectDir) {
        check(!Files.isSymbolicLink(current.toPath())) {
            "资产路径不得包含符号链接：${current.relativeTo(rootProject.projectDir)}"
        }
        current = current.parentFile
    }
}

val verifyLocalAsrAssets by tasks.registering {
    inputs.files(requiredLocalAsrAssets)
    doLast {
        requiredLocalAsrAssets.forEach { asset ->
            verifyNoSymbolicLinkInProjectPath(asset)
            check(
                asset.isFile &&
                    asset.length() > 0L,
            ) {
                val relative = asset.relativeTo(rootProject.projectDir)
                if (asset.toPath().startsWith(localAsrAssetRoot.toPath())) {
                    "缺少本地字幕模型资产：$relative；请运行 scripts/setup-local-asr.sh"
                } else {
                    "缺少仓库受控的第三方许可资产：$relative"
                }
            }
        }
        listOf(
            localAsrAssetRoot to modelAssetPaths,
            licenseAssetRoot to licenseAssetPaths,
        ).forEach { (root, expectedPaths) ->
            check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
                "本地字幕资产根目录无效或为符号链接：${root.relativeTo(rootProject.projectDir)}"
            }
            root.walkTopDown().filter(File::isDirectory).forEach { directory ->
                check(!Files.isSymbolicLink(directory.toPath())) {
                    "本地字幕资产子目录不得为符号链接：${directory.relativeTo(rootProject.projectDir)}"
                }
            }
            val actualPaths = root.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSet()
            check(actualPaths == expectedPaths) {
                "本地字幕资产目录含有缺失或非白名单文件：${root.relativeTo(rootProject.projectDir)}"
            }
        }
    }
}

val verifySubtitleAssets by tasks.registering {
    inputs.files(requiredSubtitleAssets)
    doLast {
        requiredSubtitleAssets.forEach { asset ->
            verifyNoSymbolicLinkInProjectPath(asset)
            check(asset.isFile && asset.length() > 0L) {
                val relative = asset.relativeTo(rootProject.projectDir)
                if (asset.toPath().startsWith(subtitleAssetRoot.toPath())) {
                    "缺少字幕字体资产：$relative；请运行 scripts/setup-subtitle-assets.sh"
                } else {
                    "缺少仓库受控的字幕第三方许可资产：$relative"
                }
            }
        }
        val fontFile = subtitleAssetRoot.resolve("fonts/NotoSansCJKsc-Bold.otf")
        check(fontFile.length() == 17_002_248L && fontFile.inputStream().use { input ->
            input.readNBytes(4).contentEquals("OTTO".toByteArray())
        }) {
            "Noto Sans CJK SC 2.004 字体制品长度或文件头不符合冻结值"
        }
        val attributionImage = subtitleResourceRoot.resolve(
            "drawable-xxhdpi/google_translate_attribution.png",
        )
        check(attributionImage.length() == 12_770L && attributionImage.inputStream().use { input ->
            input.readNBytes(8).contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
            )
        }) {
            "Google Translate 官方归属图形长度或 PNG 文件头不符合冻结制品"
        }
        listOf(
            subtitleAssetRoot to subtitleAssetPaths,
            subtitleLicenseAssetRoot to subtitleLicenseAssetPaths,
            subtitleResourceRoot to subtitleResourcePaths,
        ).forEach { (root, expectedPaths) ->
            verifyNoSymbolicLinkInProjectPath(root)
            check(root.isDirectory)
            val actualPaths = root.walkTopDown()
                .filter(File::isFile)
                .onEach(::verifyNoSymbolicLinkInProjectPath)
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSet()
            check(actualPaths == expectedPaths) {
                "字幕资产目录含有缺失或非白名单文件：${root.relativeTo(rootProject.projectDir)}"
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyLocalAsrAssets)
    dependsOn(verifySubtitleAssets)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.adk.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.firebase.ai)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.transformer)
    implementation(libs.sherpa.onnx)
    implementation(libs.mlkit.translate)
    implementation(libs.ass.kt)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
