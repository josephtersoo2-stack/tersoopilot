# Source and tooling inventory

Generated from existing non-ignored repository files. This inventories file roles and sizes; it does not certify that every branch has been exercised. Historical specifications are in archive/. Binary icons, lockfile contents, and generated caches are omitted.

| File | Area | Lines |
|---|---|---|
| [.editorconfig](../.editorconfig) | Project tooling | 11 |
| [.github/workflows/ci.yml](../.github/workflows/ci.yml) | Project tooling | 55 |
| [.gitignore](../.gitignore) | Project tooling | 48 |
| [README.md](../README.md) | Project tooling | 55 |
| [admin-panel/.env.example](../admin-panel/.env.example) | Frontend infrastructure | 2 |
| [admin-panel/index.html](../admin-panel/index.html) | Frontend infrastructure | 16 |
| [admin-panel/package.json](../admin-panel/package.json) | Frontend infrastructure | 25 |
| [admin-panel/postcss.config.js](../admin-panel/postcss.config.js) | Frontend infrastructure | 6 |
| [admin-panel/src/App.jsx](../admin-panel/src/App.jsx) | Frontend infrastructure | 214 |
| [admin-panel/src/api.js](../admin-panel/src/api.js) | Frontend infrastructure | 41 |
| [admin-panel/src/auth/AuthGate.jsx](../admin-panel/src/auth/AuthGate.jsx) | Dashboard authentication | 65 |
| [admin-panel/src/components/automation/AIPromptsHub.jsx](../admin-panel/src/components/automation/AIPromptsHub.jsx) | Dashboard feature UI | 509 |
| [admin-panel/src/components/automation/ExecutionConsole.jsx](../admin-panel/src/components/automation/ExecutionConsole.jsx) | Dashboard feature UI | 412 |
| [admin-panel/src/components/automation/FloatingAssistant.jsx](../admin-panel/src/components/automation/FloatingAssistant.jsx) | Dashboard feature UI | 684 |
| [admin-panel/src/components/automation/NichesHub.jsx](../admin-panel/src/components/automation/NichesHub.jsx) | Dashboard feature UI | 275 |
| [admin-panel/src/components/automation/PersonaModal.jsx](../admin-panel/src/components/automation/PersonaModal.jsx) | Dashboard feature UI | 592 |
| [admin-panel/src/components/automation/ProfilesHub.jsx](../admin-panel/src/components/automation/ProfilesHub.jsx) | Dashboard feature UI | 358 |
| [admin-panel/src/components/automation/TaskDispatchModal.jsx](../admin-panel/src/components/automation/TaskDispatchModal.jsx) | Dashboard feature UI | 578 |
| [admin-panel/src/components/automation/TersoAssistantHub.jsx](../admin-panel/src/components/automation/TersoAssistantHub.jsx) | Dashboard feature UI | 502 |
| [admin-panel/src/components/hardware/HardwareBlueprintHub.jsx](../admin-panel/src/components/hardware/HardwareBlueprintHub.jsx) | Dashboard feature UI | 622 |
| [admin-panel/src/components/layout/Sidebar.jsx](../admin-panel/src/components/layout/Sidebar.jsx) | Dashboard feature UI | 204 |
| [admin-panel/src/components/layout/TopBar.jsx](../admin-panel/src/components/layout/TopBar.jsx) | Dashboard feature UI | 103 |
| [admin-panel/src/components/legacy/HardwareHub.jsx](../admin-panel/src/components/legacy/HardwareHub.jsx) | Dashboard feature UI | 989 |
| [admin-panel/src/components/system/RuntimeSettingsHub.jsx](../admin-panel/src/components/system/RuntimeSettingsHub.jsx) | Dashboard feature UI | 180 |
| [admin-panel/src/index.css](../admin-panel/src/index.css) | Frontend infrastructure | 27 |
| [admin-panel/src/lib/cookies.js](../admin-panel/src/lib/cookies.js) | Frontend infrastructure | 18 |
| [admin-panel/src/main.jsx](../admin-panel/src/main.jsx) | Frontend infrastructure | 10 |
| [admin-panel/tailwind.config.js](../admin-panel/tailwind.config.js) | Frontend infrastructure | 25 |
| [admin-panel/tests/cookies.test.js](../admin-panel/tests/cookies.test.js) | Frontend infrastructure | 19 |
| [admin-panel/vite.config.js](../admin-panel/vite.config.js) | Frontend infrastructure | 15 |
| [app/build.gradle.kts](../app/build.gradle.kts) | Project tooling | 101 |
| [app/proguard-rules.pro](../app/proguard-rules.pro) | Project tooling | 3 |
| [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | Project tooling | 27 |
| [app/src/main/assets/extensions/antidetect/background.js](../app/src/main/assets/extensions/antidetect/background.js) | Gecko extension | 84 |
| [app/src/main/assets/extensions/antidetect/content.js](../app/src/main/assets/extensions/antidetect/content.js) | Gecko extension | 354 |
| [app/src/main/assets/extensions/antidetect/manifest.json](../app/src/main/assets/extensions/antidetect/manifest.json) | Gecko extension | 37 |
| [app/src/main/assets/extensions/antidetect/perception.js](../app/src/main/assets/extensions/antidetect/perception.js) | Gecko extension | 112 |
| [app/src/main/java/com/multibrowser/antidetect/MainActivity.kt](../app/src/main/java/com/multibrowser/antidetect/MainActivity.kt) | Android application | 2105 |
| [app/src/main/java/com/multibrowser/antidetect/automation/GhostPilotRunner.kt](../app/src/main/java/com/multibrowser/antidetect/automation/GhostPilotRunner.kt) | Android application | 492 |
| [app/src/main/java/com/multibrowser/antidetect/automation/NativeGestureInjector.kt](../app/src/main/java/com/multibrowser/antidetect/automation/NativeGestureInjector.kt) | Android application | 135 |
| [app/src/main/java/com/multibrowser/antidetect/automation/input/InputController.kt](../app/src/main/java/com/multibrowser/antidetect/automation/input/InputController.kt) | Android application | 10 |
| [app/src/main/java/com/multibrowser/antidetect/automation/input/NativeInputAdapter.kt](../app/src/main/java/com/multibrowser/antidetect/automation/input/NativeInputAdapter.kt) | Android application | 32 |
| [app/src/main/java/com/multibrowser/antidetect/automation/perception/CoordinateMapper.kt](../app/src/main/java/com/multibrowser/antidetect/automation/perception/CoordinateMapper.kt) | Android application | 54 |
| [app/src/main/java/com/multibrowser/antidetect/automation/perception/DomSnapshot.kt](../app/src/main/java/com/multibrowser/antidetect/automation/perception/DomSnapshot.kt) | Android application | 57 |
| [app/src/main/java/com/multibrowser/antidetect/automation/perception/SnapshotBridge.kt](../app/src/main/java/com/multibrowser/antidetect/automation/perception/SnapshotBridge.kt) | Android application | 124 |
| [app/src/main/java/com/multibrowser/antidetect/automation/perception/TargetResolver.kt](../app/src/main/java/com/multibrowser/antidetect/automation/perception/TargetResolver.kt) | Android application | 60 |
| [app/src/main/java/com/multibrowser/antidetect/automation/recovery/RecoveryEngine.kt](../app/src/main/java/com/multibrowser/antidetect/automation/recovery/RecoveryEngine.kt) | Android application | 117 |
| [app/src/main/java/com/multibrowser/antidetect/automation/verification/VerificationEngine.kt](../app/src/main/java/com/multibrowser/antidetect/automation/verification/VerificationEngine.kt) | Android application | 98 |
| [app/src/main/java/com/multibrowser/antidetect/data/db/AppDatabase.kt](../app/src/main/java/com/multibrowser/antidetect/data/db/AppDatabase.kt) | Android application | 420 |
| [app/src/main/java/com/multibrowser/antidetect/data/db/ProfileDao.kt](../app/src/main/java/com/multibrowser/antidetect/data/db/ProfileDao.kt) | Android application | 30 |
| [app/src/main/java/com/multibrowser/antidetect/data/model/ProfileEntity.kt](../app/src/main/java/com/multibrowser/antidetect/data/model/ProfileEntity.kt) | Android application | 42 |
| [app/src/main/java/com/multibrowser/antidetect/data/model/SavedTabEntity.kt](../app/src/main/java/com/multibrowser/antidetect/data/model/SavedTabEntity.kt) | Android application | 11 |
| [app/src/main/java/com/multibrowser/antidetect/engine/GeckoProfileEngine.kt](../app/src/main/java/com/multibrowser/antidetect/engine/GeckoProfileEngine.kt) | Android application | 303 |
| [app/src/main/java/com/multibrowser/antidetect/engine/GeckoProfileManager.kt](../app/src/main/java/com/multibrowser/antidetect/engine/GeckoProfileManager.kt) | Android application | 123 |
| [app/src/main/java/com/multibrowser/antidetect/engine/SessionPoolManager.kt](../app/src/main/java/com/multibrowser/antidetect/engine/SessionPoolManager.kt) | Android application | 290 |
| [app/src/main/java/com/multibrowser/antidetect/models/BrowserProfile.kt](../app/src/main/java/com/multibrowser/antidetect/models/BrowserProfile.kt) | Android application | 55 |
| [app/src/main/java/com/multibrowser/antidetect/network/ApiClient.kt](../app/src/main/java/com/multibrowser/antidetect/network/ApiClient.kt) | Android application | 326 |
| [app/src/main/java/com/multibrowser/antidetect/network/AuthManager.kt](../app/src/main/java/com/multibrowser/antidetect/network/AuthManager.kt) | Android application | 94 |
| [app/src/main/java/com/multibrowser/antidetect/network/CookieApiService.kt](../app/src/main/java/com/multibrowser/antidetect/network/CookieApiService.kt) | Android application | 24 |
| [app/src/main/java/com/multibrowser/antidetect/network/GhostPilotApiService.kt](../app/src/main/java/com/multibrowser/antidetect/network/GhostPilotApiService.kt) | Android application | 33 |
| [app/src/main/java/com/multibrowser/antidetect/network/TokenVault.kt](../app/src/main/java/com/multibrowser/antidetect/network/TokenVault.kt) | Android application | 40 |
| [app/src/main/java/com/multibrowser/antidetect/sync/CookieEngine.kt](../app/src/main/java/com/multibrowser/antidetect/sync/CookieEngine.kt) | Android application | 174 |
| [app/src/main/java/com/multibrowser/antidetect/sync/CookieSyncDispatcher.kt](../app/src/main/java/com/multibrowser/antidetect/sync/CookieSyncDispatcher.kt) | Android application | 66 |
| [app/src/main/java/com/multibrowser/antidetect/sync/SyncManager.kt](../app/src/main/java/com/multibrowser/antidetect/sync/SyncManager.kt) | Android application | 168 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/ActiveSessionBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/ActiveSessionBottomSheet.kt) | Android application | 255 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/AuthDialog.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/AuthDialog.kt) | Android application | 505 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/BookmarksBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/BookmarksBottomSheet.kt) | Android application | 216 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/BrowserMenuBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/BrowserMenuBottomSheet.kt) | Android application | 628 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/CookieActionDialog.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/CookieActionDialog.kt) | Android application | 244 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/CreateProfileBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/CreateProfileBottomSheet.kt) | Android application | 1179 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/HistoryBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/HistoryBottomSheet.kt) | Android application | 236 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/ProfileSwitcherBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/ProfileSwitcherBottomSheet.kt) | Android application | 317 |
| [app/src/main/java/com/multibrowser/antidetect/ui/components/TabsBottomSheet.kt](../app/src/main/java/com/multibrowser/antidetect/ui/components/TabsBottomSheet.kt) | Android application | 231 |
| [app/src/main/java/com/multibrowser/antidetect/ui/theme/Theme.kt](../app/src/main/java/com/multibrowser/antidetect/ui/theme/Theme.kt) | Android application | 204 |
| [app/src/main/res/drawable/ic_launcher_background.xml](../app/src/main/res/drawable/ic_launcher_background.xml) | Android resources | 170 |
| [app/src/main/res/drawable/ic_launcher_foreground.xml](../app/src/main/res/drawable/ic_launcher_foreground.xml) | Android resources | 30 |
| [app/src/main/res/layout/activity_main.xml](../app/src/main/res/layout/activity_main.xml) | Android resources | 70 |
| [app/src/main/res/layout/item_spinner.xml](../app/src/main/res/layout/item_spinner.xml) | Android resources | 9 |
| [app/src/main/res/layout/item_spinner_dropdown.xml](../app/src/main/res/layout/item_spinner_dropdown.xml) | Android resources | 11 |
| [app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml](../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) | Android resources | 6 |
| [app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml](../app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml) | Android resources | 6 |
| [app/src/main/res/values/strings.xml](../app/src/main/res/values/strings.xml) | Android resources | 3 |
| [app/src/main/res/values/themes.xml](../app/src/main/res/values/themes.xml) | Android resources | 7 |
| [app/src/main/res/xml/backup_rules.xml](../app/src/main/res/xml/backup_rules.xml) | Android resources | 13 |
| [app/src/main/res/xml/data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml) | Android resources | 19 |
| [app/src/test/java/com/multibrowser/antidetect/automation/TargetResolverTest.kt](../app/src/test/java/com/multibrowser/antidetect/automation/TargetResolverTest.kt) | Android tests | 68 |
| [app/src/test/java/com/multibrowser/antidetect/automation/VerificationAndRecoveryTest.kt](../app/src/test/java/com/multibrowser/antidetect/automation/VerificationAndRecoveryTest.kt) | Android tests | 140 |
| [backend/.env.example](../backend/.env.example) | Project tooling | 20 |
| [backend/ai_assistant/__init__.py](../backend/ai_assistant/__init__.py) | Django proxy admin | 1 |
| [backend/ai_assistant/admin.py](../backend/ai_assistant/admin.py) | Django proxy admin | 67 |
| [backend/ai_assistant/apps.py](../backend/ai_assistant/apps.py) | Django proxy admin | 6 |
| [backend/ai_assistant/migrations/0001_initial.py](../backend/ai_assistant/migrations/0001_initial.py) | Django proxy admin | 54 |
| [backend/ai_assistant/migrations/__init__.py](../backend/ai_assistant/migrations/__init__.py) | Django proxy admin | 0 |
| [backend/ai_assistant/models.py](../backend/ai_assistant/models.py) | Django proxy admin | 28 |
| [backend/automation/__init__.py](../backend/automation/__init__.py) | Workflow and assistant backend | 2 |
| [backend/automation/admin.py](../backend/automation/admin.py) | Workflow and assistant backend | 38 |
| [backend/automation/apps.py](../backend/automation/apps.py) | Workflow and assistant backend | 8 |
| [backend/automation/assistant_engine.py](../backend/automation/assistant_engine.py) | Workflow and assistant backend | 277 |
| [backend/automation/assistant_tools.py](../backend/automation/assistant_tools.py) | Workflow and assistant backend | 666 |
| [backend/automation/compiler.py](../backend/automation/compiler.py) | Workflow and assistant backend | 616 |
| [backend/automation/decision_engine.py](../backend/automation/decision_engine.py) | Workflow and assistant backend | 258 |
| [backend/automation/migrations/0001_initial.py](../backend/automation/migrations/0001_initial.py) | Workflow and assistant backend | 87 |
| [backend/automation/migrations/0002_aipromptconfig.py](../backend/automation/migrations/0002_aipromptconfig.py) | Workflow and assistant backend | 29 |
| [backend/automation/migrations/0003_assistantsession_alter_aipromptconfig_options_and_more.py](../backend/automation/migrations/0003_assistantsession_alter_aipromptconfig_options_and_more.py) | Workflow and assistant backend | 43 |
| [backend/automation/migrations/__init__.py](../backend/automation/migrations/__init__.py) | Workflow and assistant backend | 0 |
| [backend/automation/models.py](../backend/automation/models.py) | Workflow and assistant backend | 276 |
| [backend/automation/serializers.py](../backend/automation/serializers.py) | Workflow and assistant backend | 75 |
| [backend/automation/signals.py](../backend/automation/signals.py) | Workflow and assistant backend | 17 |
| [backend/automation/tests.py](../backend/automation/tests.py) | Workflow and assistant backend | 1133 |
| [backend/automation/urls.py](../backend/automation/urls.py) | Workflow and assistant backend | 24 |
| [backend/automation/views.py](../backend/automation/views.py) | Workflow and assistant backend | 628 |
| [backend/core/__init__.py](../backend/core/__init__.py) | Django configuration | 1 |
| [backend/core/asgi.py](../backend/core/asgi.py) | Django configuration | 5 |
| [backend/core/permissions.py](../backend/core/permissions.py) | Django configuration | 19 |
| [backend/core/settings.py](../backend/core/settings.py) | Django configuration | 123 |
| [backend/core/urls.py](../backend/core/urls.py) | Django configuration | 127 |
| [backend/core/wsgi.py](../backend/core/wsgi.py) | Django configuration | 5 |
| [backend/devices/__init__.py](../backend/devices/__init__.py) | Profiles, authentication, sync | 1 |
| [backend/devices/admin.py](../backend/devices/admin.py) | Profiles, authentication, sync | 113 |
| [backend/devices/apps.py](../backend/devices/apps.py) | Profiles, authentication, sync | 5 |
| [backend/devices/auth_views.py](../backend/devices/auth_views.py) | Profiles, authentication, sync | 121 |
| [backend/devices/migrations/0001_initial.py](../backend/devices/migrations/0001_initial.py) | Profiles, authentication, sync | 42 |
| [backend/devices/migrations/0002_llmconfig.py](../backend/devices/migrations/0002_llmconfig.py) | Profiles, authentication, sync | 29 |
| [backend/devices/migrations/0003_alter_llmconfig_model_name_alter_llmconfig_provider.py](../backend/devices/migrations/0003_alter_llmconfig_model_name_alter_llmconfig_provider.py) | Profiles, authentication, sync | 23 |
| [backend/devices/migrations/0004_globalsetting.py](../backend/devices/migrations/0004_globalsetting.py) | Profiles, authentication, sync | 23 |
| [backend/devices/migrations/0005_globalsetting_selected_ai_model_and_more.py](../backend/devices/migrations/0005_globalsetting_selected_ai_model_and_more.py) | Profiles, authentication, sync | 23 |
| [backend/devices/migrations/0006_globalsetting_saved_gemini_model_and_more.py](../backend/devices/migrations/0006_globalsetting_saved_gemini_model_and_more.py) | Profiles, authentication, sync | 23 |
| [backend/devices/migrations/0007_globalsetting_ai_generation_prompt.py](../backend/devices/migrations/0007_globalsetting_ai_generation_prompt.py) | Profiles, authentication, sync | 18 |
| [backend/devices/migrations/0008_alter_globalsetting_options_llmconfig_system_prompt.py](../backend/devices/migrations/0008_alter_globalsetting_options_llmconfig_system_prompt.py) | Profiles, authentication, sync | 22 |
| [backend/devices/migrations/0009_globalsetting_target_search_sites_and_more.py](../backend/devices/migrations/0009_globalsetting_target_search_sites_and_more.py) | Profiles, authentication, sync | 23 |
| [backend/devices/migrations/0010_savedprofile_cookie_count_savedprofile_cookies_data_and_more.py](../backend/devices/migrations/0010_savedprofile_cookie_count_savedprofile_cookies_data_and_more.py) | Profiles, authentication, sync | 61 |
| [backend/devices/migrations/__init__.py](../backend/devices/migrations/__init__.py) | Profiles, authentication, sync | 0 |
| [backend/devices/models.py](../backend/devices/models.py) | Profiles, authentication, sync | 159 |
| [backend/devices/serializers.py](../backend/devices/serializers.py) | Profiles, authentication, sync | 90 |
| [backend/devices/services.py](../backend/devices/services.py) | Profiles, authentication, sync | 670 |
| [backend/devices/sync_views.py](../backend/devices/sync_views.py) | Profiles, authentication, sync | 81 |
| [backend/devices/tests.py](../backend/devices/tests.py) | Profiles, authentication, sync | 149 |
| [backend/devices/urls.py](../backend/devices/urls.py) | Profiles, authentication, sync | 42 |
| [backend/devices/views.py](../backend/devices/views.py) | Profiles, authentication, sync | 229 |
| [backend/manage.py](../backend/manage.py) | Project tooling | 20 |
| [backend/requirements.txt](../backend/requirements.txt) | Project tooling | 9 |
| [build.gradle.kts](../build.gradle.kts) | Project tooling | 5 |
| [gradle.properties](../gradle.properties) | Project tooling | 6 |
| [gradle/libs.versions.toml](../gradle/libs.versions.toml) | Project tooling | 49 |
| [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) | Project tooling | 7 |
| [gradlew](../gradlew) | Project tooling | 172 |
| [gradlew.bat](../gradlew.bat) | Project tooling | 84 |
| [scripts/workflow.py](../scripts/workflow.py) | Project tooling | 89 |
| [settings.gradle.kts](../settings.gradle.kts) | Project tooling | 32 |
