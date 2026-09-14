import os
import json
import base64
from abc import ABC, abstractmethod
from typing import Optional, Literal, Dict, Any, Union
from pydantic import BaseModel, Field

from automation.models import TaskExecutionQueue, AIPromptConfig, Execution


class AgentRecoveryAction(BaseModel):
    action: Literal["TAP_COORDINATES", "BÉZIER_SWIPE", "WAIT", "NAVIGATE", "TERMINATE"] = Field(
        description="The physical recovery action GhostPilot should execute."
    )
    target_x: Optional[int] = Field(default=None, description="Screen X coordinate to tap, if applicable.")
    target_y: Optional[int] = Field(default=None, description="Screen Y coordinate to tap, if applicable.")
    swipe_direction: Optional[Literal["UP", "DOWN", "LEFT", "RIGHT"]] = Field(default=None)
    navigate_url: Optional[str] = Field(default=None)
    wait_seconds: Optional[int] = Field(default=None)
    next_state_override: Optional[str] = Field(
        default=None,
        description="The DAG state ID the runner should transition into next."
    )
    reasoning: str = Field(description="Brief explanation of why this action resolves the blockage.")


# ---------------------------------------------------------------------------
# Provider Adapters
# ---------------------------------------------------------------------------

class BaseLLMAdapter(ABC):
    @property
    @abstractmethod
    def model_name(self) -> str:
        pass

    @abstractmethod
    def generate_action(
        self,
        system_instruction: str,
        prompt_text: str,
        temperature: float = 0.1,
        image_base64: Optional[str] = None
    ) -> AgentRecoveryAction:
        pass


class GeminiAdapter(BaseLLMAdapter):
    def __init__(self, api_key: str, model_name: str = "gemini-2.5-flash"):
        from google import genai
        self.client = genai.Client(api_key=api_key)
        self._model_name = model_name

    @property
    def model_name(self) -> str:
        return self._model_name

    def generate_action(
        self,
        system_instruction: str,
        prompt_text: str,
        temperature: float = 0.1,
        image_base64: Optional[str] = None
    ) -> AgentRecoveryAction:
        from google.genai import types

        contents = [prompt_text]
        if image_base64:
            contents.append(
                types.Part.from_bytes(
                    data=base64.b64decode(image_base64),
                    mime_type="image/jpeg"
                )
            )

        response = self.client.models.generate_content(
            model=self._model_name,
            contents=contents,
            config=types.GenerateContentConfig(
                system_instruction=system_instruction,
                response_mime_type="application/json",
                response_schema=AgentRecoveryAction,
                temperature=temperature
            )
        )
        return AgentRecoveryAction.model_validate_json(response.text)


class OpenRouterAdapter(BaseLLMAdapter):
    def __init__(self, api_key: str, model_name: str = "deepseek/deepseek-chat"):
        from openai import OpenAI
        self.client = OpenAI(
            base_url="https://openrouter.ai/api/v1",
            api_key=api_key
        )
        self._model_name = model_name

    @property
    def model_name(self) -> str:
        return self._model_name

    def generate_action(
        self,
        system_instruction: str,
        prompt_text: str,
        temperature: float = 0.1,
        image_base64: Optional[str] = None
    ) -> AgentRecoveryAction:
        user_content = []
        if image_base64:
            user_content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}
            })
        user_content.append({"type": "text", "text": prompt_text})

        schema_instruction = f"""
        {system_instruction}

        CRITICAL: Respond ONLY with a valid JSON object matching this schema:
        {json.dumps(AgentRecoveryAction.model_json_schema(), indent=2)}
        """

        response = self.client.chat.completions.create(
            model=self._model_name,
            messages=[
                {"role": "system", "content": schema_instruction},
                {"role": "user", "content": user_content if image_base64 else prompt_text}
            ],
            response_format={"type": "json_object"},
            temperature=temperature,
            extra_headers={
                "HTTP-Referer": "https://ghostpilot.browser.farm",
                "X-Title": "GhostPilot Autonomous AntiDetect Engine"
            }
        )
        raw_json = response.choices[0].message.content
        return AgentRecoveryAction.model_validate_json(raw_json)


class LLMAdapterFactory:
    @staticmethod
    def get_adapter(
        provider: str,
        model_name: str,
        task_config: Optional[Dict[str, Any]] = None
    ) -> Optional[BaseLLMAdapter]:
        cfg = task_config or {}
        provider = (provider or "").upper()

        if provider in ["OPENROUTER", "OPENROUTER.AI"]:
            api_key = cfg.get("openrouter_api_key")
            if not api_key:
                try:
                    from ai_assistant.services import AIConfigService
                    api_key = AIConfigService.get_api_key("openrouter")
                except Exception:
                    api_key = os.environ.get("OPENROUTER_API_KEY")
            if not api_key:
                return None
            return OpenRouterAdapter(api_key=api_key, model_name=model_name or "deepseek/deepseek-chat")

        elif provider == "GEMINI":
            api_key = cfg.get("gemini_api_key")
            if not api_key:
                try:
                    from ai_assistant.services import AIConfigService
                    api_key = AIConfigService.get_api_key("gemini")
                except Exception:
                    try:
                        from devices.models import LLMConfig
                        conf = LLMConfig.objects.filter(provider="gemini", is_active=True).first()
                        if conf and conf.api_key:
                            api_key = conf.api_key
                    except Exception:
                        pass
                    if not api_key:
                        api_key = os.environ.get("GEMINI_API_KEY")
            if not api_key:
                return None
            return GeminiAdapter(api_key=api_key, model_name=model_name or "gemini-2.5-flash")

        return None


