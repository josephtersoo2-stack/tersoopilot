import random
from typing import Dict, Any, List
from devices.models import SavedProfile
from .base import BaseWorkflowAddon
from ..services.youtube_analyzer import extract_youtube_video_id


class YouTubeSearchDiscoverAddon(BaseWorkflowAddon):
    @property
    def platform(self) -> str:
        return "YOUTUBE"

    @property
    def workflow_id(self) -> str:
        return "youtube_search_discover"

    @property
    def name(self) -> str:
        return "Search & Discover (Rank Booster)"

    @property
    def description(self) -> str:
        return "Authentically searches keywords on YouTube, scans results for the exact target video URL, and performs organic watch, like, subscribe, and contextual commenting."

    @property
    def icon(self) -> str:
        return "Search"

    def get_default_config(self) -> Dict[str, Any]:
        return {
            "strategy": "search_and_target",
            "targets": [],  # List of target dicts: [{video_url, keywords, min_watch, max_watch, comments}]
            "target_video_url": "",
            "target_keywords": [],
            "target_channel": "",
            "target_video_title": "",
            "min_watch_seconds": 90,
            "max_watch_seconds": 240,
            "min_search_scroll_depth": 2,
            "max_search_scroll_depth": 8,
            "enable_like": True,
            "like_probability": 0.85,
            "enable_subscribe": True,
            "subscribe_probability": 0.40,
            "enable_comments": False,
            "generated_comments": [],
            "enable_ad_skip": True,
            "enable_scrubbing": True
        }

    def validate_config(self, config: Dict[str, Any]) -> None:
        targets = config.get("targets", [])
        if not targets and not config.get("target_video_url"):
            raise ValueError("At least one target video URL must be specified.")
        min_w = int(config.get("min_watch_seconds", 90))
        max_w = int(config.get("max_watch_seconds", 240))
        if min_w > max_w:
            raise ValueError("Minimum watch duration cannot exceed maximum watch duration.")

    def compile_dag(self, task, profile: SavedProfile, config: Dict[str, Any]) -> Dict[str, Any]:
        from ..compiler import DAGBuilder, ProfileContextResolver

        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="yt_nav_home")

        targets = config.get("targets", [])
        if not targets:
            targets = [{
                "video_url": config.get("target_video_url", ""),
                "keywords": config.get("target_keywords", []),
                "title": config.get("target_video_title", ""),
                "channel": config.get("target_channel", ""),
                "min_watch_seconds": config.get("min_watch_seconds", 90),
                "max_watch_seconds": config.get("max_watch_seconds", 240),
                "generated_comments": config.get("generated_comments", [])
            }]

        min_scroll = int(config.get("min_search_scroll_depth", 2))
        max_scroll = int(config.get("max_search_scroll_depth", 8))
        like_prob = float(config.get("like_probability", 0.85))
        sub_prob = float(config.get("subscribe_probability", 0.40))
        enable_comments = bool(config.get("enable_comments", False))
        enable_ad_skip = bool(config.get("enable_ad_skip", True))
        enable_scrubbing = bool(config.get("enable_scrubbing", True))

        # Initial home navigation
        prev_next_state = "task_complete"

        # Build chain for each video target in reverse order
        for idx in reversed(range(len(targets))):
            t = targets[idx]
            target_url = t.get("video_url", "")
            target_id = extract_youtube_video_id(target_url) or ""
            target_title = t.get("title", "")
            target_channel = t.get("channel", "")

            keywords = t.get("keywords", [])
            if isinstance(keywords, str):
                keywords = [k.strip() for k in keywords.splitlines() if k.strip()]
            if not keywords:
                keywords = [target_title or "trending videos"]
            primary_query = random.choice(keywords)

            t_min_watch = int(t.get("min_watch_seconds", config.get("min_watch_seconds", 90)))
            t_max_watch = int(t.get("max_watch_seconds", config.get("max_watch_seconds", 240)))
            actual_watch = random.randint(min(t_min_watch, t_max_watch), max(t_min_watch, t_max_watch))

            prefix = f"target_{idx}"
            next_target_entry = prev_next_state

            # Engagement tail for this target
            last_eng_node = next_target_entry

            # 4. Optional Contextual Commenting
            t_comments = t.get("generated_comments", []) or config.get("generated_comments", [])
            if enable_comments and t_comments:
                selected_comment = random.choice(t_comments)
                comment_node = f"{prefix}_post_comment"
                builder.add_node(
                    node_id=comment_node,
                    command="YT_POST_COMMENT",
                    params={
                        "comment_text": selected_comment,
                        "wpm": persona.typing_wpm,
                        "typo_probability": persona.typo_probability
                    },
                    on_success=last_eng_node
                )
                last_eng_node = comment_node

            # 3. Optional Subscribe
            if random.random() < sub_prob:
                sub_node = f"{prefix}_sub_channel"
                builder.add_node(
                    node_id=sub_node,
                    command="YT_SUBSCRIBE_CHANNEL",
                    params={"target_channel": target_channel},
                    on_success=last_eng_node
                )
                last_eng_node = sub_node

            # 2. Optional Like
            if random.random() < like_prob:
                like_node = f"{prefix}_like_video"
                builder.add_node(
                    node_id=like_node,
                    command="YT_LIKE_VIDEO",
                    params={},
                    on_success=last_eng_node
                )
                last_eng_node = like_node

            # 1. Optional Micro-Scrubbing
            if enable_scrubbing and random.random() < 0.5:
                scrub_node = f"{prefix}_scrub"
                builder.add_node(
                    node_id=scrub_node,
                    command="YT_SCRUB_TIMELINE",
                    params={"rewind_seconds": random.choice([10, 15, 25])},
                    on_success=last_eng_node
                )
                last_eng_node = scrub_node

            # 0. Optional Organic Description Inspection
            if random.random() < 0.65:
                desc_node = f"{prefix}_expand_desc"
                builder.add_node(
                    node_id=desc_node,
                    command="YT_EXPAND_DESCRIPTION",
                    params={},
                    on_success=last_eng_node
                )
                last_eng_node = desc_node

            # Playback monitoring node
            monitor_node = f"{prefix}_monitor_playback"
            builder.add_node(
                monitor_node,
                "WAIT_PLAYBACK",
                {"duration_seconds": actual_watch},
                last_eng_node,
                extra_transitions={
                    "AD_ACTIVE": f"{prefix}_wait_ad_skip",
                    "POPUP_PROMPT": f"{prefix}_dismiss_popup"
                }
            )
            builder.add_node(f"{prefix}_wait_ad_skip", "YT_DISMISS_PRE_ROLL_AD", {"max_wait_seconds": 15, "skip_enabled": enable_ad_skip}, monitor_node, monitor_node)
            builder.add_node(f"{prefix}_dismiss_popup", "DISMISS_POPUP", {"target": "generic"}, monitor_node, monitor_node)

            # Search results: type keyword and submit search (no scrolling or clicking)
            search_node = f"{prefix}_search_target"
            builder.add_node(
                search_node,
                "YT_ORGANIC_TARGET_SEARCH",
                {
                    "target_video_id": target_id,
                    "target_video_url": target_url,
                    "target_title": target_title,
                    "target_channel": target_channel,
                    "candidate_keywords": keywords,
                    "wpm": persona.typing_wpm,
                    "typo_probability": persona.typo_probability
                },
                f"{prefix}_scroll_to_target"
            )

            # Scroll to target video in search results (no clicking)
            scroll_node = f"{prefix}_scroll_to_target"
            builder.add_node(
                scroll_node,
                "YT_SCROLL_TO_TARGET",
                {
                    "target_video_id": target_id,
                    "target_video_url": target_url,
                    "target_title": target_title,
                    "scroll_past_and_return": True,
                    "max_scroll_batches": max_scroll,
                    "min_scroll_depth": min_scroll
                },
                monitor_node
            )

            # The search_node handles keyword search only; scroll_node finds/centers the target;
            # WAIT_PLAYBACK handles the actual video click and watch.
            prev_next_state = search_node

        # Initial home navigation transitioning to first target
        builder.add_node("yt_nav_home", "NAVIGATE", {"url": "https://m.youtube.com"}, "yt_wait_home", extra_transitions={"CONSENT_WALL": "yt_handle_consent"})
        builder.add_node("yt_handle_consent", "DISMISS_POPUP", {"target": "consent"}, "yt_wait_home")
        builder.add_node("yt_wait_home", "WAIT", {"seconds": random.randint(2, 4)}, prev_next_state)

        # Terminal & Exit nodes
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Video search and playback stalled"}, "task_complete", "exit")
        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 10}, "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")

        return builder.build()
