# Implementation Plan: Octo-Grade Mobile Anti-Detect Browser with AI Fingerprint Generator

**Architecture:** Hybrid (Django REST Framework + LLM Grounding Backend / Jetpack Compose + GeckoView Android Client)

**Target Reference:** Octo Browser Mobile UX (Multi-Profile Cards, Bottom Sheets, Native Isolation)

**Agent Directive:** Adhere strictly to this blueprint. Do not introduce userland prototype wrappers where native Gecko engine preferences apply. Implement strict type safety across the schema boundary.

---

## 1. System Architecture Overview

```text
┌────────────────────────────────────────────────────────┐
│               Android Client (Kotlin)                  │
│  - Jetpack Compose UI (Cards, BottomSheets, Dialogs)   │
│  - GeckoView Engine (Isolated Storage & Profiles)      │
│  - Retrofit HTTP Client (API Communication)            │
│  - Room Database (Local Encrypted Persistence)         │
└───────────────────────────▲────────────────────────────┘
                            │ REST API / JSON
┌───────────────────────────▼────────────────────────────┐
│          Django + Django REST Framework Backend        │
│  - /api/devices/generate/ (LLM + Search Grounding)     │
│  - /api/ip/lookup/ (Proxy Geolocation & Timezone)      │
│  - /api/profiles/ (Cloud Profile Synchronization CRUD) │
└───────────────────────────▲────────────────────────────┘
                            │ Structured Query + Search Tool
┌───────────────────────────▼────────────────────────────┐
│            Gemini 2.5 Flash + Google Search            │
│  - Hardware Spec Validation (SoC, GPU, Viewport, DPR)  │
│  - Strict JSON Output via Pydantic Schema              │
└────────────────────────────────────────────────────────┘
```

---

## 2. Python Backend (Django + Django REST Framework + LLM Grounding)

The backend is built with Django and Django REST Framework (DRF). It uses Gemini 2.5 Flash with search capabilities to query real-world device specifications (SoC, GPU, screen bounds, DPR) and returns a validated JSON fingerprint blueprint, with built-in model persistence for profiles.

### Backend Directory Structure

```text
backend/
├── manage.py
├── requirements.txt
├── .env
├── core/
│   ├── __init__.py
│   ├── settings.py
│   ├── urls.py
│   ├── wsgi.py
│   └── asgi.py
└── devices/
    ├── __init__.py
    ├── apps.py
    ├── models.py
    ├── serializers.py
    ├── services.py
    ├── urls.py
    └── views.py
```

### File: `backend/requirements.txt`

```text
Django>=5.1.0
djangorestframework>=3.15.2
django-cors-headers>=4.4.0
pydantic>=2.8.0
google-genai>=0.1.1
requests>=2.32.3
python-dotenv>=1.0.1
psycopg2-binary>=2.9.9
```

### File: `backend/.env`

```ini
SECRET_KEY=django-insecure-your-production-secret-key-here
DEBUG=True
GEMINI_API_KEY=your_gemini_api_key_here
ALLOWED_HOSTS=localhost,127.0.0.1,10.0.2.2,*
```

### File: `backend/core/settings.py`

```python
import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = os.getenv("SECRET_KEY", "insecure-key-for-dev")
DEBUG = os.getenv("DEBUG", "True") == "True"
ALLOWED_HOSTS = os.getenv("ALLOWED_HOSTS", "*").split(",")

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    # Third-party apps
    "rest_framework",
    "corsheaders",
    # Local apps
    "devices",
]

MIDDLEWARE = [
    "corsheaders.middleware.CorsMiddleware",
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "core.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "core.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

CORS_ALLOW_ALL_ORIGINS = True

REST_FRAMEWORK = {
    "DEFAULT_RENDERER_CLASSES": [
        "rest_framework.renderers.JSONRenderer",
    ],
    "DEFAULT_PARSER_CLASSES": [
        "rest_framework.parsers.JSONParser",
    ],
}
```

### File: `backend/core/urls.py`

```python
from django.contrib import admin
from django.urls import path, include

urlpatterns = [
    path("admin/", admin.site.urls),
    path("api/", include("devices.urls")),
]
```

