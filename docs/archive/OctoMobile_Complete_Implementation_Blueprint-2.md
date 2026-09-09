# OctoMobile — Closed-Loop Gecko Android Automation Platform
## Complete Implementation Blueprint + Workflow System

> **Purpose:** Turn the current OctoMobile architecture into a reliable, closed-loop mobile browser automation platform using **Kotlin + GeckoView/Firefox + WebExtension perception + Django + React**.
>
> **Important boundary:** This design supports legitimate browser automation, QA, research, accessibility testing, site workflows, and automation of systems you are authorized to control. It does **not** define methods for disguising bots, bypassing CAPTCHAs, defeating anti-abuse systems, or artificially inflating YouTube views/watch time/engagement.

---

# 1. Target Architecture

```text
                         ┌───────────────────────────────┐
                         │          REACT WEB APP         │
                         │                               │
                         │ Fleet Control Center          │
                         │ Profiles                      │
                         │ Tasks                         │
                         │ Workflow Builder              │
                         │ Schedules                     │
                         │ Live Execution                │
                         │ Logs / Screenshots            │
                         └───────────────┬───────────────┘
                                         │
                                REST + WebSocket
                                         │
                                         ▼
                         ┌───────────────────────────────┐
                         │            DJANGO              │
                         │                               │
                         │ API / Authentication           │
                         │ Profiles                       │
                         │ Workflow Definitions           │
                         │ DAG Compiler                   │
                         │ Task Execution Queue           │
                         │ Scheduler                      │
                         │ Device Registry                │
                         │ Execution Events               │
                         │ Audit                          │
                         └───────────────┬───────────────┘
                                         │
                              Execution Package / WS
                                         │
                                         ▼
                         ┌───────────────────────────────┐
                         │        ANDROID / KOTLIN        │
                         │                               │
                         │ AutomationAgent               │
                         │ WorkflowExecutor              │
                         │ BrowserController             │
                         │ PerceptionBridge              │
                         │ TargetResolver                │
                         │ CoordinateMapper              │
                         │ InputController               │
                         │ VerificationEngine             │
                         │ RecoveryEngine                 │
                         │ DeviceMonitor                  │
                         └───────────────┬───────────────┘
                                         │
                                         ▼
                         ┌───────────────────────────────┐
                         │       GECKOVIEW / FIREFOX      │
                         │                               │
                         │ GeckoSession                   │
                         │ WebExtension                   │
                         │ Content Script                  │
                         │ Page / DOM                     │
                         └───────────────────────────────┘
```

## Core rule

```text
Django = orchestration + persistence + scheduling
Kotlin = execution + physical device control
WebExtension = perception + page-state reporting
Native input = actual interaction
React = configuration + monitoring
```

---

# 2. Design Principles

## 2.1 Closed-loop, never blind

Every important interaction must follow:

```text
PRECONDITION
    ↓
PERCEIVE
    ↓
RESOLVE TARGET
    ↓
MAP COORDINATES
    ↓
ACT
    ↓
OBSERVE
    ↓
VERIFY
    ↓
TRANSITION
```

Never assume that a click worked just because the tap was sent.

---

## 2.2 Workflows are DAG/state machines

Do not model a workflow only as:

```text
OPEN → SEARCH → CLICK → END
```

Instead:

```text
             SEARCH
             /  |  \
       SUCCESS  |  RETRY
          |     |     |
          ▼     ▼     ▼
       RESULTS ERROR RECOVER
```

Every node can have multiple outcomes.

---

## 2.3 Workflow definitions contain intent, not coordinates

Bad:

```json
{
  "action": "tap",
  "x": 331,
  "y": 244
}
```

Good:

```json
{
  "action": "click",
  "target": {
    "role": "search_button"
  }
}
```

The actual target position is resolved at runtime.

---

## 2.4 Perception and action are separate

The WebExtension may report:

```text
element exists
element visible
element enabled
element rectangle
page state
video state
overlay state
```

The WebExtension must not be treated as the physical input injector.

Kotlin performs the interaction through the configured `InputController`.

---

## 2.5 Use adapters everywhere

Keep platform-specific details behind interfaces:

```text
BrowserController
InputController
PerceptionProvider
TargetResolver
CoordinateMapper
ScreenshotProvider
DeviceStatusProvider
StorageProvider
```

This makes the project testable and lets you replace implementation details later.

---

# 3. Recommended Project Structure

## 3.1 Django

```text
backend/
├── config/
│   ├── settings/
│   ├── urls.py
│   ├── asgi.py
│   └── routing.py
│
├── apps/
│   ├── accounts/
│   ├── devices/
│   ├── profiles/
│   ├── workflows/
│   ├── automation/
│   ├── executions/
│   ├── scheduler/
│   ├── screenshots/
│   ├── audit/
│   └── websocket/
│
├── common/
│   ├── enums.py
│   ├── errors.py
│   ├── serializers.py
│   └── utils.py
│
├── requirements/
│   ├── base.txt
│   ├── development.txt
│   └── production.txt
│
└── manage.py
```

---

## 3.2 Android/Kotlin

```text
app/src/main/java/.../
├── automation/
│   ├── AutomationAgent.kt
│   ├── WorkflowExecutor.kt
│   ├── ActionExecutor.kt
│   ├── StateMachine.kt
│   ├── ExecutionContext.kt
│   ├── RecoveryEngine.kt
│   ├── VerificationEngine.kt
│   ├── InteractionPolicy.kt
│   └── RetryPolicy.kt
│
├── browser/
│   ├── GeckoBrowserController.kt
│   ├── GeckoSessionManager.kt
│   ├── GeckoProfileManager.kt
│   └── BrowserStateDetector.kt
│
├── perception/
│   ├── PerceptionBridge.kt
│   ├── WebExtensionBridge.kt
│   ├── DomSnapshot.kt
│   ├── ElementSnapshot.kt
│   ├── TargetResolver.kt
│   └── PageStateDetector.kt
│
├── input/
│   ├── InputController.kt
│   ├── NativeGestureInjector.kt
│   ├── AccessibilityInputController.kt
│   └── CoordinateMapper.kt
│
├── network/
│   ├── ApiClient.kt
│   ├── WebSocketClient.kt
│   ├── HeartbeatManager.kt
│   └── EventReporter.kt
│
├── device/
│   ├── DeviceMonitor.kt
│   ├── DeviceState.kt
│   └── ScreenMetrics.kt
│
├── diagnostics/
│   ├── ScreenshotManager.kt
│   ├── StructuredLogger.kt
│   └── CrashReporter.kt
│
└── ui/
    └── ...
```

---

## 3.3 WebExtension

