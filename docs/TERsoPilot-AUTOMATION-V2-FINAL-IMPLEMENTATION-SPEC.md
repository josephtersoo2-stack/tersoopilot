# TersoPilot Automation V2 — Final Implementation Specification

**Repository:** `josephtersoo2-stack/tersoopilot`  
**Branch:** `main`  
**Primary goal:** Convert the current manually-dispatched/mobile-poller workflow into a durable, server-orchestrated automation platform while preserving the working Android GeckoView automation, profile storage, DAG compiler, execution engine, leases, telemetry, and dashboard.

---

## 1. Executive Decision

TersoPilot Automation V2 will use this architecture:

```text
React Admin
    |
    | REST / SSE
    v
Django Control Plane
    |
    +-- Automation Scheduler
    +-- Automation Run Manager
    +-- Execution Queue / Allocator
    +-- Lease Manager
    +-- Watchdog
    +-- Device Registry
    +-- Compiler / Plan Versioning
    +-- AI Recovery
    |
    | HTTPS / LAN
    v
Android Worker
    |
    +-- Foreground Worker Service
    +-- GhostPilotRunner
    +-- Checkpoint / Resume
    +-- Command Execution
    +-- Verification / Recovery
    +-- GeckoView
```

### Core ownership rule

**Django is authoritative for orchestration and durable state.**

**Android is the execution worker.**

Django decides:

- what should run
- when it should run
- which profiles are eligible
- which device should receive a job
- whether a job may start
- retry and cooldown policy
- execution lifecycle
- lease ownership
- recovery eligibility
- automation history

Android decides:

- how to execute the assigned command
- how to inspect the current page
- how to inject native browser input
- how to verify a physical result
- how to maintain local checkpoints
- how to report execution transitions and health

Do not create a second competing orchestration system inside Android.

---

# 2. Non-Negotiable Design Rules

## 2.1 Preserve the current architecture

Do not replace or rename the existing Django apps:

- `backend/automation`
- `backend/executions`
- `backend/devices`
- `backend/ai_assistant`

Do not rebuild the Android GeckoView layer.

Build on the existing:

- `AutomationTask`
- `Execution`
- `ExecutionLease`
- `ExecutionEvent`
- `ExecutionService`
- `RecipeCompiler`
- `decision_engine.py`
- `validator.py`
- `GhostPilotRunner.kt`
- `ExecutionCheckpointEntity`
- `ActionExecutionEntity`
- `RecoveryAttemptEntity`
- existing device registration/storage
- existing React automation components

Use incremental migrations and regression tests.

---

## 2.2 No premature Celery/Redis

Do **not** introduce Celery, Redis, RabbitMQ, or another external queue merely to create infrastructure.

The initial scheduler must run as an explicit long-running Django management process:

```bash
python manage.py run_automation_scheduler
```

The watchdog must run as:

```bash
python manage.py run_automation_watchdog
```

These processes may later be supervised by the deployment platform.

The database remains the source of truth.

Use:

- database transactions
- `select_for_update()`
- conditional updates
- uniqueness constraints
- lease expiry
- heartbeat timestamps
- idempotency keys

A future queue system may be introduced only after measured requirements demonstrate that the database-backed dispatcher is insufficient.

---

# 3. Automation Domain Model

## 3.1 New `Automation` model

Add to `backend/automation/models.py`.

Suggested fields:

```text
id                  UUID primary key
name                string
description         text
task                FK AutomationTask
enabled             boolean
schedule_type       enum
schedule_config     JSON
timezone            string
target_niches       M2M Niche
target_profiles     M2M SavedProfile
selection_mode      enum
concurrency_limit   positive integer
cooldown_minutes    positive integer
max_runtime_seconds positive integer
max_retries         positive integer
failure_threshold_percent integer
priority            integer
active_from         datetime nullable
active_until        datetime nullable
last_run_at         datetime nullable
next_run_at         datetime nullable
created_at          datetime
updated_at          datetime
```

### Schedule types for V1

Only implement:

```text
ONE_TIME
DAILY
INTERVAL
```

Do not implement CRON in Phase 1.

Reserve:

```text
WEEKLY
TIME_WINDOW
CRON
EVENT_TRIGGERED
```

for later phases.

### `schedule_config`

Keep schedule-specific fields together.

Example DAILY:

```json
{
  "time": "10:00"
}
```

Example INTERVAL:

```json
{
  "interval_minutes": 120
}
```

Example ONE_TIME:

```json
{
  "run_at": "2026-09-13T10:00:00+01:00"
}
```

