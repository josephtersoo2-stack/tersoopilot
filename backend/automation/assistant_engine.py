"""
TersoAssistant Multi-Provider Agent Loop Engine
=================================================
Iterative tool-calling conversational engine supporting both Google Gemini
(Automatic Function Calling) and OpenRouter (OpenAI-standard tools JSON schemas).

The engine executes an autonomous loop:
  User prompt → LLM requests tool call → backend executes local Django tool →
  result returned to LLM → … → final conversational response.
"""

import os
import json
from typing import Optional
from automation.models import AssistantSession, AssistantMessage, AIPromptConfig
from automation.assistant_tools import (
    OPENROUTER_TOOLS,
    execute_tool,
    tool_get_fleet_status,
    tool_list_profiles,
    tool_create_niche,
    tool_set_profile_niches,
    tool_assign_niche_to_profile,
    tool_dispatch_campaign,
    tool_execute_task_on_profiles,
    tool_abort_job,
    tool_get_job_telemetry,
)


SYSTEM_INSTRUCTION = (
    "You are TersoAssistant, the intelligent operational AI for the TersoPilot "
    "anti-detect browser farm.\n"
    "You control and monitor distributed Android GeckoView runners, DAG state-machine "
    "tasks, cookie warming schedules, and persona trust scores.\n\n"
    "CRITICAL GUIDELINES ON PROFILES & NICHES:\n"
    "- NEVER ask the operator for raw UUIDs or Profile IDs when they mention a profile or niche by name (e.g., 'Infinix Hot 60 Pro', 'Gaming Enthusiasts').\n"
    "- All backend tools (assign_niche_to_profile, set_profile_niches, dispatch_campaign, execute_task_on_profiles) automatically resolve human-readable names and substrings.\n"
    "- If an operator asks to assign a niche to a profile (e.g. 'assign this niche to infinix hot 60 pro'), call assign_niche_to_profile(profile='Infinix Hot 60 Pro', niche='Gaming Enthusiasts') directly without asking for an ID.\n\n"
    "You have access to a suite of backend tools to query fleet status, inspect profiles, "
    "configure niches, compile and dispatch campaigns, execute tasks on selected profiles, and abort stuck jobs.\n"
    "When executing actions, clearly summarize what you did and report back metrics in clean Markdown.\n"
    "Be concise, decisive, and operational. Avoid asking questions when you can execute the tool."
)


def _format_page_context(page_context: Optional[dict]) -> str:
    """Formats live UI page context and selected files/profiles for the LLM."""
    if not page_context:
        return ""
    active_tab = page_context.get("active_tab", "UNKNOWN")
    page_name = page_context.get("page_name", active_tab)
    selected_items = page_context.get("selected_items", [])
    summary = page_context.get("summary", "")

    ctx = f"\n\n[CLIENT VIEWPORT CONTEXT - LIVE OPERATOR SCREEN]\n- Active Page: {page_name} (Tab ID: {active_tab})\n"
    if summary:
        ctx += f"- Viewport Summary: {summary}\n"
    if selected_items:
        ctx += f"- Selected Profiles/Files on Screen ({len(selected_items)}):\n"
        for item in selected_items:
            ctx += f"  * [ID: {item.get('id')}] {item.get('name')} | Details: {item.get('details', '')}\n"
    else:
        ctx += "- No profiles or files currently selected on this screen.\n"
    ctx += "- Guidance: If the operator requests actions on 'selected files', 'selected profiles', or 'these', target the IDs listed above directly using execute_task_on_profiles or dispatch_campaign.\n"
    return ctx


