from .base import BaseWorkflowAddon
from .registry import WorkflowRegistry
from .youtube_search_discover import YouTubeSearchDiscoverAddon
from .youtube_shorts_surfer import YouTubeShortsSurferAddon
from .website_traffic import WebsiteTrafficAddon

# Register default core addons
WorkflowRegistry.register(YouTubeSearchDiscoverAddon())
WorkflowRegistry.register(YouTubeShortsSurferAddon())
WorkflowRegistry.register(WebsiteTrafficAddon())

__all__ = [
    "BaseWorkflowAddon",
    "WorkflowRegistry",
    "YouTubeSearchDiscoverAddon",
    "YouTubeShortsSurferAddon",
    "WebsiteTrafficAddon"
]