Never assume UTC or server local time. Use the explicit `timezone` field.

---

# 4. Automation Target Selection

Add:

```text
selection_mode:
    EXPLICIT_PROFILES
    NICHE
    ALL_ELIGIBLE
```

Rules:

### EXPLICIT_PROFILES

Only selected `SavedProfile` records are eligible.

### NICHE

Profiles are selected through `ProfileNicheAffiliation`.

### ALL_ELIGIBLE

All profiles are considered, subject to:

- ownership/access rules
- active lease state
- cooldown
- device eligibility
- task compatibility
- profile readiness
- automation policy

Never silently expand an explicit profile selection into unrelated profiles.

---

# 5. New `AutomationRun` model

Add to `backend/automation/models.py`.

Suggested fields:

```text
id                       UUID primary key
automation               FK Automation
run_key                  string
scheduled_for            datetime
status                   enum
total_target_profiles    integer
queued_count             integer
dispatched_count         integer
running_count            integer
success_count            integer
failure_count            integer
stalled_count            integer
cancelled_count          integer
skipped_count            integer
started_at               datetime nullable
completed_at             datetime nullable
summary_metrics          JSON
created_at               datetime
updated_at               datetime
```

### Run statuses

```text
SCHEDULED
RUNNING
COMPLETED
PARTIAL
FAILED
CANCELLED
```

### Idempotency requirement

Add a uniqueness constraint equivalent to:

```text
UNIQUE(automation_id, run_key)
```

The scheduler must be safe to restart at any point without creating duplicate runs.

`run_key` should represent the logical scheduled occurrence.

Example:

```text
automation UUID + scheduled occurrence timestamp
```

Do not use only `last_run_at` to prevent duplicate runs.

---

# 6. Execution Model Changes

Modify `backend/executions/models.py`.

Add:

```text
automation_run          FK AutomationRun nullable
plan_id                 UUID/string nullable
plan_version            string nullable
retry_count             integer default 0
max_retries             integer
last_confirmed_state    string nullable
last_confirmed_step     integer nullable
last_transition_id      string nullable
checkpoint_version      integer default 0
recovery_status         enum
```

### `recovery_status`

Use a separate field instead of inventing several new execution lifecycle statuses:

```text
NONE
PENDING
RUNNING
RESOLVED
EXHAUSTED
```

Keep the current `ExecutionStatus` intact unless a real business requirement proves another lifecycle state is required.

Current lifecycle remains:

```text
PENDING
DISPATCHED
RUNNING
SUCCESS
FAILED
STALLED
```

---

# 7. Execution Plan Versioning

Do not compile a DAG anonymously at dispatch time.

Create a durable versioned plan abstraction.

Recommended new model:

```text
ExecutionPlan
    id
    task
    version
    compiler_version
    compiled_dag
    config_snapshot
    created_at
```

Uniqueness:

```text
UNIQUE(task, version)
```

Workflow:

```text
Automation
    |
    v
AutomationRun
    |
    v
ExecutionPlan v12
    |
    +-- Execution Profile A
    +-- Execution Profile B
    +-- Execution Profile C
```

Once an `AutomationRun` starts, its executions must reference the exact plan version used.

Changing a task later must not mutate historical executions.

---

# 8. Lease Correctness

The existing lease service already uses transactional locking and expiry handling.

Do not claim that an index creates mutual exclusion.

An index improves lookup performance.

Mutual exclusion must be guaranteed through:

1. transaction
2. `select_for_update()` on the profile
3. active lease validation
4. expiry handling
5. appropriate uniqueness constraints where the database supports them
6. concurrency tests

Add an index for:

```text
(profile, status, expires_at)
```

but describe it correctly as a performance/indexing mechanism.

Required invariant:

```text
One profile cannot have two valid ACTIVE execution leases at the same time.
```

Two different devices attempting acquisition concurrently must result in exactly one winner.

---

# 9. Scheduler Service

Create:

```text
backend/automation/scheduler/
    __init__.py
    service.py
    planner.py
    dispatcher.py
    policies.py
```

## `service.py`

Implement:

```python
evaluate_due_automations()
calculate_eligible_profiles(automation)
create_run_if_due(automation)
build_execution_plan(automation)
queue_run(run)
```

The scheduler loop:

```text
1. Find enabled automations.
2. Determine which are due in their declared timezone.
3. Calculate a deterministic run_key.
4. Create AutomationRun atomically.
5. Resolve eligible profiles.
6. Apply concurrency and cooldown policies.
7. Create PENDING executions.
8. Update Automation.next_run_at.
9. Repeat safely.
```

