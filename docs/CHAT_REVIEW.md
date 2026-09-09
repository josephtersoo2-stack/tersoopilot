# Shared conversation review

Source: https://chatgpt.com/share/6aa0a4cd-4f1c-83ea-b2a4-63c8d1dd45b3

Reviewed on 2026-09-09 through all 23 prompt navigation entries, from greeting and repository request to the final React audit. The page virtualizes middle messages; those were opened individually. These are consolidated notes, not a verbatim transcript. Prior assistant assessments are hypotheses until checked against the local code.

## Conversation sequence

| Prompts | Subject | Notes retained |
|---|---|---|
| 1–2 | Greeting and whole repository | TersoPilot/OctoMobile architecture; Android, Django, React; empty README; fragmented specifications |
| 3 | First backend audit | Profiles, ownership, raw cookie access, auth, global configuration, AI generation, CORS and secrets |
| 4 | Backend follow-up | Niches, personas, task templates, execution queue, assistant messages, sync validation and service layer |
| 5 | Compiler and recovery | DAG construction, transitions, compiler versioning, unknown states, AI schema/prompt risks, device access |
| 6 | Assistant tools | Global queries, ambiguous profile matching, assignment replacement, campaign dispatch, abort, allowlisted router |
| 7 | Serializers and API surface | Writable ownership, execution CRUD, sensitive profile/session fields, global configuration permissions |
| 8 | Django settings/auth/tests | Insecure defaults, password validation, enumeration, throttling, token lifecycle, proxy-model architecture |
| 9 | Backend conclusion | Test/migration uncertainty, API catalogue, permissions, encrypted storage, queue/worker and monitoring proposals |
| 10–11 | Android overview (repeated request) | Modules, oversized activity, automation, perception, verification/recovery, engine, data, network, sync, lifecycle |
| 12 | Runner internals | Unknown commands reported as success; in-memory state; coroutine ownership; heartbeat; platform-specific commands |
| 13 | Sessions/sync/auth | Session resource bounds, token storage, host changes, sync races and conflict handling |
| 14 | API transport | Hardcoded HTTP/ports, automatic host fallback, credentials, logging, network contracts and replay risks |
| 15 | Cookie/database layer | Direct Gecko SQLite import/export, WAL deletion, duplicate session storage, indexes, integrity and retention |
| 16 | Entities/DAO/engine | Sensitive fields in DTOs, validation, versioning, transactional restore, Gecko runtime configuration/isolation |
| 17 | Manifest/build | Cleartext, backups disabled, minimal permissions, JDK/SDK, compatibility, shrinking, signing, service gaps |
| 18–20 | Scope clarification | The three audit areas are Django backend, Android app, and React admin, rather than three individual files |
| 21 | React overview | Authentication, central API configuration, root state, silent failures, feature modules |
| 22 | React automation components | Dispatch bounds/confirmation, logs, personas, niches, AI prompts, assistant actions |
| 23 | React hardware/settings/layout | Global permissions, configuration history, provider keys, blueprint validation, legacy UI and release readiness |

## Cross-cutting requirements extracted

- Keep the three-layer architecture: React configuration/monitoring, Django orchestration, Android browser execution, extension perception.
- Enforce authentication and ownership at API boundaries, including cookies, sync, execution polling, transitions, and telemetry.
- Keep global settings, templates, prompts, and fleet assistant administration privileged.
- Validate payloads, avoid automatic exposure of future model fields, separate session data from list metadata where possible.
- Add staff sign-in, session handling, configurable URLs, visible errors, dispatch review, and trustworthy execution reporting.
- Make failure, retry, abort, and completion explicit. Bound execution and preserve results when reporting fails.
- Protect tokens and stored browser credentials; remove automatic forwarding to unrelated servers and permit proper HTTPS endpoints.
- Preserve historical specifications and provide one current architecture, development workflow, API guide, and verification record.
- Future production work includes device identity, leases, restart recovery, storage encryption/migration, configuration history, audit trails, dependency alignment, release signing, and realistic multi-device tests.

## Corrections to prior claims

1. Backend authentication already exists. The critical problem was missing default permission enforcement and unscoped API queries.
2. `automation/tests.py` contains 44 existing tests, and migrations exist. The chat's later statement that they were absent was not supported locally.
3. Android uses SQLiteOpenHelper, not Room. A Room-specific refactor is an architectural proposal, not a repair to an existing Room implementation.
4. Heartbeat, abort, SSE, and assistant message history already exist. The frontend's SSE consumer did not match the backend event shape, and EventSource could not attach the token header.
5. Polling is an operational tradeoff, not inherently a security bug. A correctly authenticated polling UI is a supported local option.
6. Separate Gecko profile directories alone do not prove actual cookie/runtime isolation. Engine behavior needs a device-level two-profile test.
7. `allowBackup=false` is already present. A launcher activity normally needs to remain exported; blindly disabling it would break launching.
8. A browser supporting many domains cannot use a generic ban on importing cookies for particular categories of websites. Ownership, format, explicit import, and secure storage are the relevant controls.
9. Certificate pinning, JWT, Room, Hilt, Redis, Celery, and WebSockets are possible design choices, not automatic prerequisites for every local repair.
10. Ratings and completion percentages in the chat were estimates, not measured acceptance criteria.

See AUDIT.md for what was changed and what still needs implementation/validation.
