import os
import json
import logging
import urllib.parse
import re
import html
import requests
from google import genai
from google.genai import types
from pydantic import BaseModel, Field

logger = logging.getLogger("devices")

# Pydantic schema used strictly for forcing the LLM's structured output format
class DeviceFingerprintSchema(BaseModel):
    brand: str = Field(description="Manufacturer name (e.g., Samsung, OnePlus, Xiaomi)")
    model_name: str = Field(description="Commercial name (e.g., Galaxy A23 5G, OnePlus 12)")
    model_code: str = Field(description="Hardware identifier (e.g., SM-A236B, CPH2581)")
    android_version: int = Field(description="Target Android version, default 14 or 15")
    soc: str = Field(description="System on Chip chipset name (e.g., Snapdragon 695 5G, Dimensity 8200)")
    webgl_vendor: str = Field(description="ARM for Mali GPUs, Qualcomm for Adreno GPUs")
    webgl_renderer: str = Field(description="Exact GPU string, e.g., Mali-G68 MP5, Adreno (TM) 619")
    ram_gb: int = Field(description="Memory capacity in GB (e.g., 6, 8, 12)")
    cpu_cores: int = Field(description="Physical core count, typically 8")
    screen_width: int = Field(description="CSS portrait viewport width (e.g., 384, 393, 412)")
    screen_height: int = Field(description="CSS portrait viewport height (e.g., 854, 873, 915)")
    dpr: float = Field(description="Device Pixel Ratio (e.g., 2.625, 2.75, 3.0)")
    user_agent: str = Field(description="Formatted Mobile Firefox on Android User-Agent")


# Curated real-world verified fallback presets
FALLBACK_DEVICES = {
    "oneplus 12": {
        "brand": "OnePlus",
        "model_name": "OnePlus 12",
        "model_code": "CPH2581",
        "android_version": 14,
        "soc": "Snapdragon 8 Gen 3",
        "webgl_vendor": "Qualcomm",
        "webgl_renderer": "Adreno (TM) 750",
        "ram_gb": 16,
        "cpu_cores": 8,
        "screen_width": 450,
        "screen_height": 1000,
        "dpr": 3.2,
        "user_agent": "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
    },
    "samsung galaxy s24": {
        "brand": "Samsung",
        "model_name": "Galaxy S24",
        "model_code": "SM-S921B",
        "android_version": 14,
        "soc": "Exynos 2400 / Snapdragon 8 Gen 3",
        "webgl_vendor": "ARM",
        "webgl_renderer": "Mali-G720 MP12",
        "ram_gb": 8,
        "cpu_cores": 8,
        "screen_width": 360,
        "screen_height": 780,
        "dpr": 3.0,
        "user_agent": "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
    },
    "pixel 8": {
        "brand": "Google",
        "model_name": "Pixel 8",
        "model_code": "GKWS6",
        "android_version": 14,
        "soc": "Google Tensor G3",
        "webgl_vendor": "ARM",
        "webgl_renderer": "Mali-G715s MC10",
        "ram_gb": 8,
        "cpu_cores": 8,
        "screen_width": 412,
        "screen_height": 915,
        "dpr": 2.625,
        "user_agent": "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
    },
    "itel": {
        "brand": "itel",
        "model_name": "itel S23+",
        "model_code": "itel S688LN",
        "android_version": 14,
        "soc": "Unisoc T616",
        "webgl_vendor": "ARM",
        "webgl_renderer": "Mali-G57 MP1",
        "ram_gb": 8,
        "cpu_cores": 8,
        "screen_width": 360,
        "screen_height": 800,
        "dpr": 2.625,
        "user_agent": "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
    }
}


def _get_fallback_specs(device_query: str) -> dict:
    q_lower = device_query.lower()
    for key, specs in FALLBACK_DEVICES.items():
        if key in q_lower:
            return specs
    # Generic realistic Android fallback
    return {
        "brand": "Samsung",
        "model_name": f"Galaxy ({device_query})",
        "model_code": "SM-A546B",
        "android_version": 14,
        "soc": "Exynos 1380",
        "webgl_vendor": "ARM",
        "webgl_renderer": "Mali-G68 MP5",
        "ram_gb": 8,
        "cpu_cores": 8,
        "screen_width": 384,
        "screen_height": 854,
        "dpr": 2.8125,
        "user_agent": "Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0",
    }


