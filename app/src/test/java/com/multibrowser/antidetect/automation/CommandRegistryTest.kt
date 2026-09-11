package com.multibrowser.antidetect.automation

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CommandRegistryTest {

    @Test
    fun testValidDagPassesValidation() {
        val dag = JsonObject().apply {
            val states = JsonObject().apply {
                add("start", JsonObject().apply {
                    addProperty("command", "NAVIGATE")
                })
                add("search", JsonObject().apply {
                    addProperty("command", "YT_TAP_SEARCH_BAR")
                })
                add("watch", JsonObject().apply {
                    addProperty("command", "WAIT_PLAYBACK")
                })
                add("done", JsonObject().apply {
                    addProperty("command", "COMPLETE")
                })
            }
            add("states", states)
        }

        val result = CommandRegistry.validateDag(dag)
        assertTrue("Valid DAG with recognized commands must pass validation",
            result is CommandRegistry.ValidationResult.Valid)
    }

    @Test
    fun testUnsupportedCommandFailsFast() {
        val dag = JsonObject().apply {
            val states = JsonObject().apply {
                add("valid_step", JsonObject().apply {
                    addProperty("command", "NAVIGATE")
                })
                add("malicious_step", JsonObject().apply {
                    addProperty("command", "FORMAT_HARD_DRIVE")
                })
            }
            add("states", states)
        }

        val result = CommandRegistry.validateDag(dag)
        assertTrue("DAG with unsupported command must be rejected immediately",
            result is CommandRegistry.ValidationResult.Invalid)

        val invalid = result as CommandRegistry.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("FORMAT_HARD_DRIVE"))
    }

    @Test
    fun testNullOrEmptyDagRejected() {
        val nullResult = CommandRegistry.validateDag(null)
        assertTrue(nullResult is CommandRegistry.ValidationResult.Invalid)

        val emptyDag = JsonObject()
        val emptyResult = CommandRegistry.validateDag(emptyDag)
        assertTrue(emptyResult is CommandRegistry.ValidationResult.Invalid)

        val emptyStatesDag = JsonObject().apply {
            add("states", JsonObject())
        }
        val emptyStatesResult = CommandRegistry.validateDag(emptyStatesDag)
        assertTrue(emptyStatesResult is CommandRegistry.ValidationResult.Invalid)
    }

    @Test
    fun testMissingCommandPropertyRejected() {
        val dag = JsonObject().apply {
            val states = JsonObject().apply {
                add("broken_step", JsonObject().apply {
                    addProperty("params", "some_param")
                })
            }
            add("states", states)
        }

        val result = CommandRegistry.validateDag(dag)
        assertTrue(result is CommandRegistry.ValidationResult.Invalid)
        assertTrue((result as CommandRegistry.ValidationResult.Invalid).reason.contains("missing mandatory 'command'"))
    }

    @Test
    fun testAllExpectedCommandsPresentInRegistry() {
        val expected = listOf(
            "NAVIGATE", "WAIT", "GROUNDED_CLICK", "CLICK",
            "YT_TAP_SEARCH_BAR", "YT_SUBMIT_SEARCH", "SUBMIT_INPUT",
            "YT_CLICK_VIDEO_CARD", "YT_ORGANIC_TARGET_SEARCH",
            "YT_LIKE_VIDEO", "YT_SUBSCRIBE_CHANNEL",
            "YT_DISMISS_PRE_ROLL_AD", "DISMISS_POPUP",
            "TYPE_TEXT", "BÉZIER_SWIPE", "YT_SHORTS_SWIPE",
            "WAIT_PLAYBACK", "TIER2_FALLBACK", "TERMINATE", "COMPLETE"
        )

        for (cmd in expected) {
            assertTrue("Command $cmd must be present in SUPPORTED_COMMANDS",
                CommandRegistry.SUPPORTED_COMMANDS.contains(cmd))
        }
    }
}
