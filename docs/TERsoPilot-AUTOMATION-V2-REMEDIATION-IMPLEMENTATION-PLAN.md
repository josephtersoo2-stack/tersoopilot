# TersoPilot Automation V2 — Comprehensive Remediation & Hardening Implementation Plan

**Repository:** `josephtersoo2-stack/tersoopilot`  
**Branch:** `main`  
**Latest reviewed commit:** `b8d1f3a34e72e48af21bba4cbf5879966f395b62`  
**Purpose:** Fix the remaining Automation V2 correctness problems, complete the Android worker-to-browser execution bridge, remove obsolete/duplicate code paths, and prove the system with automated and real-device verification.

---

# 0. Executive Summary

Automation V2 has now been implemented across the Django backend, Android worker, and React dashboard. The latest GitHub Actions run is green for backend, dashboard, and Android build/tests.

That green CI result is useful, but it does **not** prove that unattended automation works end-to-end.

The repository still contains architectural and implementation defects that must be resolved before production readiness.

The most important finding is:

```text
Django can schedule and allocate a job.
Android can claim the job.
But the new AutomationWorkerService does not actually hand the claimed
job to GhostPilotRunner and execute the returned DAG.
```

At the same time, `GhostPilotRunner` still has its own legacy polling path.

That creates two competing execution flows.

The target architecture is:

```text
Automation Scheduler
        |
        v
AutomationRun
        |
        v
Execution
        |
        v
Device allocation + profile lease
        |
        v
Android AutomationWorkerService
        |
        v
Load correct browser profile
        |
        v
Create/attach Gecko session
        |
        v
GhostPilotRunner
        |
        v
Execute DAG
        |
        v
Verify action
        |
        v
Checkpoint
        |
        v
Server transition
        |
        v
Next state
```

There must be only **one** authoritative Android job-acquisition path.

---

# 1. Implementation Status at Start

## Already implemented

The current repository contains:

- `Automation`
- `AutomationRun`
- `ExecutionPlan`
- durable `Execution`
- `ExecutionLease`
- `ExecutionEvent`
- scheduler package
- scheduler management command
- watchdog package
- watchdog management command
- device registry
- device heartbeat
- automation dashboard
- fleet monitor
- Android `AutomationWorkerService`
- Android checkpoint database schema
- `GhostPilotRunner`
- AI operational tools
- workflow CLI commands
- CI checks

Relevant current files include:

```text
backend/automation/models.py
backend/automation/scheduler/service.py
backend/automation/scheduler/planner.py
backend/automation/scheduler/policies.py
backend/automation/scheduler/dispatcher.py
backend/automation/scheduler/watchdog.py
backend/automation/management/commands/run_automation_scheduler.py
backend/automation/management/commands/run_automation_watchdog.py
backend/automation/views.py
backend/automation/urls.py

backend/executions/models.py
backend/executions/services.py

app/src/main/java/com/multibrowser/antidetect/automation/AutomationWorkerService.kt
app/src/main/java/com/multibrowser/antidetect/automation/GhostPilotRunner.kt
app/src/main/java/com/multibrowser/antidetect/automation/AutomationCoordinator.kt
app/src/main/java/com/multibrowser/antidetect/network/GhostPilotApiService.kt
app/src/main/java/com/multibrowser/antidetect/data/model/ExecutionCheckpointEntity.kt
app/src/main/java/com/multibrowser/antidetect/data/db/AppDatabase.kt

admin-panel/src/components/automation/AutomationsHub.jsx
admin-panel/src/components/automation/FleetMonitorHub.jsx
```

---

# 2. Critical Findings That Must Be Fixed

## Finding A — Worker claims jobs but does not execute them

Current `AutomationWorkerService` claims a job and sets active job metadata, acquires a WakeLock, then immediately clears the metadata and releases the WakeLock. It does not call `GhostPilotRunner`.

This must be fixed before any end-to-end success claim.

---

## Finding B — GhostPilotRunner still owns a legacy polling path

Current `GhostPilotRunner.executionLoop()` still contains the old `pollJob(...)` path while the new worker service uses `claimNext(...)`.

This creates two job-acquisition systems.

Final architecture:

```text
AutomationWorkerService
    |
    +--> claimNext()
    |
    +--> profile/session preparation
    |
    +--> GhostPilotRunner.executeClaimedJob()
```

Not:

```text
AutomationWorkerService --> claimNext()
GhostPilotRunner --> pollJob()
```

---

## Finding C — Worker has no complete profile-to-Gecko session bridge

`GhostPilotRunner` expects an existing `GeckoSession`, browser view, and input controller. That works for interactive `MainActivity` use, but unattended worker mode needs to construct those resources from a server-selected `profile_id` without requiring the operator to manually open the profile.

Required bridge:

```text
profile_id
  -> local profile load
  -> Gecko profile preparation
  -> GeckoSession
  -> GeckoView
  -> InputController
  -> GhostPilotRunner
```

---

## Finding D — `ALL_ELIGIBLE` references `automation.task.user`

The current eligibility code assumes `automation.task.user`, but `AutomationTask` does not define a `user` field.

Remove that assumption and use the repository's actual ownership boundary.

Never create an ownership rule around a nonexistent field.

---

## Finding E — Compiler failure creates a fake fallback DAG

The scheduler currently creates a synthetic `WAIT -> TERMINATE` graph when recipe compilation fails.

That is dangerous.

Correct behavior:

```text
Compiler failure
    |
    v
AutomationRun FAILED
    |
    v
No fake execution
    |
    v
Structured error + event + dashboard visibility
```

Never turn compiler failure into apparent success.

---

## Finding F — Plan versioning is ambiguous

`ExecutionPlan` exists and is a good addition. However, the scheduler also creates a profile-specific DAG for each execution.

Formalize the meaning:

```text
ExecutionPlan
    = immutable task-level compilation/version

Execution.compiled_dag
    = immutable profile-resolved DAG derived from that plan
```

Historical executions must remain reproducible.

---

## Finding G — Cancellation semantics are inconsistent

`ExecutionStatus.CANCELLED` exists, but the run cancellation endpoint currently turns pending executions into `FAILED`.

Correct behavior:

```text
Operator cancels run
    |
    +--> PENDING -> CANCELLED
    |
    +--> DISPATCHED/RUNNING -> cancellation requested
    |
    +--> terminal -> unchanged
```

Cancellation must not inflate failure counts.

---

## Finding H — Run `started_at` is populated too early

A run can be stored as `SCHEDULED` while already having `started_at` populated.

Correct semantics:

```text
created_at    = database row creation time
scheduled_for = intended schedule occurrence
started_at    = first actual dispatch/start
completed_at  = final terminal state
```

