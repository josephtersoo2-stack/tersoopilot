import logging
from typing import Dict, List, Optional, Type
from .base import BaseWorkflowAddon

logger = logging.getLogger(__name__)


class WorkflowRegistry:
    """
    Central registry for all modular platform automation workflows and addons.
    """
    _registry: Dict[str, BaseWorkflowAddon] = {}

    @classmethod
    def register(cls, addon: BaseWorkflowAddon) -> None:
        key = addon.workflow_id
        cls._registry[key] = addon
        logger.info("Registered workflow addon: %s (%s)", addon.name, addon.platform)

    @classmethod
    def get(cls, workflow_id: str) -> Optional[BaseWorkflowAddon]:
        return cls._registry.get(workflow_id)

    @classmethod
    def list_all(cls) -> List[Dict]:
        return [
            {
                "workflow_id": addon.workflow_id,
                "platform": addon.platform,
                "name": addon.name,
                "description": addon.description,
                "icon": addon.icon,
                "default_config": addon.get_default_config()
            }
            for addon in cls._registry.values()
        ]

    @classmethod
    def list_for_platform(cls, platform: str) -> List[Dict]:
        platform_upper = platform.upper()
        return [
            {
                "workflow_id": addon.workflow_id,
                "platform": addon.platform,
                "name": addon.name,
                "description": addon.description,
                "icon": addon.icon,
                "default_config": addon.get_default_config()
            }
            for addon in cls._registry.values()
            if addon.platform.upper() == platform_upper
        ]