def _search_web_for_device(device_query: str, target_sites_str: str = None) -> str:
    """
    Performs live web search for device specifications prioritizing user-specified
    authoritative phone specification sites (e.g. gsmarena.com, devicespecifications.com,
    phonearena.com, kimovil.com, nanoreview.net) to ensure newly released and authentic hardware
    details are prioritized over outdated model training cutoffs.
    """
    clean_q = device_query.strip()
    target_sites = []
    if target_sites_str:
        target_sites = [s.strip() for s in target_sites_str.replace(",", "\n").splitlines() if s.strip()]

    if not target_sites:
        from .models import GlobalSetting
        try:
            settings = GlobalSetting.load()
            sites_text = getattr(settings, "target_search_sites", "") or ""
            target_sites = [s.strip() for s in sites_text.replace(",", "\n").splitlines() if s.strip()]
        except Exception:
            target_sites = []

    if not target_sites:
        target_sites = ["gsmarena.com", "devicespecifications.com", "phonearena.com", "kimovil.com", "nanoreview.net"]

    cleaned_domains = []
    for s in target_sites:
        d = s.lower().replace("https://", "").replace("http://", "").split("/")[0].strip()
        if d and d not in cleaned_domains:
            cleaned_domains.append(d)

    results = []
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5",
    }

    # 1. Query Wikipedia Search API (Zero rate limits, indexes GSMArena & official phone releases)
    try:
        wiki_url = f"https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={urllib.parse.quote_plus(clean_q)}&format=json"
        wiki_resp = requests.get(wiki_url, headers=headers, timeout=4.0)
        if wiki_resp.status_code == 200:
            w_data = wiki_resp.json()
            for item in w_data.get("query", {}).get("search", [])[:3]:
                snippet = html.unescape(re.sub(r'<[^>]+>', '', item.get("snippet", ""))).strip()
                if len(snippet) > 25 and not any(snippet[:40] in r for r in results):
                    results.append(f"- [wikipedia.org / {item.get('title')}] {snippet}")
    except Exception as ex:
        logger.debug("Wikipedia search query error: %s", ex)

    # 2. Targeted query incorporating the authoritative specification domains via DuckDuckGo
    primary_query = f"{clean_q} specifications processor soc gpu {' '.join(cleaned_domains[:2])}"
    try:
        url = f"https://html.duckduckgo.com/html/?q={urllib.parse.quote_plus(primary_query)}"
        resp = requests.get(url, headers=headers, timeout=5.0)
        if resp.status_code == 200:
            matches = re.findall(r'class="result__snippet[^>]*>(.*?)</a>', resp.text, re.DOTALL)
            for m in matches[:5]:
                text = html.unescape(re.sub(r'<[^>]+>', '', m)).strip()
                if len(text) > 25 and not any(text[:40] in r for r in results):
                    results.append(f"- [web] {text}")
    except Exception as ex:
        logger.debug("Targeted search query failed: %s", ex)

    if results:
        sites_header = ", ".join(cleaned_domains)
        return f"Targeted Authoritative Sites Searched ({sites_header}):\n" + "\n".join(results[:6])
    return ""