```text
extension/
├── manifest.json
├── background/
│   └── background.js
├── content/
│   ├── perception.js
│   ├── domSnapshot.js
│   └── pageState.js
├── schemas/
│   └── perception.schema.json
└── shared/
    └── protocol.js
```

---

## 3.4 React

```text
web/
├── src/
│   ├── api/
│   ├── components/
│   ├── pages/
│   │   ├── dashboard/
│   │   ├── devices/
│   │   ├── profiles/
│   │   ├── tasks/
│   │   ├── workflows/
│   │   ├── executions/
│   │   └── settings/
│   ├── workflow/
│   │   ├── canvas/
│   │   ├── nodes/
│   │   ├── edges/
│   │   ├── inspector/
│   │   └── validation/
│   ├── hooks/
│   ├── stores/
│   ├── types/
│   └── utils/
```

---

# 4. Django Data Model

## 4.1 Device

```python
class Device(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=120)
    device_key = models.CharField(max_length=255, unique=True)
    android_version = models.CharField(max_length=50, blank=True)
    model = models.CharField(max_length=120, blank=True)

    status = models.CharField(
        max_length=30,
        choices=[
            ("ONLINE", "Online"),
            ("OFFLINE", "Offline"),
            ("BUSY", "Busy"),
            ("PAUSED", "Paused"),
            ("ERROR", "Error"),
        ],
        default="OFFLINE",
    )

    battery_percent = models.PositiveIntegerField(default=0)
    is_charging = models.BooleanField(default=False)
    last_seen_at = models.DateTimeField(null=True, blank=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
```

---

# 5. Browser Profiles

```python
class BrowserProfile(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=120)

    category = models.CharField(max_length=50, default="Default")

    browser_engine = models.CharField(
        max_length=30,
        default="gecko"
    )

    locale = models.CharField(max_length=30, default="en-US")
    language = models.CharField(max_length=30, default="en")
    timezone = models.CharField(max_length=80, default="UTC")

    config = models.JSONField(default=dict)

    active = models.BooleanField(default=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
```

Keep profile configuration separate from workflow configuration.

---

# 6. Workflow Model

```python
class Workflow(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=160)
    slug = models.SlugField(unique=True)

    version = models.PositiveIntegerField(default=1)

    graph = models.JSONField(default=dict)

    published = models.BooleanField(default=False)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
```

Never mutate an already-running version.

Use immutable published workflow versions for reproducibility.

---

# 7. Execution Model

```python
class TaskExecution(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)

    task_id = models.UUIDField()
    workflow_id = models.UUIDField()
    workflow_version = models.PositiveIntegerField()

    device = models.ForeignKey(Device, on_delete=models.PROTECT)
    profile = models.ForeignKey(BrowserProfile, on_delete=models.PROTECT)

    status = models.CharField(max_length=30, default="QUEUED")

    current_node_id = models.CharField(max_length=120, blank=True)

    input_variables = models.JSONField(default=dict)
    runtime_state = models.JSONField(default=dict)

    started_at = models.DateTimeField(null=True, blank=True)
    finished_at = models.DateTimeField(null=True, blank=True)

    error_code = models.CharField(max_length=100, blank=True)
    error_message = models.TextField(blank=True)

    created_at = models.DateTimeField(auto_now_add=True)
```

---

# 8. Execution Events

```python
class ExecutionEvent(models.Model):
    execution = models.ForeignKey(
        TaskExecution,
        on_delete=models.CASCADE,
        related_name="events"
    )

    type = models.CharField(max_length=60)

    node_id = models.CharField(max_length=120, blank=True)

    payload = models.JSONField(default=dict)

    timestamp = models.DateTimeField(auto_now_add=True)
```

Suggested event types:

```text
EXECUTION_QUEUED
EXECUTION_STARTED
NODE_STARTED
NODE_COMPLETED
NODE_FAILED
RECOVERY_STARTED
RECOVERY_COMPLETED
STATE_CHANGED
SCREENSHOT_CAPTURED
BROWSER_OPENED
BROWSER_CLOSED
EXECUTION_PAUSED
EXECUTION_RESUMED
EXECUTION_COMPLETED
EXECUTION_ABORTED
```

---

# 9. Workflow JSON Schema

A workflow should look approximately like:

```json
{
  "schema_version": 1,
  "workflow": {
    "id": "research-basic",
    "name": "Research Page",
    "version": 1
  },

  "variables": {
    "url": "",
    "query": ""
  },

  "start_node": "open_page",

  "nodes": [
    {
      "id": "open_page",
      "type": "OPEN_URL",
      "config": {
        "url": "{{url}}"
      },
      "transitions": {
        "SUCCESS": "wait_page",
        "TIMEOUT": "recover_page",
        "NETWORK_ERROR": "recover_network"
      }
    },

    {
      "id": "wait_page",
      "type": "WAIT_FOR_STATE",
      "config": {
        "state": "PAGE_READY",
        "timeout_ms": 15000
      },
      "transitions": {
        "SUCCESS": "inspect_page",
        "TIMEOUT": "recover_page"
      }
    }
  ]
}
```

---

# 10. Standard Outcome Model

Use a consistent base outcome set:

```text
SUCCESS
FAILED
TIMEOUT
RETRY
SKIP
FALLBACK
CANCELLED
```

Add semantic states when useful:

```text
CONSENT_REQUIRED
LOGIN_REQUIRED
NETWORK_ERROR
ELEMENT_NOT_FOUND
ELEMENT_NOT_INTERACTABLE
PAGE_CHANGED
CONTENT_UNAVAILABLE
OVERLAY_PRESENT
UNKNOWN_PAGE
```

Do not force every workflow to handle every possible outcome.

---

# 11. Kotlin Core Interfaces

## 11.1 InputController

```kotlin
data class ScreenPoint(
    val x: Float,
    val y: Float
)

interface InputController {

    suspend fun tap(point: ScreenPoint)

    suspend fun longPress(
        point: ScreenPoint,
        durationMs: Long
    )

    suspend fun swipe(
        start: ScreenPoint,
        end: ScreenPoint,
        durationMs: Long
    )

    suspend fun type(text: String)

    suspend fun clearText()

    suspend fun back()

    suspend fun home()
}
```

---

## 11.2 BrowserController

```kotlin
interface BrowserController {

    suspend fun launchProfile(profileId: String)

    suspend fun close()

    suspend fun openUrl(url: String)

    suspend fun reload()

    suspend fun currentUrl(): String?

    suspend fun waitForPage(timeoutMs: Long): Boolean
}
```

---

## 11.3 PerceptionProvider

```kotlin
interface PerceptionProvider {

    suspend fun snapshot(): DomSnapshot

    suspend fun detectPageState(): PageState

    suspend fun waitForCondition(
        condition: PerceptionCondition,
        timeoutMs: Long
    ): Boolean
}
```

