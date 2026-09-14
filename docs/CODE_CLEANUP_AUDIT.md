# TersoPilot Code Cleanup & Remediation Audit

This document maintains an immutable audit record of all removed obsolete functions, dead legacy code paths, and deprecated endpoints across both Android and Django during the Automation V2 remediation.

---

### Audit Record 01: `GhostPilotApiService.pollJob`
- **Symbol / File**: `GhostPilotApiService.pollJob` (`app/src/main/java/com/multibrowser/antidetect/network/GhostPilotApiService.kt`)
- **Classification**: LEGACY (Superseded Polling Endpoint)
- **Evidence Searched**: `git grep "pollJob"`, `git grep "poll/"`
- **Dependent Components**: Formerly called by `GhostPilotRunner.executionLoop()`
- **Reason for Removal**: Eliminated competing job acquisition architecture. The worker service (`claimNext`) is now the sole authoritative entry point.
- **Replacement**: `GhostPilotApiService.claimNext` via `POST /api/automation/ghostpilot/claim-next/`
- **Verification**: Android debug compilation (`compileDebugKotlin`) succeeded with zero errors.
- **Remediation Phase**: Phase 1 & Phase 47

---

### Audit Record 02: `GhostPilotRunner.executionLoop` Legacy Polling Branch
- **Symbol / File**: `GhostPilotRunner.executionLoop()` (`app/src/main/java/com/multibrowser/antidetect/automation/GhostPilotRunner.kt`)
- **Classification**: LEGACY / DUPLICATE
- **Evidence Searched**: `api.pollJob` invocations in `GhostPilotRunner.kt`
- **Dependent Components**: Previously internal loop polling the server independently of foreground service.
- **Reason for Removal**: Violated single job-acquisition authority. Replaced by direct invocation of `suspend fun execute(ClaimedExecution): ExecutionResult`.
- **Replacement**: `GhostPilotRunner.execute(ClaimedExecution): ExecutionResult`
- **Verification**: Kotlin compilation verified; foreground service calls runner directly with active `GeckoSession` context.
- **Remediation Phase**: Phase 1, 3 & 47

---

### Audit Record 03: Synthetic Fallback Compiler DAG (`WAIT -> TERMINATE`)
- **Symbol / File**: `SchedulerService._compile_run_dag` synthetic error handler (`backend/automation/scheduler/service.py`)
- **Classification**: DANGEROUS DEFECT
- **Evidence Searched**: `backend/automation/scheduler/service.py`
- **Dependent Components**: Execution compiler during scheduled run generation.
- **Reason for Removal**: Recipe compilation failures silently generated fake `WAIT -> TERMINATE` graphs that appeared successful while performing no actual automation work.
- **Replacement**: Explicit `PlanCompilationError` and `ProfilePlanCompilationError` with run marked `FAILED`, zero fake executions minted, and clear metrics payload.
- **Verification**: Concurrency & compiler tests assert explicit run failure without fake executions.
- **Remediation Phase**: Phase 8, 9, 10 & 47

---

### Audit Record 04: Non-Existent `automation.task.user` Reference
- **Symbol / File**: `EligibilityService._get_all_eligible_profiles` (`backend/automation/scheduler/planner.py`)
- **Classification**: BROKEN CODE / SCHEMA MISMATCH
- **Evidence Searched**: `backend/automation/scheduler/planner.py`
- **Dependent Components**: `ALL_ELIGIBLE` profile targeting mode.
- **Reason for Removal**: `AutomationTask` does not define a `user` field. Caused AttributeError during all-eligible targeting.
- **Replacement**: Added `owner = models.ForeignKey(settings.AUTH_USER_MODEL, ...)` to `Automation` with migration `0006_automation_owner.py` and linked profile queries to `automation.owner`.
- **Verification**: Database migration applied; automated planner tests pass.
- **Remediation Phase**: Phase 6 & 47

---

### Audit Record 05: `AutomationCoordinator.startAutomation` Unused Method
- **Symbol / File**: `AutomationCoordinator.startAutomation(profileId)` (`app/src/main/java/com/multibrowser/antidetect/automation/AutomationCoordinator.kt`)
- **Classification**: UNUSED DEAD CODE
- **Evidence Searched**: `git grep "startAutomation"` across the entire codebase. 0 callers found.
- **Dependent Components**: None.
- **Reason for Removal**: Automation execution is strictly driven by `AutomationWorkerService` and `WorkerProfileSessionManager`, rendering this orphaned helper dead code.
- **Replacement**: Direct invocation via `AutomationWorkerService` -> `WorkerProfileSessionManager` -> `GhostPilotRunner.execute()`.
- **Verification**: Kotlin compilation verified cleanly without warnings.
- **Remediation Phase**: Phase 53 & Phase 47

---

### Audit Record 06: Run Cancellation Falsely Marking Executions `FAILED`
- **Symbol / File**: `cancel_run` in `backend/automation/views.py`
- **Classification**: DEFECT / SEMANTIC INCONSISTENCY
- **Evidence Searched**: `cancel_run` action in `AutomationRunViewSet`.
- **Dependent Components**: Run cancellation endpoint.
- **Reason for Removal**: Operator run cancellations were marking pending executions as `FAILED`, inflating error rates and tripping false circuit breakers.
- **Replacement**: Pending executions transition to `ExecutionStatus.CANCELLED`, running executions receive `cancel_requested = True`, and `cancelled_count` is accurately incremented.
- **Verification**: Test suite verifies `ExecutionStatus.CANCELLED` on aborted runs and executions.
- **Remediation Phase**: Phase 21 & Phase 65