### File: `backend/devices/models.py`

```python
import uuid
from django.db import models

class SavedProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=150)
    brand = models.CharField(max_length=100)
    model_name = models.CharField(max_length=150)
    model_code = models.CharField(max_length=100)
    android_version = models.IntegerField(default=14)
    soc = models.CharField(max_length=150)
    webgl_vendor = models.CharField(max_length=100)
    webgl_renderer = models.CharField(max_length=150)
    ram_gb = models.IntegerField(default=8)
    cpu_cores = models.IntegerField(default=8)
    screen_width = models.IntegerField(default=384)
    screen_height = models.IntegerField(default=854)
    dpr = models.FloatField(default=2.8125)
    user_agent = models.TextField()
    
    # Proxy configurations
    proxy_type = models.CharField(max_length=20, default="DIRECT")
    proxy_host = models.CharField(max_length=255, blank=True, default="")
    proxy_port = models.IntegerField(default=0)
    proxy_user = models.CharField(max_length=100, blank=True, default="")
    proxy_pass = models.CharField(max_length=100, blank=True, default="")
    
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self):
        return f"{self.name} ({self.model_name})"
```

### File: `backend/devices/services.py`

```python
import os
import json
from google import genai
from google.genai import types
from pydantic import BaseModel, Field

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


def generate_device_specs_with_llm(device_query: str) -> dict:
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("GEMINI_API_KEY environment variable is not configured.")

    client = genai.Client(api_key=api_key)

    system_instruction = (
        "You are an expert mobile hardware and anti-detect fingerprinting engineer. "
        "Use web search to look up verified hardware specifications for the target mobile device. "
        "Ensure the GPU renderer, SoC, CSS viewport resolution, DPR, and model code match real-world devices. "
        "The user_agent MUST be an authentic Mobile Firefox on Android string: "
        "'Mozilla/5.0 (Android {android_version}; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0'."
    )

    response = client.models.generate_content(
        model='gemini-2.5-flash',
        contents=f"Retrieve and build complete fingerprint specifications for device: {device_query}",
        config=types.GenerateContentConfig(
            system_instruction=system_instruction,
            tools=[types.Tool(google_search=types.GoogleSearch())],
            response_mime_type="application/json",
            response_schema=DeviceFingerprintSchema,
            temperature=0.1
        )
    )

    return json.loads(response.text)
```

### File: `backend/devices/serializers.py`

```python
from rest_framework import serializers
from .models import SavedProfile

class DeviceGenerateRequestSerializer(serializers.Serializer):
    query = serializers.CharField(max_length=255, required=True)

class DeviceFingerprintResponseSerializer(serializers.Serializer):
    brand = serializers.CharField()
    model_name = serializers.CharField()
    model_code = serializers.CharField()
    android_version = serializers.IntegerField()
    soc = serializers.CharField()
    webgl_vendor = serializers.CharField()
    webgl_renderer = serializers.CharField()
    ram_gb = serializers.IntegerField()
    cpu_cores = serializers.IntegerField()
    screen_width = serializers.IntegerField()
    screen_height = serializers.IntegerField()
    dpr = serializers.FloatField()
    user_agent = serializers.CharField()

class IPLookupResponseSerializer(serializers.Serializer):
    ip = serializers.CharField()
    country = serializers.CharField()
    country_code = serializers.CharField()
    city = serializers.CharField()
    timezone = serializers.CharField()
    latitude = serializers.FloatField()
    longitude = serializers.FloatField()

class SavedProfileSerializer(serializers.ModelSerializer):
    class Meta:
        model = SavedProfile
        fields = "__all__"
```

### File: `backend/devices/views.py`