def fetch_available_models_from_provider(provider: str, api_key: str = None) -> dict:
    """
    Fetches all available models directly from the provider's API.
    No models are hardcoded.
    """
    provider = (provider or "").lower().strip()

    # 1. Resolve API key if not supplied
    if not api_key:
        try:
            from ai_assistant.services import AIConfigService
            api_key = AIConfigService.get_api_key(provider) or ""
        except Exception:
            try:
                from .models import LLMConfig
                cfg = LLMConfig.objects.filter(provider=provider, is_active=True).first()
                if cfg and cfg.api_key.strip():
                    api_key = cfg.api_key.strip()
            except Exception:
                pass

    if provider == "openrouter":
        if not api_key:
            api_key = os.getenv("OPENROUTER_API_KEY", "")

        headers = {
            "HTTP-Referer": "https://octobrowser.local",
            "X-Title": "OctoMobile Anti-Detect",
        }
        if api_key and api_key != "your_openrouter_api_key_here":
            headers["Authorization"] = f"Bearer {api_key}"

        try:
            resp = requests.get("https://openrouter.ai/api/v1/models", headers=headers, timeout=15)
            resp.raise_for_status()
            data = resp.json()
            raw_models = data.get("data", [])
            models = []
            for item in raw_models:
                m_id = item.get("id")
                if not m_id:
                    continue
                models.append({
                    "id": m_id,
                    "name": item.get("name") or m_id,
                    "description": (item.get("description") or "")[:250],
                    "context_length": item.get("context_length", 0)
                })
            return {"provider": "openrouter", "models": models, "count": len(models)}
        except Exception as e:
            return {"provider": "openrouter", "models": [], "error": str(e)}



    elif provider == "gemini":
        if not api_key:
            api_key = os.getenv("GEMINI_API_KEY", "")

        # If no key configured, return the full official Gemini models catalog
        if not api_key or api_key == "your_gemini_api_key_here":
            return {
                "provider": "gemini",
                "models": GEMINI_CATALOG_MODELS,
                "count": len(GEMINI_CATALOG_MODELS),
                "notice": "Using official Gemini models catalog. Configure an API key for live account-level sync."
            }

        live_models = []
        fetch_err = None

        # Try Google GenAI SDK
        try:
            client = genai.Client(api_key=api_key)
            for m in client.models.list():
                raw_name = getattr(m, "name", "") or ""
                m_id = raw_name.replace("models/", "")
                methods = getattr(m, "supported_generation_methods", []) or []
                if not methods or "generateContent" in methods:
                    live_models.append({
                        "id": m_id,
                        "name": getattr(m, "display_name", "") or m_id,
                        "description": (getattr(m, "description", "") or "")[:250],
                        "context_length": getattr(m, "input_token_limit", 0) or 0
                    })
        except Exception as e:
            fetch_err = str(e)
            # Fallback to direct REST API
            try:
                resp = requests.get(
                    "https://generativelanguage.googleapis.com/v1beta/models",
                    headers={"x-goog-api-key": api_key},
                    timeout=10
                )
                if resp.status_code == 200:
                    data = resp.json()
                    for item in data.get("models", []):
                        m_name = item.get("name", "").replace("models/", "")
                        methods = item.get("supportedGenerationMethods", [])
                        if not methods or "generateContent" in methods:
                            live_models.append({
                                "id": m_name,
                                "name": item.get("displayName") or m_name,
                                "description": (item.get("description") or "")[:250],
                                "context_length": item.get("inputTokenLimit", 0)
                            })
                    fetch_err = None
            except Exception as e2:
                fetch_err = f"{fetch_err} | {str(e2)}"

        if live_models:
            return {"provider": "gemini", "models": live_models, "count": len(live_models)}

        # Fallback to catalog if live network query failed
        return {
            "provider": "gemini",
            "models": GEMINI_CATALOG_MODELS,
            "count": len(GEMINI_CATALOG_MODELS),
            "notice": f"Loaded official Gemini catalog (Live query notice: {fetch_err})"
        }

    return {"provider": provider, "models": [], "error": f"Unknown provider: {provider}"}


