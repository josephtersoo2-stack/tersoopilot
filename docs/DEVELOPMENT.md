# Development workflows

## Prerequisites

- Python 3.12; dependencies installed into `backend/.venv`.
- Node 22 and npm; `npm ci` uses the committed frontend lockfile.
- Android: JDK 21, Android SDK platform 36 and the build tools requested by Gradle.
- Set `JAVA_HOME` to your JDK. On this Windows workstation, Android Studio's `C:\Program Files\Android\Android Studio\jbr` is available.
- Set `sdk.dir` in ignored `local.properties`, or configure `ANDROID_HOME`.

Bootstrap installs dependencies and copies missing `.env` examples. It never overwrites existing environment files. It does not migrate your database automatically.

## Choose a workflow

| Option | Command | When to use |
|---|---|---|
| Diagnose setup | `python scripts/workflow.py doctor` | Inspect tools without installing anything |
| Install dependencies | `python scripts/workflow.py bootstrap` | Fresh checkout |
| Backend only | `python scripts/workflow.py backend` | APIs and Django admin |
| Dashboard only | `python scripts/workflow.py frontend` | React UI with a separately running backend |
| Migration preview | `python scripts/workflow.py migration-plan` | Review schema changes |
| Apply migrations | `python scripts/workflow.py migrate` | After backup/review on an existing database |
| Staff account | `python scripts/workflow.py createsuperuser` | Interactive account creation |
| Backend checks | `python scripts/workflow.py test-backend` | Check, migration drift, and tests |
| Dashboard tests/build | `python scripts/workflow.py test-frontend` | Cookie format tests and production assets |
| Android tests | `python scripts/workflow.py test-android` | JVM tests |
| Debug APK | `python scripts/workflow.py build-android` | Build for device testing |
| Android lint | `python scripts/workflow.py lint-android` | Inspect Android diagnostics |
| Web/backend gate | `python scripts/workflow.py check` | Regular changes |
| All build gates | `python scripts/workflow.py check-all` | Changes across clients/API |
| Deployment settings | `python scripts/workflow.py release-check` | Run against real production environment settings |

Commands stop on a failure. CI independently runs backend checks, the dashboard build, and Android tests/build on pushes and pull requests. Frontend compilation is not a replacement for interaction tests.

## Android connection options

**USB with ADB reverse:** run `adb reverse tcp:8000 tcp:8000`, then save `http://127.0.0.1:8000` in the Android server settings.

**Emulator:** save `http://10.0.2.2:8000`; Django must accept that host.

**Explicit LAN development:** bind Django using `python scripts/workflow.py backend --host 0.0.0.0`, add your chosen host to `ALLOWED_HOSTS`, and save that exact server URL in Android. This is an explicit local testing option. No host discovery or credential forwarding is performed.

**HTTPS deployment build:** use `./gradlew :app:assembleRelease -PapiBaseUrl=https://your-api.example/` (Windows: `gradlew.bat`). Replace the example with your server origin. Release signing is not configured; supply your own protected signing setup before distribution. Release builds reject cleartext API endpoints.

Changing the Android server clears account credentials. The app now requires pressing **Save server** rather than changing the destination on every keystroke.

## Database and assistant options

SQLite is the local default. Set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_HOST`, and `POSTGRES_PORT` for PostgreSQL. Switching settings does not copy existing data: back up, migrate, transfer, and validate it separately.

Assistant tools default to read-only. Staff can inspect fleet state and telemetry. Mutations use dashboard controls. `ASSISTANT_ALLOW_WRITES=True` explicitly enables assistant tool writes for staff sessions; this mode does not provide per-action approval or audit history. Keep it disabled until that control is implemented for production.

## Change workflow

1. Create a `codex/` feature branch or work in your chosen existing branch.
2. Record the concrete bug or contract change in the audit/specification.
3. Add a regression test for access control, storage integrity, or execution behavior changes.
4. Run the narrow relevant checks, then `check-all` for changes spanning Android and Django.
5. Review the diff and migrations. Keep credentials, databases, caches, APKs, and local environment files out of commits.
6. For releases, run device smoke tests: login, server change, two-profile isolation, cookie import/export, abort, offline transition retry, and app restart.

## Production requirements

Set `DEBUG=False`, a strong unique `SECRET_KEY`, exact hosts/CORS origins, HTTPS redirect and secure cookie settings. Configure TLS at your server and use a production WSGI/ASGI server. Do not publish Vite's development server. Existing environment files are preserved, so insecure local overrides must be reviewed explicitly.

The Android build currently suppresses AAR metadata and Kotlin metadata compatibility checks and disables shrinking. Resolve dependency alignment and test release builds before removing those suppressions. This audit does not certify that configuration for distribution.