The scheduler must tolerate:

- process restart
- clock jitter
- temporary database errors
- duplicate wake-ups
- multiple scheduler instances accidentally running

The database must remain the final protection against duplicate runs.

---

# 10. Scheduler Management Command

Create:

```text
backend/automation/management/commands/run_automation_scheduler.py
```

Run:

```bash
python manage.py run_automation_scheduler
```

Requirements:

- long-running process
- configurable polling interval
- structured logs
- graceful shutdown
- transaction per scheduler cycle
- no infinite database transaction
- no duplicate run creation
- recover cleanly after temporary DB errors
- expose last scheduler heartbeat

Recommended settings:

```text
AUTOMATION_SCHEDULER_INTERVAL_SECONDS=15
```

Do not hardcode the interval.

---

# 11. Eligibility Engine

Create or evolve:

```text
backend/automation/scheduler/planner.py
```

A profile is eligible only when all required conditions pass.

Checks:

```text
1. Profile exists
2. Profile is accessible
3. Profile is enabled/usable
4. Profile matches automation targeting
5. Profile is not currently leased
6. Cooldown period has elapsed
7. Required device capability exists
8. Task is valid
9. Execution plan is valid
10. Automation limits are not exceeded
```

Return an explicit reason when excluded.

Example:

```json
{
  "profile_id": "...",
  "eligible": false,
  "reason": "COOLDOWN_ACTIVE"
}
```

Do not silently hide eligibility decisions.

---

# 12. Device Registry and Allocation

Extend the existing `devices.Device` model/services rather than creating duplicate device tables.

Track:

```text
device_id
owner
status
app_version
android_version
geckoview_version
battery_percent
screen_width
screen_height
capabilities
last_seen
last_heartbeat
current_execution
registered_at
updated_at
```

Recommended device status:

```text
ONLINE
BUSY
OFFLINE
DISABLED
REJECTED
```

A device must not be assigned a job if:

```text
OFFLINE
DISABLED
REJECTED
```

unless an explicit administrative override exists.

---

# 13. Capability Matching

Device registration must report capabilities.

Example:

```json
{
  "geckoview": true,
  "native_gestures": true,
  "dom_snapshot": true,
  "screenshot": true,
  "cookie_sync": true,
  "max_concurrent_profiles": 1
}
```

The scheduler matches execution requirements to device capabilities.

Never dispatch a command the device cannot execute.

Unsupported commands must continue to fail explicitly instead of being reported as successful.

---

# 14. Job Allocation

Implement:

```text
backend/automation/scheduler/dispatcher.py
```

Core operation:

```text
allocate_next_execution(device)
```

Rules:

1. Select a compatible `PENDING` execution.
2. Lock the candidate row.
3. Re-check automation state.
4. Re-check profile lease state.
5. Re-check device status.
6. Acquire profile lease.
7. Transition execution to `DISPATCHED`.
8. Associate lease with execution.
9. Record a `RUN_STARTED` event.
10. Return the job.

Do not perform allocation using a read-then-write race.

---

# 15. Worker Claim API

Create or modify:

```text
POST /api/automation/ghostpilot/claim-next/
```

Request:

```json
{
  "device_id": "device-123"
}
```

Server must:

- authenticate device
- verify device registration
- verify device status
- select an eligible job atomically
- acquire the corresponding profile lease
- return the execution and plan
- return the lease
- return resume/checkpoint metadata

Example response:

```json
{
  "execution_id": "...",
  "automation_run_id": "...",
  "plan_id": "...",
  "plan_version": "12",
  "lease_id": "...",
  "current_state_id": "start",
  "resume_required": false,
  "compiled_dag": {}
}
```

---

# 16. Watchdog

Create:

```text
backend/automation/scheduler/watchdog.py
backend/automation/management/commands/run_automation_watchdog.py
```

Run:

```bash
python manage.py run_automation_watchdog
```

Responsibilities:

### Lease reaper

Find:

```text
ACTIVE leases where expires_at < now
```

Mark them:

```text
EXPIRED
```

### Execution watchdog

If the associated execution is still incomplete:

```text
RUNNING/DISPATCHED -> STALLED
```

Only do this after a clearly defined timeout policy.

### Device health

If no heartbeat is received within the configured threshold:

```text
ONLINE/BUSY -> OFFLINE
```

Do not infer offline state from a single failed request.

### Recovery

For recoverable executions:

```text
STALLED
    ↓
recovery_status = PENDING
    ↓
eligible device reconnects
    ↓
resume
```

For exhausted retries:

```text
STALLED
    ↓
recovery_status = EXHAUSTED
    ↓
FAILED
```