def _call_openrouter(api_key: str, model_name: str, device_query: str, custom_prompt: str = None, search_context: str = None, target_sites_str: str = None) -> dict:
    if not model_name:
        models_data = fetch_available_models_from_provider("openrouter", api_key)
        if models_data.get("models"):
            model_name = models_data["models"][0]["id"]
        else:
            raise ValueError("No model specified and could not dynamically fetch models from OpenRouter.")

    url = "https://openrouter.ai/api/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "HTTP-Referer": "https://octobrowser.local",
        "X-Title": "OctoMobile Anti-Detect",
        "Content-Type": "application/json"
    }

    system_prompt = (custom_prompt or "").strip()
    if not system_prompt:
        system_prompt = (
            "You are an expert mobile hardware and anti-detect browser engineer. "
            "Your task is to generate verified, real-world hardware and browser fingerprint specifications for the requested mobile device. "
            "You must respond ONLY with a raw JSON object containing these exact fields: "
            "brand (string), model_name (string), model_code (string, e.g. SM-S921B), android_version (integer, 14 or 15), "
            "soc (string), webgl_vendor (string: 'ARM' for Mali, 'Qualcomm' for Adreno), "
            "webgl_renderer (string, e.g. 'Adreno (TM) 750'), ram_gb (integer), cpu_cores (integer, e.g. 8), "
            "screen_width (integer, CSS viewport e.g. 412), screen_height (integer, CSS viewport e.g. 915), "
            "dpr (float, e.g. 2.625), user_agent (string, format: 'Mozilla/5.0 (Android {android_version}; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0'). "
            "Output valid JSON only. No markdown formatting, no code blocks, no other text."
        )

    target_sites = []
    if target_sites_str:
        target_sites = [s.strip() for s in target_sites_str.replace(",", "\n").splitlines() if s.strip()]

    user_query = f"Generate accurate fingerprint blueprint for device: {device_query}"
    if target_sites:
        sites_header = ", ".join(target_sites)
        user_query += (
            f"\n\nMANDATORY SEARCH INSTRUCTION: Retrieve and verify hardware specifications from these authoritative websites: {sites_header}.\n"
            f"Use the exact real-world SoC chipset name, GPU renderer, Android version, RAM, and screen specs found on those sites."
        )
    if search_context:
        user_query += (
            f"\n\n--- LIVE ONLINE WEB SEARCH RESULTS FOR THIS DEVICE ---\n"
            f"{search_context}\n"
            f"--- MANDATORY PRIORITY RULE ---\n"
            f"The above data was retrieved LIVE from online web search. You MUST prioritize this live search data over your internal training data. "
            f"Use the exact SoC chipset name, GPU renderer, Android version, RAM, and screen specs found in these search results."
        )

    payload = {
        "model": model_name,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_query}
        ],
        "temperature": 0.1,
        "response_format": {"type": "json_object"}
    }

    resp = requests.post(url, headers=headers, json=payload, timeout=20)
    resp.raise_for_status()
    data = resp.json()
    content = data["choices"][0]["message"]["content"]
    content = content.strip()
    if content.startswith("```json"):
        content = content[7:]
    if content.startswith("```"):
        content = content[3:]
    if content.endswith("```"):
        content = content[:-3]
    return json.loads(content.strip())


def _call_gemini(api_key: str, model_name: str, device_query: str, custom_prompt: str = None, search_context: str = None, target_sites_str: str = None) -> dict:
    if not model_name:
        models_data = fetch_available_models_from_provider("gemini", api_key)
        if models_data.get("models"):
            model_name = models_data["models"][0]["id"]
        else:
            raise ValueError("No model specified and could not dynamically fetch models from Gemini.")

    client = genai.Client(api_key=api_key)

    system_instruction = (custom_prompt or "").strip()
    if not system_instruction:
        system_instruction = (
            "You are an expert mobile hardware and anti-detect fingerprinting engineer. "
            "Generate verified hardware specifications for the target mobile device. "
            "Ensure the GPU renderer, SoC, CSS viewport resolution, DPR, and model code match real-world devices. "
            "The user_agent MUST be an authentic Mobile Firefox on Android string: "
            "'Mozilla/5.0 (Android {android_version}; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0'."
        )

    target_model = model_name
    if "/" in target_model:
        target_model = target_model.split("/")[-1]

    target_sites = []
    if target_sites_str:
        target_sites = [s.strip() for s in target_sites_str.replace(",", "\n").splitlines() if s.strip()]

    contents = f"Retrieve and build complete fingerprint specifications for device: {device_query}"
    if target_sites:
        contents += f"\nMANDATORY: You must search and cross-reference hardware specs from these authoritative sites: {', '.join(target_sites)}."
    if search_context:
        contents += (
            f"\n\n--- LIVE ONLINE WEB SEARCH RESULTS FOR THIS DEVICE ---\n"
            f"{search_context}\n"
            f"--- MANDATORY PRIORITY RULE ---\n"
            f"The above data was retrieved LIVE from online web search. You MUST prioritize this live search data over your internal training data. "
            f"Use the exact SoC chipset name, GPU renderer, Android version, RAM, and screen specs found in these search results."
        )

    # First attempt: Google Search Grounding for live authoritative web search
    try:
        response = client.models.generate_content(
            model=target_model,
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=system_instruction,
                tools=[types.Tool(google_search=types.GoogleSearch())],
                temperature=0.1
            )
        )
        text = response.text.strip()
        if text.startswith("```json"):
            text = text[7:]
        if text.startswith("```"):
            text = text[3:]
        if text.endswith("```"):
            text = text[:-3]
        return json.loads(text.strip())
    except Exception as search_err:
        logger.info("Gemini live search grounding notice: %s. Using structured schema generation.", search_err)

    # Fallback attempt: Standard generation with strict Pydantic response schema
    response = client.models.generate_content(
        model=target_model,
        contents=contents,
        config=types.GenerateContentConfig(
            system_instruction=system_instruction,
            response_mime_type="application/json",
            response_schema=DeviceFingerprintSchema,
            temperature=0.1
        )
    )
    return json.loads(response.text)


