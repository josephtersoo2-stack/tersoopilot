package com.multibrowser.antidetect.automation.perception

class TargetResolver {

    /**
     * Resolves a TargetSpec against the current DomSnapshot.
     * Evaluates candidates based on semantic hierarchy:
     * 1. Aria-Label Match
     * 2. Text Content Match
     * 3. Role / Tag Match
     * 4. Element ID / Selector Match
     */
    fun resolve(spec: TargetSpec, snapshot: DomSnapshot): ResolvedTarget? {
        val candidates = snapshot.elements.filter { it.enabled }

        // 1. Explicit Aria-Label Substring Match
        if (!spec.ariaLabel.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.ariaLabel?.contains(spec.ariaLabel, ignoreCase = true) == true
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 2. Visible Text Snippet Match
        if (!spec.textSnippet.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.text?.contains(spec.textSnippet, ignoreCase = true) == true
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 3. Semantic Role / HTML Tag Match
        if (!spec.role.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.role.equals(spec.role, ignoreCase = true) || 
                it.tag.equals(spec.role, ignoreCase = true)
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 4. Element ID or Selector Match
        if (!spec.selector.isNullOrBlank()) {
            val cleanSelector = spec.selector.removePrefix("#").lowercase()
            val matched = candidates.firstOrNull {
                it.id.lowercase() == cleanSelector
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        return null
    }
}
