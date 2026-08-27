package com.chill.familyvlog.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdkDependencyContractTest {
    @Test
    fun `production pins only ADK core 0_8_0 and has no direct GenAI or Firebase ADK adapter`() {
        val root = projectRoot()
        val catalog = root.resolve("gradle/libs.versions.toml").readText()
        val appBuild = root.resolve("app/build.gradle.kts").readText()
        val productionSources = root.resolve("app/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

        assertEquals(1, Regex("""(?m)^adk = "0\.8\.0"$""").findAll(catalog).count())
        assertTrue(
            catalog.contains(
                """adk-core = { module = "com.google.adk:google-adk-kotlin-core", version.ref = "adk" }""",
            ),
        )
        assertEquals(1, Regex("implementation\\(libs\\.adk\\.core\\)").findAll(appBuild).count())
        assertFalse(catalog.contains("google-adk-kotlin-firebase"))
        assertFalse(appBuild.contains("google-adk-kotlin-firebase"))
        assertFalse(productionSources.contains("com.google.genai"))
        assertFalse(productionSources.contains("com.google.adk.kt.firebase"))
        assertFalse(productionSources.contains("Genkit"))
        assertFalse(productionSources.contains("Antigravity"))
    }

    private fun projectRoot(): File = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }.first { it.resolve("settings.gradle.kts").isFile }
}
