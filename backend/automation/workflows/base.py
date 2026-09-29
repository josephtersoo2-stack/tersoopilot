from abc import ABC, abstractmethod
from typing import Dict, Any, List, Optional
from devices.models import SavedProfile


class BaseWorkflowAddon(ABC):
    """
    Abstract base class for modular automation workflows.
    Each addon declares its target platform, unique strategy identifier,
    configuration schema, and compiler method.
    """

    @property
    @abstractmethod
    def platform(self) -> str:
        """e.g. YOUTUBE, WEBSITE, FACEBOOK, TWITTER, TIKTOK"""
        pass

    @property
    @abstractmethod
    def workflow_id(self) -> str:
        """e.g. youtube_search_discover"""
        pass

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable display name, e.g. 'Search & Discover (Rank Booster)'"""
        pass

    @property
    @abstractmethod
    def description(self) -> str:
        """Short summary of the workflow."""
        pass

    @property
    def icon(self) -> str:
        """Icon name for frontend rendering."""
        return "Search"

    def get_default_config(self) -> Dict[str, Any]:
        """Returns the default configuration dictionary."""
        return {}

    @abstractmethod
    def validate_config(self, config: Dict[str, Any]) -> None:
        """Raises serializers.ValidationError if config is invalid."""
        pass

    @abstractmethod
    def compile_dag(self, task, profile: SavedProfile, config: Dict[str, Any]) -> Dict[str, Any]:
        """Compiles the task and profile into an executable DAG dictionary."""
        pass
