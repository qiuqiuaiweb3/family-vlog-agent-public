package com.chill.familyvlog

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LauncherIconResourcesTest {
    @Test
    fun familyMarkKeepsItsEstablishedTechnicalGeometry() {
        val image = readPng("src/main/res/drawable-nodpi/family_mark.png")

        assertEquals(1024, image.width)
        assertEquals(768, image.height)
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(4, image.colorModel.numComponents)
        assertEquals(AlphaBounds(173, 100, 861, 679), image.alphaBounds())
    }

    @Test
    fun launcherForegroundIsRgbaSquareAndStaysInsideTheAdaptiveSafeZone() {
        val image = readPng("src/main/res/drawable-nodpi/ic_launcher_foreground.png")
        val bounds = image.alphaBounds()

        assertEquals(1080, image.width)
        assertEquals(1080, image.height)
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(4, image.colorModel.numComponents)
        assertEquals(AlphaBounds(220, 270, 860, 809), bounds)
        assertTrue(bounds.width in 480..660)
        assertTrue(bounds.height in 480..660)
        assertTrue(bounds.left >= 210)
        assertTrue(bounds.top >= 210)
        assertTrue(bounds.right <= 870)
        assertTrue(bounds.bottom <= 870)
    }

    @Test
    fun regularAndRoundAdaptiveIconsUseTheApprovedThreeLayers() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(
                appDirectory.resolve("src/main/res/mipmap-anydpi-v26/$name"),
            )
            val root = document.documentElement

            assertEquals("adaptive-icon", root.localName)
            assertEquals("@color/tui_background", root.layerDrawable("background"))
            assertEquals("@drawable/ic_launcher_foreground", root.layerDrawable("foreground"))
            assertEquals("@drawable/ic_launcher_foreground", root.layerDrawable("monochrome"))
        }
    }

    private fun readPng(relativePath: String): BufferedImage {
        val image = ImageIO.read(appDirectory.resolve(relativePath))
        assertNotNull("PNG must decode: $relativePath", image)
        return image
    }

    private fun BufferedImage.alphaBounds(): AlphaBounds {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((getRGB(x, y) ushr 24) != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        assertTrue("image must contain non-transparent pixels", right > left && bottom > top)
        return AlphaBounds(left, top, right, bottom)
    }

    private fun Element.layerDrawable(tagName: String): String {
        val nodes = getElementsByTagName(tagName)
        assertEquals("adaptive icon must contain exactly one $tagName layer", 1, nodes.length)
        return (nodes.item(0) as Element).getAttributeNS(ANDROID_NAMESPACE, "drawable")
    }

    private val appDirectory: File by lazy {
        generateSequence(
            File(LauncherIconResourcesTest::class.java.protectionDomain!!.codeSource.location.toURI()),
        ) { it.parentFile }
            .first { it.name == "build" }
            .parentFile!!
    }

    private data class AlphaBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