---

## Finding I — Lease index is not a mutual-exclusion guarantee

An index such as:

```text
(profile, status, expires_at)
```

improves lookup performance. It does not by itself guarantee one active lease.

The invariant must be protected by transaction locking, database constraints where supported, and concurrent tests.

---

# 3. Target Architecture

```text
                        React Admin
                            |
                            | HTTPS / REST / SSE
                            v
                  +-----------------------+
                  |    Django Control     |
                  |        Plane          |
                  |-----------------------|
                  | Scheduler             |
                  | Eligibility           |
                  | Plan Compiler         |
                  | Run Manager           |
                  | Dispatcher            |
                  | Lease Manager         |
                  | Watchdog              |
                  | Device Registry       |
                  | Recovery              |
                  +-----------+-----------+
                              |
                         PostgreSQL
                              |
                   +----------+----------+
                   |                     |
                   v                     v
             Android Worker A       Android Worker B
                   |                     |
                   v                     v
          AutomationWorkerService  AutomationWorkerService
                   |                     |
                   v                     v
             Profile Loader         Profile Loader
                   |                     |
                   v                     v
             Gecko Session          Gecko Session
                   |                     |
                   v                     v
            GhostPilotRunner       GhostPilotRunner
```

Ownership:

```text
Django:
    schedule
    target
    allocate
    lease
    persist
    recover
    authorize

Android:
    claim
    load browser profile
    execute
    inspect page
    verify result
    checkpoint
    heartbeat
    transition
```

---

# 4. Phase 0 — Freeze and Baseline

Before changes:

```bash
git rev-parse HEAD
git status --short
```

Do not erase user changes.

Run:

```bash
python scripts/workflow.py check
python scripts/workflow.py check-all
```

Backend:

```bash
cd backend
python manage.py check
python manage.py makemigrations --check --dry-run
python manage.py test --noinput
```

Dashboard:

```bash
cd admin-panel
npm test
npm run build
```

Android:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Record the baseline result in the implementation notes.

---

# 5. Phase 1 — Eliminate the Double Android Polling Architecture

This is the first blocker.

## 5.1 Make the worker the only acquisition authority

`AutomationWorkerService` owns:

```text
claimNext
```

`GhostPilotRunner` owns execution only.

---

## 5.2 Remove legacy polling from GhostPilotRunner

Search before deletion:

```bash
git grep -n "pollJob"
git grep -n "work_available"
git grep -n "poll/{profile_id}"
```

If there are no legitimate remaining consumers:

- remove `pollJob()` from `GhostPilotApiService.kt`
- remove old poll execution branches from `GhostPilotRunner.kt`
- remove stale parameters only used by polling
- remove tests that exist solely for deleted polling behavior
- remove obsolete comments
- update docs

Do not merely comment the old code out.

---

## 5.3 Add a claimed execution envelope

Use a strongly typed model instead of passing unvalidated JSON through the whole worker.

Sample:

```kotlin
data class ClaimedExecution(
    val executionId: String,
    val taskId: String,
    val profileId: String,
    val planId: String?,
    val planVersion: String,
    val compiledDag: JsonObject,
    val entryState: String,
    val leaseId: String,
    val leaseExpiresAt: String?,
    val checkpointVersion: Int,
    val lastConfirmedState: String?,
    val lastConfirmedStep: Int?,
    val lastTransitionId: String?,
    val recoveryStatus: String?,
    val executionContext: Map<String, Any?>
)
```

Validate required fields before starting execution.

---

# 6. Phase 2 — Build the Missing Profile-to-Gecko Worker Bridge

Create:

```text
app/src/main/java/com/multibrowser/antidetect/automation/WorkerProfileSessionManager.kt
```

Responsibilities:

```text
load local profile
verify cloud mapping when required
prepare Gecko profile
create GeckoSession
attach GeckoView
create InputController
construct GhostPilotRunner
```

Suggested context object:

```kotlin
data class WorkerExecutionContext(
    val profile: ProfileEntity,
    val geckoSession: GeckoSession,
    val targetView: GeckoView,
    val inputController: InputController,
    val runner: GhostPilotRunner
)
```

---

## 6.1 Reuse existing browser infrastructure

Inspect before adding new abstractions:

```text
GeckoProfileEngine.kt
GeckoProfileManager.kt
BrowserCoordinator.kt
SessionPoolManager.kt
```

Find the existing profile/session creation path.

Prefer adapting an existing service over creating duplicates such as:

```text
WorkerGeckoProfileEngine
WorkerGeckoProfileManager
WorkerSessionManager
```

unless a real separation of lifecycle requires them.

---

# 7. Phase 3 — Refactor GhostPilotRunner to Execute an Assigned Job

Recommended interface:

```kotlin
suspend fun execute(
    execution: ClaimedExecution
): ExecutionResult
```

The runner should initialize from the assigned execution:

```kotlin
suspend fun execute(execution: ClaimedExecution): ExecutionResult {
    currentJobId = execution.executionId
    currentDag = execution.compiledDag
    currentStateId = execution.entryState
    currentPlanId = execution.planId.orEmpty()
    currentPlanVersion = execution.planVersion
    currentCheckpointVersion = execution.checkpointVersion

    reconcileCheckpoint(execution)

    while (isRunning) {
        executeNextState()
    }

    return buildExecutionResult()
}
```

The outer worker controls lifecycle.

The runner controls:

```text
DAG execution
command execution
verification
checkpointing
transition reporting
recovery
```

---

# 8. Phase 4 — Correct AutomationWorkerService

The current placeholder logic must be replaced.

Sample target flow:

```kotlin
private suspend fun processClaimedJob(job: JsonObject) {
    val execution = parseClaimedExecution(job)

    activeJobId = execution.executionId
    activeProfileName = execution.profileId

    acquireWakeLock()

    try {
        val workerContext = profileSessionManager.prepare(
            profileId = execution.profileId
        )

        updateNotification(
            "Executing ${workerContext.profile.name}"
        )

        val result = workerContext.runner.execute(execution)

        handleExecutionResult(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportWorkerExecutionFailure(
            executionId = execution.executionId,
            message = e.message ?: "Worker execution failed"
        )
    } finally {
        cleanupWorkerExecution()
    }
}
```

And:

```kotlin
private fun cleanupWorkerExecution() {
    activeJobId = null
    activeProfileName = null
    releaseWakeLock()
}
```

Do not show "completed" before execution has actually reached a terminal state.

---

# 9. Phase 5 — Separate Interactive and Autonomous Execution

The existing `MainActivity` is allowed to manage interactive browser sessions.

Autonomous worker mode must not depend on:

```text
Compose state
current foreground profile
operator opening a profile
current UI tab
```