def generate_device_specs_with_llm(device_query: str, provider_override: str = None, model_override: str = None) -> dict:
    from .models import LLMConfig, GlobalSetting
    settings = GlobalSetting.load()

    provider = (provider_override or settings.selected_ai_provider or "openrouter").lower().strip()
    api_key = None
    model_name = model_override

    # Look up saved model and API key for this specific provider if no explicit model_override is provided
    try:
        from ai_assistant.services import AIConfigService
        api_key = AIConfigService.get_api_key(provider)
        ai_cfg = AIConfigService.get_active_provider_config(provider)
        if ai_cfg and not model_name and ai_cfg.model_name:
            model_name = ai_cfg.model_name
    except Exception:
        pass

    try:
        active_config = LLMConfig.objects.filter(provider=provider, is_active=True).first()
        if active_config:
            if not api_key and active_config.api_key.strip():
                api_key = active_config.api_key.strip()
            if not model_name and active_config.model_name.strip():
                model_name = active_config.model_name.strip()

        if not model_name:
            if provider == "gemini":
                model_name = getattr(settings, "saved_gemini_model", None) or "gemini-2.5-flash"
            elif provider == "openrouter":
                model_name = getattr(settings, "saved_openrouter_model", None) or "google/gemini-3.8-flash"
    except Exception as e:
        logger.error("Error resolving saved model for provider %s: %s", provider, e)

    if not provider:
        provider = "openrouter"

    custom_prompt = None
    try:
        from ai_assistant.services import AIConfigService
        from ai_assistant.models import PromptCategory
        custom_prompt = AIConfigService.get_active_prompt(PromptCategory.DEVICE_SYNTHESIS)
    except Exception:
        pass
    if not custom_prompt and active_config and active_config.system_prompt and active_config.system_prompt.strip():
        custom_prompt = active_config.system_prompt.strip()
    if not custom_prompt:
        custom_prompt = getattr(settings, "ai_generation_prompt", None)

    # Perform live online web search for real-world specifications
    target_sites_str = getattr(settings, "target_search_sites", "") or None
    search_context = _search_web_for_device(device_query, target_sites_str=target_sites_str)
    if search_context:
        logger.info("Live web search successfully retrieved specifications for '%s'", device_query)

    if provider == "openrouter":
        if not api_key:
            api_key = os.getenv("OPENROUTER_API_KEY")

        if not api_key or api_key == "your_openrouter_api_key_here":
            return _get_fallback_specs(device_query)

        try:
            raw_specs = _call_openrouter(
                api_key, 
                model_name, 
                device_query, 
                custom_prompt=custom_prompt, 
                search_context=search_context,
                target_sites_str=target_sites_str
            )
            return _normalize_device_specs(raw_specs)
        except Exception as ex:
            logger.warning("OpenRouter API failed: %s, falling back to preset", ex)
            return _get_fallback_specs(device_query)

    else:
        # Gemini
        if not api_key:
            api_key = os.getenv("GEMINI_API_KEY")

        if not api_key or api_key == "your_gemini_api_key_here":
            return _get_fallback_specs(device_query)

        try:
            raw_specs = _call_gemini(
                api_key, 
                model_name, 
                device_query, 
                custom_prompt=custom_prompt, 
                search_context=search_context,
                target_sites_str=target_sites_str
            )
            return _normalize_device_specs(raw_specs)
        except Exception as ex:
            logger.warning("Gemini API failed: %s, falling back to preset", ex)
            return _get_fallback_specs(device_query)


def _normalize_device_specs(specs: dict) -> dict:
    if not isinstance(specs, dict):
        return specs
    brand = (specs.get("brand") or "").strip()
    model_name = (specs.get("model_name") or "").strip()
    if brand and model_name.lower().startswith(brand.lower()):
        cleaned = model_name[len(brand):].strip()
        if cleaned:
            specs["model_name"] = cleaned
    return specs