---

# 12. DOM Snapshot

```kotlin
data class DomRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

data class ElementSnapshot(
    val id: String,
    val role: String?,
    val tag: String?,
    val text: String?,
    val ariaLabel: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val rect: DomRect
)

data class ViewportInfo(
    val width: Float,
    val height: Float,
    val dpr: Float,
    val scrollX: Float,
    val scrollY: Float
)

data class DomSnapshot(
    val url: String,
    val title: String?,
    val viewport: ViewportInfo,
    val elements: List<ElementSnapshot>,
    val pageState: String,
    val overlays: List<String>
)
```

---

# 13. Target Resolver

```kotlin
data class TargetSpec(
    val id: String? = null,
    val role: String? = null,
    val text: String? = null,
    val ariaLabel: String? = null,
    val selector: String? = null
)

data class ResolvedTarget(
    val element: ElementSnapshot
)

interface TargetResolver {
    suspend fun resolve(
        target: TargetSpec,
        snapshot: DomSnapshot
    ): ResolvedTarget?
}
```

Resolution priority:

```text
1. stable automation id
2. accessibility/ARIA identifier
3. semantic role
4. explicit CSS selector
5. exact text
6. carefully scoped text match
7. fallback strategy
```

Do not use arbitrary coordinates as the primary selector.

---

# 14. CoordinateMapper

```kotlin
interface CoordinateMapper {

    fun map(
        rect: DomRect,
        viewport: ViewportInfo,
        screen: ScreenMetrics
    ): ScreenPoint
}
```

Inputs that must be considered:

```text
CSS pixel scale
device density
browser chrome offset
window offset
scroll position
orientation
viewport size
safe insets
```

Create calibration tests for every supported device family.

---

# 15. Gesture Engine

The gesture layer should be deterministic enough for testing while allowing configurable timing/gesture variation for realistic QA.

```kotlin
data class GesturePolicy(
    val tapSettleMs: LongRange = 60L..180L,
    val swipeDurationMs: LongRange = 450L..950L,
    val pauseAfterActionMs: LongRange = 250L..900L
)
```

Do not hard-code timing assumptions into every action.

---

# 16. Action Executor

```kotlin
sealed class ActionResult {

    data object Success : ActionResult()

    data class Failure(
        val code: String,
        val message: String? = null
    ) : ActionResult()

    data object Timeout : ActionResult()

    data class Branch(
        val outcome: String
    ) : ActionResult()
}
```

```kotlin
interface ActionExecutor {
    suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext
    ): ActionResult
}
```

---

# 17. Execution Context

```kotlin
data class ExecutionContext(
    val executionId: String,
    val taskId: String,
    val workflowId: String,
    val profileId: String,
    val variables: MutableMap<String, Any?>,
    val runtimeState: MutableMap<String, Any?>,
    var currentNodeId: String
)
```

---

# 18. State Machine

```kotlin
class WorkflowExecutor(
    private val actionExecutor: ActionExecutor,
    private val verifier: VerificationEngine,
    private val recovery: RecoveryEngine,
    private val reporter: EventReporter
) {

    suspend fun execute(
        workflow: CompiledWorkflow,
        context: ExecutionContext
    ) {
        var nodeId = workflow.startNodeId

        while (nodeId != null) {
            context.currentNodeId = nodeId

            reporter.nodeStarted(context, nodeId)

            val node = workflow.node(nodeId)

            val result = try {
                actionExecutor.execute(node, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActionResult.Failure(
                    code = "UNEXPECTED_ERROR",
                    message = e.message
                )
            }

            val verified = verifier.verify(node, result, context)

            if (verified.isSuccess) {
                reporter.nodeCompleted(context, nodeId)
                nodeId = node.transitionFor(verified.outcome)
            } else {
                nodeId = recovery.recover(
                    node = node,
                    result = verified,
                    context = context
                )
            }
        }
    }
}
```

---

# 19. Verification Engine

Never make this optional for important actions.

```kotlin
interface VerificationEngine {

    suspend fun verify(
        node: WorkflowNode,
        result: ActionResult,
        context: ExecutionContext
    ): VerificationResult
}
```

Examples:

```text
CLICK SEARCH
→ verify search interface visible

OPEN URL
→ verify current URL/page identity

TYPE QUERY
→ verify expected value exists

SCROLL
→ verify scroll position or visible-content change

OPEN RESULT
→ verify URL/page state changed

PLAY MEDIA (authorized test environment)
→ verify media state changed
```

---

# 20. Recovery Engine

```kotlin
interface RecoveryEngine {

    suspend fun recover(
        node: WorkflowNode,
        result: VerificationResult,
        context: ExecutionContext
    ): String?
}
```

Recovery strategies:

```text
REFRESH_PERCEPTION
WAIT_LONGER
RE_RESOLVE_TARGET
SCROLL_TO_TARGET
RELOAD
GO_BACK
RETRY
FALLBACK
SKIP
ABORT
```

Example:

```text
ELEMENT_NOT_FOUND
  ↓
Refresh DOM
  ↓
Check page state
  ↓
Scroll target into view
  ↓
Resolve target again
  ↓
Retry
```

---

# 21. Browser State Detector

Create normalized states:

```kotlin
enum class PageState {
    UNKNOWN,
    LOADING,
    HOME,
    SEARCH,
    SEARCH_RESULTS,
    CONTENT,
    LOGIN,
    CONSENT,
    ERROR,
    OFFLINE
}
```

The detector should be based on multiple signals, not one selector.

---

# 22. WebExtension Perception Contract

The extension should expose read-only page information.

Example message:

```json
{
  "type": "DOM_SNAPSHOT_REQUEST"
}
```

Response:

```json
{
  "type": "DOM_SNAPSHOT",
  "payload": {
    "url": "https://example.com",
    "title": "Example",
    "viewport": {
      "width": 408,
      "height": 907,
      "dpr": 2.75,
      "scrollX": 0,
      "scrollY": 460
    },
    "elements": [
      {
        "id": "search_button",
        "role": "button",
        "ariaLabel": "Search",
        "visible": true,
        "enabled": true,
        "rect": {
          "left": 320,
          "top": 90,
          "width": 50,
          "height": 50
        }
      }
    ]
  }
}
```

The bridge should also support:

```text
GET_PAGE_STATE
GET_ELEMENT
GET_DOM_SNAPSHOT
CHECK_CONDITION
GET_SCROLL_POSITION
GET_MEDIA_STATE
```

Avoid giving the extension generic commands such as:

```text
CLICK_ELEMENT
DISPATCH_TOUCH
SIMULATE_TAP
```

---

# 23. Workflow Node Types

Start with these.

