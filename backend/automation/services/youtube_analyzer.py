import re
import os
import json
import logging
import urllib.request
import urllib.parse
from typing import Optional, Dict, Any, List

logger = logging.getLogger(__name__)


def extract_youtube_video_id(url: str) -> Optional[str]:
    """
    Extracts the 11-character YouTube video ID from various URL formats.
    """
    if not url:
        return None
    url = url.strip()
    patterns = [
        r"(?:v=|\/v\/|embed\/|shorts\/|youtu\.be\/|\/e\/|watch\?v=|\&v=)([a-zA-Z0-9_-]{11})",
        r"^([a-zA-Z0-9_-]{11})$"
    ]
    for pattern in patterns:
        match = re.search(pattern, url)
        if match:
            return match.group(1)
    return None


def parse_iso8601_duration(duration_str: str) -> int:
    """
    Converts ISO 8601 duration string (e.g. PT12M45S, PT1H2M30S) into total seconds.
    """
    if not duration_str:
        return 180  # Default 3 mins fallback
    match = re.match(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", duration_str)
    if not match:
        return 180
    hours = int(match.group(1) or 0)
    minutes = int(match.group(2) or 0)
    seconds = int(match.group(3) or 0)
    return hours * 3600 + minutes * 60 + seconds


def format_duration(seconds: int) -> str:
    """
    Formats total seconds into MM:SS or HH:MM:SS.
    """
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    secs = seconds % 60
    if hours > 0:
        return f"{hours:02d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


class YouTubeAnalyzerService:
    """
    Zero-external-dependency service to inspect YouTube video metadata
    and use AI to generate high-ranking search keywords and natural comments.
    """

    @classmethod
    def fetch_video_metadata(cls, video_url: str) -> Dict[str, Any]:
        video_id = extract_youtube_video_id(video_url)
        if not video_id:
            raise ValueError(f"Invalid or unparseable YouTube URL: '{video_url}'")

        canonical_url = f"https://www.youtube.com/watch?v={video_id}"
        title = f"YouTube Video {video_id}"
        channel_name = "YouTube Creator"
        thumbnail_url = f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"
        duration_seconds = 240
        description = ""

        # 1. Query oEmbed for authoritative title and channel name
        try:
            oembed_url = f"https://www.youtube.com/oembed?url={urllib.parse.quote(canonical_url)}&format=json"
            req = urllib.request.Request(
                oembed_url,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
            )
            with urllib.request.urlopen(req, timeout=6.0) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    title = data.get("title", title)
                    channel_name = data.get("author_name", channel_name)
                    thumbnail_url = data.get("thumbnail_url", thumbnail_url)
        except Exception as e:
            logger.warning("YouTube oEmbed lookup failed for %s: %s", video_id, e)

        # 2. Scrape watch page meta tags and player data for exact duration and description
        try:
            req = urllib.request.Request(
                canonical_url,
                headers={
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    "Accept-Language": "en-US,en;q=0.9"
                }
            )
            with urllib.request.urlopen(req, timeout=12.0) as resp:
                if resp.status == 200:
                    chunks = []
                    total_read = 0
                    # Stream up to 2 MB to capture ytInitialPlayerResponse and meta tags
                    while total_read < 2 * 1024 * 1024:
                        chunk = resp.read(64 * 1024)
                        if not chunk:
                            break
                        chunks.append(chunk)
                        total_read += len(chunk)
                        text_so_far = b"".join(chunks).decode("utf-8", errors="replace")
                        if ('"lengthSeconds":' in text_so_far or 'itemprop="duration"' in text_so_far) and (
                            'name="description"' in text_so_far or '"shortDescription"' in text_so_far
                        ):
                            break
                    html = b"".join(chunks).decode("utf-8", errors="replace")

                    # 1. Primary: lengthSeconds from ytInitialPlayerResponse / videoDetails
                    length_sec_match = re.search(r'"lengthSeconds"\s*:\s*"(\d+)"', html)
                    if length_sec_match:
                        parsed_sec = int(length_sec_match.group(1))
                        if parsed_sec > 0:
                            duration_seconds = parsed_sec

                    # 2. Secondary: approxDurationMs
                    if duration_seconds == 240:
                        dur_ms_match = re.search(r'"approxDurationMs"\s*:\s*"(\d+)"', html)
                        if dur_ms_match:
                            parsed_sec = max(5, int(dur_ms_match.group(1)) // 1000)
                            if parsed_sec > 0:
                                duration_seconds = parsed_sec

                    # 3. Tertiary: itemprop="duration" content="PT...S"
                    if duration_seconds == 240:
                        dur_match = re.search(r'itemprop="duration"[^>]*content="(PT[^"]+)"', html)
                        if dur_match:
                            duration_seconds = parse_iso8601_duration(dur_match.group(1))

                    # Extract description
                    desc_match = re.search(r'name="description"\s+content="([^"]*)"', html)
                    if desc_match:
                        description = desc_match.group(1)
                    elif '"shortDescription":"' in html:
                        desc_parts = html.split('"shortDescription":"', 1)[1]
                        description = desc_parts.split('","isCrawlable"', 1)[0].replace('\\"', '"').replace('\\n', ' ')
        except Exception as e:
            logger.warning("YouTube watch page meta scraping failed for %s: %s", video_id, e)

        return {
            "video_id": video_id,
            "video_url": canonical_url,
            "title": title,
            "channel_name": channel_name,
            "thumbnail_url": thumbnail_url,
            "duration_seconds": duration_seconds,
            "formatted_duration": format_duration(duration_seconds),
            "description": description[:600]
        }

    @classmethod
    def generate_ranking_keywords_and_comments(
        cls,
        title: str,
        channel_name: str,
        duration_seconds: int,
        description: str,
        thumbnail_url: str = "",
        provider_override: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Uses LLM (OpenRouter / Gemini) to analyze stripped video metadata
        (title, channel name, thumbnail URL, description) and generate exactly 3
        high-probability search keywords tailored to rank this video in positions 1-5,
        alongside authentic comments.
        """
        prompt = (
            f"You are a master YouTube SEO strategist and search algorithm ranking engineer.\n"
            f"Analyze this YouTube video based on its stripped metadata:\n"
            f"- Video Title: {title}\n"
            f"- Channel Name: {channel_name}\n"
            f"- Thumbnail Context: {thumbnail_url}\n"
            f"- Duration: {format_duration(duration_seconds)} ({duration_seconds}s)\n"
            f"- Description: {description}\n\n"
            f"Generate two things:\n"
            f"1. Exactly 3 distinct search keywords/phrases that authentic viewers type to find this video, specifically designed to rank this video in position 1 to 5 on YouTube search:\n"
            f"   - Keyword 1 (Primary): High-intent query that ranks this video at position 1-3.\n"
            f"   - Keyword 2 (Fallback 1): Natural phrase variation ranking this video in position 1-5.\n"
            f"   - Keyword 3 (Fallback 2): Focused niche or creator-branded query ensuring top-5 rank if broader terms face heavy competition.\n"
            f"2. A list of 4 natural, engaging comments that sound like genuine viewers praising or discussing a specific point in the video. NEVER use generic bot comments like 'great video' or 'nice'. Make them sound organic, thoughtful, and human.\n\n"
            f"Respond ONLY with valid JSON in this exact structure without markdown or backticks:\n"
            f"{{\n"
            f'  "ranking_keywords": ["keyword 1", "keyword 2", "keyword 3"],\n'
            f'  "suggested_keywords": ["keyword 1", "keyword 2", "keyword 3"],\n'
            f'  "generated_comments": ["comment 1", "comment 2", "comment 3", "comment 4"]\n'
            f"}}"
        )

        try:
            from devices.models import GlobalSetting, LLMConfig
            from ai_assistant.services import AIConfigService
            global_settings = GlobalSetting.load()
            provider = (provider_override or global_settings.selected_ai_provider or "openrouter").lower().strip()

            api_key = AIConfigService.get_api_key(provider)
            model_name = None
            ai_cfg = AIConfigService.get_active_provider_config(provider)
            if ai_cfg and ai_cfg.model_name:
                model_name = ai_cfg.model_name

            active_llm = LLMConfig.objects.filter(provider=provider, is_active=True).first()
            if active_llm:
                if not api_key and active_llm.api_key.strip():
                    api_key = active_llm.api_key.strip()
                if not model_name and active_llm.model_name.strip():
                    model_name = active_llm.model_name.strip()

            if not model_name:
                model_name = "google/gemini-2.5-flash" if provider == "openrouter" else "gemini-2.5-flash"

            if provider == "openrouter" and not api_key:
                api_key = os.getenv("OPENROUTER_API_KEY")
            elif provider == "gemini" and not api_key:
                api_key = os.getenv("GEMINI_API_KEY")

            raw_response = None
            if api_key and api_key != "your_openrouter_api_key_here":
                try:
                    raw_response = cls._call_llm_api(provider, api_key, model_name, prompt)
                except Exception as ex:
                    logger.warning("Primary provider %s failed (%s), attempting fallback provider...", provider, ex)
                    alt_provider = "gemini" if provider == "openrouter" else "openrouter"
                    alt_key = AIConfigService.get_api_key(alt_provider) or os.getenv(f"{alt_provider.upper()}_API_KEY")
                    if alt_key and alt_key != f"your_{alt_provider}_api_key_here":
                        alt_model = "gemini-2.5-flash" if alt_provider == "gemini" else "google/gemini-2.5-flash"
                        try:
                            raw_response = cls._call_llm_api(alt_provider, alt_key, alt_model, prompt)
                        except Exception as ex2:
                            logger.warning("Fallback provider %s also failed: %s", alt_provider, ex2)

            if raw_response:
                clean_json = raw_response.strip()
                if clean_json.startswith("```"):
                    clean_json = re.sub(r"^```(?:json)?\s*", "", clean_json)
                    clean_json = re.sub(r"\s*```$", "", clean_json)
                data = json.loads(clean_json)
                ranking = [str(k).strip() for k in data.get("ranking_keywords", []) if str(k).strip()]
                suggested = [str(k).strip() for k in data.get("suggested_keywords", []) if str(k).strip()]
                keywords = ranking if ranking else suggested
                comments = [str(c).strip() for c in data.get("generated_comments", []) if str(c).strip()]
                if keywords:
                    return {
                        "ranking_keywords": keywords[:3],
                        "suggested_keywords": keywords,
                        "generated_comments": comments
                    }
        except Exception as e:
            logger.warning("LLM keyword/comment generation failed: %s", e)

        # Smart fallback generation based on video title tokens
        clean_words = [w.strip(",.!?\"'").lower() for w in title.split() if len(w) > 3]
        fallback_keywords = [
            title,
            f"{channel_name} {' '.join(clean_words[:3])}" if clean_words else f"{channel_name} {title}",
            f"{' '.join(clean_words[:4])}" if len(clean_words) >= 4 else f"best {clean_words[0]} review" if clean_words else title
        ]
        fallback_comments = [
            f"Really impressed with the production quality on this, great breakdown!",
            f"This explained everything so clearly. Appreciate the detailed pacing.",
            f"Found this right when I needed it, thanks for sharing this guide!",
            f"Subscribed! Looking forward to the next upload from {channel_name}."
        ]
        return {
            "ranking_keywords": fallback_keywords[:3],
            "suggested_keywords": list(dict.fromkeys(fallback_keywords)),
            "generated_comments": fallback_comments
        }

    @classmethod
    def _call_llm_api(cls, provider: str, api_key: str, model_name: str, prompt: str) -> Optional[str]:
        if provider == "openrouter":
            url = "https://openrouter.ai/api/v1/chat/completions"
            payload = {
                "model": model_name,
                "messages": [
                    {"role": "system", "content": "You are a professional YouTube SEO and engagement engineer. Respond with strict JSON."},
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.7,
                "max_tokens": 2048
            }
            headers = {
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "HTTP-Referer": "http://localhost:8001",
                "X-Title": "Multibrowser GhostPilot Suite"
            }
            req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=20.0) as resp:
                res_data = json.loads(resp.read().decode("utf-8"))
                return res_data["choices"][0]["message"]["content"]
        else:
            # Gemini
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent?key={api_key}"
            payload = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.7,
                    "maxOutputTokens": 2048,
                    "responseMimeType": "application/json"
                }
            }
            headers = {"Content-Type": "application/json"}
            req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
            with urllib.request.urlopen(req, timeout=20.0) as resp:
                res_data = json.loads(resp.read().decode("utf-8"))
                return res_data["candidates"][0]["content"]["parts"][0]["text"]

    @classmethod
    def analyze(cls, video_url: str, provider_override: Optional[str] = None) -> Dict[str, Any]:
        meta = cls.fetch_video_metadata(video_url)
        ai_data = cls.generate_ranking_keywords_and_comments(
            title=meta["title"],
            channel_name=meta["channel_name"],
            duration_seconds=meta["duration_seconds"],
            description=meta["description"],
            thumbnail_url=meta.get("thumbnail_url", ""),
            provider_override=provider_override
        )
        ranking = ai_data.get("ranking_keywords", [])
        if not ranking:
            ranking = ai_data.get("suggested_keywords", [])[:3]
        return {
            **meta,
            "ranking_keywords": ranking[:3],
            "suggested_keywords": ai_data.get("suggested_keywords", []),
            "generated_comments": ai_data.get("generated_comments", [])
        }