Define a mode if needed:

```kotlin
enum class AutomationExecutionMode {
    INTERACTIVE,
    AUTONOMOUS_WORKER
}
```

The worker lifecycle must function with the application UI not visible.

---

# 10. Phase 6 — Fix ALL_ELIGIBLE Ownership

Remove:

```python
automation.task.user
```

Use the application's actual ownership model.

Preferred pattern if `Automation` truly owns the schedule:

```python
owner = models.ForeignKey(
    settings.AUTH_USER_MODEL,
    on_delete=models.CASCADE,
    related_name="automations",
)
```

Then:

```python
SavedProfile.objects.filter(user=automation.owner)
```

Only introduce the field if the existing ownership design requires it. Do not add duplicate ownership relationships.

---

# 11. Phase 7 — Formalize Selection Modes

`SelectionMode` must mean exactly:

```text
EXPLICIT_PROFILES
    -> only target_profiles

NICHE
    -> profiles connected to target_niches

ALL_ELIGIBLE
    -> all profiles inside the automation's legitimate ownership/fleet boundary
```

Return explicit reasons for exclusions:

```text
PROFILE_NOT_FOUND
PROFILE_DISABLED
TARGET_EXCLUDED
NO_NICHE_AFFILIATION
ACTIVE_LEASE_CONFLICT
COOLDOWN_ACTIVE
ALREADY_QUEUED
INVALID_TASK
```

---

# 12. Phase 8 — Remove Fake Compiler Fallbacks

Delete behavior equivalent to:

```python
except Exception:
    compiled_dag = {
        "entry_state": "init",
        "states": {
            "init": {"cmd": "WAIT", "params": {"seconds": 2}, "on_success": "exit"},
            "exit": {"cmd": "TERMINATE", "params": {}}
        }
    }
```

Replace with an explicit error.

Sample:

```python
class PlanCompilationError(Exception):
    """The task cannot be safely compiled into an execution plan."""
```

Then:

```python
try:
    compiled_dag = RecipeCompiler.compile_recipe(
        task,
        profile=None,
    )
    DAGValidator.validate(compiled_dag)
except Exception as exc:
    logger.error(
        "Plan compilation failed for task %s",
        task.id,
        exc_info=True,
    )
    raise PlanCompilationError(
        f"Unable to compile task {task.id}"
    ) from exc
```

---

# 13. Phase 9 — Correct Run-Level Compiler Failure

When the run cannot obtain a valid plan:

```python
run.status = AutomationRunStatus.FAILED
run.completed_at = now
run.summary_metrics = {
    "reason": "PLAN_COMPILATION_FAILED",
    "error": str(exc),
}
run.save(...)
```

Do not create fake executions.

Use a run-level event/audit record if the event model is extended to support it. Do not invent a fake execution just to attach an error event.

---

# 14. Phase 10 — Remove Profile Compilation Fallbacks

Current anti-pattern:

```python
try:
    profile_dag = RecipeCompiler.compile_recipe(...)
except Exception:
    profile_dag = plan.compiled_dag
```

Replace with:

```python
try:
    profile_dag = RecipeCompiler.compile_recipe(
        automation.task,
        profile=profile,
    )
    DAGValidator.validate(profile_dag)
except Exception as exc:
    raise ProfilePlanCompilationError(
        f"Profile-specific compilation failed for profile={profile.id}"
    ) from exc
```

The scheduler must decide explicitly whether that profile is skipped or the run fails according to policy. It must never silently execute an unverified graph.

---

# 15. Phase 11 — Formalize Plan Version Semantics

Keep the existing `ExecutionPlan` model.

Define:

```text
ExecutionPlan:
    task-level immutable base plan

Execution:
    immutable profile-resolved plan snapshot
```

Recommended execution metadata:

```text
plan
plan_version
base_plan_version
profile_resolution_hash
compiled_dag
```

Historical executions must never change when the source task changes later.

---

# 16. Phase 12 — Fix Run Timestamps

When created:

```python
run.status = AutomationRunStatus.SCHEDULED
run.started_at = None
```

When first execution is actually dispatched:

```python
if run.status == AutomationRunStatus.SCHEDULED:
    run.status = AutomationRunStatus.RUNNING
    run.started_at = timezone.now()
```

When all required work is terminal:

```python
run.completed_at = timezone.now()
```

---

# 17. Phase 13 — Preserve the Actual Scheduled Occurrence

Do not populate `scheduled_for` with scheduler polling time when the schedule has an explicit occurrence.

Example:

```text
schedule = 10:00 Africa/Lagos
scheduler wakes at 10:00:14
```

Then:

```text
scheduled_for = 10:00 Africa/Lagos
created_at    = 10:00:14
```

The `run_key` should represent that same logical occurrence.

---

# 18. Phase 14 — Scheduler Idempotency

Keep:

```text
UNIQUE(automation, run_key)
```

Use:

```python
run, created = AutomationRun.objects.get_or_create(
    automation=automation,
    run_key=run_key,
    defaults={
        "scheduled_for": scheduled_for,
        "status": AutomationRunStatus.SCHEDULED,
    },
)
```

If `created` is false, do not create another set of executions.

Must be concurrency tested.

---

# 19. Phase 15 — Interval Scheduling Semantics

Define interval behavior explicitly.

Recommended:

```text
INTERVAL = schedule occurrences, not completion-to-completion delay
```

Store schedule occurrence state separately when needed.

Do not use execution completion timing as an accidental scheduler clock.

---

# 20. Phase 16 — Lease Hardening

Retain the existing centralized service:

```text
LeaseService.acquire_lease()
LeaseService.heartbeat()
LeaseService.release_lease()
LeaseService.get_profile_lease_status()
```

No duplicate lease logic in views, worker, dispatcher, or runner.

---

## 20.1 Active lease invariant

Required invariant:

```text
One profile cannot have two valid ACTIVE leases simultaneously.
```

Use:

```text
transaction
select_for_update(profile)
active lease validation
expiry handling
optional database uniqueness constraint
concurrent tests
```

The index is only an optimization.

---

# 21. Phase 17 — Partial Active-Lease Constraint

Where the production database supports it safely, use a constraint conceptually equivalent to:

```python
models.UniqueConstraint(
    fields=["profile"],
    condition=models.Q(status=ExecutionLeaseStatus.ACTIVE),
    name="uniq_active_lease_per_profile",
)
```

Verify migration compatibility before applying.

If the production database cannot support it, preserve transactional locking and prove the invariant through concurrency tests.

---

# 22. Phase 18 — Dispatcher Atomicity

The dispatcher must perform allocation atomically:

```text
BEGIN
  |
  v
select candidate with row lock
  |
  v
validate automation
  |
  v
validate device
  |
  v
acquire profile lease
  |
  v
mark execution DISPATCHED
  |
  v
associate lease/device
  |
  v
update run state
  |
  v
COMMIT
```

Any failure rolls the allocation back.

---

# 23. Phase 19 — Device Capability Matching

The device registry should report capabilities such as:

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

The scheduler/dispatcher must never send a command requiring an unsupported capability.

---

# 24. Phase 20 — Device Registration and Revocation

Device registration must be idempotent.

On re-registration:

```text
update presence
update app version
update capability snapshot
update heartbeat
```

Do not overwrite ownership accidentally.

Disabled/rejected devices must not claim new work or renew execution leases.

---

# 25. Phase 21 — Correct Cancellation Semantics

Use:

```text
PENDING -> CANCELLED
DISPATCHED/RUNNING -> cancel requested
SUCCESS -> unchanged
FAILED -> unchanged
STALLED -> policy-specific
```

If required, add:

```python
cancel_requested = models.BooleanField(default=False)
```

Do not use `FAILED` as a synonym for `CANCELLED`.

---

# 26. Phase 22 — Active Execution Cancellation

For a running job:

```text
operator clicks Abort
      |
      v
server marks cancellation requested
      |
      v
Android heartbeat/transition receives CANCEL
      |
      v
runner stops safely
      |
      v
execution becomes CANCELLED
      |
      v
lease released
```

Watchdog must clean up the lease if the device disappears.

---

# 27. Phase 23 — Heartbeat Contracts

Keep separate meanings:

## Device heartbeat

Tracks:

```text
device presence
battery
application health
network availability
```

## Execution heartbeat

Tracks:

```text
execution_id
lease_id
state
checkpoint
```

Recommended execution response:

```json
{
  "command": "CONTINUE",
  "execution_id": "...",
  "lease_valid": true,
  "server_status": "RUNNING",
  "current_state_id": "watch_video",
  "checkpoint_version": 7
}
```

Possible commands:

```text
CONTINUE
CANCEL
TERMINAL_SUCCESS
TERMINAL_FAILURE
LEASE_EXPIRED
RESUME_REQUIRED
```

---

# 28. Phase 24 — Checkpoint Contract

The current Android checkpoint entity already provides a useful foundation:

```kotlin
data class ExecutionCheckpointEntity(
    val jobId: String,
    val profileId: String,
    val currentStateId: String,
    val executedSteps: Int,
    val lastCommand: String,
    val status: String,
    val updatedAt: Long,
    val planId: String,
    val planVersion: String,
    val contextVars: String,
    val lastTransitionId: String,
    val checkpointVersion: Int,
)
```

Do not create another local checkpoint table.

---

# 29. Phase 25 — Correct Checkpoint Timing

Distinguish:

```text
INTENT CHECKPOINT
    state we are about to execute

CONFIRMED CHECKPOINT
    state/action already executed and verified
```

The server-confirmed checkpoint is authoritative for recovery.

The local checkpoint survives worker/process interruption.

---

# 30. Phase 26 — Physical Action Idempotency

Use a deterministic action ID:

```kotlin
val actionId =
    "${executionId}:${stateId}:${stepIndex}"
```

Before physical input:

```text
check local action result
```

If confirmed `SUCCESS`, do not repeat the physical action.

---

# 31. Phase 27 — Network Retry Must Not Repeat Physical Input

Correct:

```text
physical action
    |
    v
verify
    |
    v
persist local result
    |
    v
report transition
    |
    X network timeout
    |
    v
retry transition only
```

Never interpret an HTTP/network timeout as proof that the browser action failed.

---

# 32. Phase 28 — Resume Algorithm

On worker startup/reconnection:

```text
1. Find incomplete local checkpoints.
2. Authenticate device.
3. Ask server whether execution remains valid.
4. Validate lease.
5. Validate plan version.
6. Compare checkpoint versions.
7. Receive authoritative resume state.
8. Prepare correct Gecko profile/session.
9. Verify physical browser state.
10. Continue or fail safely.
```

---

# 33. Phase 29 — Resume Endpoint

Keep:

```text
GET /api/automation/ghostpilot/{id}/resume/
```

Require sufficient authorization/context such as:

```text
device_id
lease_id
```

where the current contract permits it.

Response example:

```json
{
  "execution_id": "...",
  "plan_id": "...",
  "plan_version": "12",
  "current_state_id": "watch_video",
  "last_confirmed_step": 14,
  "checkpoint_version": 9,
  "resume_required": true
}
```

---

# 34. Phase 30 — Validate Resume Against Plan

Reject resume when:

```text
execution is terminal
lease invalid
profile unauthorized
plan missing
plan version mismatch
checkpoint references unknown state
checkpoint belongs to another execution
```

Never guess a resume point.

---

# 35. Phase 31 — GhostPilotRunner Closed-Loop Execution

For each state:

```text
state lookup
    |
    v
command validation
    |
    v
intent checkpoint
    |
    v
physical command
    |
    v
verification
    |
    +---- SUCCESS -> confirmed checkpoint -> transition
    |
    +---- FAILURE -> recovery policy
```

Do not equate "Kotlin method returned without exception" with successful browser state.

---

# 36. Phase 32 — Command-Specific Verification

At minimum, review:

```text
NAVIGATE
    verify expected URL/page

CLICK
    verify expected DOM/page state

TYPE_TEXT
    verify field/result where safely observable

SUBMIT_INPUT
    verify resulting page/state

SCROLL
    verify viewport/state change

other state-changing commands
    define command-specific verification
```

Use the existing `VerificationEngine` rather than building a duplicate verification system.

---

# 37. Phase 33 — Unsupported Command Contract

Every emitted compiler command must have:

```text
CommandRegistry entry
Android implementation
validation
verification where applicable
test coverage
```

Add a contract test that fails when the compiler can emit a command the Android registry cannot execute.

---

# 38. Phase 34 — Android Foreground Worker

`AutomationWorkerService` may remain the long-lived worker lifecycle owner.

It must:

```text
register device
maintain presence
claim job
prepare profile
start runner
monitor runner
report heartbeat
handle cancellation
release resources
```

Do not claim `START_STICKY` alone proves restart reliability.

---

# 39. Phase 35 — Foreground Service Policy

The manifest currently uses a foreground-service declaration.

Before production, validate the selected foreground-service type against:

```text
actual workload
supported Android versions
permissions
service startup restrictions
notification requirements
distribution policies
```

Do not claim that the current declaration is certified merely because the app compiles.

---

# 40. Phase 36 — WakeLock Policy

A WakeLock is a bounded execution aid, not a guarantee of uninterrupted work.

