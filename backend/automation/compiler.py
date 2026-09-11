import random
import re
from typing import Dict, Any, List
from devices.models import SavedProfile
from automation.models import AutomationTask, ProfilePersona, Niche, ProfileNicheAffiliation, PlatformCategory


def extract_youtube_video_id(url_or_id: str) -> str:
    """
    Reliably extracts the 11-character YouTube video ID from various URL formats
    or validates raw 11-character IDs.
    """
    if not url_or_id or not isinstance(url_or_id, str):
        return ""
    text = url_or_id.strip()
    # 1. Raw 11-character ID
    if re.fullmatch(r'[a-zA-Z0-9_-]{11}', text):
        return text
    # 2. Match standard URL parameters or path segments
    patterns = [
        r'(?:v=|\/watch\?v=|\/shorts\/|\/embed\/|youtu\.be\/|\/v\/)([a-zA-Z0-9_-]{11})',
        r'[\?&]v=([a-zA-Z0-9_-]{11})'
    ]
    for p in patterns:
        m = re.search(p, text)
        if m:
            return m.group(1)
    return ""


def parse_candidate_keywords(cfg: Dict[str, Any], fallback_niche=None) -> List[str]:
    """
    Parses candidate keywords from array, comma-separated, or newline-separated strings.
    Ensures sequential trial list without merging all keywords into a single string.
    """
    raw_keywords = cfg.get("target_keywords")
    if not raw_keywords:
        raw_single = cfg.get("target_keyword", "")
        if raw_single:
            raw_keywords = raw_single

    keywords: List[str] = []
    if isinstance(raw_keywords, list):
        for item in raw_keywords:
            if isinstance(item, str) and item.strip():
                keywords.append(item.strip())
    elif isinstance(raw_keywords, str):
        if "\n" in raw_keywords:
            parts = raw_keywords.split("\n")
        elif "," in raw_keywords:
            parts = raw_keywords.split(",")
        else:
            parts = [raw_keywords]
        for part in parts:
            p = part.strip()
            if p:
                keywords.append(p)

    if not keywords and fallback_niche and getattr(fallback_niche, "seed_keywords", None):
        keywords = [random.choice(fallback_niche.seed_keywords)]
    if not keywords:
        keywords = ["technology test"]

    return keywords


class DAGBuilder:
    """Helper utility for assembling structured state-machine nodes."""
    def __init__(self, entry_state: str = "start"):
        self.entry_state = entry_state
        self.nodes: Dict[str, Dict[str, Any]] = {}

    def add_node(
        self,
        node_id: str,
        command: str,
        params: Dict[str, Any],
        on_success: str,
        on_failure: str = "failure_fallback",
        extra_transitions: Dict[str, str] = None
    ):
        transitions = {
            "SUCCESS": on_success,
            "FAILURE": on_failure
        }
        if extra_transitions:
            transitions.update(extra_transitions)

        self.nodes[node_id] = {
            "command": command,
            "params": params,
            "transitions": transitions
        }

    def build(self) -> Dict[str, Any]:
        return {
            "entry_state": self.entry_state,
            "states": self.nodes
        }


class ProfileContextResolver:
    """Resolves weighted niches and persona parameters for a profile."""
    @staticmethod
    def resolve_target_niche(profile: SavedProfile, fallback_niche: Niche = None) -> Niche:
        affiliations = list(profile.niche_affiliations.select_related("niche").all())
        if not affiliations:
            return fallback_niche

        # Weighted random selection based on weight_percentage
        total_weight = sum(aff.weight_percentage for aff in affiliations)
        if total_weight <= 0:
            return affiliations[0].niche

        pick = random.uniform(0, total_weight)
        current = 0
        for aff in affiliations:
            current += aff.weight_percentage
            if current >= pick:
                return aff.niche
        return affiliations[0].niche

    @staticmethod
    def resolve_persona(profile: SavedProfile) -> ProfilePersona:
        persona, _ = ProfilePersona.objects.get_or_create(profile=profile)
        return persona