## Navigation

```text
OPEN_URL
RELOAD
BACK
FORWARD
WAIT_FOR_PAGE
```

## Perception

```text
WAIT_FOR_ELEMENT
WAIT_FOR_STATE
ASSERT_ELEMENT
ASSERT_TEXT
ASSERT_URL
SCREENSHOT
```

## Input

```text
CLICK
LONG_PRESS
TYPE
CLEAR
SWIPE
SCROLL
```

## Logic

```text
CONDITION
BRANCH
RETRY
WAIT
LOOP
SET_VARIABLE
```

## Recovery

```text
RECOVER
SKIP
ABORT
```

## Content/Media observation

```text
READ_TEXT
READ_ATTRIBUTE
GET_MEDIA_STATE
ASSERT_MEDIA_STATE
```

Keep the first release intentionally small and stable.

---

# 24. Workflow Examples

## 24.1 Website Research

```text
START
 ↓
OPEN_URL
 ↓
WAIT_FOR_PAGE
 ↓
WAIT_FOR_ELEMENT(search)
 ↓
CLICK(search)
 ↓
TYPE(query)
 ↓
CLICK(submit)
 ↓
WAIT_FOR_STATE(search_results)
 ↓
READ_TEXT
 ↓
SCREENSHOT
 ↓
END
```

---

## 24.2 Website Login Test

Only for an account/site you are authorized to test.

```text
START
 ↓
OPEN_URL
 ↓
WAIT_FOR_STATE(LOGIN)
 ↓
CLICK(username)
 ↓
TYPE(username)
 ↓
CLICK(password)
 ↓
TYPE(password)
 ↓
CLICK(sign_in)
 ↓
VERIFY dashboard visible
 ├── SUCCESS → END
 ├── MFA_REQUIRED → PAUSE_FOR_HUMAN
 └── INVALID_CREDENTIALS → ABORT
```

Never attempt to automate around MFA/CAPTCHA protections.

---

## 24.3 YouTube Research / QA Workflow

Suitable for research and testing, not engagement manipulation:

```text
START
 ↓
OPEN_YOUTUBE
 ↓
WAIT_FOR_PAGE
 ↓
SEARCH(query)
 ↓
WAIT_FOR_SEARCH_RESULTS
 ↓
READ_RESULT_METADATA
 ↓
SELECT_RESULT_BY_RULE
 ↓
OPEN_RESULT
 ↓
VERIFY_CONTENT_PAGE
 ↓
CAPTURE_ALLOWED_METADATA
 ↓
SCREENSHOT
 ↓
END
```

The workflow should not include a mechanism intended to generate artificial views or watch-time.

---

## 24.4 Generic Content Reading

```text
OPEN_URL
 ↓
WAIT_FOR_PAGE
 ↓
READ_TEXT
 ↓
SWIPE
 ↓
WAIT_FOR_CONTENT
 ↓
READ_TEXT
 ↓
SCREENSHOT
 ↓
END
```

---

## 24.5 Conditional Workflow

```text
OPEN_PAGE
 ↓
DETECT_STATE
 ├── CONSENT → HANDLE_CONSENT
 │              ↓
 │            CONTINUE
 │
 ├── LOGIN → PAUSE_FOR_HUMAN
 │
 ├── ERROR → RETRY
 │
 └── CONTENT → CONTINUE
```

---

# 25. Workflow Builder UX

The React app should provide a drag-and-drop DAG canvas.

```text
┌────────────────────────────────────────────┐
│ Workflow: YouTube Research                 │
├────────────────────────────────────────────┤
│                                            │
│                    [START]                 │
│                       │                    │
│                       ▼                    │
│                  [OPEN URL]                │
│                       │                    │
│                       ▼                    │
│                [WAIT PAGE]                 │
│                       │                    │
│                       ▼                    │
│                   [SEARCH]                 │
│                  /   |    \                │
│             SUCCESS ERROR  LOGIN            │
│                │      │      │             │
│                ▼      ▼      ▼             │
│            [RESULT] [RETRY] [PAUSE]        │
│                                            │
├────────────────────────────────────────────┤
│ + Add Node          Save Draft   Publish   │
└────────────────────────────────────────────┘
```

---

# 26. Node Inspector

Clicking a node opens an inspector:

```text
NODE
────────────────────
Type: CLICK

TARGET
Role:
[ button ]

ARIA label:
[ Search ]

Selector:
[                         ]

TIMEOUT
[ 10000 ]

VERIFICATION
Type:
[ PAGE_STATE ]

Expected:
[ SEARCH_OPEN ]

RECOVERY
☑ Refresh perception
☑ Re-resolve target
☑ Retry

Retries:
[ 3 ]
```

---

# 27. Workflow Validation

Before publishing:

```text
Validator
```

must check:

```text
START exists
END reachable
no unreachable nodes
no missing transitions
no circular loop without exit
all variables declared
selectors valid
timeout values valid
retry policy valid
verification rules valid
```

Example error:

```text
Workflow cannot publish.

Node: click_search

Problem:
SUCCESS transition points to missing node "search_result".
```

---

# 28. Draft / Published Versions

Use:

```text
DRAFT
 ↓
VALIDATE
 ↓
PUBLISH
 ↓
VERSION 4
```

Running tasks must pin to:

```text
workflow_id
workflow_version
```

Never silently switch an active execution to a newly edited workflow.

---

# 29. Task Model

A task should reference:

```text
Task
 ├── Workflow
 ├── Workflow version
 ├── Profiles
 ├── Devices
 ├── Variables
 ├── Schedule
 └── Policy
```

Example:

```json
{
  "name": "Research Python Tutorials",
  "workflow_id": "youtube-research",
  "profile_ids": [
    "default-profile"
  ],
  "device_ids": [
    "android-01"
  ],
  "variables": {
    "query": "Python Django tutorial"
  }
}
```

---

# 30. Task Scheduling

Support:

```text
MANUAL
ONCE
RECURRING
CRON
INTERVAL
```

Add safeguards:

```text
max concurrent tasks
device concurrency = 1
cooldown between jobs
maintenance windows
battery threshold
network requirement
```

Example:

```text
Do not start if:
battery < 20%
device offline
browser locked
another execution active
maintenance mode enabled
```

---

# 31. Device Assignment

Use a scheduler:

```text
Task
 ↓
Requirements
 ↓
Eligible devices
 ↓
Choose device
 ↓
Reserve device
 ↓
Dispatch execution
```

Possible constraints:

```text
profile compatibility
device state
network availability
battery
region/locale requirement
browser version
app version
current workload
```

---

# 32. Device Heartbeat

Android should send a heartbeat every few seconds.