Keep it bounded and release it on:

```text
success
failure
cancel
pause
service stop
exception
```

Test actual behavior with screen lock and Doze.

---

# 41. Phase 37 — Watchdog Correctness

Watchdog order:

```text
1. expire leases
2. reconcile active executions
3. detect stale devices
4. mark stalled/recovery state
5. apply retry policy
6. evaluate failure thresholds
7. finalize run metrics
```

State transitions must be conditional so multiple watchdog processes cannot retry or recount the same execution repeatedly.

Example:

```python
updated = Execution.objects.filter(
    id=execution.id,
    status__in=[
        ExecutionStatus.RUNNING,
        ExecutionStatus.DISPATCHED,
    ],
).update(
    status=ExecutionStatus.STALLED,
    recovery_status=RecoveryStatus.PENDING,
)
```

If `updated == 0`, another worker already handled it.

---

# 42. Phase 38 — Run Failure Threshold

When the failure threshold is reached:

```text
1. stop new pending dispatches
2. cancel remaining PENDING executions according to policy
3. preserve active work unless explicit emergency abort policy says otherwise
4. mark run PARTIAL
5. record threshold reason
6. notify dashboard
```

Do not silently delete work.

---

# 43. Phase 39 — Run Finalization Semantics

Use clear meanings:

```text
COMPLETED:
    all relevant work terminal with successful outcome

PARTIAL:
    mixed success/failure/skipped/cancelled outcomes

FAILED:
    run-level fatal condition prevented normal execution

CANCELLED:
    operator/system intentionally cancelled the run
```

Document exact precedence.

---

# 44. Phase 40 — Dashboard Server Truth

`AutomationsHub.jsx` and `FleetMonitorHub.jsx` should consume authoritative server state.

Do not make React the source of truth for:

```text
success count
failure count
lease status
run lifecycle
execution status
```

---

# 45. Phase 41 — Server-Side Run History Filtering

The dashboard currently filters run history client-side.

Prefer server filtering:

```text
GET /api/automation/runs/?automation=<id>
```

or an equivalent endpoint.

This prevents unnecessary transfer and scales better.

---

# 46. Phase 42 — Fleet Monitor

Fleet view must show:

```text
device
status
battery
app version
GeckoView version
current execution
profile
DAG state
last heartbeat
lease
```

Actions:

```text
disable
configure/enable
abort execution
inspect telemetry
```

Dangerous actions require confirmation.

---

# 47. Phase 43 — API Contract Cleanup

Current Android API includes an obsolete polling method alongside the new claim method.

After migration:

```kotlin
// REMOVE when no callers remain
suspend fun pollJob(...)
```

Keep only the authoritative worker contract.

Also review duplicate backend route registrations such as:

```text
/automations/
/rules/
```

Pick one canonical API. Keep an alias only when a real compatibility requirement exists, and mark it deprecated if so.

---

# 48. Phase 44 — TaskExecutionQueue Cleanup

`TaskExecutionQueue` currently acts as a backward-compatibility adapter.

Do not delete it immediately.

First:

```bash
git grep -n "TaskExecutionQueue"
```

Classify every reference:

```text
ACTIVE
LEGACY-COMPATIBILITY
TEST-ONLY
UNUSED
```

Only remove it after active runtime references are gone and tests prove the replacement path is complete.

---

# 49. Phase 45 — Mandatory Unused-Code Removal Program

This is a **required phase**, not optional cleanup.

Antigravity must actively inspect and remove:

## Python

```text
unused functions
unused classes
unused imports
dead serializers
duplicate services
duplicate views
obsolete model properties
unused management commands
unused exceptions
legacy adapters with no consumers
```

## Kotlin

```text
unused functions
unused properties
unused constructor parameters
dead polling code
duplicate session managers
legacy runner entry points
unused API methods
unused callbacks
unused DTOs
```

## React

```text
unused API functions
dead components
unused props
unused state
unused imports
duplicate routes
obsolete endpoints
```

---

# 50. Phase 46 — Do Not Delete Code by Guessing

Every deletion follows:

```text
1. Search symbol usage.
2. Search imports.
3. Search API route usage.
4. Search tests.
5. Search documentation.
6. Check migrations/admin registration.
7. Confirm no runtime dependency.
8. Delete.
9. Run focused tests.
10. Run full tests/builds.
11. Search again.
```

Do not use "probably unused" as proof.

---

# 51. Phase 47 — Mandatory Code Cleanup Audit File

Create:

```text
docs/CODE_CLEANUP_AUDIT.md
```

Each removed symbol/file must be documented:

```text
Symbol/File
Classification
Evidence searched
Dependent components
Reason for removal
Replacement
Verification
Phase/commit
```

Example:

```text
pollJob
LEGACY
No runtime references after worker migration
Replaced by claimNext
Removed GhostPilotApiService poll method
Android unit tests + build passed
Phase 1
```

---

# 52. Phase 48 — No Commented-Out Legacy Code

Do not preserve old code as:

```python
# old implementation
# TODO remove later
```

Once replacement is verified, delete the old implementation. Git history preserves it.

---

# 53. Phase 49 — No Duplicate Services

Search concepts, not just class names:

```text
Scheduler
AutomationScheduler
TaskScheduler

ExecutionManager
ExecutionService
GhostPilotExecution

ProfileManager
GeckoProfileManager
WorkerProfileManager
```

There should be one clear owner per responsibility.

---

# 54. Phase 50 — Backend Responsibility Boundaries

Final recommended ownership:

```text
automation.scheduler
    scheduling
    eligibility
    allocation policy

executions.services
    execution state transitions
    lease lifecycle

devices.services
    device registry
    device state

automation.compiler
    task -> DAG

automation.validator
    DAG validation

automation.assistant_tools
    AI tool adapters
automation.assistant_engine
    conversational AI loop
```

Views orchestrate API requests; they should not become giant business-logic containers.

---

# 55. Phase 51 — Incremental View Split

If `automation/views.py` remains oversized, split by domain:

```text
backend/automation/views/
    __init__.py
    tasks.py
    niches.py
    automations.py
    runs.py
    ghostpilot.py
    assistant.py
```

Only perform the split after existing behavior is covered by tests.

Preserve API route behavior.

---

# 56. Phase 52 — Android Responsibility Boundaries

Final recommendation:

```text
AutomationWorkerService
    worker lifecycle + acquisition

WorkerProfileSessionManager
    profile/session preparation

GhostPilotRunner
    DAG execution

CommandRegistry
    supported command registry + validation

VerificationEngine
    result verification

RecoveryEngine
    deterministic recovery

Api services
    network transport

AppDatabase/DAO
    local execution state
```

