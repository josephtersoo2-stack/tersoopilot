# API and access rules

Base path: `/api/`. JSON requests and `Authorization: Token <token>` for authenticated clients. The dashboard uses the configured API base; Vite proxies `/api/` during local development.

| Route | Methods | Access / behavior |
|---|---|---|
| `auth/register/` | POST | Anonymous; Django password validation; throttled |
| `auth/login/` | POST | Anonymous; generic invalid-credential errors; throttled |
| `auth/me/` | GET | Authenticated; identity and staff flag |
| `auth/logout/` | POST | Authenticated; revokes this user's shared API token |
| `profiles/` and `{uuid}/` | CRUD | Member-owned records; staff fleet-wide; owner cannot be supplied/reassigned by API |
| `profiles/{id}/cookies/export/` | GET | Owner or staff; cloud UUID or unambiguous device sync ID |
| `profiles/{id}/cookies/import/` | POST | Owner or staff; existing profile required; sync new device profiles first |
| `sync/push/` | POST | Owner; single object or list/envelope; 1–100 profiles; atomic validated batch |
| `sync/pull/` | GET | Owner's profiles including session data |
| `sync/auto-save/` | POST | Owner plus stable ID; never falls back to another profile |
| `settings/global/` | GET/PATCH | Authenticated read, staff write |
| `devices/models/`, `devices/generate/` | GET/POST | Authenticated; scoped AI throttling |
| `ip/lookup/` | GET | Authenticated |
| `automation/niches/` | CRUD | Staff, global catalogue |
| `automation/tasks/` | CRUD | Staff, reusable templates |
| `automation/tasks/{id}/dispatch/` | POST | Staff; validates all profile UUIDs before inserting jobs |
| `automation/ghostpilot/` | GET | Owner's jobs or staff fleet-wide |
| `automation/ghostpilot/poll/{profile_id}/` | GET | Owner/staff; atomic pending-job claim; query route also supported |
| `automation/ghostpilot/{id}/transition/` | POST | Owner/staff; optional unique `transition_id` for safe retries |
| `automation/ghostpilot/{id}/heartbeat/` | POST | Owner/staff; reports terminal state |
| `automation/ghostpilot/{id}/abort/` | POST | Owner/staff; marks FAILED with abort log |
| `automation/ghostpilot/{id}/decision/` | POST | Owner/staff; validates recovery state references |
| `automation/ghostpilot/{id}/stream/` | GET | Owner/staff; authenticated SSE; `once=true` for a finite response |
| `automation/executions/` | Same | Compatibility alias for GhostPilot routes |
| `automation/profiles-orchestration/` actions | GET/POST | Staff; persona and niche administration |
| `automation/ai-config/` actions | CRUD/POST | Staff; active configuration update is atomic |
| `automation/assistant/` actions | CRUD/POST | Staff; shared administration sessions; writes disabled by default |

## Transition request

```json
{
  "outcome": "SUCCESS",
  "transition_id": "a-client-generated-uuid-kept-for-retries",
  "context_update": {"watch_seconds_spent": 0}
}
```

Reuse the same transition ID when a request times out; generate a new one for the next completed action. Duplicate IDs return the current execution state without adding another transition. Members cannot edit job records with PATCH to bypass the transition API.

## Synchronization request

```json
{"profiles": [{"device_sync_id": "stable-local-id", "name": "My profile", "cookies_data": []}]}
```

The server matches stable IDs inside the authenticated account. Display names are not identifiers. Cookie count is derived from the validated cookie array. Do not use a frontend API token in a URL or browser `window.open`; use the authenticated client for downloads.

## Limitations

Tokens are account-level and currently do not expire. Logout revokes the shared token for all clients using that account token. There is no separate installation/device token, organisation role hierarchy, or durable audit event model. Profile/session fields remain visible to their owner and staff; encrypted storage and narrower metadata serializers remain future work.