```python
import requests
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status, viewsets
from .models import SavedProfile
from .serializers import (
    DeviceGenerateRequestSerializer,
    DeviceFingerprintResponseSerializer,
    IPLookupResponseSerializer,
    SavedProfileSerializer,
)
from .services import generate_device_specs_with_llm

class GenerateDeviceProfileView(APIView):
    def post(self, request):
        input_serializer = DeviceGenerateRequestSerializer(data=request.data)
        if not input_serializer.is_valid():
            return Response(input_serializer.errors, status=status.HTTP_400_BAD_REQUEST)

        query = input_serializer.validated_data["query"]

        try:
            device_specs = generate_device_specs_with_llm(query)
            output_serializer = DeviceFingerprintResponseSerializer(data=device_specs)
            output_serializer.is_valid(raise_exception=True)
            return Response(output_serializer.data, status=status.HTTP_200_OK)
        except Exception as e:
            return Response(
                {"error": f"Failed to generate specs: {str(e)}"},
                status=status.HTTP_500_INTERNAL_SERVER_ERROR
            )

class IPLookupView(APIView):
    def get(self, request):
        target_ip = request.query_params.get("ip", "")
        lookup_url = f"https://ipapi.co/{target_ip + '/' if target_ip else ''}json/"

        try:
            resp = requests.get(lookup_url, timeout=5.0)
            if resp.status_code != 200:
                return Response({"error": "Failed to query IP service"}, status=status.HTTP_502_BAD_GATEWAY)

            data = resp.json()
            payload = {
                "ip": data.get("ip", "Unknown"),
                "country": data.get("country_name", "Unknown"),
                "country_code": data.get("country_code", "US"),
                "city": data.get("city", "Unknown"),
                "timezone": data.get("timezone", "UTC"),
                "latitude": float(data.get("latitude", 0.0)),
                "longitude": float(data.get("longitude", 0.0)),
            }
            serializer = IPLookupResponseSerializer(data=payload)
            serializer.is_valid(raise_exception=True)
            return Response(serializer.data, status=status.HTTP_200_OK)
        except Exception as e:
            return Response({"error": str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

class SavedProfileViewSet(viewsets.ModelViewSet):
    """
    CRUD endpoints for syncing profiles across cloud and devices.
    """
    queryset = SavedProfile.objects.all().order_by("-updated_at")
    serializer_class = SavedProfileSerializer
```

### File: `backend/devices/urls.py`

```python
from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import GenerateDeviceProfileView, IPLookupView, SavedProfileViewSet

router = DefaultRouter()
router.register(r"profiles", SavedProfileViewSet, basename="saved-profile")

urlpatterns = [
    path("devices/generate/", GenerateDeviceProfileView.as_view(), name="generate-device"),
    path("ip/lookup/", IPLookupView.as_view(), name="ip-lookup"),
    path("", include(router.urls)),
]
```

---

## 3. Android Client Dependencies & Permissions

### File: `app/build.gradle`

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    namespace 'com.multibrowser.antidetect'
    compileSdk 35

    defaultConfig {
        applicationId "com.multibrowser.antidetect"
        minSdk 24
        targetSdk 35
        versionCode 2
        versionName "2.0.0"

        ndk {
            abiFilters "arm64-v8a", "armeabi-v7a"
        }
    }

    buildFeatures {
        compose true
        viewBinding true
    }

    composeOptions {
        kotlinCompilerExtensionVersion '1.5.14'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    // Core & Compose
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.4'
    implementation 'androidx.activity:activity-compose:1.9.1'
    implementation platform('androidx.compose:compose-bom:2024.06.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.material3:material3:1.2.1'
    implementation 'androidx.compose.material:material-icons-extended'

    // Mozilla GeckoView Engine
    implementation "org.mozilla.geckoview:geckoview:135.0.20250130090000"

    // Local Storage: Room
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"

    // Networking: Retrofit & OkHttp
    implementation 'com.squareup.retrofit2:retrofit:2.11.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.11.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
}
```

---

## 4. Local Database & Profile Data Models

### File: `app/src/main/java/com/multibrowser/antidetect/data/model/ProfileEntity.kt`

```kotlin
package com.multibrowser.antidetect.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "browser_profiles")
data class ProfileEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val tag: String = "",
    val brand: String,
    val modelName: String,
    val modelCode: String,
    val androidVersion: Int = 14,
    val userAgent: String,
    val soc: String,
    val webGlVendor: String,
    val webGlRenderer: String,
    val ramGb: Int,
    val cpuCores: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val dpr: Double,
    // Proxy & Location Configuration
    val proxyType: String = "DIRECT", // DIRECT, HTTP, SOCKS5
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val timezone: String = "Auto",
    val language: String = "Auto",
    val webRtcMode: String = "Mdns", // Mdns, Disabled, Direct
    // Runtime Metadata
    val lastUsedTimestamp: Long = 0L,
    val cookieCount: Int = 0,
    val selectedCameraVideoPath: String? = null
)
```

### File: `app/src/main/java/com/multibrowser/antidetect/data/db/ProfileDao.kt`

```kotlin
package com.multibrowser.antidetect.data.db