# ---------------------------------------------------------------------------
# GhostPilot Decision Engine
# ---------------------------------------------------------------------------

class GhostPilotDecisionEngine:
    """Universal Decision Brain with dynamic prompts and models sourced from DB."""

    @classmethod
    def resolve_stuck_state(
        cls,
        job: Union[Execution, TaskExecutionQueue, Any],
        page_snapshot: Dict[str, Any],
        image_base64: Optional[str] = None
    ) -> AgentRecoveryAction:
        # 1. Fetch active database configuration
        db_config = AIPromptConfig.get_active_config()
        task_cfg = (job.task.config if (hasattr(job, "task") and job.task and isinstance(job.task.config, dict)) else {})

        # 2. Determine provider and model (task-level override takes precedence)
        provider = task_cfg.get("ai_provider") or os.environ.get("AI_PROVIDER") or db_config.provider
        model_name = task_cfg.get("ai_model") or os.environ.get("AI_MODEL") or db_config.model_name
        
        temp_val = task_cfg.get("temperature")
        if temp_val is None:
            temp_val = os.environ.get("AI_TEMPERATURE")
        if temp_val is None:
            temp_val = db_config.temperature
        try:
            temperature = float(temp_val)
        except (ValueError, TypeError):
            temperature = 0.1

        adapter = LLMAdapterFactory.get_adapter(
            provider=provider,
            model_name=model_name,
            task_config=task_cfg
        )

        entry_state = getattr(job, "entry_state_id", None)
        compiled_dag = getattr(job, "compiled_dag", {}) or {}
        states = (compiled_dag.get("states") if isinstance(compiled_dag, dict) else {}) or {}
        if not entry_state or entry_state not in states:
            entry_state = compiled_dag.get("entry_state") if isinstance(compiled_dag, dict) else None
        valid_next_state = entry_state if (entry_state and entry_state in states) else None

        if not adapter:
            return AgentRecoveryAction(
                action="BÉZIER_SWIPE",
                swipe_direction="DOWN",
                next_state_override=valid_next_state,
                reasoning=f"No active API key found for provider '{provider}'. Fallback swipe executed."
            )

        # 3. Clean optional base64 image data URI prefix if provided
        clean_image_b64 = image_base64.strip() if isinstance(image_base64, str) else None
        if clean_image_b64 and "," in clean_image_b64 and "base64" in clean_image_b64:
            clean_image_b64 = clean_image_b64.split(",", 1)[1].strip()
        if not clean_image_b64:
            clean_image_b64 = None

        # 4. Format dynamic system prompt template with runtime variables
        raw_template = task_cfg.get("custom_system_prompt")
        if not raw_template:
            try:
                from ai_assistant.services import AIConfigService
                from ai_assistant.models import PromptCategory
                raw_template = AIConfigService.get_active_prompt(
                    PromptCategory.DAG_RECOVERY,
                    default=db_config.system_prompt
                )
            except Exception:
                raw_template = db_config.system_prompt

        replacements = {
            "{task_name}": job.task.name if (hasattr(job, "task") and job.task) else "Unknown",
            "{task_category}": job.task.category if (hasattr(job, "task") and job.task) else "General",
            "{current_state}": str(getattr(job, "current_state_id", "") or ""),
            "{execution_context}": json.dumps(getattr(job, "execution_context", {}) or {})
        }
        try:
            system_instruction = raw_template.format(
                task_name=replacements["{task_name}"],
                task_category=replacements["{task_category}"],
                current_state=replacements["{current_state}"],
                execution_context=replacements["{execution_context}"]
            )
        except Exception:
            # Fallback if operator typed broken format placeholders or included unescaped JSON braces
            system_instruction = raw_template
            for placeholder, val in replacements.items():
                system_instruction = system_instruction.replace(placeholder, str(val))

        prompt_text = f"Page Snapshot:\n{json.dumps(page_snapshot, indent=2)}"

        try:
            result = adapter.generate_action(
                system_instruction=system_instruction,
                prompt_text=prompt_text,
                temperature=temperature,
                image_base64=clean_image_b64
            )

            if result.next_state_override:
                override = result.next_state_override.strip()
                if override.lower() in ("", "null", "none"):
                    result.next_state_override = None
                else:
                    result.next_state_override = override

            current_logs = list(job.logs or []) if isinstance(job.logs, list) else []
            current_logs.append({
                "type": "AI_DECISION",
                "provider": adapter.__class__.__name__,
                "model": adapter.model_name,
                "state": getattr(job, "current_state_id", ""),
                "action": result.action,
                "reasoning": result.reasoning
            })
            job.logs = current_logs
            job.save()
            return result

        except Exception as e:
            fallback = AgentRecoveryAction(
                action="BÉZIER_SWIPE",
                swipe_direction="DOWN",
                next_state_override=valid_next_state,
                reasoning=f"LLM provider error ({str(e)}). Falling back to safe downward swipe."
            )
            current_logs = list(job.logs or []) if isinstance(job.logs, list) else []
            current_logs.append({"type": "AI_DECISION_FAILED", "error": str(e)})
            job.logs = current_logs
            job.save()
            return fallback