Do not make `MainActivity` the autonomous scheduler.

---

# 57. Phase 53 — AutomationCoordinator Review

The existing `AutomationCoordinator` can remain for interactive runner lifecycle if it still has real callers.

After worker migration, search every public method.

If a method has zero callers:

```text
remove method
remove tests that only exist for it
update docs
```

Do not retain dead APIs merely because they were once useful.

---

# 58. Phase 54 — AppDatabase Cleanup

The existing database owns checkpoint/action/recovery tables.

Keep one authoritative local representation.

Search:

```bash
git grep -n "execution_checkpoints"
git grep -n "action_executions"
git grep -n "recovery_attempts"
```

If duplicate tables or wrappers exist, consolidate them after usage analysis.

---

# 59. Phase 55 — Android Database Migration Discipline

Every schema change must:

```text
increment schema version
add explicit migration
test old -> new
preserve existing execution data
```

Never solve schema issues by silently deleting local automation state.

---

# 60. Phase 56 — Offline Network Behavior

When the network disappears:

```text
current physical action may finish safely
local result must be persisted
transition reporting can retry
```

Do not start new server-controlled jobs without valid server connectivity/lease state.

When lease validity is no longer trustworthy:

```text
stop safely
persist checkpoint
await server reconciliation
```

---

# 61. Phase 57 — Server Restart Behavior

Test:

```text
Android executing
      |
      v
Django restarts
      |
      v
Android reconnects
      |
      v
server reconciles execution
      |
      v
execution resumes or fails explicitly
```

Document exactly what actions may continue during the server outage.

---

# 62. Phase 58 — Process Crash Recovery

Test:

```text
execution step 10
kill Android process
restart worker
```

Expected:

```text
device registers
checkpoint found
server execution found
lease reconciled
resume plan returned
physical state verified
execution continues safely
```

---

# 63. Phase 59 — Duplicate Transition Test

Simulate:

```text
action succeeds
transition request times out
same transition submitted again
```

Expected:

```text
state advances once
physical action is not repeated
result remains deterministic
```

---

# 64. Phase 60 — Two-Device Lease Conflict Test

Setup:

```text
Device A
Device B
Profile X
```

Both request the same work simultaneously.

Expected:

```text
one lease acquired
one request rejected
never two active leases
```

The test must be concurrent, not sequential.

---

# 65. Phase 61 — Multi-Device Multi-Profile Test

Setup:

```text
Device A -> Profile X
Device B -> Profile Y
```

Expected:

```text
both execute independently
no profile session state crosses devices
```

---

# 66. Phase 62 — Profile Isolation Test

Verify between two profiles:

```text
cookies
history
tabs
Gecko storage
proxy configuration
session state
```

No cross-profile contamination is acceptable.

---

# 67. Phase 63 — Scheduler Concurrency Test

Conceptual test:

```python
def worker():
    SchedulerService.create_run_if_due(
        automation=automation,
        run_key="same-key",
    )
```

Run two workers concurrently.

Assert:

```text
one AutomationRun
one execution set
no duplicates
```

---

# 68. Phase 64 — Compiler Failure Test

Mock the compiler to raise.

Assert:

```text
run = FAILED
no fake DAG
no fake execution
error is visible
```

---

# 69. Phase 65 — Cancellation Test

Create a run with pending jobs.

Cancel it.

Assert:

```text
run.status == CANCELLED
pending execution.status == CANCELLED
failure_count excludes cancellation
cancelled_count is correct
```

For active work, assert `cancel_requested` or equivalent authoritative cancellation state.

---

# 70. Phase 66 — Worker Claim-to-Runner Test

This is mandatory.

Mock:

```text
claimNext
profile loading
Gecko session creation
GhostPilotRunner
```

Expected call sequence:

```text
claim
-> load profile
-> create session
-> construct runner
-> execute runner
-> cleanup
```

The test must fail if `activeJobId` is cleared before runner execution.

---

# 71. Phase 67 — Worker Failure Cleanup Test

Simulate runner failure.

Assert:

```text
active job cleared
WakeLock released
session resources cleaned
server receives failure/recovery signal
worker can claim subsequent work
```

---

# 72. Phase 68 — Resume Test

Persist:

```text
execution_id
state
step
plan version
checkpoint version
transition id
```

Restart worker.

Assert:

```text
server state reconciled
current browser state verified
already completed action not repeated
runner resumes
```

---

# 73. Phase 69 — Unsupported Command Test

Create a task containing an unsupported compiler command.

Expected:

```text
DAG validation fails
execution does not start
explicit failure shown
no fake success
```

---

# 74. Phase 70 — Device Health Test

Set a device heartbeat sufficiently old for the watchdog threshold.

Run:

```bash
python manage.py run_automation_watchdog --once
```

Assert:

```text
device -> OFFLINE
```

Do not mark a device offline because of one transient failed request.

---

# 75. Phase 71 — Watchdog Recovery Test

Create:

```text
RUNNING execution
expired lease
retry_count below max
```

Run watchdog.

Expected:

```text
lease -> EXPIRED
execution -> STALLED
recovery_status -> PENDING
retry_count incremented
```

After retries are exhausted:

```text
execution -> FAILED
recovery_status -> EXHAUSTED
```

---

# 76. Phase 72 — Real Device Unattended Test

Minimum setup:

```text
1 Django server
1 Android phone
2 profiles
1 task
1 automation
```

Run:

```text
backend
scheduler
watchdog
dashboard
Android worker
```

Create scheduled automation.

Observe the complete path:

```text
schedule
-> run
-> execution queue
-> device claim
-> profile preparation
-> Gecko session
-> GhostPilotRunner
-> command
-> verification
-> checkpoint
-> transition
-> next state
-> terminal
-> lease release
```

No manual profile opening should be required for autonomous mode.

---

# 77. Phase 73 — Screen Lock Test

During active execution:

```text
turn screen off
```

Verify:

```text
service behavior
heartbeat
Gecko execution
checkpointing
final outcome
```

Do not infer success from the notification alone.

---

# 78. Phase 74 — Background / Process Death Test

Test:

```text
app background
process termination
service restart
```

Expected safe recovery, or an explicit safe failure if recovery is impossible.

---

# 79. Phase 75 — Battery Safety Test

At low battery:

```text
< 15%
not charging
```

Worker should stop claiming new work.

Define and test policy for already-running work.

---

# 80. Phase 76 — Dashboard Acceptance Test

From React:

```text
create automation
edit automation
enable
disable
run now
pause
resume
cancel
view run
view execution
view fleet
abort active job
```

Every action must produce a real backend state change.

---

# 81. Phase 77 — AI Operational Acceptance

