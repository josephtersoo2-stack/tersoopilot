import random
from typing import Dict, Any
from devices.models import SavedProfile
from .base import BaseWorkflowAddon


class YouTubeShortsSurferAddon(BaseWorkflowAddon):
    @property
    def platform(self) -> str:
        return "YOUTUBE"

    @property
    def workflow_id(self) -> str:
        return "youtube_shorts_surfing"

    @property
    def name(self) -> str:
        return "Shorts Loop & Surfer"

    @property
    def description(self) -> str:
        return "Authentically browses and surfs YouTube Shorts with human micro-pauses, vertical flicks, replay loops, and probabilistic likes."

    @property
    def icon(self) -> str:
        return "Zap"

    def get_default_config(self) -> Dict[str, Any]:
        return {
            "strategy": "shorts_surfing",
            "shorts_count": 8,
            "like_probability": 0.35,
            "replay_probability": 0.25,
            "min_watch_seconds": 15,
            "max_watch_seconds": 55
        }

    def validate_config(self, config: Dict[str, Any]) -> None:
        count = int(config.get("shorts_count", 8))
        if count < 1 or count > 50:
            raise ValueError("Shorts count must be between 1 and 50.")

    def compile_dag(self, task, profile: SavedProfile, config: Dict[str, Any]) -> Dict[str, Any]:
        from ..compiler import DAGBuilder, ProfileContextResolver

        builder = DAGBuilder(entry_state="yt_nav_shorts")
        shorts_count = int(config.get("shorts_count", 8))
        like_prob = float(config.get("like_probability", 0.35))
        min_w = int(config.get("min_watch_seconds", 15))
        max_w = int(config.get("max_watch_seconds", 55))

        builder.add_node("yt_nav_shorts", "NAVIGATE", {"url": "https://m.youtube.com/shorts"}, "short_0_watch")

        for i in range(shorts_count):
            curr_watch = f"short_{i}_watch"
            curr_like = f"short_{i}_like"
            curr_swipe = f"short_{i}_swipe"
            next_state = f"short_{i+1}_watch" if i < shorts_count - 1 else "task_complete"

            duration = random.randint(min_w, max_w)
            builder.add_node(curr_watch, "WAIT_PLAYBACK", {"duration_seconds": duration}, curr_like)

            # Probabilistic like
            if random.random() < like_prob:
                builder.add_node(curr_like, "YT_LIKE_VIDEO", {}, curr_swipe)
            else:
                builder.add_node(curr_like, "WAIT", {"seconds": 1}, curr_swipe)

            builder.add_node(curr_swipe, "SWIPE_VERTICAL", {"direction": "UP", "duration_ms": random.randint(280, 480)}, next_state)

        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Shorts browsing stalled"}, "task_complete", "exit")
        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 5}, "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")

        return builder.build()
