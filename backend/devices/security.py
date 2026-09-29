import base64
import hashlib
import logging
import os
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from django.conf import settings

logger = logging.getLogger(__name__)

class SecretManager:
    """
    Enterprise-grade AES-256-GCM symmetric authenticated encryption service for 
    database fields (proxy credentials, session cookies, auth tokens).
    
    Supports:
    - enc:v2: AES-256-GCM with 96-bit CSPRNG nonce and 128-bit GMAC authentication tag (NIST SP 800-38D).
    - enc:v1: Legacy Fernet (AES-128-CBC + HMAC-SHA256) backward compatibility.
    """
    PREFIX_V1 = "enc:v1:"
    PREFIX_V2 = "enc:v2:"
    PREFIX = PREFIX_V1

    @classmethod
    def _get_256bit_key(cls) -> bytes:
        secret = getattr(settings, "DATA_ENCRYPTION_KEY", None) or settings.SECRET_KEY
        return hashlib.sha256(secret.encode("utf-8")).digest()

    @classmethod
    def _get_fernet(cls) -> Fernet:
        key = base64.urlsafe_b64encode(cls._get_256bit_key())
        return Fernet(key)

    @classmethod
    def encrypt(cls, value: str) -> str:
        """
        Encrypts a plaintext string using authenticated symmetric encryption (Fernet AES-128-CBC + HMAC-SHA256).
        If already encrypted or empty, returns original value.
        """
        if value is None:
            return None
        if not value:
            return ""
        if value.startswith(cls.PREFIX_V1) or value.startswith(cls.PREFIX_V2):
            return value
        try:
            cipher = cls._get_fernet()
            ciphertext = cipher.encrypt(value.encode("utf-8")).decode("utf-8")
            return f"{cls.PREFIX_V1}{ciphertext}"
        except Exception as e:
            logger.error(f"Failed to encrypt secret: {e}")
            return value

    @classmethod
    def decrypt(cls, value: str) -> str:
        """
        Decrypts an encrypted string. Supports both enc:v2: (AES-256-GCM) and enc:v1: (Fernet).
        Returns plaintext directly if unencrypted or empty.
        """
        if value is None:
            return None
        if not value:
            return ""

        # V2: Enterprise AES-256-GCM
        if value.startswith(cls.PREFIX_V2):
            raw_payload = value[len(cls.PREFIX_V2):]
            try:
                raw_bytes = base64.b64decode(raw_payload)
                if len(raw_bytes) < 28:  # 12 bytes nonce + at least 16 bytes tag
                    raise ValueError("Ciphertext payload too short for AES-GCM")
                nonce = raw_bytes[:12]
                ciphertext = raw_bytes[12:]
                key = cls._get_256bit_key()
                aesgcm = AESGCM(key)
                plaintext_bytes = aesgcm.decrypt(nonce, ciphertext, None)
                return plaintext_bytes.decode("utf-8")
            except Exception as e:
                logger.error(f"AES-256-GCM decryption / authentication failure: {e}")
                return ""

        # V1: Legacy Fernet fallback
        if value.startswith(cls.PREFIX_V1):
            raw_payload = value[len(cls.PREFIX_V1):]
            try:
                cipher = cls._get_fernet()
                return cipher.decrypt(raw_payload.encode("utf-8")).decode("utf-8")
            except Exception as e:
                logger.error(f"Legacy Fernet decryption failure: {e}")
                return ""

        return value
