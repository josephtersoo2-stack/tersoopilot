package com.multibrowser.antidetect.automation

import com.multibrowser.antidetect.automation.perception.*
import org.junit.Assert.*
import org.junit.Test

class TargetResolverPriorityTest {

    private val resolver = TargetResolver()

    private val sampleSnapshot = DomSnapshot(
        url = "https://example.com/app",
        title = "Test Page",
        pageState = "PAGE_READY",
        viewport = ViewportInfo(width = 400f, height = 800f, dpr = 2f, scrollX = 0f, scrollY = 0f),
        elements = listOf(
            ElementSnapshot(
                id = "elem_id_match",
                role = "button",
                tag = "button",
                text = "Click Me",
                ariaLabel = "Accessible Submit",
                visible = true,
                enabled = true,
                rect = DomRect(left = 10f, top = 10f, width = 100f, height = 50f)
            ),
            ElementSnapshot(
                id = "elem_aria_match",
                role = "button",
                tag = "button",
                text = "Different Text",
                ariaLabel = "Special Action",
                visible = true,
                enabled = true,
                rect = DomRect(left = 10f, top = 70f, width = 100f, height = 50f)
            ),
            ElementSnapshot(
                id = "elem_text_match",
                role = "link",
                tag = "a",
                text = "Target Link Text",
                ariaLabel = "Unrelated Aria",
                visible = true,
                enabled = true,
                rect = DomRect(left = 10f, top = 130f, width = 100f, height = 50f)
            ),
            ElementSnapshot(
                id = "elem_role_match",
                role = "slider",
                tag = "input",
                text = "Volume",
                ariaLabel = "Volume Slider",
                visible = true,
                enabled = true,
                rect = DomRect(left = 10f, top = 190f, width = 200f, height = 30f)
            ),
            ElementSnapshot(
                id = "elem_coord_target",
                role = "generic",
                tag = "div",
                text = "Bottom Box",
                ariaLabel = "Box Area",
                visible = true,
                enabled = true,
                rect = DomRect(left = 50f, top = 500f, width = 150f, height = 150f)
            )
        ),
        videoState = null
    )

    @Test
    fun testTier1SelectorTakesPrecedenceOverAriaAndText() {
        // Spec has selector matching elem_id_match, but ariaLabel matching elem_aria_match
        val spec = TargetSpec(
            selector = "#elem_id_match",
            ariaLabel = "Special Action",
            textSnippet = "Target Link Text"
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("Selector must win over lower priority tiers", "elem_id_match", resolved!!.element.id)
    }

    @Test
    fun testTier2AriaLabelTakesPrecedenceOverTextAndRole() {
        // Spec has ariaLabel matching elem_aria_match, and text matching elem_text_match
        val spec = TargetSpec(
            ariaLabel = "Special Action",
            textSnippet = "Target Link Text",
            role = "slider"
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("AriaLabel must win over text and role", "elem_aria_match", resolved!!.element.id)
    }

    @Test
    fun testTier3TextSnippetTakesPrecedenceOverRoleAndCoordinates() {
        // Spec has text matching elem_text_match, role matching elem_role_match, and coordinates inside elem_coord_target
        val spec = TargetSpec(
            textSnippet = "Target Link Text",
            role = "slider",
            coordinateX = 100f,
            coordinateY = 550f
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("Text snippet must win over role and coordinate fallback", "elem_text_match", resolved!!.element.id)
    }

    @Test
    fun testTier4RoleTakesPrecedenceOverCoordinates() {
        // Spec has role matching elem_role_match, and coordinates inside elem_coord_target
        val spec = TargetSpec(
            role = "slider",
            coordinateX = 100f,
            coordinateY = 550f
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("Role match must win over coordinate fallback", "elem_role_match", resolved!!.element.id)
    }

    @Test
    fun testTier5CoordinateDirectContainment() {
        // Spec has only coordinates inside elem_coord_target (left=50, top=500, width=150, height=150)
        val spec = TargetSpec(
            coordinateX = 100f,
            coordinateY = 550f
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("elem_coord_target", resolved!!.element.id)
    }

    @Test
    fun testTier5CoordinateNearestDistanceFallback() {
        // Spec has coordinate close to elem_id_match (center ~ 60, 35), say (55, 30)
        val spec = TargetSpec(
            coordinateX = 55f,
            coordinateY = 30f
        )
        val resolved = resolver.resolve(spec, sampleSnapshot)

        assertNotNull(resolved)
        assertEquals("elem_id_match", resolved!!.element.id)
    }
}