---

# 17. Recovery Rules

Recovery must be deterministic before AI is involved.

Order:

```text
1. Detect failure.
2. Classify failure.
3. Check whether retry is allowed.
4. Verify checkpoint.
5. Verify current physical state.
6. Resume from safe point if possible.
7. Use AI recovery only for supported unexpected states.
8. Validate AI action.
9. Execute.
10. Verify.
11. Continue or fail.
```

Never let an AI response directly mutate execution state without validation.

---

# 18. Checkpoint Contract

The Android project already contains checkpoint-related local entities.

Use them as the local persistence layer.

Before executing a meaningful DAG transition:

```text
save local checkpoint
```

Checkpoint fields should include:

```text
executionId
planId
planVersion
stateId
stepIndex
contextVars
lastTransitionId
checkpointVersion
timestamp
```

After successful execution and server acknowledgement:

```text
server last_confirmed_state
server last_confirmed_step
server last_transition_id
server checkpoint_version
```

The server-confirmed checkpoint is authoritative for recovery.

The local checkpoint is the worker's recovery cache.

---

# 19. Idempotent Action Replay

Before replaying an action after reconnect:

```text
1. Compare checkpoint to server state.
2. Verify current page/state.
3. Check action result history.
4. Determine whether the action already succeeded.
5. Skip duplicate physical input when success is confirmed.
6. Only replay when safe.
```

Do not assume:

```text "request retried" = "action was not executed"
```

Network failure can happen after the browser action succeeded.

---

# 20. Android Foreground Worker Service

Create:

```text
AutomationWorkerService.kt
```

Path:

```text
app/src/main/java/com/multibrowser/antidetect/automation/
```

Responsibilities:

- maintain worker lifecycle
- claim work
- execute work
- heartbeat
- reconnect
- resume
- graceful shutdown
- service notification

Do not claim that a foreground service or WakeLock guarantees uninterrupted execution.

Test actual behavior under:

- screen off
- Doze
- app background
- temporary network loss
- process death
- device reboot
- supported Android 14+
- supported Android 15+

Select and declare the correct Android foreground-service type and permissions for the actual workload.

Do not add a WakeLock merely as a promise of reliability.

---

# 21. GhostPilotRunner Changes

Modify:

```text
GhostPilotRunner.kt
```

Add:

```text
restoreCheckpoint()
saveCheckpoint()
requestResumePlan()
verifyBeforeReplay()
reportTransition()
handleServerTerminalState()
```

Execution contract:

```text
Claim job
  ↓
Load plan
  ↓
Load checkpoint
  ↓
Reconcile local/server state
  ↓
Verify browser state
  ↓
Execute next command
  ↓
Verify physical result
  ↓
Persist checkpoint
  ↓
Report transition
  ↓
Receive next state
  ↓
Repeat
```

Never advance locally and assume the server accepted the transition.

---

# 22. Heartbeat Contract

Android should send a heartbeat at a configurable interval.

Suggested:

```text
30 seconds
```

Heartbeat must include:

```json
{
  "device_id": "...",
  "execution_id": "...",
  "lease_id": "...",
  "current_state_id": "...",
  "checkpoint_version": 17,
  "battery_percent": 73,
  "timestamp": "..."
}
```

Server response must clearly indicate:

```text
CONTINUE
CANCEL
TERMINAL_SUCCESS
TERMINAL_FAILURE
LEASE_EXPIRED
RESUME_REQUIRED
```

Android must stop when the server says the execution is terminal or the lease is invalid.

---

# 23. Device Registration API

Add or extend:

```text
POST /api/devices/register/
```

Report:

```text
device_id
Android version
app version
GeckoView version
battery
screen dimensions
capabilities
```

Do not trust arbitrary client-provided ownership.

Device authentication must remain bound to the authenticated account/device record.

---

# 24. Resume API

Add:

```text
GET /api/automation/ghostpilot/{execution_id}/resume/
```

Server returns:

```json
{
  "execution_id": "...",
  "plan_id": "...",
  "plan_version": "12",
  "state_id": "watch_video",
  "step_index": 17,
  "checkpoint_version": 9,
  "context": {},
  "resume_required": true
}
```

Before returning a resume plan, verify:

- execution ownership
- device authorization
- valid lease
- execution status
- recovery status
- plan version
- checkpoint consistency

---

# 25. Automation Policies

Store policies on `Automation`.

Required:

```text
concurrency_limit
cooldown_minutes
max_runtime_seconds
max_retries
failure_threshold_percent
priority
```

Example:

```json
{
  "concurrency_limit": 5,
  "cooldown_minutes": 30,
  "max_runtime_seconds": 1800,
  "max_retries": 2,
  "failure_threshold_percent": 30
}
```

---

# 26. Failure Threshold

A run may automatically pause when the configured failure threshold is exceeded.

Example:

```text
50 executions
30% threshold

If failures >= 15:
    pause remaining queued work
    mark AutomationRun PARTIAL
    create operator alert
```

Do not automatically delete or permanently disable the automation.

Require explicit operator action to resume a policy-paused automation unless a future policy enables automatic recovery.

---

# 27. Cooldown Handling

Cooldown must be evaluated server-side.

Do not rely on Android clocks.

Store the last successful or relevant execution timestamp needed by the business rule and compare using server time.

Example:

```text
last_completed_at + cooldown >= now
```

Return an explicit exclusion reason:

```text
COOLDOWN_ACTIVE
```

---

# 28. Automation Dashboard

Create:

```text
admin-panel/src/components/automation/AutomationsHub.jsx
```

Features:

### Automation list

Show:

```text
Name
Task
Status
Schedule
Next run
Profiles
Concurrency
Last run
Success rate
```

### Create/edit automation

Sections:

```text
Basic
Schedule
Targeting
Execution policy
Failure policy
Review
```

### Automation detail

Show:

```text
Automation
Next scheduled run
Current run
Queued
Running
Success
Failed
Stalled
Skipped
```

### Actions

```text
Enable
Disable
Run now
Pause
Resume
Cancel current run
View history
```

---

# 29. Fleet Monitor

Create:

```text
FleetMonitorHub.jsx
```

Show:

```text
Device
Status
Battery
App version
GeckoView
Current profile
Current execution
Current DAG state
Last heartbeat
Lease
```

Status colors should be accessible and should not rely only on color.

Actions:

```text
Pause execution
Abort execution
Release lease
Disable device
View telemetry
```

Dangerous actions require confirmation.

---

# 30. Automation History

Create a run history view.

Example:

```text
Run #184
2026-09-13 10:00

50 target profiles

Queued      50
Running      5
Success     40
Failed       3
Stalled      1
Skipped      1
Cancelled    0
```

Clicking the run opens executions.

Clicking an execution opens:

```text
Timeline
Plan version
Device
Lease
State transitions
Checkpoints
Recovery attempts
AI decisions
Errors
Final outcome
```

---

# 31. API Endpoints

Minimum V2 API:

```text
GET    /api/automation/rules/
POST   /api/automation/rules/
GET    /api/automation/rules/{id}/
PATCH  /api/automation/rules/{id}/
DELETE /api/automation/rules/{id}/

POST   /api/automation/rules/{id}/run-now/
POST   /api/automation/rules/{id}/pause/
POST   /api/automation/rules/{id}/resume/

GET    /api/automation/runs/
GET    /api/automation/runs/{id}/

GET    /api/automation/fleet/

POST   /api/automation/ghostpilot/claim-next/
POST   /api/automation/ghostpilot/heartbeat/
GET    /api/automation/ghostpilot/{id}/resume/
POST   /api/automation/ghostpilot/{id}/transition/
POST   /api/automation/ghostpilot/{id}/abort/

POST   /api/devices/register/
POST   /api/devices/heartbeat/
```

Names may be adjusted to match the existing API convention, but behavior must remain equivalent.

---

# 32. Event and Audit Model

Continue using `ExecutionEvent` as the structured execution timeline.

Add event types only when needed.

Events should include:

```text
AUTOMATION_RUN_CREATED
EXECUTION_QUEUED
DEVICE_ASSIGNED
LEASE_ACQUIRED
RUN_STARTED
STATE_CHANGED
HEARTBEAT
CHECKPOINT_SAVED
RECOVERY_STARTED
AI_DECISION
AI_RECOVERY
STALLED
RESUMED
SUCCESS
FAILED
ABORTED
LEASE_EXPIRED
```

Do not store secrets in event payloads.

Never log:

- API keys
- authentication tokens
- raw credentials
- private cookie values
- unredacted sensitive provider responses

---

# 33. AI Recovery Boundary

The existing TersoAssistant and recovery code should remain available.

AI is allowed to:

- inspect relevant execution context
- inspect sanitized DOM/state
- classify unexpected state
- propose supported recovery action
- provide reasoning
- choose among validated recovery tools

AI is not allowed to:

- bypass authorization
- bypass profile leases
- directly alter execution status
- directly modify security settings
- invent unsupported commands
- bypass policy validation

