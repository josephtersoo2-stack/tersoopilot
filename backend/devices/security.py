import base64
import hashlib
import logging
from cryptography.fernet import Fernet
from django.conf import settings

logger = logging.getLogger(__name__)

class SecretManager:
    """
    Symmetric encryption service for database fields (such as proxy passwords,
    auth tokens, and sensitive cookie payloads).
    """
    PREFIX = "enc:v1:"

    @classmethod
    def _get_fernet(cls) -> Fernet:
        secret = getattr(settings, "DATA_ENCRYPTION_KEY", None) or settings.SECRET_KEY
        derived = hashlib.sha256(secret.encode("utf-8")).digest()
        key = base64.urlsafe_b64encode(derived)
        return Fernet(key)

    @classmethod
    def encrypt(cls, value: str) -> str:
        """
        Encrypts a plaintext string into a versioned ciphertext.
        If already encrypted or empty, returns original value.
        """
        if value is None:
            return None
        if not value:
            return ""
        if value.startswith(cls.PREFIX):
            return value
        try:
            cipher = cls._get_fernet()
            ciphertext = cipher.encrypt(value.encode("utf-8")).decode("utf-8")
            return f"{cls.PREFIX}{ciphertext}"
        except Exception as e:
            logger.error(f"Failed to encrypt secret: {e}")
            return value

    @classmethod
    def decrypt(cls, value: str) -> str:
        """
        Decrypts an encrypted string. If value is unencrypted or empty,
        returns it directly for backward compatibility.
        """
        if value is None:
            return None
        if not value:
            return ""
        if not value.startswith(cls.PREFIX):
            return value
        raw_payload = value[len(cls.PREFIX):]
        try:
            cipher = cls._get_fernet()
            return cipher.decrypt(raw_payload.encode("utf-8")).decode("utf-8")
        except Exception as e:
            logger.error(f"Failed to decrypt secret: {e}")
            return ""