class WarmerCompiler:
    """
    Compiles multi-phase web entropy to build third-party ad tracking cookies,
    Google search footprint, and niche authority visits.
    """
    AUTHORITY_WEB_SEEDS = [
        {"url": "https://www.wikipedia.org", "dwell": (4, 8)},
        {"url": "https://www.bbc.com/news", "dwell": (6, 12)},
        {"url": "https://www.reddit.com/r/technology", "dwell": (8, 15)},
        {"url": "https://www.reuters.com", "dwell": (5, 10)},
        {"url": "https://news.ycombinator.com", "dwell": (4, 7)}
    ]

    GOOGLE_SEARCH_SEEDS = [
        "best tech deals", "today weather forecast", "how does fiber optic work",
        "latest scientific breakthroughs", "healthy breakfast recipes"
    ]

    @classmethod
    def compile(cls, task: AutomationTask, profile: SavedProfile) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        niche = ProfileContextResolver.resolve_target_niche(profile, fallback_niche=task.niche)
        builder = DAGBuilder(entry_state="init_warmer")

        # Step 1: Initial web seed
        builder.add_node(
            node_id="init_warmer",
            command="WAIT",
            params={"seconds": random.randint(2, 4)},
            on_success="phase1_nav"
        )

        # Phase 1: High-Authority Web Browsing (Ad Network Tracker Cookies)
        seed_site = random.choice(cls.AUTHORITY_WEB_SEEDS)
        builder.add_node(
            node_id="phase1_nav",
            command="NAVIGATE",
            params={"url": seed_site["url"]},
            on_success="phase1_dwell",
            extra_transitions={"CONSENT_WALL": "handle_consent_p1"}
        )
        builder.add_node(
            node_id="handle_consent_p1",
            command="DISMISS_POPUP",
            params={"target": "cookie_consent"},
            on_success="phase1_dwell",
            on_failure="phase1_dwell"
        )
        builder.add_node(
            node_id="phase1_dwell",
            command="WAIT",
            params={"seconds": int(random.randint(*seed_site["dwell"]) * persona.patience_index) + 3},
            on_success="phase1_scroll"
        )
        builder.add_node(
            node_id="phase1_scroll",
            command="BÉZIER_SWIPE",
            params={"direction": "DOWN", "duration_ms": random.randint(500, 850)},
            on_success="phase2_google_nav"
        )

        # Phase 2: Google Ecosystem Seeding (Google Search)
        query = random.choice(niche.seed_keywords) if niche and niche.seed_keywords else random.choice(cls.GOOGLE_SEARCH_SEEDS)
        builder.add_node(
            node_id="phase2_google_nav",
            command="NAVIGATE",
            params={"url": "https://www.google.com"},
            on_success="phase2_consent_check",
            extra_transitions={"CONSENT_WALL": "handle_google_consent"}
        )
        builder.add_node(
            node_id="handle_google_consent",
            command="DISMISS_POPUP",
            params={"target": "google_consent"},
            on_success="phase2_search_type",
            on_failure="phase2_search_type"
        )
        builder.add_node(
            node_id="phase2_consent_check",
            command="WAIT",
            params={"seconds": random.randint(2, 4)},
            on_success="phase2_search_type"
        )
        builder.add_node(
            node_id="phase2_search_type",
            command="TYPE_TEXT",
            params={
                "text": query,
                "wpm": persona.typing_wpm,
                "typo_probability": persona.typo_probability
            },
            on_success="phase2_search_submit"
        )
        builder.add_node(
            node_id="phase2_search_submit",
            command="SUBMIT_INPUT",
            params={},
            on_success="phase2_browse_results"
        )
        builder.add_node(
            node_id="phase2_browse_results",
            command="BÉZIER_SWIPE",
            params={"direction": "DOWN", "duration_ms": 700},
            on_success="phase3_niche_nav"
        )

        # Phase 3: Niche Authority Browsing
        niche_url = (niche.seed_websites[0] if niche and niche.seed_websites else "https://www.theverge.com")
        if not niche_url.startswith("http"):
            niche_url = f"https://{niche_url}"

        builder.add_node(
            node_id="phase3_niche_nav",
            command="NAVIGATE",
            params={"url": niche_url},
            on_success="phase3_dwell"
        )
        builder.add_node(
            node_id="phase3_dwell",
            command="WAIT",
            params={"seconds": random.randint(8, 16)},
            on_success="phase3_scroll"
        )
        builder.add_node(
            node_id="phase3_scroll",
            command="BÉZIER_SWIPE",
            params={"direction": "DOWN", "duration_ms": 800},
            on_success="task_complete"
        )

        # Terminal Nodes
        builder.add_node(
            node_id="task_complete",
            command="COMPLETE",
            params={"increment_trust_score": 3, "record_domains": [seed_site["url"], niche_url]},
            on_success="exit"
        )
        builder.add_node(
            node_id="failure_fallback",
            command="TIER2_FALLBACK",
            params={"reason": "Step timed out or unrecoverable error"},
            on_success="task_complete",
            on_failure="exit"
        )
        builder.add_node(
            node_id="exit",
            command="TERMINATE",
            params={},
            on_success="exit"
        )

        return builder.build()