Assistant should be able to inspect and operate the control plane through validated tools:

```text
list automations
inspect automation
run automation now
inspect run
inspect fleet
inspect device
inspect execution
abort execution
```

The AI cannot bypass:

```text
authorization
lease rules
scheduler policy
execution validation
```

---

# 82. Phase 78 — AI Tool Cleanup

Search:

```bash
git grep -n "tool_"
git grep -n "OPENROUTER_TOOLS"
```

Classify every tool:

```text
ACTIVE
LEGACY
DUPLICATE
UNUSED
```

Remove unused tools after reference analysis.

---

# 83. Phase 79 — Prompt Cleanup

Update prompts that still reference:

```text
old polling architecture
old endpoint names
obsolete states
manual-only workflows
```

Do not leave AI instructions describing functionality that no longer exists.

---

# 84. Phase 80 — Security Cleanup

Search for accidental sensitive logging/storage:

```text
API keys
tokens
passwords
raw cookie values
proxy credentials
```

Never put secrets in:

```text
ExecutionEvent payloads
AI prompts
dashboard telemetry
test snapshots
Git
```

---

# 85. Phase 81 — Error Handling Cleanup

Eliminate patterns such as:

```python
except Exception:
    pass
```

and:

```python
except Exception:
    return success
```

Kotlin equivalent:

```kotlin
catch (e: Exception) {
    // ignore
}
```

is allowed only when the exception is explicitly harmless and documented.

---

# 86. Phase 82 — Logging Standard

Important backend events should include:

```text
execution_id
automation_run_id
profile_id
device_id
state_id
plan_version
event type
```

Example:

```python
logger.info(
    "Execution dispatched",
    extra={
        "execution_id": str(execution.id),
        "automation_run_id": str(run.id),
        "profile_id": str(execution.profile_id),
        "device_id": device_id,
    },
)
```

Do not log sensitive payloads.

---

# 87. Phase 83 — Database Query Review

Review high-frequency paths:

```text
scheduler
dispatcher
watchdog
heartbeats
fleet dashboard
run history
execution telemetry
```

Use `select_related` / `prefetch_related` where needed.

Do not add indexes without a query/use-case reason.

---

# 88. Phase 84 — Pagination

Avoid returning unbounded collections for:

```text
profiles
executions
events
runs
devices
```

Use server-side pagination where required.

---

# 89. Phase 85 — SSE / Polling Review

Use existing telemetry mechanisms where they work.

Preferred:

```text
REST = CRUD/control
SSE = live execution telemetry
```

Do not introduce WebSockets solely because they sound more advanced.

---

# 90. Phase 86 — Management Commands

Final useful commands should include:

```bash
python manage.py run_automation_scheduler
python manage.py run_automation_watchdog
python manage.py automation_status
python manage.py automation_reconcile
```

Test each.

Delete unused commands after the code cleanup audit.

---

# 91. Phase 87 — Workflow CLI

Keep only meaningful wrappers such as:

```text
scheduler
watchdog
automation-status
automation-reconcile
test-automation
```

Every wrapper must map to a working command.

---

# 92. Phase 88 — Documentation Cleanup

Update:

```text
README.md
docs/PROJECT_SPECIFICATION.md
docs/AUDIT.md
docs/API.md
docs/DEVELOPMENT.md
docs/FILE_MAP.md
docs/CODE_CLEANUP_AUDIT.md
```

Remove statements describing the old mobile-poller architecture after migration.

---

# 93. Phase 89 — FILE_MAP Accuracy

`docs/FILE_MAP.md` must reflect:

```text
new files
removed files
renamed files
```

No stale file listings.

---

# 94. Phase 90 — CI Expansion

Existing CI already checks:

```text
backend
React dashboard
Android unit tests/build
```

Add/ensure coverage for:

```text
scheduler idempotency
lease concurrency
compiler failure
cancellation
watchdog recovery
worker claim-to-runner integration
checkpoint/recovery logic
```

---

# 95. Phase 91 — Compiler Command Contract Test

Conceptual Python test:

```python
def test_every_compiler_command_is_supported():
    commands = collect_all_compiler_commands()

    missing = [
        command
        for command in commands
        if not CommandRegistry.supports(command)
    ]

    assert missing == []
```

Adapt the exact implementation to the project's existing test framework.

---

# 96. Phase 92 — Final Repository Search

After all phases:

```bash
git status --short
git diff --check
```

Then specifically:

```bash
git grep -n "pollJob"
git grep -n "work_available"
git grep -n "automation.task.user"
git grep -n "fallback DAG"
git grep -n "except Exception"
```

Every result must be reviewed.

---

# 97. Phase 93 — Final Build Verification

Backend:

```bash
python manage.py check
python manage.py makemigrations --check --dry-run
python manage.py test --noinput
```

Dashboard:

```bash
npm test
npm run build
```

Android:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Combined:

```bash
python scripts/workflow.py check-all
```

---

# 98. Phase 94 — Final Real-Device Verification

Do not mark Automation V2 production-ready without real-device evidence for:

```text
manual run-now
scheduled run
profile loading
Gecko session creation
DAG execution
verification
checkpoint
transition
lease
cancellation
screen lock
network interruption
process restart
resume
profile isolation
two-device lease conflict
```

---

# 99. Exact Acceptance Criteria for the Main Blocker

This exact chain must work on a real device:

```text
Django creates Execution
        |
        v
Android calls claim-next
        |
        v
Django gives Execution + lease + plan
        |
        v
AutomationWorkerService receives claim
        |
        v
Worker loads profile
        |
        v
Worker creates GeckoSession
        |
        v
Worker creates/attaches GhostPilotRunner
        |
        v
GhostPilotRunner executes first state
        |
        v
Verification succeeds
        |
        v
Checkpoint saved
        |
        v
Transition reported
        |
        v
Django validates transition
        |
        v
Next state returned
        |
        v
Execution continues
        |
        v
Terminal state reached
        |
        v
Execution terminal
        |
        v
Lease released
        |
        v
Next eligible execution can be claimed
```

If any link is missing, the system is not yet end-to-end complete.

---

# 100. Antigravity Mandatory Rules

## Rule 1 — Inspect before editing

Before modifying a file:

```text
inspect implementation
trace callers
trace dependencies
inspect tests
```

---

## Rule 2 — Never duplicate an existing capability

Before creating a service/model/helper:

```bash
git grep -n "<symbol or responsibility>"
```

Reuse existing code when it already owns the responsibility.

---

## Rule 3 — One authoritative owner per responsibility

```text
Scheduler -> scheduling
Dispatcher -> allocation
LeaseService -> leases
ExecutionService -> transitions
WorkerService -> worker lifecycle/acquisition
GhostPilotRunner -> execution
VerificationEngine -> verification
```

