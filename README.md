# TersoPilot / MultiBrowser

Android GeckoView browser profiles, a Django orchestration API, and a React administration dashboard.

## Start here

- [Current architecture and scope](docs/PROJECT_SPECIFICATION.md)
- [Development workflows and commands](docs/DEVELOPMENT.md)
- [Shared conversation notes](docs/CHAT_REVIEW.md)
- [Verified fixes and remaining risks](docs/AUDIT.md)
- [API access rules](docs/API.md)
- [Source inventory](docs/FILE_MAP.md)
- [Original planning documents](docs/archive/)

## Local setup

Use Python 3.12, Node 22, JDK 21, and the Android SDK for Android work. Run commands from the repository root.

```sh
python scripts/workflow.py doctor
python scripts/workflow.py bootstrap
python scripts/workflow.py migration-plan
python scripts/workflow.py migrate
python scripts/workflow.py createsuperuser
```

Start these in separate terminals:

```sh
python scripts/workflow.py backend
python scripts/workflow.py frontend
```

Open http://127.0.0.1:5173 and sign in with a staff account. The development server proxies `/api`, `/admin`, and `/static` to Django on port 8000. Members use the Android app and can access their own profiles. Existing ownerless profiles are visible to staff only; assign their owner through Django admin.

```sh
python scripts/workflow.py check
python scripts/workflow.py check-all
```

The first checks Django, migration drift, backend tests, and the dashboard build. The second also runs Android unit tests and builds a debug APK.

## Repository layout

```text
app/                  Android app, resources, extension, and unit tests
backend/              Django project and domain apps
admin-panel/          React/Vite administration UI
docs/                 Current specification, audit, API, and guides
  archive/            Preserved historical proposals
scripts/              Cross-platform development entry point
.github/workflows/    Backend, dashboard, and Android CI checks
gradle/               Gradle wrapper and dependency catalogue
```

This is a development system with known production gaps listed in the audit. Debug Android builds permit HTTP for explicit local testing; release builds require HTTPS. No deployment, database reset, account provisioning, or signing-key creation is performed automatically.