import androidx.room.*
import com.multibrowser.antidetect.data.model.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM browser_profiles ORDER BY lastUsedTimestamp DESC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM browser_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("UPDATE browser_profiles SET lastUsedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)

    @Query("UPDATE browser_profiles SET cookieCount = :count WHERE id = :id")
    suspend fun updateCookieCount(id: String, count: Int)
}
```

### File: `app/src/main/java/com/multibrowser/antidetect/data/db/AppDatabase.kt`

```kotlin
package com.multibrowser.antidetect.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity

@Database(entities = [ProfileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "antidetect_browser.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

---

## 5. API Client Service

### File: `app/src/main/java/com/multibrowser/antidetect/network/ApiClient.kt`

```kotlin
package com.multibrowser.antidetect.network

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

data class GenerateDeviceRequest(val query: String)

data class DeviceFingerprintDto(
    val brand: String,
    @SerializedName("model_name") val modelName: String,
    @SerializedName("model_code") val modelCode: String,
    @SerializedName("android_version") val androidVersion: Int,
    val soc: String,
    @SerializedName("webgl_vendor") val webGlVendor: String,
    @SerializedName("webgl_renderer") val webGlRenderer: String,
    @SerializedName("ram_gb") val ramGb: Int,
    @SerializedName("cpu_cores") val cpuCores: Int,
    @SerializedName("screen_width") val screenWidth: Int,
    @SerializedName("screen_height") val screenHeight: Int,
    val dpr: Double,
    @SerializedName("user_agent") val userAgent: String
)

data class IPLookupDto(
    val ip: String,
    val country: String,
    @SerializedName("country_code") val countryCode: String,
    val city: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double
)

interface ApiService {
    @POST("/api/devices/generate/")
    suspend fun generateDevice(@Body request: GenerateDeviceRequest): DeviceFingerprintDto

    @GET("/api/ip/lookup/")
    suspend fun lookupIp(@Query("ip") ip: String? = null): IPLookupDto
}

object RetrofitInstance {
    // Point to your FastAPI backend IP/host
    private const val BASE_URL = "http://10.0.2.2:8000" 

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
```

---

## 6. GeckoView Engine Initialization & Sandboxing

### File: `app/src/main/java/com/multibrowser/antidetect/engine/GeckoProfileEngine.kt`

```kotlin
package com.multibrowser.antidetect.engine

import android.content.Context
import com.multibrowser.antidetect.data.model.ProfileEntity
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.io.FileWriter

class GeckoProfileEngine(private val context: Context) {

    private var activeRuntime: GeckoRuntime? = null
    private var activeSession: GeckoSession? = null
    private var nativePort: WebExtension.Port? = null

    fun launchProfile(profile: ProfileEntity): GeckoSession {
        // Stop current session if running
        stopCurrentSession()

        // 1. Filesystem Sandbox per Profile ID
        val profileDirectory = File(context.filesDir, "profiles/${profile.id}").apply {
            if (!exists()) mkdirs()
        }

        // 2. Generate native YAML preferences (C++ ResistFingerprinting + WebRTC controls)
        val configFile = File(profileDirectory, "geckoview-config.yaml")
        generateYamlConfiguration(configFile, profile)

        // 3. Instantiate Sandboxed GeckoRuntime
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .configFilePath(configFile.absolutePath)
            .consoleOutput(false)
            .build()

        val runtime = GeckoRuntime.create(context, runtimeSettings)
        this.activeRuntime = runtime

        // 4. Install Background Extension Bridge for Native Callbacks
        installExtensionBridge(runtime, profile)

        // 5. Initialize GeckoSession
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentOverride(profile.userAgent)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        session.open(runtime)
        this.activeSession = session

        return session
    }

    private fun generateYamlConfiguration(targetFile: File, profile: ProfileEntity) {
        val webRtcArgs = when (profile.webRtcMode) {
            "Disabled" -> """
              - "--pref"
              - "media.peerconnection.enabled=false"
            """.trimIndent()
            "Direct" -> """
              - "--pref"
              - "media.peerconnection.ice.default_address_only=false"
            """.trimIndent()
            else -> """
              - "--pref"
              - "media.peerconnection.ice.default_address_only=true"
              - "--pref"
              - "media.peerconnection.ice.no_host=true"
            """.trimIndent()
        }

        val proxyArgs = if (profile.proxyType != "DIRECT" && profile.proxyHost.isNotBlank()) {
            val pType = if (profile.proxyType == "SOCKS5") 2 else 1
            """
              - "--pref"
              - "network.proxy.type=$pType"
              - "--pref"
              - "network.proxy.http=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.http_port=${profile.proxyPort}"
              - "--pref"
              - "network.proxy.ssl=${profile.proxyHost}"
              - "--pref"
              - "network.proxy.ssl_port=${profile.proxyPort}"
            """.trimIndent()
        } else ""

        val yaml = """
            env:
              MOZ_REMOTE_SETTINGS_DEV: "1"
            args:
              - "--pref"
              - "privacy.resistFingerprinting=true"
              - "--pref"
              - "privacy.resistFingerprinting.autoDeclineNoUserInputCanvasPrompts=true"
              - "--pref"
              - "privacy.reduceTimerPrecision=true"
              - "--pref"
              - "privacy.resistFingerprinting.reduceTimerPrecision.microseconds=20000"
              $webRtcArgs
              $proxyArgs
        """.trimIndent()

        FileWriter(targetFile, false).use { it.write(yaml) }
    }

    private fun installExtensionBridge(runtime: GeckoRuntime, profile: ProfileEntity) {
        val extensionUri = "resource://android/assets/extensions/antidetect/"
        runtime.webExtensionController.installBuiltIn(extensionUri)
            .accept(
                { extension ->
                    extension?.let { ext ->
                        runtime.webExtensionController.setMessageDelegate(
                            ext,
                            object : WebExtension.MessageDelegate {
                                override fun onConnect(port: WebExtension.Port) {
                                    nativePort = port
                                    sendConfigPayload(profile)
                                }
                            },
                            "antidetect_bridge"
                        )
                    }
                },
                { error -> error.printStackTrace() }
            )
    }

    private fun sendConfigPayload(profile: ProfileEntity) {
        val payload = JSONObject().apply {
            put("action", "SYNC_CONFIG")
            put("cores", profile.cpuCores)
            put("vendor", profile.webGlVendor)
            put("renderer", profile.webGlRenderer)
            put("fakeVideo", profile.selectedCameraVideoPath ?: "")
        }
        nativePort?.postMessage(payload)
    }

    fun stopCurrentSession() {
        activeSession?.close()
        activeSession = null
        activeRuntime = null
    }
}
```

---

## 7. WebExtension Assets (Pre-Execution Injection)

### File: `app/src/main/assets/extensions/antidetect/manifest.json`

```json
{
  "manifest_version": 2,
  "name": "OctoAntiDetectEngine",
  "version": "2.0",
  "permissions": ["<all_urls>", "nativeMessaging", "geckoViewAddons"],
  "background": {
    "scripts": ["background.js"]
  },
  "content_scripts": [
    {
      "matches": ["<all_urls>"],
      "js": ["content.js"],
      "run_at": "document_start",
      "all_frames": true,
      "match_about_blank": true
    }
  ]
}
```

### File: `app/src/main/assets/extensions/antidetect/background.js`

```javascript
let currentSettings = null;
const bridgePort = browser.runtime.connectNative("antidetect_bridge");

bridgePort.onMessage.addListener((msg) => {
  if (msg.action === "SYNC_CONFIG") {
    currentSettings = msg;
  }
});

browser.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.query === "GET_HARDWARE_CONFIG") {
    sendResponse({ settings: currentSettings });
  }
  return true;
});
```

### File: `app/src/main/assets/extensions/antidetect/content.js`

```javascript
(() => {
  'use strict';

  browser.runtime.sendMessage({ query: "GET_HARDWARE_CONFIG" }, (response) => {
    if (!response || !response.settings) return;
    const s = response.settings;

    const injectionPayload = `
      (() => {
        'use strict';
        const nativeToString = Function.prototype.toString;
        const nativeMap = new WeakMap();

        function disguise(fn, name) {
          nativeMap.set(fn, 'function ' + name + '() { [native code] }');
          return fn;
        }

        Function.prototype.toString = new Proxy(nativeToString, {
          apply(target, thisArg, args) {
            if (nativeMap.has(thisArg)) return nativeMap.get(thisArg);
            return Reflect.apply(target, thisArg, args);
          }
        });
        disguise(Function.prototype.toString, 'toString');

        // 1. Hardware Concurrency
        const hcDesc = Object.getOwnPropertyDescriptor(Navigator.prototype, 'hardwareConcurrency');
        if (hcDesc) {
          Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
            get: disguise(() => ${s.cores}, 'get hardwareConcurrency'),
            configurable: true,
            enumerable: true
          });
        }

        // 2. WebGL Parameter Masking
        const hookGL = (proto) => {
          if (!proto) return;
          const origParam = proto.prototype.getParameter;
          proto.prototype.getParameter = disguise(function(param) {
            if (param === 0x9245) return '${s.vendor}';
            if (param === 0x9246) return '${s.renderer}';
            return origParam.call(this, param);
          }, 'getParameter');
        };

        if (window.WebGLRenderingContext) hookGL(WebGLRenderingContext);
        if (window.WebGL2RenderingContext) hookGL(WebGL2RenderingContext);

        // 3. Camera Emulation Hook (if enabled)
        ${s.fakeVideo ? `
        if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
          const origGUM = navigator.mediaDevices.getUserMedia;
          navigator.mediaDevices.getUserMedia = disguise(async function(constraints) {
            if (constraints && constraints.video) {
              const video = document.createElement('video');
              video.src = '${s.fakeVideo}';
              video.crossOrigin = 'anonymous';
              video.loop = true;
              video.muted = true;
              await video.play();
              return video.captureStream();
            }
            return origGUM.call(this, constraints);
          }, 'getUserMedia');
        }
        ` : ''}
      })();
    `;

    const script = document.createElement('script');
    script.textContent = injectionPayload;
    (document.head || document.documentElement).appendChild(script);
    script.remove();
  });
})();
```