class YouTubeCompiler:
    """
    Modular compiler creating targeted DAGs for Search & Target,
    Shorts Surfing, Rabbit Hole Binging, Channel Exploration, and Direct URL.
    """

    @classmethod
    def compile(cls, task: AutomationTask, profile: SavedProfile) -> Dict[str, Any]:
        cfg = task.config or {}
        strategy = cfg.get("strategy", "SEARCH_TARGET")

        if strategy == "SHORTS_SURF":
            return cls._compile_shorts_surfing(task, profile, cfg)
        elif strategy == "RABBIT_HOLE":
            return cls._compile_rabbit_hole(task, profile, cfg)
        elif strategy == "CHANNEL_BINGE":
            return cls._compile_channel_binge(task, profile, cfg)
        elif strategy == "DIRECT_URL":
            return cls._compile_direct_watch(task, profile, cfg)
        else:
            return cls._compile_search_and_target(task, profile, cfg)

    @classmethod
    def _calculate_watch_duration(cls, cfg: Dict[str, Any], persona) -> int:
        base_min = cfg.get("min_watch_seconds", 90)
        base_max = cfg.get("max_watch_seconds", 240)
        base = random.randint(base_min, base_max)
        patience = getattr(persona, "patience_index", 0.6)
        return int(base * (0.8 + (patience * 0.4)))

    @classmethod
    def _inject_engagement_nodes(cls, builder: DAGBuilder, exit_state: str, cfg: Dict[str, Any], persona) -> str:
        """Injects timeline scrubbing, comment browsing, likes, and subscribes."""
        last_node = exit_state
        patience = getattr(persona, "patience_index", 0.6)
        engagement_rate = getattr(persona, "engagement_rate", 0.15)

        # 1. Timeline Micro-Scrubbing (probabilistic rewind)
        if cfg.get("enable_scrubbing", False) and random.random() < 0.6:
            scrub_node = f"yt_scrub_{random.randint(100, 999)}"
            builder.add_node(
                node_id=scrub_node,
                command="YT_SCRUB_TIMELINE",
                params={"rewind_seconds": random.choice([10, 15, 20])},
                on_success=last_node
            )
            last_node = scrub_node

        # 2. Reading Comments
        if cfg.get("enable_comments", True):
            comments_node = f"yt_comments_{random.randint(100, 999)}"
            dwell_node = f"yt_dwell_comments_{random.randint(100, 999)}"
            builder.add_node(
                node_id=dwell_node,
                command="YT_DWELL_ON_COMMENTS",
                params={"dwell_seconds": int(random.randint(6, 15) * patience)},
                on_success=last_node
            )
            builder.add_node(
                node_id=comments_node,
                command="YT_SCROLL_TO_COMMENTS",
                params={},
                on_success=dwell_node
            )
            last_node = comments_node

        # 3. Probabilistic Like
        like_prob = float(cfg.get("like_probability", engagement_rate))
        if random.random() < like_prob:
            like_node = f"yt_like_{random.randint(100, 999)}"
            builder.add_node(
                node_id=like_node,
                command="YT_LIKE_VIDEO",
                params={},
                on_success=last_node
            )
            last_node = like_node

        # 4. Probabilistic Subscribe
        sub_prob = float(cfg.get("subscribe_probability", engagement_rate * 0.5))
        if random.random() < sub_prob:
            sub_node = f"yt_sub_{random.randint(100, 999)}"
            builder.add_node(
                node_id=sub_node,
                command="YT_SUBSCRIBE_CHANNEL",
                params={},
                on_success=last_node
            )
            last_node = sub_node

        return last_node

    # -----------------------------------------------------------------------
    # Strategy 1: Search & Target Matching
    # -----------------------------------------------------------------------
    @classmethod
    def _compile_search_and_target(cls, task: AutomationTask, profile: SavedProfile, cfg: Dict[str, Any]) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        niche = ProfileContextResolver.resolve_target_niche(profile, fallback_niche=task.niche)
        builder = DAGBuilder(entry_state="yt_nav_home")

        candidate_keywords = parse_candidate_keywords(cfg, niche)
        initial_query = candidate_keywords[0]

        target_url = cfg.get("target_video_url", "") or cfg.get("target_url", "")
        target_video_id = extract_youtube_video_id(target_url)

        watch_duration = cls._calculate_watch_duration(cfg, persona)

        # Video format selector preservation (long_form, shorts, both)
        video_format = cfg.get("video_format", "long_form")
        if isinstance(video_format, str):
            video_format = video_format.lower().strip()
        if video_format not in ("long_form", "shorts", "both"):
            video_format = "long_form"

        max_scroll_depth = int(cfg.get("max_search_scroll_depth", 10))

        # Base navigation and initial query
        builder.add_node("yt_nav_home", "NAVIGATE", {"url": "https://m.youtube.com"}, "yt_wait_home", extra_transitions={"CONSENT_WALL": "yt_handle_consent"})
        builder.add_node("yt_handle_consent", "DISMISS_POPUP", {"target": "consent"}, "yt_wait_home")
        builder.add_node("yt_wait_home", "WAIT", {"seconds": random.randint(2, 4)}, "yt_tap_search")
        builder.add_node("yt_tap_search", "YT_TAP_SEARCH_BAR", {}, "yt_type_query")
        builder.add_node("yt_type_query", "TYPE_TEXT", {"text": initial_query, "wpm": persona.typing_wpm, "typo_probability": persona.typo_probability}, "yt_submit_search")
        builder.add_node("yt_submit_search", "YT_SUBMIT_SEARCH", {}, "yt_select_video")

        # Deep search with multi-keyword fallback and video link/ID targeting
        builder.add_node(
            "yt_select_video",
            "YT_CLICK_VIDEO_CARD",
            {
                "candidate_keywords": candidate_keywords,
                "target_keyword": initial_query,
                "target_video_url": target_url,
                "target_video_id": target_video_id,
                "target_title": cfg.get("target_video_title", ""),
                "target_channel": cfg.get("target_channel", ""),
                "max_scroll_depth": max_scroll_depth,
                "video_format": video_format,
                "wpm": persona.typing_wpm,
                "typo_probability": persona.typo_probability
            },
            "yt_monitor_playback"
        )

        # Build engagement tail
        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 5, "watch_time_recorded": watch_duration}, "exit")
        tail_entry = cls._inject_engagement_nodes(builder, "task_complete", cfg, persona)

        # Playback monitoring node
        builder.add_node(
            "yt_monitor_playback",
            "WAIT_PLAYBACK",
            {"duration_seconds": watch_duration},
            tail_entry,
            extra_transitions={"AD_ACTIVE": "yt_wait_ad_skip", "POPUP_PROMPT": "yt_dismiss_popup"}
        )
        builder.add_node("yt_wait_ad_skip", "YT_DISMISS_PRE_ROLL_AD", {"max_wait_seconds": 15}, "yt_monitor_playback", "yt_monitor_playback")
        builder.add_node("yt_dismiss_popup", "DISMISS_POPUP", {"target": "app_upsell"}, "yt_monitor_playback", "yt_monitor_playback")
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "YouTube flow stalled or node failed"}, "task_complete", "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")

        return builder.build()


    # -----------------------------------------------------------------------
    # Strategy 2: Shorts Infinite Surfing
    # -----------------------------------------------------------------------
    @classmethod
    def _compile_shorts_surfing(cls, task: AutomationTask, profile: SavedProfile, cfg: Dict[str, Any]) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="yt_nav_shorts")

        total_shorts = int(cfg.get("shorts_count", 6))

        builder.add_node("yt_nav_shorts", "NAVIGATE", {"url": "https://m.youtube.com/shorts"}, "yt_wait_first_short")
        builder.add_node("yt_wait_first_short", "WAIT", {"seconds": random.randint(3, 5)}, "short_loop_1")

        for i in range(1, total_shorts + 1):
            short_node = f"short_loop_{i}"
            next_node = f"short_swipe_{i}" if i < total_shorts else "task_complete"

            # Human dwell: some reels are skipped in 3s; engaging reels are watched 15-30s
            is_engaging = random.random() < 0.6
            dwell_seconds = random.randint(15, 32) if is_engaging else random.randint(3, 7)

            builder.add_node(
                short_node,
                "WAIT",
                {"seconds": dwell_seconds},
                next_node
            )

            if i < total_shorts:
                builder.add_node(
                    next_node,
                    "YT_SHORTS_SWIPE",
                    {"duration_ms": random.randint(350, 550)},
                    f"short_loop_{i + 1}"
                )

        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 4, "shorts_surfed": total_shorts}, "exit")
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Shorts flow stalled"}, "task_complete", "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")
        return builder.build()

    # -----------------------------------------------------------------------
    # Strategy 3: Algorithmic Rabbit Hole (Up Next Binge)
    # -----------------------------------------------------------------------
    @classmethod
    def _compile_rabbit_hole(cls, task: AutomationTask, profile: SavedProfile, cfg: Dict[str, Any]) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="yt_nav_home")

        depth = int(cfg.get("rabbit_hole_depth", 3))
        builder.add_node("yt_nav_home", "NAVIGATE", {"url": "https://m.youtube.com"}, "yt_wait_home")
        builder.add_node("yt_wait_home", "WAIT", {"seconds": 3}, "yt_click_initial")
        builder.add_node(
            "yt_click_initial",
            "YT_CLICK_VIDEO_CARD",
            {
                "target_index": random.randint(1, 2),
                "video_format": cfg.get("video_format", "long_form")
            },
            "rabbit_watch_1"
        )

        for d in range(1, depth + 1):
            watch_node = f"rabbit_watch_{d}"
            next_step = f"rabbit_click_next_{d}" if d < depth else "task_complete"
            dwell = int(random.randint(60, 140) * getattr(persona, "patience_index", 0.6))

            builder.add_node(watch_node, "WAIT_PLAYBACK", {"duration_seconds": dwell}, next_step)

            if d < depth:
                builder.add_node(
                    next_step,
                    "YT_CLICK_UP_NEXT",
                    {"target_index": random.randint(1, 2)},
                    f"rabbit_watch_{d + 1}"
                )

        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 6, "rabbit_hole_depth": depth}, "exit")
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Rabbit hole flow stalled"}, "task_complete", "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")
        return builder.build()

    # -----------------------------------------------------------------------
    # Strategy 4: Channel Binge & Sequential Play
    # -----------------------------------------------------------------------
    @classmethod
    def _compile_channel_binge(cls, task: AutomationTask, profile: SavedProfile, cfg: Dict[str, Any]) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="yt_nav_channel")

        channel = cfg.get("target_channel", "@MKBHD").strip()
        channel_url = f"https://m.youtube.com/{channel}/videos" if not channel.startswith("http") else channel

        builder.add_node("yt_nav_channel", "NAVIGATE", {"url": channel_url}, "yt_wait_channel")
        builder.add_node("yt_wait_channel", "WAIT", {"seconds": random.randint(3, 5)}, "yt_select_channel_video")
        builder.add_node(
            "yt_select_channel_video",
            "YT_CLICK_VIDEO_CARD",
            {
                "target_index": random.randint(1, 4),
                "video_format": cfg.get("video_format", "long_form")
            },
            "yt_watch_channel_video"
        )

        watch_duration = cls._calculate_watch_duration(cfg, persona)
        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 5, "binged_channel": channel}, "exit")
        tail_entry = cls._inject_engagement_nodes(builder, "task_complete", cfg, persona)

        builder.add_node("yt_watch_channel_video", "WAIT_PLAYBACK", {"duration_seconds": watch_duration}, tail_entry)
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Channel binge stalled"}, "task_complete", "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")
        return builder.build()

    # -----------------------------------------------------------------------
    # Strategy 5: Direct Target URL Watch
    # -----------------------------------------------------------------------
    @classmethod
    def _compile_direct_watch(cls, task: AutomationTask, profile: SavedProfile, cfg: Dict[str, Any]) -> Dict[str, Any]:
        persona = ProfileContextResolver.resolve_persona(profile)
        builder = DAGBuilder(entry_state="yt_nav_direct")

        target_url = cfg.get("target_url") or "https://m.youtube.com"
        watch_duration = cls._calculate_watch_duration(cfg, persona)

        builder.add_node("yt_nav_direct", "NAVIGATE", {"url": target_url}, "yt_wait_load")
        builder.add_node("yt_wait_load", "WAIT", {"seconds": random.randint(3, 6)}, "yt_watch_direct")

        builder.add_node("task_complete", "COMPLETE", {"increment_trust_score": 4, "direct_url": target_url}, "exit")
        tail_entry = cls._inject_engagement_nodes(builder, "task_complete", cfg, persona)

        builder.add_node("yt_watch_direct", "WAIT_PLAYBACK", {"duration_seconds": watch_duration}, tail_entry)
        builder.add_node("failure_fallback", "TIER2_FALLBACK", {"reason": "Direct watch stalled"}, "task_complete", "exit")
        builder.add_node("exit", "TERMINATE", {}, "exit")
        return builder.build()


class RecipeCompiler:
    """Master factory routing task compilation to specific platforms."""
    @classmethod
    def compile_recipe(cls, task: AutomationTask, profile: SavedProfile) -> Dict[str, Any]:
        category = getattr(task, "category", "")
        if category in [PlatformCategory.WARMING, "WARMING"]:
            dag = WarmerCompiler.compile(task, profile)
        elif category in [PlatformCategory.YOUTUBE, "YOUTUBE"]:
            dag = YouTubeCompiler.compile(task, profile)
        else:
            # Fallback simple navigator
            builder = DAGBuilder(entry_state="nav")
            config = task.config or {}
            builder.add_node(
                node_id="nav",
                command="NAVIGATE",
                params={"url": config.get("url", "https://google.com")},
                on_success="complete"
            )
            builder.add_node(
                node_id="complete",
                command="COMPLETE",
                params={},
                on_success="exit"
            )
            builder.add_node(
                node_id="exit",
                command="TERMINATE",
                params={},
                on_success="exit"
            )
            dag = builder.build()

        from .validator import DAGValidator
        DAGValidator.validate(dag)
        return dag
