from automation.models import (
    AssistantSession as AutomationAssistantSession,
    AssistantMessage as AutomationAssistantMessage,
    AIPromptConfig as AutomationAIPromptConfig,
)

class AssistantSession(AutomationAssistantSession):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "Assistant Session"
        verbose_name_plural = "Assistant Sessions"


class AssistantMessage(AutomationAssistantMessage):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "Assistant Message"
        verbose_name_plural = "Assistant Messages"


class AIPromptConfig(AutomationAIPromptConfig):
    class Meta:
        proxy = True
        app_label = "ai_assistant"
        verbose_name = "AI Prompt Configuration"
        verbose_name_plural = "AI Prompt Configurations"
