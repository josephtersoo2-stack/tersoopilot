package com.multibrowser.antidetect.automation.perception

class TargetResolver {

    /**
     * Resolves a TargetSpec against the current DomSnapshot using strict production hierarchy:
     * 1. Stable DOM Identifier (ID / CSS selector match)
     * 2. Accessibility Label (Aria-label match)
     * 3. Visible Text Snippet Match
     * 4. Semantic Role / HTML Tag Match
     * 5. Coordinate Fallback (Geometry containment / nearest point)
     */
    fun resolve(spec: TargetSpec, snapshot: DomSnapshot): ResolvedTarget? {
        val candidates = snapshot.elements.filter { it.enabled }

        // 1. Stable DOM Identifier / Selector Match (Highest Stability)
        if (!spec.selector.isNullOrBlank()) {
            val cleanSelector = spec.selector.removePrefix("#").lowercase()
            val matched = candidates.firstOrNull {
                it.id.lowercase() == cleanSelector ||
                it.id.equals(spec.selector, ignoreCase = true)
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 2. Accessibility Label Substring Match (High Stability across i18n & layout)
        if (!spec.ariaLabel.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.ariaLabel?.contains(spec.ariaLabel, ignoreCase = true) == true
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 3. Visible Text Snippet Match
        if (!spec.textSnippet.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.text?.contains(spec.textSnippet, ignoreCase = true) == true
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 4. Semantic Role / HTML Tag Match
        if (!spec.role.isNullOrBlank()) {
            val matched = candidates.firstOrNull {
                it.role.equals(spec.role, ignoreCase = true) || 
                it.tag.equals(spec.role, ignoreCase = true)
            }
            if (matched != null) {
                return ResolvedTarget(matched, isInsideViewport = matched.visible)
            }
        }

        // 5. Coordinate Fallback (Matches element containing point or closest center)
        if (spec.coordinateX != null && spec.coordinateY != null) {
            val cx = spec.coordinateX
            val cy = spec.coordinateY
            // Check direct containment
            val containing = candidates.firstOrNull {
                cx >= it.rect.left && cx <= (it.rect.left + it.rect.width) &&
                cy >= it.rect.top && cy <= (it.rect.top + it.rect.height)
            }
            if (containing != null) {
                return ResolvedTarget(containing, isInsideViewport = containing.visible)
            }

            // Fallback to nearest element by Euclidean distance
            val nearest = candidates.minByOrNull {
                val elemCenterX = it.rect.left + it.rect.width / 2f
                val elemCenterY = it.rect.top + it.rect.height / 2f
                val dx = cx - elemCenterX
                val dy = cy - elemCenterY
                dx * dx + dy * dy
            }
            if (nearest != null) {
                return ResolvedTarget(nearest, isInsideViewport = nearest.visible)
            }
        }

        return null
    }
}