---

## 8. Jetpack Compose UI (Octo-Style Experience)

### File: `app/src/main/java/com/multibrowser/antidetect/ui/theme/Theme.kt`

```kotlin
package com.multibrowser.antidetect.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF34C759),
    background = Color(0xFF121214),
    surface = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    onBackground = Color(0xFFE5E5EA),
    onSurface = Color(0xFFFFFFFF),
    outline = Color(0xFF2C2C2E)
)

@Composable
fun OctoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
```

### File: `app/src/main/java/com/multibrowser/antidetect/ui/components/CreateProfileBottomSheet.kt`

```kotlin
package com.multibrowser.antidetect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.network.GenerateDeviceRequest
import com.multibrowser.antidetect.network.RetrofitInstance
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProfileBottomSheet(
    onDismiss: () -> Unit,
    onSaveProfile: (ProfileEntity) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var profileName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var aiQuery by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    // Fingerprint parameters
    var selectedModel by remember { mutableStateOf("Select or generate...") }
    var selectedModelCode by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var androidVersion by remember { mutableStateOf(14) }
    var userAgent by remember { mutableStateOf("Mozilla/5.0 (Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0") }
    var soc by remember { mutableStateOf("") }
    var webGlVendor by remember { mutableStateOf("ARM") }
    var webGlRenderer by remember { mutableStateOf("Mali-G68 MP5") }
    var ramGb by remember { mutableStateOf(8) }
    var cpuCores by remember { mutableStateOf(8) }
    var screenWidth by remember { mutableStateOf(384) }
    var screenHeight by remember { mutableStateOf(854) }
    var dpr by remember { mutableStateOf(2.8125) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Create Profile", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Profile Name") },
                modifier = Modifier.fillMaxWidth()
            )

            // AI Device Generator Box
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI Device Blueprint Generator", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = aiQuery,
                            onValueChange = { aiQuery = it },
                            placeholder = { Text("e.g. Samsung Galaxy A23 5G") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (aiQuery.isNotBlank()) {
                                    coroutineScope.launch {
                                        isGenerating = true
                                        try {
                                            val res = RetrofitInstance.api.generateDevice(GenerateDeviceRequest(aiQuery))
                                            brand = res.brand
                                            selectedModel = res.modelName
                                            selectedModelCode = res.modelCode
                                            androidVersion = res.androidVersion
                                            soc = res.soc
                                            webGlVendor = res.webGlVendor
                                            webGlRenderer = res.webGlRenderer
                                            ramGb = res.ramGb
                                            cpuCores = res.cpuCores
                                            screenWidth = res.screenWidth
                                            screenHeight = res.screenHeight
                                            dpr = res.dpr
                                            userAgent = res.userAgent
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            isGenerating = false
                                        }
                                    }
                                }
                            }
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Generate", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Specs Confirmation
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Assigned Model: $selectedModel ($selectedModelCode)", style = MaterialTheme.typography.bodyMedium)
                Text("SoC: $soc | GPU: $webGlRenderer", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("Resolution: ${screenWidth}x${screenHeight} @ ${dpr}x", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            Button(
                onClick = {
                    val profile = ProfileEntity(
                        name = if (profileName.isBlank()) "Profile ${System.currentTimeMillis() % 1000}" else profileName,
                        description = description,
                        brand = brand,
                        modelName = selectedModel,
                        modelCode = selectedModelCode,
                        androidVersion = androidVersion,
                        userAgent = userAgent,
                        soc = soc,
                        webGlVendor = webGlVendor,
                        webGlRenderer = webGlRenderer,
                        ramGb = ramGb,
                        cpuCores = cpuCores,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        dpr = dpr
                    )
                    onSaveProfile(profile)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Profile")
            }
        }
    }
}
```