class TersoAssistantEngine:
    """
    Stateless engine class — every call reconstructs conversation history
    from the database so sessions survive across server restarts.
    """

    @classmethod
    def process_prompt(
        cls,
        session: AssistantSession,
        user_text: str,
        page_context: Optional[dict] = None,
        max_tool_iterations: int = 5
    ) -> str:
        """
        Entry point. Records the user message, determines the active AI provider,
        and dispatches to the appropriate tool-calling loop with optional page context.
        """
        # 1. Record incoming user message
        AssistantMessage.objects.create(
            session=session,
            role=AssistantMessage.RoleChoices.USER,
            content=user_text
        )

        # 2. Fetch active AI provider & config from the database
        cfg = AIPromptConfig.get_active_config()
        provider = cfg.provider
        model_name = cfg.model_name
        from django.conf import settings
        policy = "\nAssistant mode: read-only. Explain proposed changes; direct the operator to dashboard controls." if not settings.ASSISTANT_ALLOW_WRITES else ""
        system_instruction = SYSTEM_INSTRUCTION + policy + _format_page_context(page_context)

        if provider == "GEMINI":
            return cls._run_gemini_loop(
                session, user_text, model_name, system_instruction, max_tool_iterations
            )
        else:
            return cls._run_openrouter_loop(
                session, model_name, system_instruction, max_tool_iterations
            )

    # ------------------------------------------------------------------
    # OpenRouter / OpenAI-Compatible Tool-Calling Loop
    # ------------------------------------------------------------------

    @classmethod
    def _run_openrouter_loop(
        cls,
        session: AssistantSession,
        model_name: str,
        system_instruction: str,
        max_iterations: int,
    ) -> str:
        try:
            from ai_assistant.services import AIConfigService
            api_key = AIConfigService.get_api_key("openrouter")
        except Exception:
            api_key = os.environ.get("OPENROUTER_API_KEY")
        if not api_key:
            msg = "Error: OPENROUTER_API_KEY is not configured in backend environment."
            AssistantMessage.objects.create(
                session=session,
                role=AssistantMessage.RoleChoices.ASSISTANT,
                content=msg,
            )
            return msg

        from openai import OpenAI

        client = OpenAI(
            base_url="https://openrouter.ai/api/v1",
            api_key=api_key,
        )

        for _ in range(max_iterations):
            # Assemble full message history from DB
            db_messages = session.messages.all().order_by("created_at")
            messages_payload = [{"role": "system", "content": system_instruction}]

            for msg in db_messages:
                entry = {"role": msg.role, "content": msg.content or ""}
                if msg.tool_calls:
                    entry["tool_calls"] = msg.tool_calls
                    # OpenAI requires content to be None when tool_calls present
                    if not msg.content:
                        entry["content"] = None
                if msg.tool_call_id:
                    entry["tool_call_id"] = msg.tool_call_id
                messages_payload.append(entry)

            response = client.chat.completions.create(
                model=model_name,
                messages=messages_payload,
                tools=OPENROUTER_TOOLS,
                tool_choice="auto",
                temperature=0.2,
                extra_headers={
                    "HTTP-Referer": "https://tersopilot.browser.farm",
                    "X-Title": "TersoAssistant Fleet Copilot",
                },
            )

            choice = response.choices[0]
            message = choice.message

            # ── LLM requested tool execution ──
            if message.tool_calls:
                serialized_calls = [
                    {
                        "id": tc.id,
                        "type": tc.type,
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for tc in message.tool_calls
                ]

                AssistantMessage.objects.create(
                    session=session,
                    role=AssistantMessage.RoleChoices.ASSISTANT,
                    content=message.content or "",
                    tool_calls=serialized_calls,
                )

                # Execute each requested tool locally
                for tc in message.tool_calls:
                    fn_name = tc.function.name
                    args = json.loads(tc.function.arguments or "{}")
                    result = execute_tool(fn_name, args)

                    AssistantMessage.objects.create(
                        session=session,
                        role=AssistantMessage.RoleChoices.TOOL,
                        content=json.dumps(result, default=str),
                        tool_call_id=tc.id,
                    )
                # Continue loop so LLM can read tool results and reply
                continue

            # ── Final conversational response ──
            final_text = message.content or ""
            AssistantMessage.objects.create(
                session=session,
                role=AssistantMessage.RoleChoices.ASSISTANT,
                content=final_text,
            )
            return final_text

        return "Reached maximum tool iterations without a final response."

    # ------------------------------------------------------------------
    # Gemini Automatic Function Calling (AFC) Loop
    # ------------------------------------------------------------------

    @classmethod
    def _run_gemini_loop(
        cls,
        session: AssistantSession,
        user_text: str,
        model_name: str,
        system_instruction: str,
        max_iterations: int,
    ) -> str:
        try:
            from ai_assistant.services import AIConfigService
            api_key = AIConfigService.get_api_key("gemini")
        except Exception:
            api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            msg = "Error: GEMINI_API_KEY is not configured in backend environment."
            AssistantMessage.objects.create(
                session=session,
                role=AssistantMessage.RoleChoices.ASSISTANT,
                content=msg,
            )
            return msg

        client = genai.Client(api_key=api_key)

        # Gemini AFC: pass raw Python callables as tools —
        # the SDK auto-generates function declarations and executes the loop.
        gemini_tools = [
            tool_get_fleet_status,
            tool_list_profiles,
            tool_create_niche,
            tool_set_profile_niches,
            tool_assign_niche_to_profile,
            tool_dispatch_campaign,
            tool_execute_task_on_profiles,
            tool_abort_job,
            tool_get_job_telemetry,
        ]

        chat = client.chats.create(
            model=model_name or "gemini-2.5-flash",
            config=types.GenerateContentConfig(
                system_instruction=system_instruction,
                tools=gemini_tools,
                temperature=0.2,
            ),
        )

        response = chat.send_message(user_text)
        final_text = response.text or "Execution completed."

        AssistantMessage.objects.create(
            session=session,
            role=AssistantMessage.RoleChoices.ASSISTANT,
            content=final_text,
        )
        return final_text
