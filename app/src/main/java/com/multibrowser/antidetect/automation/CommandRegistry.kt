package com.multibrowser.antidetect.automation

import com.google.gson.JsonObject

/**
 * Command registry and static DAG pre-validator.
 * Fails invalid or unsupported workflows before claiming jobs or beginning execution cycles.
 */
object CommandRegistry {

    val SUPPORTED_COMMANDS: Set<String> = setOf(
        "NAVIGATE",
        "WAIT",
        "GROUNDED_CLICK",
        "CLICK",
        "DYNAMIC_ACTION",
        "ADDON_STEP",
        "EXECUTE_SCRIPT",
        "EVALUATE_JAVASCRIPT",
        "CUSTOM_ACTION",
        "DYNAMIC_TAP",
        "DYNAMIC_SWIPE",
        "YT_TAP_SEARCH_BAR",
        "YT_SUBMIT_SEARCH",
        "SUBMIT_INPUT",
        "YT_CLICK_VIDEO_CARD",
        "YT_ORGANIC_TARGET_SEARCH",
        "YT_SEARCH_AND_DISCOVER",
        "SEARCH_TARGET_VIDEO",
        "VIDEO_SEARCH",
        "YT_SCROLL_TO_TARGET",
        "SCROLL_TO_TARGET",
        "SCROLL_TARGET_VIDEO",
        "SCROLL_TO_TARGET_VIDEO",
        "SCROLL_VIDEO",
        "ORGANIC_SCROLL",
        "YT_EXPAND_DESCRIPTION",
        "YT_LIKE_VIDEO",
        "YT_DISLIKE_VIDEO",
        "YT_SHARE_VIDEO",
        "YT_SUBSCRIBE_CHANNEL",
        "YT_DISMISS_PRE_ROLL_AD",
        "DISMISS_POPUP",
        "TYPE_TEXT",
        "BÉZIER_SWIPE",
        "YT_SHORTS_SWIPE",
        "WAIT_PLAYBACK",
        "YT_SCRUB_TIMELINE",
        "YT_SCROLL_TO_COMMENTS",
        "YT_DWELL_ON_COMMENTS",
        "YT_POST_COMMENT",
        "YT_CLICK_UP_NEXT",
        "CLICK_LINK",
        "CLICK_ELEMENT",
        "SPATIAL_ANCHOR_CLICK",
        "TYPE_AND_ENTER",
        "CLICK_AD_IFRAME",
        "AI_TYPE",
        "GO_TO_URL",
        "PAGE_DWELL",
        "CLICK_INTERNAL_LINK",
        "SUBMIT_FORM",
        "SWIPE_VERTICAL",
        "TIER2_FALLBACK",
        "TERMINATE",
        "COMPLETE"
    )

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Validates the structure and command compatibility of an incoming workflow DAG.
     */
    fun validateDag(dag: JsonObject?): ValidationResult {
        if (dag == null) {
            return ValidationResult.Invalid("DAG object is null")
        }

        val states = dag.getAsJsonObject("states")
            ?: return ValidationResult.Invalid("Missing 'states' dictionary in workflow DAG")

        if (states.size() == 0) {
            return ValidationResult.Invalid("Workflow DAG 'states' dictionary is empty")
        }

        for ((stateId, nodeElement) in states.entrySet()) {
            if (!nodeElement.isJsonObject) {
                return ValidationResult.Invalid("State '$stateId' is not a valid JSON object")
            }
            val node = nodeElement.asJsonObject
            val command = node.get("command")?.asString
                ?: return ValidationResult.Invalid("State '$stateId' is missing mandatory 'command' field")

            val isKnownOrDynamic = SUPPORTED_COMMANDS.contains(command) ||
                command.startsWith("ADDON_") ||
                command.startsWith("DYNAMIC_") ||
                command.startsWith("CUSTOM_") ||
                node.has("script") ||
                node.has("js") ||
                node.has("addon_id")

            if (!isKnownOrDynamic) {
                return ValidationResult.Invalid("State '$stateId' specifies unsupported command: '$command'")
            }
        }

        return ValidationResult.Valid
    }
}