### File: `app/src/main/java/com/multibrowser/antidetect/ui/components/ActiveSessionBottomSheet.kt`

```kotlin
package com.multibrowser.antidetect.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multibrowser.antidetect.data.model.ProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionBottomSheet(
    profile: ProfileEntity,
    liveIp: String,
    location: String,
    onStopSession: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text("$liveIp • $location", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF34C759))
                }
                IconButton(onClick = { /* Handle copy */ }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy details")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Stored Cookies")
                Text("${profile.cookieCount}")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Camera Video Emulation")
                Text(if (profile.selectedCameraVideoPath == null) "None" else "Active", color = Color.Gray)
            }

            Button(
                onClick = onStopSession,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Profile")
            }
        }
    }
}
```

### File: `app/src/main/java/com/multibrowser/antidetect/ui/MainActivity.kt`

```kotlin
package com.multibrowser.antidetect.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.multibrowser.antidetect.data.db.AppDatabase
import com.multibrowser.antidetect.data.model.ProfileEntity
import com.multibrowser.antidetect.engine.GeckoProfileEngine
import com.multibrowser.antidetect.network.RetrofitInstance
import com.multibrowser.antidetect.ui.components.ActiveSessionBottomSheet
import com.multibrowser.antidetect.ui.components.CreateProfileBottomSheet
import com.multibrowser.antidetect.ui.theme.OctoTheme
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var engine: GeckoProfileEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        engine = GeckoProfileEngine(this)

        setContent {
            OctoTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val profiles by db.profileDao().getAllProfiles().collectAsState(initial = emptyList())
        var activeProfile by remember { mutableStateOf<ProfileEntity?>(null) }
        var currentSession by remember { mutableStateOf<GeckoSession?>(null) }
        var showCreateSheet by remember { mutableStateOf(false) }
        var showActiveSheet by remember { mutableStateOf(false) }
        var liveIp by remember { mutableStateOf("Fetching...") }
        var location by remember { mutableStateOf("Detecting...") }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Profiles (${profiles.size})") },
                    actions = {
                        if (activeProfile != null) {
                            FilterChip(
                                selected = true,
                                onClick = { showActiveSheet = true },
                                label = { Text(activeProfile!!.name) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF34C759), CircleShape)
                                    )
                                }
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Profile")
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (currentSession != null) {
                    // Active Browser Rendering Layer
                    AndroidView(
                        factory = { ctx ->
                            GeckoView(ctx).apply {
                                setSession(currentSession!!)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Profile Selection Cards
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(profiles) { profile ->
                            ProfileCard(
                                profile = profile,
                                onLaunch = {
                                    val session = engine.launchProfile(profile)
                                    currentSession = session
                                    activeProfile = profile
                                    session.loadUri("https://iphey.com")

                                    // Resolve Network Status
                                    lifecycleScope.launch {
                                        db.profileDao().updateLastUsed(profile.id, System.currentTimeMillis())
                                        try {
                                            val info = RetrofitInstance.api.lookupIp()
                                            liveIp = info.ip
                                            location = "${info.city}, ${info.country}"
                                        } catch (e: Exception) {
                                            liveIp = "Direct IP"
                                            location = "Unknown"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (showCreateSheet) {
                CreateProfileBottomSheet(
                    onDismiss = { showCreateSheet = false },
                    onSaveProfile = { newProfile ->
                        lifecycleScope.launch {
                            db.profileDao().insertProfile(newProfile)
                            showCreateSheet = false
                        }
                    }
                )
            }

            if (showActiveSheet && activeProfile != null) {
                ActiveSessionBottomSheet(
                    profile = activeProfile!!,
                    liveIp = liveIp,
                    location = location,
                    onStopSession = {
                        engine.stopCurrentSession()
                        currentSession = null
                        activeProfile = null
                        showActiveSheet = false
                    },
                    onDismiss = { showActiveSheet = false }
                )
            }
        }
    }

    @Composable
    fun ProfileCard(profile: ProfileEntity, onLaunch: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onLaunch() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${profile.brand} ${profile.modelName} • Android ${profile.androidVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Text(
                        "${profile.screenWidth}x${profile.screenHeight} • ${profile.webGlRenderer}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                IconButton(onClick = onLaunch) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start Profile", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

---

## 9. Verification & Execution Steps

1. **Deploy and Run Backend (Django + DRF):**
* Apply migrations: `python manage.py makemigrations && python manage.py migrate`
* Start the development server: `python manage.py runserver 0.0.0.0:8000`
* Test `/api/devices/generate/` using `curl`:
  ```bash
  curl -X POST http://127.0.0.1:8000/api/devices/generate/ \
    -H "Content-Type: application/json" \
    -d '{"query": "OnePlus 12"}'
  ```
  Ensure valid JSON with an Adreno GPU renderer and matching CSS dimensions is returned.


2. **Compile Android Application:**
* Open the project in Android Studio with JDK 17.
* Verify Gradle sync fetches GeckoView and Jetpack Compose dependencies cleanly.
* Run the app on an Android device (`arm64-v8a`).


3. **Verify Fingerprint Quality:**
* In the app, tap the `+` button and request a "Samsung Galaxy A23 5G".
* Ensure the AI generator fills in `SM-A236B`, `Adreno (TM) 619`, and matching DPR.
* Launch the profile to `https://iphey.com` and `https://abrahamjuliot.github.io/creepjs/`.
* Verify that the profile achieves:
* **Iphey:** Status `Not detected`, MX Score `100`, matching hardware specs.
* **CreepJS:** 0% Headless, 0% Stealth, and zero WebRTC local IP leaks.