```json
{
  "device_id": "android-01",
  "status": "BUSY",
  "battery": 82,
  "charging": true,
  "browser_open": true,
  "current_execution": "9821",
  "current_node": "search",
  "timestamp": "..."
}
```

Django marks the device offline when heartbeats expire.

---

# 33. WebSocket Channels

Suggested channels:

```text
device.{device_id}
execution.{execution_id}
user.{user_id}
fleet
```

Events:

```text
DEVICE_STATUS_CHANGED
EXECUTION_STARTED
NODE_STARTED
NODE_COMPLETED
NODE_FAILED
SCREENSHOT_AVAILABLE
EXECUTION_COMPLETED
```

React subscribes only to relevant channels.

---

# 34. Execution API

Suggested endpoints:

```text
GET    /api/devices/
POST   /api/devices/register
POST   /api/devices/{id}/heartbeat

GET    /api/profiles/
POST   /api/profiles/
PATCH  /api/profiles/{id}

GET    /api/workflows/
POST   /api/workflows/
PATCH  /api/workflows/{id}
POST   /api/workflows/{id}/validate
POST   /api/workflows/{id}/publish

GET    /api/tasks/
POST   /api/tasks/
POST   /api/tasks/{id}/start
POST   /api/tasks/{id}/pause
POST   /api/tasks/{id}/resume
POST   /api/tasks/{id}/stop

GET    /api/executions/
GET    /api/executions/{id}
GET    /api/executions/{id}/events
GET    /api/executions/{id}/screenshots
```

---

# 35. Android → Django Event Protocol

Example:

```json
{
  "type": "NODE_COMPLETED",
  "execution_id": "9821",
  "node_id": "click_search",
  "result": {
    "outcome": "SUCCESS"
  },
  "timestamp": "..."
}
```

Failure:

```json
{
  "type": "NODE_FAILED",
  "execution_id": "9821",
  "node_id": "click_search",
  "result": {
    "outcome": "ELEMENT_NOT_FOUND",
    "attempt": 2
  }
}
```

---

# 36. Screenshots

Capture:

```text
on demand
on critical failure
before recovery
after recovery
```

Do not capture continuously unless needed.

Store metadata:

```text
execution_id
node_id
device_id
timestamp
screen dimensions
page URL
browser state
```

---

# 37. Observability

Use structured logs, not only plain text.

Example:

```json
{
  "event": "target_resolution",
  "execution_id": "9821",
  "node_id": "click_search",
  "target": "search_button",
  "resolved": true,
  "rect": {
    "left": 320,
    "top": 90,
    "width": 50,
    "height": 50
  }
}
```

Track metrics:

```text
workflow success rate
node success rate
retry rate
recovery rate
average node duration
browser startup time
target resolution time
verification time
device disconnect rate
```

---

# 38. Security

## Android authentication

Each device should have a unique device credential.

Use:

```text
TLS
device token/key
rotation
revocation
```

Never embed a Django superuser token inside the app.

## API

Use:

```text
authentication
authorization
rate limits
audit logs
```

## Workflow permissions

Add permissions such as:

```text
VIEW_WORKFLOW
EDIT_WORKFLOW
PUBLISH_WORKFLOW
EXECUTE_TASK
STOP_EXECUTION
MANAGE_DEVICES
MANAGE_PROFILES
```

---

# 39. Audit Trail

Store:

```text
who created workflow
who changed workflow
who published version
who started execution
who stopped execution
which device executed it
which profile was used
```

For sensitive actions:

```text
human confirmation
```

can be required.

---

# 40. Human-in-the-loop Nodes

Support a special node:

```text
PAUSE_FOR_HUMAN
```

Example:

```text
LOGIN
 ↓
MFA detected
 ↓
PAUSE_FOR_HUMAN
 ↓
User completes MFA
 ↓
RESUME
```

The React UI shows:

```text
Execution requires attention.

Device: Android 01
Task: Website Login
Reason: Additional verification required

[Open Live View]
[Resume]
[Abort]
```

Do not attempt to bypass the verification mechanism.

---

# 41. Generic "Wait" Engine

Avoid fixed global sleeps.

Prefer:

```text
WAIT_FOR_ELEMENT
WAIT_FOR_STATE
WAIT_FOR_NAVIGATION
WAIT_FOR_TEXT
WAIT_FOR_CONDITION
```

Allow a bounded safety delay after page transitions for rendering/animation settling.

---

# 42. Interaction Policy

Create one centrally controlled policy:

```kotlin
data class InteractionPolicy(
    val tapPauseMs: LongRange,
    val scrollDistancePx: IntRange,
    val scrollDurationMs: LongRange,
    val postActionPauseMs: LongRange
)
```

Use this for:

```text
QA realism
animation settling
device differences
network/page latency
```

Do not use it to evade anti-bot systems.

---

# 43. Page-State Detection

Combine:

```text
URL
title
known DOM markers
visible controls
page text
document readiness
known application state
```

Never identify a state from one selector alone where the page has multiple UI modes.

---

# 44. Target Resolution Strategy

Example:

```text
Target: Search

1. exact automation ID?
2. exact aria-label?
3. role + label?
4. stable CSS selector?
5. unique visible text?
6. fallback recovery strategy
```

For each candidate:

```text
visible?
enabled?
within viewport?
not covered by known overlay?
reasonable size?
```

Then produce a `ResolvedTarget`.

---

# 45. Safe Click Point

When an element rectangle is available, choose an appropriate point inside the element rather than relying on a historical coordinate.

Example concept:

```text
left/top + size → candidate interior point
```

Avoid edges where possible.

The point should then be passed through:

```text
DOM → viewport → Android screen
```

and finally to `InputController`.

---

# 46. Scroll-Into-View

A target not currently visible should use:

```text
PERCEIVE
 ↓
Target exists?
 ↓
Visible?
 ├── YES → map + interact
 └── NO
      ↓
  calculate bounded scroll
      ↓
  scroll
      ↓
  re-perceive
      ↓
  resolve target again
```

Never hold a stale rectangle across an arbitrary scroll.

---

# 47. Stale Target Protection

Every resolved target should have a snapshot/version.

Example:

```kotlin
data class TargetHandle(
    val perceptionVersion: Long,
    val elementId: String,
    val rect: DomRect
)
```

After:

```text
navigation
scroll
layout change
orientation
popup
```

invalidate the handle and re-resolve.

---

# 48. Recovery Rules

Example:

```text
ELEMENT_NOT_FOUND
→ refresh DOM
→ wait 500ms
→ resolve again
→ if missing: scroll
→ resolve again
→ retry
→ if still missing: fallback

TIMEOUT
→ capture screenshot
→ inspect page state
→ retry or abort

NETWORK_ERROR
→ wait
→ reload if safe
→ retry bounded number of times

UNKNOWN_PAGE
→ screenshot
→ classify
→ fallback/abort
```

