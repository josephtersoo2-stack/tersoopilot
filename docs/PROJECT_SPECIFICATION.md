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

1. Staff create an AutomationTask with category and configuration.
2. Dispatch validates 1–100 unique profile UUIDs and creates queue records atomically.
3. A member or staff client claims an accessible pending job using an atomic status comparison.
4. Android executes a command, retains its result, and reports a transition ID.
5. Django applies each transition ID once, checks transition references, and records outcome logs.
6. Failure at an exit stays FAILED; terminal jobs cannot be advanced again.
7. Heartbeats return terminal status after an abort. Android stops on that response.

Jobs remain activity-bound and in-memory on Android. A 500-step/30-minute bound stops unbounded execution but is not durable restart recovery. A stopped or disconnected job may still need operator cleanup. Two different devices may claim different jobs for the same profile; installation leases are not implemented.

## Structure decisions

Keep `app`, `backend`, and `admin-panel` in place to preserve Gradle paths, Python app labels, migrations, and frontend imports. Put shared tools at `scripts/` and current documentation at `docs/`. Use feature folders within each app. Move large controllers incrementally with regression tests instead of renaming Django apps or breaking migration identities.

The existing compiler exposes several platform strategies. Some emitted platform commands do not have Android executors; they now fail explicitly rather than report false success. Do not describe all strategies as end-to-end complete.
