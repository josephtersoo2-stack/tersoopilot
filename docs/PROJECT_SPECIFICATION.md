# Current project specification

This is the authoritative map of the current implementation. Historical requirements are preserved in `archive/`; those documents contain planned features and must not be mistaken for verified capabilities.

## Component responsibilities

| Component | Owns | Does not own |
|---|---|---|
| React dashboard | Staff sign-in, profiles, settings, niches, task dispatch, telemetry, assistant UI | Browser execution |
| Django devices app | User authentication, profile CRUD, cookie transfer, cloud synchronization, global configuration | Gecko storage |
| Django automation app | Task templates, compilation, execution state, heartbeat, telemetry, AI recovery and assistant data | Native input |
| Django ai_assistant app | Proxy models and administration presentation | Separate assistant tables |
| Android automation | Polling, input, perception, verification, recovery, transition reporting | Scheduling across installations |
| Android engine | Gecko sessions and extension integration | Server-side permissions |
| Android data/sync | SQLiteOpenHelper persistence and profile/cookie transfer | Conflict-free multi-device synchronization |

## Access model

Staff administer the shared fleet and global niches, task templates, AI configuration, and assistant. Members see and synchronize their own profiles and can run the jobs assigned to those profiles. Anonymous clients can only register and sign in. This is not a multi-tenant organisation/RBAC implementation.

Authentication remains compatible with Android's `Authorization: Token` contract. Logout can revoke the account token through the API. The dashboard stores its token in sessionStorage, verifies staff status before rendering, and clears the local session on HTTP 401. Android encrypts its saved token with an Android Keystore AES-GCM key.

## Execution contract
1. Staff create an AutomationTask with category and configuration, and optionally an Automation rule (cadence, targeting policy, concurrency, cooldown).
2. Automation Scheduler or operator mints an authoritative AutomationRun, planning executions with deduplicated `ExecutionPlan` versions.
3. Android worker nodes (`AutomationWorkerService`) claim pending work via `POST /api/automation/ghostpilot/claim-next/` with an atomic lease (`ExecutionLease`) preventing multi-device collisions.
4. Android executes commands, persists local SQLite checkpoints (`execution_checkpoints`), and sends heartbeat presence telemetry.
5. Django Watchdog supervises active leases and heartbeats, reaping dead leases and triggering recovery or circuit breakers.
6. Android reports transitions with unique `transition_id` idempotency keys.
7. Terminal executions finalize parent `AutomationRun` counts and metrics, safely audited in `ExecutionEvent`.

## Structure decisions

Keep `app`, `backend`, and `admin-panel` in place to preserve Gradle paths, Python app labels, migrations, and frontend imports. Put shared tools at `scripts/` and current documentation at `docs/`. Use feature folders within each app. Move large controllers incrementally with regression tests instead of renaming Django apps or breaking migration identities.

The existing compiler exposes several platform strategies. Some emitted platform commands do not have Android executors; they now fail explicitly rather than report false success. Do not describe all strategies as end-to-end complete.