---

# 49. Idempotency

Every node should declare whether it is safe to repeat.

```json
{
  "retry_policy": {
    "max_attempts": 3,
    "idempotent": true
  }
}
```

Examples:

```text
READ_TEXT          safe
SCREENSHOT         safe
WAIT               safe
OPEN_URL           generally safe

FORM_SUBMIT        may not be safe
PAYMENT            never auto-retry blindly
DELETE             never blindly retry
PURCHASE           human confirmation recommended
```

This prevents dangerous duplicate operations.

---

# 50. Workflow Categories

Create a library.

## Browser Navigation

```text
Open URL
Navigate
Back
Reload
Open New Tab
Close Tab
```

## Research

```text
Search Site
Read Page
Extract Metadata
Capture Screenshot
Follow Result
```

## Testing

```text
Login Test
Search Test
Checkout Test
Form Test
Navigation Test
Regression Test
```

## Monitoring

```text
Check Page
Check Text
Check Availability
Capture Screenshot
Report Difference
```

## Content QA

```text
Open Video
Verify Player UI
Read Metadata
Capture Page State
Verify Controls
```

---

# 51. Workflow Templates

Create reusable templates.

```text
templates/
├── browser/
│   ├── open-and-verify
│   ├── search-site
│   └── navigate-and-capture
│
├── testing/
│   ├── login-test
│   ├── form-test
│   └── checkout-test
│
├── research/
│   ├── search-read-capture
│   └── result-comparison
│
└── monitoring/
    ├── page-check
    └── availability-check
```

A template becomes a normal editable workflow after cloning.

---

# 52. Variables and Expressions

Support:

```text
{{query}}
{{url}}
{{profile.name}}
{{execution.id}}
```

Example:

```json
{
  "action": "OPEN_URL",
  "config": {
    "url": "{{base_url}}/search?q={{query}}"
  }
}
```

Use a safe templating engine. Never execute arbitrary Python or JavaScript from workflow variables.

---

# 53. Conditions

Example:

```json
{
  "type": "CONDITION",
  "condition": {
    "left": "{{page.state}}",
    "operator": "equals",
    "right": "SEARCH_RESULTS"
  },
  "transitions": {
    "TRUE": "open_result",
    "FALSE": "recover"
  }
}
```

Keep the expression language intentionally restricted.

---

# 54. Loops

Loops must always be bounded.

Bad:

```text
LOOP FOREVER
```

Good:

```json
{
  "max_iterations": 10
}
```

Also support:

```text
BREAK
CONTINUE
```

and a global execution timeout.

---

# 55. Timeouts

Use several levels:

```text
node timeout
recovery timeout
workflow timeout
device communication timeout
browser startup timeout
```

Example:

```text
Node: 15 sec
Recovery: 20 sec
Workflow: 15 min
Device heartbeat: 10 sec
```

Tune based on observed device/network performance.

---

# 56. Cancellation

Cancellation must be cooperative.

```text
React STOP
 ↓
Django sends CANCEL
 ↓
Android sees cancellation flag
 ↓
current action safely terminates
 ↓
browser left in known state
 ↓
execution marked ABORTED
```

Avoid killing the Android process to stop an ordinary workflow.

---

# 57. Pause / Resume

A paused execution should persist:

```text
current node
runtime variables
browser URL
workflow version
profile
device
```

On resume:

```text
re-perceive page
validate state
resume from safe boundary
```

Do not blindly replay the last interaction after a long pause.

---

# 58. Browser Profile Lifecycle

```text
SELECT PROFILE
 ↓
VALIDATE
 ↓
LOAD PROFILE
 ↓
START GECKO
 ↓
WORKFLOW
 ↓
CLOSE OR RETAIN
```

Keep a clear policy for:

```text
persistent profile
temporary profile
clean profile
```

---

# 59. Gecko Session Isolation

Each profile should map to its own controlled Gecko storage/session strategy.

Your profile manager should manage:

```text
cookies
storage
permissions
preferences
session state
```

Never mix profile storage between users/tasks unintentionally.

---

# 60. Android App States

The agent itself should have:

```text
IDLE
CONNECTING
READY
STARTING_BROWSER
RUNNING
PAUSED
WAITING_FOR_USER
ERROR
STOPPING
```

These are separate from browser page states.

---

# 61. Execution State Model

Use:

```text
QUEUED
RESERVED
DISPATCHED
STARTING
RUNNING
PAUSED
WAITING_FOR_HUMAN
RECOVERING
COMPLETED
FAILED
ABORTED
```

---

# 62. React Fleet Dashboard

Main cards:

```text
Devices
──────────────
● Online     4
○ Offline    1
⚙ Busy       3

Tasks
──────────────
● Running    5
◷ Scheduled  8
✓ Completed  92
✕ Failed     3
```

Device card:

```text
Android 01
● Online

Profile: Default
Browser: Gecko
Battery: 82%
Task: Search Research
Node: Search
Progress: 48%

[View]
[Pause]
[Stop]
```

---

# 63. Execution Detail Screen

```text
Execution #9821

STATUS
Running

PROFILE
Default

DEVICE
Android 01

WORKFLOW
YouTube Research v3

CURRENT NODE
Search

TIMELINE
✓ Open URL
✓ Wait for page
✓ Search field
✓ Type query
↻ Search submit
□ Read results
□ Capture metadata

[Live screenshot]
```

---

# 64. Live Debug View

For development, add:

```text
DOM snapshot
Resolved target
Mapped coordinates
Last action
Verification result
Recovery action
Screenshot
Current URL
Page state
```

This will save enormous amounts of development time.

Example:

```text
Target: search_button
DOM rect: 320,90,50,50
Screen point: 351,164
Input: TAP
Verification: SEARCH_OPEN
Result: SUCCESS
```

---

# 65. Testing Strategy

You need four levels.

## Unit

Test:

```text
TargetResolver
CoordinateMapper
WorkflowCompiler
Validator
ConditionEngine
RetryPolicy
RecoveryEngine
```

## Integration

Test:

```text
Django ↔ device protocol
Django ↔ WebSocket
Workflow compiler ↔ executor
Android ↔ extension bridge
```

## Device

Test on real Android hardware:

```text
small screen
large screen
different DPI
slow network
fast network
portrait
landscape
dark mode
browser UI changes
```

## End-to-end

```text
React
 ↓
Django
 ↓
Task Queue
 ↓
Android
 ↓
Gecko
 ↓
Web Page
 ↓
Verification
 ↓
React live status
```

---

# 66. Coordinate Mapping Test Matrix

Create test fixtures containing:

```text
device resolution
density
browser viewport
toolbar height
orientation
scroll offset
known DOM rectangle
expected screen point
```

Example fixture:

```json
{
  "device": "408x907",
  "dpr": 2.75,
  "viewport": {
    "width": 408,
    "height": 760
  },
  "scroll_y": 300,
  "dom_rect": {
    "left": 100,
    "top": 220,
    "width": 100,
    "height": 50
  }
}
```

Validate mapping independently before running real workflows.

---

# 67. Failure Injection Tests

Create deliberate failures:

```text
element disappears
page loads slowly
URL changes unexpectedly
overlay appears
browser reloads
network disconnects
device disconnects
workflow is cancelled
target moves after reflow
orientation changes
```

Expected behavior must be deterministic.

---

# 68. Mock Perception

For unit tests, do not require Firefox.

Example:

```kotlin
class FakePerceptionProvider(
    private var snapshots: MutableList<DomSnapshot>
) : PerceptionProvider {

    override suspend fun snapshot(): DomSnapshot {
        return snapshots.removeFirst()
    }
}
```

Then your executor can be tested entirely offline.

---

# 69. Mock Input

```kotlin
class FakeInputController : InputController {

    val events = mutableListOf<String>()

    override suspend fun tap(point: ScreenPoint) {
        events += "tap:${point.x},${point.y}"
    }

    override suspend fun back() {
        events += "back"
    }

    // ...
}
```

Now you can assert:

```text
target resolved
tap sent
verification observed
next node selected
```

without touching a device.

---

# 70. Django Workflow Compiler

The compiler should transform:

```text
Draft graph
```

into:

```text
CompiledWorkflow
```

with:

```text
validated nodes
normalized transitions
resolved defaults
compiled conditions
workflow version
hash/checksum
```

Pseudo-flow:

```python
def compile_workflow(workflow):
    validate_schema(workflow)
    validate_graph(workflow)
    validate_transitions(workflow)
    validate_variables(workflow)
    normalize_defaults(workflow)

    return CompiledWorkflow(...)
```

---

# 71. Workflow Hashing

Store a content hash.

```text
SHA-256(
    canonical_workflow_json
)
```

This makes debugging and audit easier.

Execution reports can say:

```text
workflow version: 7
workflow hash: abc123...
```

---

# 72. Celery / Queue

Recommended backend flow:

```text
React
 ↓
Django API
 ↓
TaskExecution created
 ↓
Celery job
 ↓
Scheduler chooses device
 ↓
Dispatch
```

Use device reservations to prevent two jobs from controlling one device simultaneously.

---

# 73. Device Reservation

Model:

```python
class DeviceReservation(models.Model):
    device = models.OneToOneField(Device, on_delete=models.CASCADE)
    execution = models.OneToOneField(TaskExecution, on_delete=models.CASCADE)
    reserved_at = models.DateTimeField(auto_now_add=True)
```

A device with an active reservation should not accept a second execution.

---

# 74. Settings

React should expose:

```text
Automation Settings
├── Default timeouts
├── Retry limits
├── Recovery strategy
├── Screenshot policy
├── Log retention
├── Device heartbeat interval
├── Max concurrent executions
└── Safety confirmation rules
```

---

# 75. Profile Settings

```text
Profile
├── General
├── Browser
├── Locale
├── Language
├── Timezone
├── Permissions
├── Storage
└── Assignment
```

---

# 76. Workflow Settings

```text
Workflow
├── General
├── Variables
├── Nodes
├── Recovery
├── Timeouts
├── Limits
└── Publishing
```

---

# 77. Global Safety Rules

Add a policy layer before execution.

```text
PolicyEngine
```

Example checks:

```text
Is task authorized?
Is device allowed?
Is profile allowed?
Is workflow published?
Does workflow contain restricted actions?
Is human confirmation required?
Does task exceed execution limits?
```

---

# 78. Restricted Action Policy

Examples of actions that should require stronger controls:

```text
financial transaction
account deletion
password reset
security-setting change
purchase
irreversible admin action
```

Potential policy:

```text
READ_ONLY       → automatic
REVERSIBLE      → automatic
SENSITIVE       → policy dependent
IRREVERSIBLE    → human confirmation
```

---

# 79. Workflow Marketplace / Library

Once stable, add:

```text
My Workflows
Templates
Published
Drafts
Archived
```

Allow:

```text
clone
version
export
import
duplicate
```

Use a JSON export format.

---

# 80. Export Format

```json
{
  "format": "octomobile.workflow",
  "version": 1,
  "workflow": {
    "name": "Research Site",
    "schema_version": 1,
    "variables": {},
    "graph": {}
  }
}
```

Never import executable server-side code through workflow import.

---

# 81. Recommended Initial Workflows

Build these first:

### 1. Open and Verify

```text
OPEN_URL
→ WAIT_FOR_PAGE
→ ASSERT_URL
→ SCREENSHOT
→ END
```

### 2. Search Site

```text
OPEN_URL
→ WAIT
→ CLICK_SEARCH
→ TYPE_QUERY
→ SUBMIT
→ VERIFY_RESULTS
→ END
```

### 3. Read and Capture

```text
OPEN_URL
→ WAIT
→ READ_TEXT
→ SCROLL
→ READ_TEXT
→ SCREENSHOT
→ END
```

### 4. Form Test

```text
OPEN
→ FIND_FIELD
→ TYPE
→ FIND_BUTTON
→ CLICK
→ VERIFY_RESULT
```

### 5. Error Recovery

```text
OPEN
→ WAIT
→ ASSERT
→ FAILURE
→ RECOVER
→ RETRY
```

### 6. Human Approval

```text
OPEN
→ PREPARE
→ PAUSE_FOR_HUMAN
→ RESUME
→ VERIFY
→ END
```

### 7. Multi-Branch Research

```text
OPEN
→ DETECT_PAGE_STATE
   ├── RESULTS → EXTRACT
   ├── LOGIN → PAUSE
   ├── CONSENT → HANDLE
   └── ERROR → RETRY
```

---

# 82. Recommended Development Phases

## Phase A — Foundations

```text
Workflow schema
Node types
Graph validator
Versioning
Execution model
```

## Phase B — Perception

```text
WebExtension bridge
DOM snapshot
ElementSnapshot
PageStateDetector
TargetResolver
```

## Phase C — Physical Interaction

```text
CoordinateMapper
InputController
Gesture implementation
Scroll-into-view
```

## Phase D — Closed Loop

```text
ActionExecutor
VerificationEngine
RecoveryEngine
StateMachine
```

## Phase E — Device Orchestration

```text
WebSocket
heartbeat
device reservation
task dispatch
cancellation
pause/resume
```

## Phase F — React Builder