Every AI-generated action must pass the existing tool/action validator before execution.

---

# 34. No Direct AI Scheduler Control

Do not let the LLM decide fundamental scheduling facts.

Bad:

```text
LLM decides whether profile 17 can run.
```

Correct:

```text
Scheduler decides profile 17 is eligible.
AI handles unexpected runtime conditions if necessary.
```

The scheduler must remain deterministic and testable.

---

# 35. Existing Assistant Compatibility

Do not break:

```text
assistant_engine.py
assistant_tools.py
```

Existing tools such as:

```text
tool_get_fleet_status
tool_list_profiles
tool_dispatch_campaign
tool_execute_task_on_profiles
tool_abort_job
tool_get_job_telemetry
```

must continue to work.

Add new tools only where useful:

```text
tool_list_automations
tool_get_automation
tool_run_automation_now
tool_pause_automation
tool_resume_automation
tool_get_automation_run
tool_get_device_status
```

Mutation access should remain controlled by existing assistant write policies.

---

# 36. Management Commands

Add:

```bash
python manage.py run_automation_scheduler
python manage.py run_automation_watchdog
python manage.py automation_status
python manage.py automation_reconcile
```

`automation_reconcile` should:

- find orphan executions
- find inconsistent leases
- detect executions missing plans
- detect stale device assignments
- report inconsistencies
- perform only explicitly safe repairs

It must not silently destroy data.

---

# 37. Development Workflow

Extend:

```text
scripts/workflow.py
```

Add commands:

```text
scheduler
watchdog
automation-status
automation-reconcile
test-automation
```

Example:

```bash
python scripts/workflow.py scheduler
python scripts/workflow.py watchdog
python scripts/workflow.py test-automation
```

Do not alter the meaning of existing commands.

---

# 38. Database Migration Rules

Every model change requires:

```bash
python manage.py makemigrations
python manage.py migrate
python manage.py makemigrations --check --dry-run
```

Before migrations:

- inspect generated migration
- verify no unintended destructive operations
- preserve existing data
- create regression tests
- verify rollback/recovery strategy for production

Never reset the production database to solve migration problems.

---

# 39. Testing Strategy

## Backend scheduler tests

Test:

```text
daily due evaluation
interval due evaluation
one-time execution
timezone correctness
run idempotency
duplicate scheduler wake-up
eligibility filtering
cooldown
concurrency limits
priority
device compatibility
```

## Lease tests

Test:

```text
one device acquires
same device renews
different device rejected
expired lease becomes available
concurrent acquisition
lease/execution association
```

## Execution tests

Test:

```text
pending -> dispatched
dispatched -> running
running -> success
running -> failed
stalled detection
terminal execution cannot advance
duplicate transition is idempotent
```

## Checkpoint tests

Test:

```text
checkpoint save
checkpoint restore
server/local reconciliation
duplicate action prevention
resume after process restart
resume after network interruption
```

## Watchdog tests

Test:

```text
expired lease
stale heartbeat
stalled execution
retry
retry exhaustion
device recovery
```

## API tests

Test:

```text
authentication
authorization
device ownership
profile ownership
lease validation
malformed payloads
duplicate requests
terminal execution behavior
```

---

# 40. Android Test Matrix

Minimum real-device test matrix:

```text
Screen on
Screen locked
App backgrounded
Network temporarily disconnected
Wi-Fi reconnect
Server restart
Android process restart
Phone reboot
Lease expiry
Execution cancellation
Execution resume
Duplicate transition
Two devices competing for one profile
Two independent profiles on two devices
```

Do not mark the feature production-ready from JVM tests alone.

---

# 41. End-to-End Acceptance Test

This is the primary acceptance scenario.

### Setup

One Django server.

One Android phone connected over Wi-Fi.

At least two valid profiles.

At least one valid automation task.

### Procedure

```text
1. Start Django.
2. Start scheduler.
3. Start watchdog.
4. Start React dashboard.
5. Start Android worker.
6. Register device.
7. Create ONE_TIME automation targeting 2 profiles.
8. Press Run Now.
9. Scheduler creates AutomationRun.
10. Two executions are queued.
11. Android claims Profile A.
12. Lease is acquired.
13. Profile A begins.
14. Heartbeats continue.
15. Checkpoints are written.
16. Profile A completes.
17. Lease is released.
18. Android claims Profile B.
19. Profile B completes.
20. AutomationRun becomes COMPLETED.
21. Dashboard shows final metrics.
```

### Failure test

Repeat the run and interrupt the Android process during execution.

Expected:

```text
execution becomes STALLED
lease expires
watchdog detects stale execution
recovery becomes PENDING
device reconnects
worker receives resume plan
checkpoint is reconciled
execution resumes or safely fails
no duplicate physical action is performed
run metrics remain correct
```

---

# 42. Two-Device Mutual Exclusion Test

This is mandatory.

Setup:

```text
Device A
Device B
Profile X
```

Both devices attempt to claim work for Profile X simultaneously.

Expected:

```text
Device A -> lease acquired
Device B -> lease rejected
```

There must never be two valid active leases for Profile X.

This test must run concurrently, not sequentially.

---

# 43. Scheduler Reliability Test

Start two scheduler processes accidentally.

Expected:

```text
One logical due occurrence
One AutomationRun
```

The database's uniqueness and transactional logic must prevent duplicate runs.

The system must remain correct even if scheduler processes overlap.

---

# 44. Security Requirements

Production must include:

```text
DEBUG=False
strong SECRET_KEY
HTTPS
trusted CORS origins
secure cookies
device authentication
authorization checks
secret-safe telemetry
token protection
```

Do not expose:

```text
API keys
provider credentials
session credentials
cookies
internal exception traces
```

inside dashboard telemetry or AI prompts unless explicitly required and securely redacted.

---

# 45. Android Security

Continue using the existing secure token storage.

Do not revert to plaintext saved tokens.

Device registration must identify the actual authenticated device.

A revoked/disabled device must not be allowed to claim new jobs.

---

# 46. Production Deployment Shape

Initial deployment may use:

```text
Django API process
Django scheduler process
Django watchdog process
PostgreSQL
Android worker devices
React static build
```

The scheduler and watchdog should be separately supervised processes.

Do not expose the Vite development server in production.

Use a production ASGI/WSGI configuration appropriate to the deployed Django stack.

---

# 47. Implementation Order

Do not implement everything at once.

## Phase 0 — Baseline

Before code changes:

```text
run backend tests
run dashboard tests/build
run Android JVM tests
run Android debug build
inspect migration state
record current commit
```

No feature work until baseline is green or existing failures are documented.

---

## Phase 1 — Domain and Plan Versioning

Implement:

```text
Automation
AutomationRun
ExecutionPlan
Execution additions
migrations
serializers
admin
tests
```

Acceptance:

```text
create automation
calculate next run
create run
create versioned plan
historical execution retains exact plan
```

---

## Phase 2 — Deterministic Scheduler

Implement:

```text
scheduler/service.py
scheduler/planner.py
scheduler/dispatcher.py
run_automation_scheduler
```

Acceptance:

```text
ONE_TIME works
DAILY works
INTERVAL works
timezone works
duplicate scheduler cycles do not duplicate runs
eligible profile selection works
```

---

## Phase 3 — Lease and Device Allocation

Implement:

```text
device capability matching
atomic allocation
lease validation
worker claim API
concurrency tests
```

Acceptance:

```text
one profile = one active lease
two devices cannot execute one profile simultaneously
unsupported device does not receive incompatible task
```

---

## Phase 4 — Android Worker Resilience

Implement:

```text
AutomationWorkerService
heartbeat
checkpoint integration
resume API
server/local checkpoint reconciliation
```

Acceptance:

```text
screen lock
backgrounding
network interruption
process restart
resume
terminal stop
```

---

## Phase 5 — Watchdog and Recovery

Implement:

```text
watchdog
stale device detection
lease reaper
stalled executions
retry rules
recovery status
```

Acceptance:

```text
crashed worker does not permanently block profile
stale job is recovered safely
retry limits are respected
```

---

## Phase 6 — Dashboard

Implement:

```text
AutomationsHub
FleetMonitorHub
Automation history
Execution detail
```

Acceptance:

```text
operator can create automation
operator can schedule
operator can run now
operator can pause/resume
operator can inspect live execution
operator can inspect failures
```

---

## Phase 7 — AI Operational Layer

Extend TersoAssistant:

```text
automation inspection
run-now
pause/resume
fleet diagnosis
recovery explanation
```

Keep deterministic scheduling outside the LLM.

---

## Phase 8 — Production Hardening

Before production:

```text
PostgreSQL
HTTPS
secret management
device revocation
release Android build
real-device testing
dependency audit
migration rehearsal
backup/restore rehearsal
observability
```

---

# 48. Definition of Done

Automation V2 is complete only when all are true:

```text
[ ] Automation model exists
[ ] AutomationRun exists
[ ] ExecutionPlan versioning exists
[ ] Scheduler is a real running process
[ ] Scheduler is idempotent
[ ] ONE_TIME works
[ ] DAILY works
[ ] INTERVAL works
[ ] Timezone handling works
[ ] Eligibility rules work
[ ] Cooldown works
[ ] Concurrency limits work
[ ] Device capabilities work
[ ] Atomic job allocation works
[ ] Lease exclusivity is proven by concurrency tests
[ ] Worker registration works
[ ] Heartbeats work
[ ] Checkpoints work
[ ] Resume works
[ ] Watchdog works
[ ] Retry policy works
[ ] Recovery exhaustion works
[ ] Dashboard automation management works
[ ] Fleet monitor works
[ ] Run history works
[ ] Execution telemetry works
[ ] AI recovery remains validated
[ ] Security requirements pass
[ ] Backend tests pass
[ ] Dashboard tests/build pass
[ ] Android tests/build pass
[ ] Real-device acceptance test passes
[ ] Two-device mutual exclusion test passes
[ ] Restart/reconnect recovery test passes
```

---

# 49. Antigravity Execution Rules

Antigravity must follow these rules while implementing this document.

### Rule 1 — Inspect before editing

Before changing a file:

- inspect the current implementation
- confirm whether the planned symbol already exists
- reuse existing services where possible
- do not create duplicate models or services

### Rule 2 — Small phases

Only implement one phase at a time.

After each phase:

```text
run tests
run build/checks
inspect diff
update docs
```

Do not start the next phase while the previous phase is failing.

### Rule 3 — Regression protection

Every bug discovered during implementation requires:

```text
root cause
fix
regression test
```

Do not patch symptoms only.

### Rule 4 — No destructive shortcuts

Never:

```text
delete database
reset migrations
remove existing tests
replace working execution engine
disable authentication
disable authorization
```

to make tests pass.

### Rule 5 — Preserve contracts

Existing Android/API contracts must remain backwards-compatible unless a deliberate versioned migration is implemented.

### Rule 6 — Verify unsupported behavior

Do not claim completion merely because:

```text
Python imports
Django starts
React builds
Android compiles
```

A feature that requires actual device execution must receive real-device verification before being marked complete.

### Rule 7 — Keep documentation current

Update:

```text
docs/PROJECT_SPECIFICATION.md
docs/AUDIT.md
docs/DEVELOPMENT.md
docs/API.md
docs/FILE_MAP.md
```

as implementation changes the architecture.

---

# 50. Final Architecture

The finished system should behave like:

```text
                    ┌────────────────────────────┐
                    │       React Admin          │
                    │                            │
                    │ Automations                 │
                    │ Fleet Monitor               │
                    │ Run History                 │
                    │ Execution Detail             │
                    │ TersoAssistant               │
                    └─────────────┬──────────────┘
                                  │
                                  ▼
                    ┌────────────────────────────┐
                    │     Django Control Plane   │
                    │                            │
                    │ Scheduler                  │
                    │ Eligibility Engine          │
                    │ Run Manager                 │
                    │ Plan Compiler               │
                    │ Device Allocator            │
                    │ Lease Manager               │
                    │ Watchdog                    │
                    │ Execution Service           │
                    │ AI Recovery                 │
                    └─────────────┬──────────────┘
                                  │
                    ┌─────────────┴──────────────┐
                    │     Durable Database        │
                    │                            │
                    │ Automations                 │
                    │ AutomationRuns              │
                    │ ExecutionPlans              │
                    │ Executions                  │
                    │ Leases                      │
                    │ Devices                     │
                    │ Events                      │
                    └─────────────┬──────────────┘
                                  │
                         HTTPS / LAN
                                  │
               ┌──────────────────┼──────────────────┐
               ▼                  ▼                  ▼
        ┌────────────┐     ┌────────────┐     ┌────────────┐
        │ Android A  │     │ Android B  │     │ Android C  │
        │ Worker     │     │ Worker     │     │ Worker     │
        │            │     │            │     │            │
        │ Service    │     │ Service    │     │ Service    │
        │ Runner     │     │ Runner     │     │ Runner     │
        │ Checkpoint │     │ Checkpoint │     │ Checkpoint │
        │ GeckoView  │     │ GeckoView  │     │ GeckoView  │
        └────────────┘     └────────────┘     └────────────┘
```

This architecture keeps the current project intact while adding the missing durable automation layer.

**The critical invariant is:**

```text
Django decides.
Database remembers.
Lease prevents conflicts.
Android executes.
Checkpoint enables recovery.
Verification confirms reality.
AI assists only inside validated boundaries.
```

That is the target architecture for TersoPilot Automation V2.
