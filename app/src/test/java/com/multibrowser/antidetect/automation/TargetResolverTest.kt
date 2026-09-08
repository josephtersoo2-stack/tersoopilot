package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.automation.perception.*
import org.junit.Assert.*
import org.junit.Test

class TargetResolverTest {

    private val resolver = TargetResolver()

    private val sampleSnapshot = DomSnapshot(
        url = "https://m.youtube.com",
        title = "YouTube",
        pageState = "PAGE_READY",
        viewport = ViewportInfo(width = 412f, height = 915f, dpr = 2.625f, scrollX = 0f, scrollY = 0f),
        elements = listOf(
            ElementSnapshot(
                id = "search_btn",
                role = "button",
                tag = "button",
                text = "Search",
                ariaLabel = "Search YouTube",
                visible = true,
                enabled = true,
                rect = DomRect(left = 320f, top = 12f, width = 48f, height = 48f)
            ),
            ElementSnapshot(
                id = "video_card_1",
                role = "link",
                tag = "a",
                text = "Mechanical Keyboard Review 2026",
                ariaLabel = "Watch Mechanical Keyboard Review 2026",
                visible = false, // Rendered below viewport
                enabled = true,
                rect = DomRect(left = 16f, top = 980f, width = 380f, height = 210f)
            )
        ),
        videoState = null
    )

    @Test
    fun testResolveByAriaLabel() {
        val spec = TargetSpec(ariaLabel = "Search YouTube")
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("search_btn", resolved!!.element.id)
        assertTrue(resolved.isInsideViewport)
    }

    @Test
    fun testResolveByTextSnippet() {
        val spec = TargetSpec(textSnippet = "Mechanical Keyboard")
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("video_card_1", resolved!!.element.id)
        assertFalse(resolved.isInsideViewport)
    }

    @Test
    fun testMissingElementReturnsNull() {
        val spec = TargetSpec(textSnippet = "Nonexistent Element")
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNull(resolved)
    }
}