```text
DAG canvas
node palette
node inspector
validation
publish
```

## Phase G — Reliability

```text
device matrix
failure injection
profiling
metrics
diagnostics
```

---

# 83. Definition of Done

The interaction layer is complete when all of the following work:

```text
[ ] DOM snapshot arrives reliably
[ ] Page state is detected
[ ] Targets can be resolved
[ ] Coordinates map correctly
[ ] Physical tap executes
[ ] Scroll executes
[ ] Type executes
[ ] Result is verified
[ ] Failed target triggers recovery
[ ] Stale target is invalidated
[ ] Workflow branches correctly
[ ] Device disconnect is handled
[ ] Execution resumes safely
[ ] Screenshots are attached to failures
[ ] React receives live events
[ ] Workflow versions are reproducible
```

---

# 84. Performance Targets

Use these as engineering goals, then measure.

```text
DOM snapshot             < 200 ms when page is stable
target resolution        < 200 ms typical
local action dispatch    < 100 ms overhead
verification             < 500 ms typical
WebSocket event          < 1 s typical
device heartbeat         5–10 s
```

These are targets, not guarantees; real performance depends on device/browser/page complexity.

---

# 85. Logging Correlation

Every component should carry:

```text
request_id
execution_id
task_id
device_id
profile_id
workflow_id
workflow_version
node_id
```

That lets you trace:

```text
React click
→ Django
→ queue
→ Android
→ Gecko
→ DOM snapshot
→ gesture
→ verification
→ result
```

from one execution ID.

---

# 86. Example Full Execution

```text
USER
 │
 │ Press START
 ▼
REACT
 │
 ▼
DJANGO
 │ Validate task
 │ Reserve device
 │ Load workflow v8
 ▼
QUEUE
 │
 ▼
ANDROID
 │ Load profile
 │ Start Gecko
 ▼
WEBEXTENSION
 │ Report page state
 ▼
EXECUTOR
 │
 │ Node: OPEN_URL
 ▼
VERIFIER
 │ URL correct?
 ▼
SUCCESS
 │
 ▼
NODE: SEARCH
 │
 ▼
PERCEPTION
 │ Search target found
 ▼
TARGET RESOLVER
 │ rect = ...
 ▼
COORDINATE MAPPER
 │ screen point = ...
 ▼
INPUT CONTROLLER
 │ physical tap
 ▼
PERCEPTION
 │ Search UI visible
 ▼
VERIFIER
 │ SUCCESS
 ▼
NEXT NODE
```

---

# 87. What to Build First in Your Existing Codebase

Given your existing architecture, do **not** rewrite your backend.

Prioritize these components:

```text
1. WebExtensionPerceptionBridge
2. DomSnapshot
3. TargetResolver
4. CoordinateMapper
5. NativeInputController
6. ActionExecutor
7. VerificationEngine
8. RecoveryEngine
9. BrowserStateDetector
10. ExecutionEventReporter
```

Then connect them to your existing:

```text
automation app
DAG compiler
TaskExecutionQueue
device management
Fleet Control Center
GeckoSession management
```

---

# 88. Suggested Integration Boundary

Your current queue should effectively deliver:

```kotlin
data class ExecutionPackage(
    val executionId: String,
    val workflowId: String,
    val workflowVersion: Int,
    val workflowHash: String,
    val profileId: String,
    val variables: Map<String, Any?>,
    val compiledGraph: CompiledWorkflow
)
```

Android should then execute that package locally.

---

# 89. Final Architecture

```text
                         REACT
                           │
                           │
                    REST / WebSocket
                           │
                           ▼
                        DJANGO
                           │
      ┌────────────────────┼─────────────────────┐
      │                    │                     │
   Profiles             Workflows             Devices
      │                    │                     │
      └────────────────┬───┴─────────────────────┘
                       │
                 Workflow Compiler
                       │
                       ▼
                TaskExecutionQueue
                       │
                       ▼
                 Android Agent
                       │
       ┌───────────────┼────────────────┐
       │               │                │
   State Machine   Perception       Input Control
       │               │                │
       │           WebExtension      Native Input
       │               │                │
       └───────────────┼────────────────┘
                       │
                    VERIFIER
                       │
                 ┌─────┴─────┐
                 │           │
              SUCCESS      FAILURE
                 │           │
                 ▼           ▼
              NEXT NODE    RECOVERY
                 │           │
                 └─────┬─────┘
                       ▼
                    NEW STATE
```

---

# 90. Final Recommendation

The platform should be treated as a **distributed stateful automation system**, not a collection of browser macros.

The strongest implementation sequence is:

```text
                WORKFLOW
                    │
                    ▼
                STATE NODE
                    │
                    ▼
                PERCEPTION
                    │
                    ▼
              TARGET RESOLUTION
                    │
                    ▼
             COORDINATE MAPPING
                    │
                    ▼
              NATIVE ACTION
                    │
                    ▼
              STATE OBSERVER
                    │
                    ▼
               VERIFICATION
                    │
          ┌─────────┴─────────┐
          │                   │
       SUCCESS              FAILURE
          │                   │
          ▼                   ▼
      NEXT NODE            RECOVERY
```

That is the foundation that will allow you to add many workflows later without changing the underlying Android automation engine.

---

# 91. Immediate Implementation Checklist

```text
BACKEND
[ ] Workflow schema
[ ] DAG compiler validation
[ ] Workflow versioning
[ ] TaskExecution model
[ ] ExecutionEvent model
[ ] Device reservation
[ ] WebSocket execution events

ANDROID
[ ] GeckoSession manager
[ ] Profile manager
[ ] WebExtension bridge
[ ] DOM snapshot parser
[ ] Page-state detector
[ ] Target resolver
[ ] Coordinate mapper
[ ] Input controller
[ ] Action executor
[ ] Verification engine
[ ] Recovery engine
[ ] Cancellation
[ ] Pause/resume
[ ] Screenshot diagnostics

REACT
[ ] Workflow list
[ ] Workflow builder
[ ] Node palette
[ ] Node inspector
[ ] Edge/transition editor
[ ] Validation panel
[ ] Publish/version UI
[ ] Task runner
[ ] Device dashboard
[ ] Live execution page

TESTS
[ ] Unit tests
[ ] Compiler tests
[ ] Target resolver tests
[ ] Coordinate mapping tests
[ ] Recovery tests
[ ] WebSocket tests
[ ] Device integration tests
[ ] E2E tests
[ ] Failure injection tests
```

## The core implementation rule

**Do not add more automation actions before the closed-loop interaction pipeline is reliable.**

Once:

```text
Perception → Resolve → Map → Native Action → Verify → Recover
```

is stable, adding new workflows becomes mostly a matter of composing existing nodes rather than writing new device-control code.