---

## Rule 4 — Remove obsolete code

Do not leave old implementations around simply because "they might be useful later".

If unused and proven safe to remove:

```text
delete it
update references
test
```

---

## Rule 5 — Never delete by assumption

Deletion requires repository-wide reference analysis.

---

## Rule 6 — No fake production behavior

Never implement:

```text
fake SUCCESS
fake fallback DAG
fake device assignment
fake recovery
placeholder automation result
```

Mocks belong only in tests.

---

## Rule 7 — Do not weaken tests

A failing test is a signal to find the root cause.

Do not delete or dilute tests to make CI green.

---

## Rule 8 — Cross-component changes must be contract-driven

When changing backend/API/Android together, define and test the JSON contract first.

---

## Rule 9 — Update documentation after architecture changes

No stale architecture documentation.

---

# 101. Recommended Implementation Commit Sequence

Use small commits rather than one enormous commit.

```text
fix(automation): remove duplicate Android polling path
fix(automation): connect worker claims to GhostPilotRunner
fix(android): add unattended profile session manager
fix(automation): reject compiler fallback success
fix(automation): correct eligibility ownership
fix(executions): correct cancellation semantics
fix(automation): correct run timestamps and schedule occurrence
fix(automation): formalize execution plan semantics
fix(android): harden checkpoint reconciliation
fix(android): prevent duplicate physical action replay
test(automation): add scheduler idempotency coverage
test(executions): add concurrent lease coverage
test(android): add worker claim-to-runner coverage
test(android): add resume/recovery coverage
refactor(cleanup): remove obsolete polling code
refactor(cleanup): remove unused automation APIs
refactor(cleanup): remove dead Android helpers
docs(automation): update architecture and cleanup audit
```

Each commit should leave the repository buildable.

---

# 102. Final Definition of Done

## Backend

```text
[ ] Automation scheduling is deterministic
[ ] Run creation is idempotent
[ ] Timezones are correct
[ ] Eligibility ownership is correct
[ ] No fake compiler fallback exists
[ ] Profile-specific compilation failures are explicit
[ ] ExecutionPlan semantics are reproducible
[ ] Cancellation semantics are correct
[ ] Lease exclusivity is proven
[ ] Dispatcher is atomic
[ ] Watchdog recovery is proven
```

## Android

```text
[ ] One server job acquisition path exists
[ ] pollJob legacy path removed
[ ] Worker can load a selected profile autonomously
[ ] Worker can create a Gecko session autonomously
[ ] Worker constructs GhostPilotRunner
[ ] Worker executes the claimed DAG
[ ] Runner checkpoints
[ ] Runner verifies actions
[ ] Runner reports transitions
[ ] Worker handles cancellation
[ ] Worker resumes after interruption
[ ] Worker releases resources
```

## Dashboard

```text
[ ] Automation CRUD works
[ ] Run Now works
[ ] Pause/resume works
[ ] Cancel works
[ ] Run history works
[ ] Execution detail works
[ ] Fleet monitor works
[ ] Live state is server-authoritative
```

## Cleanup

```text
[ ] Unused Python code removed
[ ] Unused Kotlin code removed
[ ] Unused React code removed
[ ] Legacy polling code removed
[ ] Duplicate APIs removed or deprecated
[ ] Duplicate services removed
[ ] Dead imports removed
[ ] Dead tests removed only with proven replacement coverage
[ ] CODE_CLEANUP_AUDIT.md exists
[ ] FILE_MAP is accurate
```

## Verification

```text
[ ] Backend tests pass
[ ] Dashboard tests/build pass
[ ] Android tests/build pass
[ ] Scheduler concurrency test passes
[ ] Lease concurrency test passes
[ ] Duplicate transition test passes
[ ] Compiler failure test passes
[ ] Cancellation test passes
[ ] Worker claim-to-runner test passes
[ ] Resume test passes
[ ] Profile isolation test passes
[ ] Real-device unattended test passes
[ ] Screen-lock test passes
[ ] Process-restart test passes
[ ] Two-device lease conflict test passes
```

---

# 103. Final Runtime Architecture

The desired production flow is:

```text
                  ┌──────────────────┐
                  │  Automation Rule  │
                  └────────┬─────────┘
                           |
                           v
                  ┌──────────────────┐
                  │     Scheduler    │
                  └────────┬─────────┘
                           |
                           v
                  ┌──────────────────┐
                  │  AutomationRun   │
                  └────────┬─────────┘
                           |
                           v
                  ┌──────────────────┐
                  │    Execution     │
                  │     PENDING      │
                  └────────┬─────────┘
                           |
                           v
                  ┌──────────────────┐
                  │  Device Claim    │
                  └────────┬─────────┘
                           |
                     Lease acquired
                           |
                           v
             ┌────────────────────────────┐
             │ AutomationWorkerService    │
             └─────────────┬──────────────┘
                           |
                           v
             ┌────────────────────────────┐
             │ WorkerProfileSessionManager│
             └─────────────┬──────────────┘
                           |
                           v
                    Gecko Profile
                           |
                           v
                    GeckoSession
                           |
                           v
                   GhostPilotRunner
                           |
                           v
                       DAG State
                           |
                           v
                    Physical Action
                           |
                           v
                     Verification
                           |
                 +---------+---------+
                 |                   |
              SUCCESS             FAILURE
                 |                   |
                 v                   v
            Checkpoint          Recovery Policy
                 |                   |
                 v                   v
              Transition         AI/local recovery
                 |                   |
                 +---------+---------+
                           |
                           v
                    Server validation
                           |
                           v
                      Next state
                           |
                           v
                    Terminal state
                           |
                           v
                     Release lease
                           |
                           v
                     Run metrics
```

---

# 104. Final Engineering Principle

The finished system must satisfy:

```text
Django decides.
Database remembers.
Lease prevents conflicts.
Android executes.
Checkpoint preserves progress.
Verification confirms reality.
Watchdog repairs stale state.
AI assists only inside validated boundaries.
Unused code is removed.
Tests prove contracts.
Real devices prove runtime behavior.
```

The system is not considered fully autonomous merely because a scheduler exists.

Automation V2 is complete only when this entire chain is demonstrated:

```text
SCHEDULE
   ->
ALLOCATE
   ->
CLAIM
   ->
LOAD PROFILE
   ->
CREATE SESSION
   ->
EXECUTE
   ->
VERIFY
   ->
CHECKPOINT
   ->
REPORT
   ->
RECOVER
   ->
COMPLETE
```

This document is the implementation and remediation contract for Antigravity. Do not mark a phase complete until its acceptance tests and required cleanup are complete.
