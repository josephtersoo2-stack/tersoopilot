import random
from typing import Dict, Any
from devices.models import SavedProfile
from .base import BaseWorkflowAddon


class WebsiteTrafficAddon(BaseWorkflowAddon):
    @property
    def platform(self) -> str:
        return "WEBSITE"

    @property
    def workflow_id(self) -> str:
        return "website_organic_traffic"

    @property
    def name(self) -> str:
        return "Organic Google Search & Dwell"

    @property
    def description(self) -> str:
        return "Navigates to Google, searches targeted keywords, locates target website in search results, dwells for realistic reading time, and navigates internal links to reduce bounce rate."

    @property
    def icon(self) -> str:
        return "Globe"

    def get_default_config(self) -> Dict[str, Any]:
        return {
            "strategy": "organic_search_traffic",
            "target_url": "",
            "target_domain": "",
            "search_keywords": [],
            "dwell_time_seconds": 120,
            "max_search_pages": 3,
            "internal_pages_to_visit": 2
        }

    def validate_config(self, config: Dict[str, Any]) -> None:
        if not config.get("target_url") and not config.get("target_domain"):
            raise ValueError("Target website URL or domain is required.")

    def compile_dag(self, task, profile: SavedProfile, config: Dict[str, Any]) -> Dict[str, Any]:
        from ..compiler import DAGBuilder, ProfileContextResolver

        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="google_nav")

        target_url = config.get("target_url", "")
        keywords = config.get("search_keywords", [])
        if isinstance(keywords, str):
            keywords = [k.strip() for k in keywords.splitlines() if k.strip()]
        search_query = random.choice(keywords) if keywords else target_url
        dwell_time = int(config.get("dwell_time_seconds", 120))

        builder.add_node("google_nav", "NAVIGATE", {"url": "https://www.google.com"}, "google_wait")
        builder.add_node("google_wait", "WAIT", {"seconds": random.randint(2, 4)}, "google_type")
        builder.add_node("google_type", "TYPE_TEXT", {"text": search_query, "wpm": persona.typing_wpm}, "google_search")
        builder.add_node("google_search", "SUBMIT_FORM", {}, "google_click_result")
        builder.add_node(
            "google_click_result",
            "CLICK_LINK",
            {"target_url": target_url, "match_domain": True},
            "site_dwell"
        )
        builder.add_node("site_dwell", "PAGE_DWELL", {"duration_seconds": dwell_time, "scroll_reading": True}, "site_internal_click")
        builder.add_node("site_internal_click", "CLICK_INTERNAL_LINK", {}, "task_complete")
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Website navigation or dwell stalled"}, "task_complete", "exit")
        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 8}, "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")

        return builder.build()
