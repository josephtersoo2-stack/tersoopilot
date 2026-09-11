"""
Expiring token authentication backend.

Tokens older than TOKEN_EXPIRY_HOURS (default: 72h) are rejected and deleted,
forcing the client to re-authenticate.
"""
from datetime import timedelta
from django.conf import settings
from django.utils import timezone
from rest_framework.authentication import TokenAuthentication
from rest_framework.exceptions import AuthenticationFailed


class ExpiringTokenAuthentication(TokenAuthentication):
    """
    Token authentication that enforces expiry.

    Configure via the TOKEN_EXPIRY_HOURS setting (default: 72 hours).
    """

    def authenticate_credentials(self, key):
        user, token = super().authenticate_credentials(key)
        expiry_hours = getattr(settings, "TOKEN_EXPIRY_HOURS", 72)
        if expiry_hours and token.created < timezone.now() - timedelta(hours=expiry_hours):
            token.delete()
            raise AuthenticationFailed("Token has expired. Please log in again.")
        return user, token
