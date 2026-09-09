# Project audit and remediation

Date: 2026-09-09. Scope: shared-chat review, local repository analysis, cross-component repairs, development tooling, and verification. This is not a claim that all production features or all possible defects are complete.

## Fixed or hardened

| Finding | Change |
|---|---|
| Anonymous fleet/profile access | Authenticated-by-default DRF configuration |
| Unscoped cookies, profiles, executions | Member ownership queries; explicit staff fleet access |
| Writable profile owner | Read-only owner; creator attached server-side |
| Public global/assistant administration | Staff permissions; authenticated settings reads |
| Four-character passwords and enumeration | Django validators; generic login failures; scoped throttling |
| Silent/partial sync corruption | Serializer validation, bounded atomic batches, stable-ID matching |
| Autosave writes to most recent unrelated profile | Missing/unknown IDs rejected; no display-name fallback |
| Cookie import creates records before validation | Existing synced profile required |
| Terminal failures reported SUCCESS | Failure-first terminal handling |
| Job CRUD bypasses runtime checks | Read-only job viewset plus explicit action routes |
| Duplicate poll claims | Conditional PENDING→DISPATCHED update |
| Duplicate transition on network retry | Transition IDs and retry-safe Android pending outcomes |
| Aborted jobs continue on Android | Heartbeat terminal response stops the runner |
| Invalid AI state override | Unknown state rejected before assignment |
| Failed AI config selection deactivates everything | Lookup and activation in one transaction |
| Niche replacement deletes before validation | Validate unique weights and references before atomic replace |
| Dashboard lacks login/API authentication | Staff gate, central token client, logout and 401 handling |
| Hardcoded frontend endpoint | Environment-controlled base URL and development proxy |
| Incompatible unauthenticated SSE UI | Authenticated execution detail polling with correct fields |
| Cookie download bypasses token headers | Authenticated blob download |
| Silent dashboard initial failures | Errors reach the notification path |
| Unbounded bulk dispatch | 1–100 profile validation and dispatch confirmation |
| Assistant raw tools bypass write policy | Common server-controlled guard for Gemini and OpenRouter callables |
| Android forwards credentials to guessed hosts | One configured origin; explicit server changes clear credentials |
| HTTPS/ports stripped by Android | Full origin validation and release HTTPS requirement |
| Server setting applied per keystroke | Explicit Save server action with validation errors |
| Plaintext saved Android token | Android Keystore AES-GCM with migration on read |
| Production networking logs | Release logging disabled; redirects/replays disabled |
| Unknown Android commands return SUCCESS | Explicit FAILURE; SUBMIT_INPUT mapped to enter/submit |
| Unbounded state loops | 500-step/30-minute local execution bound |
| Cookie WAL/SHM files manually deleted | SQLite owns journal recovery |
| Netscape HttpOnly lines discarded | HttpOnly prefix recognized |
| Cookie insert count ignores failures | Failed SQLite insert raises, transaction rolls back |
| Missing project workflow/README | Shared command runner, CI, environment examples, current documentation |

## Remaining architectural and production work

| Priority | Work | Acceptance evidence needed |
|---|---|---|
| High | Encrypt backend cookies/proxy/provider credentials and Android session database | Key lifecycle, reversible migration, backup/restore and key-loss tests |
| High | Verify Gecko profile and extension isolation | Two simultaneous profiles with different cookies/proxies; verify actual runtime storage |
| High | Device identity and durable execution leases | Two devices cannot execute the same profile concurrently; revoked installation denied |
| High | Persist active execution and support restart/reconnect | Kill/restart app mid-action without duplicate input; server recovers stale jobs |
| High | Complete or remove unsupported compiler commands | Contract tests for every emitted command and real-device verification |
| High | Align Android/Kotlin dependencies | Remove metadata-check suppressions and compile/test all build variants |
| High | Production release configuration | Real HTTPS backend, signing, deployment checks, dependency audit |
| Medium | Expiring/revocable per-device tokens | Expiry, refresh, logout and device-revocation integration tests |
| Medium | Assistant per-action approval/audit and session ownership | Approved action bound to exact tool arguments and actor; tenant separation if needed |
| Medium | DAG schema/version validation and recovery limits | Every transition references a valid state; graph execution bounded and observable |
| Medium | Sync version/conflict policy | Two devices update same profile without silent overwrite |
| Medium | Configuration history and audit trail | Actor/timestamp/change records without secret payloads |
| Medium | Split MainActivity and large engine modules incrementally | Behavior preserved with lifecycle and instrumentation tests |
| Medium | Dedicated UI interaction tests | Login expiry, dispatch selection, cookie import and abort flows |
| Medium | Secret-safe error messages and telemetry | Provider errors cannot expose credentials, session data, or internal debug information |

The existing specs describe a substantially larger platform than can be certified by compilation. Dependency injection, Room, WebSockets, PostgreSQL, queues, and push delivery should be selected for concrete requirements and validated, rather than added as empty scaffolds.

## Verification record

- Baseline: 44 backend tests passed; dashboard production build passed; Android debug build and JVM tests passed.
- Updated dashboard production build passed after authentication/API changes.
- Updated Android debug APK and JVM tests passed after networking/token/cookie changes.
- Expanded backend suite: 62 tests passed; Django system check passed; migration drift check reports no changes.
- Android JVM suite: 7 tests passed; updated debug APK builds successfully.
- Frontend cookie parser suite: 3 tests passed, including HttpOnly, zero expiry, empty values and malformed rows.
- `git diff --check` passed.

No production database migration, real-device session test, deployment, release signing, or external account change was performed. Existing `.env`, local databases, API-key files, and the user's document content were preserved.
