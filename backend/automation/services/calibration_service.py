import os
import json
import base64
import logging
from typing import Dict, Any, Optional
from pydantic import BaseModel, Field

from django.conf import settings
from django.utils import timezone
from ..models import PlatformCalibration

logger = logging.getLogger("automation.calibration")


class SpatialAnchorSpot(BaseModel):
    x: Optional[int] = Field(default=None, description="Normalized X coordinate (0-1000 where 0 is left, 1000 is right).")
    y: Optional[int] = Field(default=None, description="Normalized Y coordinate (0-1000 where 0 is top, 1000 is bottom).")
    label: str = Field(description="Human readable name of the UI control.")
    description: str = Field(default="", description="Visual description of the element.")
    confidence: float = Field(default=0.0, description="Confidence score between 0.0 and 1.0.")


class CalibrationVLMResult(BaseModel):
    platform: str = Field(default="YOUTUBE")
    page_context: str = Field(default="HOMEPAGE", description="Detected page context (HOMEPAGE, SEARCH_RESULTS, WATCH_PAGE, etc.)")
    anchors: Dict[str, SpatialAnchorSpot] = Field(description="Dictionary mapping anchor key to its normalized spatial coordinates.")


class InterfaceCalibrationService:
    """
    Manages platform interface calibration, LLM-based visual spot detection,
    and spatial anchor persistence for the mobile automation engine.
    """

    DEFAULT_YOUTUBE_ANCHORS = {
        "SEARCH_BUTTON_HOME": {
            "x": None,
            "y": None,
            "label": "Search Icon (Homepage)",
            "description": "Magnifying glass search icon in the top-right header on m.youtube.com homepage.",
            "confidence": 0.0
        },
        "SEARCH_BUTTON_WATCH": {
            "x": None,
            "y": None,
            "label": "Search Icon (Watch / Playing Video)",
            "description": "Round magnifying glass search icon in header bar while a video is opened and playing.",
            "confidence": 0.0
        },
        "SEARCH_BUTTON_RESULTS": {
            "x": None,
            "y": None,
            "label": "Search Icon (Results Feed)",
            "description": "Magnifying glass search icon in the header when search results or active video feed is loaded.",
            "confidence": 0.0
        },
        "SEARCH_INPUT": {
            "x": None,
            "y": None,
            "label": "Search Input Box",
            "description": "Search text input field where queries are typed.",
            "confidence": 0.0
        },
        "SEARCH_CLEAR": {
            "x": None,
            "y": None,
            "label": "Clear Search Button",
            "description": "X button inside the search box to clear query.",
            "confidence": 0.0
        },
        "SEARCH_SUBMIT": {
            "x": None,
            "y": None,
            "label": "Search Submit Icon",
            "description": "Search button on right side of search bar to submit query.",
            "confidence": 0.0
        },
        "VIDEO_MENU_DOTS": {
            "x": None,
            "y": None,
            "label": "3-Dot Video Menu",
            "description": "Three vertical dots menu button on video card or below player.",
            "confidence": 0.0
        },
        "COMMENTS_SECTION": {
            "x": None,
            "y": None,
            "label": "Comments Section Teaser",
            "description": "Comments teaser banner located directly below the video title and engagement row.",
            "confidence": 0.0
        },
        "DESCRIPTION_EXPAND": {
            "x": None,
            "y": None,
            "label": "Description Expand (...more)",
            "description": "More / expand button below video title to open full description.",
            "confidence": 0.0
        },
        "LIKE_BUTTON": {
            "x": None,
            "y": None,
            "label": "Like Button",
            "description": "Thumbs-up like button in the video engagement bar.",
            "confidence": 0.0
        },
        "DISLIKE_BUTTON": {
            "x": None,
            "y": None,
            "label": "Dislike Button",
            "description": "Thumbs-down dislike button in engagement bar.",
            "confidence": 0.0
        },
        "SHARE_BUTTON": {
            "x": None,
            "y": None,
            "label": "Share Button",
            "description": "Share curved-arrow button in engagement bar.",
            "confidence": 0.0
        },
        "SUBSCRIBE_BUTTON": {
            "x": None,
            "y": None,
            "label": "Subscribe Button",
            "description": "Subscribe button located under video title next to channel name.",
            "confidence": 0.0
        },
        "CHANNEL_AVATAR": {
            "x": None,
            "y": None,
            "label": "Channel Avatar",
            "description": "Circular channel profile icon next to channel name.",
            "confidence": 0.0
        },
        "NAV_HOME": {
            "x": None,
            "y": None,
            "label": "Home Tab",
            "description": "Bottom navigation Home icon.",
            "confidence": 0.0
        },
        "NAV_SHORTS": {
            "x": None,
            "y": None,
            "label": "Shorts Tab",
            "description": "Bottom navigation Shorts icon.",
            "confidence": 0.0
        },
        "NAV_SUBSCRIPTIONS": {
            "x": None,
            "y": None,
            "label": "Subscriptions Tab",
            "description": "Bottom navigation Subscriptions icon.",
            "confidence": 0.0
        },
        "NAV_PROFILE": {
            "x": None,
            "y": None,
            "label": "You/Profile Tab",
            "description": "Bottom navigation You/Profile icon.",
            "confidence": 0.0
        },
        "PLAYER_PLAY_PAUSE": {
            "x": None,
            "y": None,
            "label": "Player Play/Pause",
            "description": "Center of active video player container.",
            "confidence": 0.0
        },
        "PLAYER_QUALITY_MENU": {
            "x": None,
            "y": None,
            "label": "Player Settings Gear",
            "description": "Settings gear icon inside video player control overlay.",
            "confidence": 0.0
        }
    }

    DEFAULT_SETTINGS = {
        "initial_scroll_count": 2,
        "natural_scroll_delay_min": 1.5,
        "natural_scroll_delay_max": 2.8,
        "videos_per_batch": 10,
        "max_batches_per_keyword": 2,
        "scroll_duration_min": 500,
        "scroll_duration_max": 750,
        "overshoot_scroll_enabled": True
    }

    @classmethod
    def get_or_create_calibration(cls, platform: str = "YOUTUBE") -> PlatformCalibration:
        """Retrieves or creates the primary calibration entry for the platform."""
        cal = PlatformCalibration.objects.filter(platform=platform.upper()).first()
        if not cal:
            cal = PlatformCalibration.objects.create(
                platform=platform.upper(),
                device_model="Physical Android",
                viewport_width=1080,
                viewport_height=2400,
                anchors=cls.DEFAULT_YOUTUBE_ANCHORS,
                settings=cls.DEFAULT_SETTINGS
            )
        else:
            # Ensure any newly introduced anchor keys exist in saved record
            updated = False
            current_anchors = dict(cal.anchors or {})
            for k, default_val in cls.DEFAULT_YOUTUBE_ANCHORS.items():
                if k not in current_anchors:
                    current_anchors[k] = default_val
                    updated = True
            if updated:
                cal.anchors = current_anchors
                cal.save(update_fields=["anchors", "updated_at"])
        return cal

    @classmethod
    def calibrate_with_vlm(
        cls,
        image_base64: str,
        platform: str = "YOUTUBE",
        provider: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Uses multimodal LLM (Gemini or OpenRouter) to inspect the screenshot,
        identify all key interactive elements on a normalized 0..1000 scale,
        and persist them to the database.
        """
        gemini_key = os.environ.get("GEMINI_API_KEY") or getattr(settings, "GEMINI_API_KEY", None)
        openrouter_key = os.environ.get("OPENROUTER_API_KEY") or getattr(settings, "OPENROUTER_API_KEY", None)

        system_instruction = (
            "You are a computer vision specialist specializing in mobile user interface parsing. "
            "Analyze the provided mobile browser screenshot of the platform. "
            "Locate all key interactive spots on a strictly normalized 0..1000 coordinate scale, where: "
            "(0, 0) is the top-left corner, and (1000, 1000) is the bottom-right corner. "
            "For each element, specify the EXACT clickable center point (target_x, target_y in 0..1000 range), "
            "a concise label, and visual description. "
            "You MUST detect or estimate the coordinates for: "
            "1. SEARCH_BUTTON_HOME (search icon on homepage top-right) "
            "2. SEARCH_BUTTON_WATCH (round search icon in header when video is opened and playing) "
            "3. SEARCH_BUTTON_RESULTS (search icon when search results / video feed is active) "
            "4. SEARCH_INPUT (search input bar text area) "
            "5. SEARCH_CLEAR (clear query 'X' button inside search bar) "
            "6. SEARCH_SUBMIT (magnifying glass or submit button inside/next to search bar) "
            "7. VIDEO_MENU_DOTS (3 vertical dots menu on video card or below player) "
            "8. COMMENTS_SECTION (comments teaser / expand comments area below video title) "
            "9. DESCRIPTION_EXPAND ('...more' or expand description text) "
            "10. LIKE_BUTTON (thumbs-up like icon in engagement row) "
            "11. DISLIKE_BUTTON (thumbs-down dislike icon in engagement row) "
            "12. SHARE_BUTTON (share arrow in engagement row) "
            "13. SUBSCRIBE_BUTTON (red or black pill Subscribe button) "
            "14. CHANNEL_AVATAR (channel avatar circle) "
            "15. NAV_HOME (bottom navigation Home tab) "
            "16. NAV_SHORTS (bottom navigation Shorts tab) "
            "17. NAV_SUBSCRIPTIONS (bottom navigation Subscriptions tab) "
            "18. NAV_PROFILE (bottom navigation You / Profile tab) "
            "19. PLAYER_PLAY_PAUSE (center of video player) "
            "20. PLAYER_QUALITY_MENU (settings gear icon in player overlay) "
            "Return valid JSON adhering to the CalibrationVLMResult schema."
        )

        detected_anchors = {}

        # 1. Try Gemini first if key available
        if gemini_key and (provider is None or provider.lower() == "gemini"):
            try:
                from google import genai
                from google.genai import types
                client = genai.Client(api_key=gemini_key)
                clean_b64 = image_base64
                if "," in clean_b64:
                    clean_b64 = clean_b64.split(",", 1)[1]
                image_bytes = base64.b64decode(clean_b64)

                response = client.models.generate_content(
                    model="gemini-2.5-flash",
                    contents=[
                        "Locate and calibrate all mobile interface clickable spots on a 0-1000 normalized scale.",
                        types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg")
                    ],
                    config=types.GenerateContentConfig(
                        system_instruction=system_instruction,
                        response_mime_type="application/json",
                        response_schema=CalibrationVLMResult,
                        temperature=0.1
                    )
                )
                vlm_result = CalibrationVLMResult.model_validate_json(response.text)
                for k, spot in vlm_result.anchors.items():
                    clean_k = k.strip().upper()
                    detected_anchors[clean_k] = {
                        "x": max(0, min(1000, spot.x)),
                        "y": max(0, min(1000, spot.y)),
                        "label": spot.label,
                        "description": spot.description,
                        "confidence": float(spot.confidence)
                    }
                logger.info(f"Successfully calibrated {len(detected_anchors)} visual anchors using Gemini.")
            except Exception as e:
                logger.warning(f"Gemini VLM calibration failed: {e}. Falling back to OpenRouter or defaults.")

        # 2. Try OpenRouter if Gemini was not available or failed
        if not detected_anchors and openrouter_key:
            try:
                from openai import OpenAI
                client = OpenAI(
                    base_url="https://openrouter.ai/api/v1",
                    api_key=openrouter_key
                )
                clean_b64 = image_base64
                if "," in clean_b64:
                    clean_b64 = clean_b64.split(",", 1)[1]

                completion = client.chat.completions.create(
                    model="deepseek/deepseek-chat",
                    messages=[
                        {"role": "system", "content": system_instruction},
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": "Locate and calibrate all mobile interface clickable spots on a 0-1000 normalized scale. Adhere strictly to the JSON schema."},
                                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{clean_b64}"}}
                            ]
                        }
                    ],
                    response_format={"type": "json_object"},
                    temperature=0.1
                )
                raw_json = json.loads(completion.choices[0].message.content)
                parsed = CalibrationVLMResult.model_validate(raw_json)
                for k, spot in parsed.anchors.items():
                    clean_k = k.strip().upper()
                    detected_anchors[clean_k] = {
                        "x": max(0, min(1000, spot.x)),
                        "y": max(0, min(1000, spot.y)),
                        "label": spot.label,
                        "description": spot.description,
                        "confidence": float(spot.confidence)
                    }
                logger.info(f"Successfully calibrated {len(detected_anchors)} visual anchors using OpenRouter.")
            except Exception as e:
                logger.error(f"OpenRouter VLM calibration failed: {e}")

        # 3. Merge with canonical defaults so no crucial spots are ever missing
        cal = cls.get_or_create_calibration(platform)
        merged_anchors = dict(cls.DEFAULT_YOUTUBE_ANCHORS)
        merged_anchors.update(detected_anchors)

        cal.anchors = merged_anchors
        if image_base64:
            cal.screenshot_base64 = image_base64
        cal.updated_at = timezone.now()
        cal.save()

        return {
            "platform": cal.platform,
            "anchors": cal.anchors,
            "settings": cal.settings,
            "detected_count": len(detected_anchors),
            "total_count": len(merged_anchors),
            "updated_at": cal.updated_at.isoformat()
        }

    @classmethod
    def flush_calibration(cls, platform: str = "YOUTUBE") -> PlatformCalibration:
        """
        Total flush: resets all spatial anchor coordinates to empty (None)
        and resets confidence to 0.0, allowing clean user re-calibration.
        """
        cal = cls.get_or_create_calibration(platform)
        flushed_anchors = {}
        for key, default_meta in cls.DEFAULT_YOUTUBE_ANCHORS.items():
            flushed_anchors[key] = {
                "x": None,
                "y": None,
                "label": default_meta.get("label", key),
                "description": default_meta.get("description", ""),
                "confidence": 0.0
            }
        cal.anchors = flushed_anchors
        cal.updated_at = timezone.now()
        cal.save(update_fields=["anchors", "updated_at"])
        return cal

    @classmethod
    def import_anchors_json(cls, data: Dict[str, Any], platform: str = "YOUTUBE") -> Dict[str, Any]:
        """
        Imports and validates an exported or custom anchors JSON payload.
        """
        cal = cls.get_or_create_calibration(platform)
        imported_anchors = data.get("anchors") if "anchors" in data else data
        if not isinstance(imported_anchors, dict):
            raise ValueError("Invalid JSON format: missing 'anchors' dictionary.")

        current_anchors = dict(cal.anchors or {})
        imported_count = 0

        for key, spot_val in imported_anchors.items():
            if not isinstance(key, str):
                continue
            norm_key = key.strip().upper()
            if isinstance(spot_val, dict):
                x = spot_val.get("x")
                y = spot_val.get("y")
                label = spot_val.get("label", current_anchors.get(norm_key, {}).get("label", norm_key))
                desc = spot_val.get("description", current_anchors.get(norm_key, {}).get("description", ""))
                conf = float(spot_val.get("confidence", 1.0 if (x is not None and y is not None) else 0.0))
                current_anchors[norm_key] = {
                    "x": int(x) if x is not None else None,
                    "y": int(y) if y is not None else None,
                    "label": label,
                    "description": desc,
                    "confidence": conf
                }
                if x is not None and y is not None:
                    imported_count += 1
            elif isinstance(spot_val, (list, tuple)) and len(spot_val) >= 2:
                current_anchors[norm_key] = {
                    "x": int(spot_val[0]) if spot_val[0] is not None else None,
                    "y": int(spot_val[1]) if spot_val[1] is not None else None,
                    "label": current_anchors.get(norm_key, {}).get("label", norm_key),
                    "description": current_anchors.get(norm_key, {}).get("description", ""),
                    "confidence": 1.0
                }
                imported_count += 1

        cal.anchors = current_anchors
        if "settings" in data and isinstance(data["settings"], dict):
            cal.settings = {**(cal.settings or cls.DEFAULT_SETTINGS), **data["settings"]}
        if "viewport_width" in data and data["viewport_width"]:
            cal.viewport_width = int(data["viewport_width"])
        if "viewport_height" in data and data["viewport_height"]:
            cal.viewport_height = int(data["viewport_height"])

        cal.updated_at = timezone.now()
        cal.save()

        return {
            "status": "IMPORTED",
            "platform": cal.platform,
            "imported_count": imported_count,
            "total_anchors": len(cal.anchors),
            "anchors": cal.anchors,
            "settings": cal.settings,
            "updated_at": cal.updated_at.isoformat()
        }

    @classmethod
    def export_anchors_json(cls, platform: str = "YOUTUBE") -> Dict[str, Any]:
        """
        Exports the canonical JSON payload saved by Android clients
        as `youtube_spatial_anchors.json`.
        """
        cal = cls.get_or_create_calibration(platform)
        anchors_map = {}
        for key, data in (cal.anchors or {}).items():
            if isinstance(data, dict):
                x = data.get("x")
                y = data.get("y")
                anchors_map[key] = {
                    "x": int(x) if x is not None else None,
                    "y": int(y) if y is not None else None,
                    "label": data.get("label", key),
                    "description": data.get("description", "")
                }
            elif isinstance(data, (list, tuple)) and len(data) >= 2:
                anchors_map[key] = {
                    "x": int(data[0]) if data[0] is not None else None,
                    "y": int(data[1]) if data[1] is not None else None,
                    "label": key,
                    "description": ""
                }

        return {
            "version": 2,
            "platform": cal.platform,
            "exported_at": timezone.now().isoformat(),
            "viewport_width": cal.viewport_width,
            "viewport_height": cal.viewport_height,
            "settings": cal.settings or cls.DEFAULT_SETTINGS,
            "anchors": anchors_map
        }

    @classmethod
    def get_adb_path(cls) -> str:
        import shutil
        adb_path = shutil.which("adb")
        if not adb_path:
            localappdata = os.environ.get("LOCALAPPDATA", "")
            candidate = os.path.join(localappdata, "Android", "Sdk", "platform-tools", "adb.exe")
            if os.path.exists(candidate):
                adb_path = candidate
            else:
                adb_path = "adb"
        return adb_path

    @classmethod
    def get_connected_devices(cls) -> list:
        import subprocess
        adb = cls.get_adb_path()
        devices = []
        try:
            res = subprocess.run([adb, "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
            for line in res.stdout.splitlines():
                line = line.strip()
                if line and not line.startswith("List of devices") and "\tdevice" in line:
                    parts = line.split("\t")
                    devices.append(parts[0].strip())
        except Exception as e:
            logger.warning(f"Failed to query adb devices: {e}")
        return devices

    @classmethod
    def get_device_resolution(cls, device_id: Optional[str] = None) -> tuple:
        import subprocess, re
        adb = cls.get_adb_path()
        cmd = [adb]
        if device_id:
            cmd.extend(["-s", device_id])
        cmd.extend(["shell", "wm", "size"])

        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=5)
            matches = re.findall(r"(\d+)x(\d+)", res.stdout)
            if matches:
                w, h = matches[-1]
                return int(w), int(h)
        except Exception as e:
            logger.warning(f"Failed to query wm size: {e}")
        return (1080, 2400)

    @classmethod
    def save_single_anchor(cls, platform: str, anchor_id: str, x: Optional[int], y: Optional[int], label: str = None, description: str = None, category: str = None) -> dict:
        cal = cls.get_or_create_calibration(platform)
        norm_key = anchor_id.strip().upper()
        current = dict(cal.anchors or {})

        prev_anchor = current.get(norm_key, {})
        current[norm_key] = {
            "x": int(x) if x is not None else None,
            "y": int(y) if y is not None else None,
            "label": label or prev_anchor.get("label", norm_key),
            "description": description or prev_anchor.get("description", ""),
            "category": category or prev_anchor.get("category", "CUSTOM"),
            "confidence": 1.0 if (x is not None and y is not None) else 0.0,
            "updated_at": timezone.now().isoformat()
        }
        cal.anchors = current
        cal.updated_at = timezone.now()
        cal.save(update_fields=["anchors", "updated_at"])
        return current[norm_key]

    @classmethod
    def delete_single_anchor(cls, platform: str, anchor_id: str) -> bool:
        cal = cls.get_or_create_calibration(platform)
        norm_key = anchor_id.strip().upper()
        current = dict(cal.anchors or {})
        if norm_key in current:
            del current[norm_key]
            cal.anchors = current
            cal.updated_at = timezone.now()
            cal.save(update_fields=["anchors", "updated_at"])
            return True
        return False

    @classmethod
    def test_anchor_tap(cls, x: int, y: int, device_id: Optional[str] = None, anchor_id: Optional[str] = None) -> dict:
        """
        Executes a hardware touch event directly on the connected Android device
        using normalized 0..1000 coordinates mapped to physical screen pixels.
        """
        import subprocess
        adb = cls.get_adb_path()
        devices = cls.get_connected_devices()
        if not devices and not device_id:
            raise RuntimeError("No connected Android device found via ADB. Please connect a phone with USB debugging enabled.")

        target_device = device_id or devices[0]
        width, height = cls.get_device_resolution(target_device)

        px_x = int(round((int(x) * width) / 1000.0))
        px_y = int(round((int(y) * height) / 1000.0))

        cmd = [adb, "-s", target_device, "shell", "input", "tap", str(px_x), str(px_y)]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=8)
        if res.returncode != 0:
            err = res.stderr.strip()
            raise RuntimeError(f"ADB input tap failed: {err}")

        logger.info(f"Test tap executed on device {target_device}: norm=({x}, {y}) -> phys=({px_x}, {px_y}) [screen={width}x{height}]")
        return {
            "status": "SUCCESS",
            "device_id": target_device,
            "anchor_id": anchor_id,
            "normalized": {"x": int(x), "y": int(y)},
            "physical": {"x": px_x, "y": px_y},
            "screen_size": f"{width}x{height}",
            "timestamp": timezone.now().isoformat()
        }

    @classmethod
    def get_all_anchors_with_addons(cls, platform: str = "YOUTUBE") -> dict:
        """
        Combines base calibrated anchors with dynamic anchors declared by active WorkflowAddons.
        """
        from ..models import WorkflowAddon
        cal = cls.get_or_create_calibration(platform)
        anchors = dict(cal.anchors or cls.DEFAULT_YOUTUBE_ANCHORS)

        try:
            for addon in WorkflowAddon.objects.filter(is_active=True):
                addon_anchors = addon.manifest.get("anchors") or []
                if isinstance(addon_anchors, list):
                    for item in addon_anchors:
                        aid = item.get("id") or item.get("name")
                        if aid and aid.upper() not in anchors:
                            anchors[aid.upper()] = {
                                "x": item.get("x"),
                                "y": item.get("y"),
                                "label": item.get("label", aid),
                                "description": item.get("description", f"Declared by extension {addon.name}"),
                                "category": item.get("category", "EXTENSION"),
                                "source": "addon",
                                "addon_name": addon.name,
                                "addon_slug": addon.slug
                            }
                elif isinstance(addon_anchors, dict):
                    for aid, a_val in addon_anchors.items():
                        if aid.upper() not in anchors:
                            anchors[aid.upper()] = {
                                "x": a_val.get("x") if isinstance(a_val, dict) else None,
                                "y": a_val.get("y") if isinstance(a_val, dict) else None,
                                "label": a_val.get("label", aid) if isinstance(a_val, dict) else aid,
                                "description": a_val.get("description", f"Declared by extension {addon.name}") if isinstance(a_val, dict) else "",
                                "category": "EXTENSION",
                                "source": "addon",
                                "addon_name": addon.name,
                                "addon_slug": addon.slug
                            }
        except Exception as e:
            logger.warning(f"Could not load addon anchors: {e}")

        return anchors

    @classmethod
    def push_anchors_to_device(cls, device_id: Optional[str] = None, platform: str = "YOUTUBE") -> dict:
        """
        Pushes canonical spatial anchors JSON directly to connected Android device(s)
        storage (`files/youtube_spatial_anchors.json`) via ADB and notifies the app.
        """
        import subprocess, tempfile
        adb = cls.get_adb_path()
        devices = [device_id] if device_id else cls.get_connected_devices()
        if not devices:
            return {"status": "NO_DEVICES", "synced_devices": [], "message": "No connected ADB devices found."}

        export_data = cls.export_anchors_json(platform)
        json_content = json.dumps(export_data, indent=2)

        synced_devices = []
        errors = []

        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tf:
            tf.write(json_content)
            temp_path = tf.name

        try:
            for dev in devices:
                try:
                    remote_tmp = f"/data/local/tmp/spatial_anchors_{dev}.json"
                    push_res = subprocess.run([adb, "-s", dev, "push", temp_path, remote_tmp], capture_output=True, text=True, timeout=8)
                    if push_res.returncode != 0:
                        errors.append(f"{dev}: ADB push failed - {push_res.stderr.strip()}")
                        continue

                    cp_cmd = [adb, "-s", dev, "shell", "run-as", "com.multibrowser.antidetect", "cp", remote_tmp, "files/youtube_spatial_anchors.json"]
                    cp_res = subprocess.run(cp_cmd, capture_output=True, text=True, timeout=8)

                    subprocess.run([adb, "-s", dev, "shell", "rm", remote_tmp], capture_output=True, timeout=5)

                    if cp_res.returncode == 0:
                        subprocess.run([adb, "-s", dev, "shell", "am", "broadcast", "-a", "com.multibrowser.antidetect.RELOAD_SPATIAL_ANCHORS"], capture_output=True, timeout=5)
                        synced_devices.append(dev)
                    else:
                        errors.append(f"{dev}: run-as cp failed - {cp_res.stderr.strip()}")
                except Exception as e:
                    errors.append(f"{dev}: {str(e)}")
        finally:
            if os.path.exists(temp_path):
                os.remove(temp_path)

        return {
            "status": "SYNCED" if synced_devices else "FAILED",
            "synced_devices": synced_devices,
            "errors": errors,
            "anchors_count": len(export_data.get("anchors", {})),
            "timestamp": timezone.now().isoformat()
        }

    @classmethod
    def pull_anchors_from_device(cls, device_id: Optional[str] = None, platform: str = "YOUTUBE") -> dict:
        """
        Reads `files/youtube_spatial_anchors.json` from the connected Android device via ADB
        and imports all anchors and settings into the backend PlatformCalibration database.
        """
        import subprocess
        adb = cls.get_adb_path()
        devices = [device_id] if device_id else cls.get_connected_devices()
        if not devices:
            raise RuntimeError("No connected Android device found via ADB.")

        target_dev = devices[0]
        cat_cmd = [adb, "-s", target_dev, "shell", "run-as", "com.multibrowser.antidetect", "cat", "files/youtube_spatial_anchors.json"]
        res = subprocess.run(cat_cmd, capture_output=True, text=True, timeout=8)
        if res.returncode != 0 or not res.stdout.strip():
            raise RuntimeError(f"Could not read youtube_spatial_anchors.json from device {target_dev}: {res.stderr.strip()}")

        device_json = json.loads(res.stdout.strip())
        import_res = cls.import_anchors_json(device_json, platform=platform)
        import_res["source_device"] = target_dev
        return import_res

