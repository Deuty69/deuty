# Deuty Agent — Architecture

A computer-use agent for Android that operates the phone **through the normal user
interface** — accessibility tree, gestures, global actions — and never through
app-specific APIs, SDKs, MCP servers, or root.

This document describes the system as it is implemented today (V1/MVP), why it is
shaped this way, and where the seams are for later versions. Design trade-offs and
rejected alternatives live in [DECISIONS.md](DECISIONS.md); build and run
instructions in [BUILD.md](BUILD.md); test strategy in [TESTING.md](TESTING.md).

---

## 1. The one rule that shapes everything

> **AI decides WHAT. A deterministic Android engine decides HOW.**

An LLM (or the rule-based planner that ships by default) produces a *plan*: a list
of typed steps with declarative targets and declarative expectations. It never
produces gestures, never produces coordinates, never talks to Android.

Everything below the plan line is deterministic Kotlin: resolve the target against
the live UI tree, check the precondition, dispatch the gesture, wait for the
postcondition, retry within a budget, recover within a fixed ladder, and stop when
the budget is gone.

Two consequences drive the whole design:

1. **The engine is usable with no AI at all.** `RuleBasedPlanner` is a real planner
   (~20 grammar patterns), not a stub, so the app is fully functional offline on a
   4 GB device. Plugging in a model later changes *one* class behind *one*
   interface.
2. **The engine is testable on a JVM.** Targeting, verification, recovery, risk,
   budgets, planning, recording and persistence are pure Kotlin with zero Android
   imports, so 108 unit tests run in seconds without an emulator.

---

## 2. Layer map

```
                ┌──────────────────────────────────────────────┐
   user text ──▶│  planner/     ControlGrammar · RuleBasedPlanner│  WHAT
                │               PlannerProvider · model stubs    │
                └───────────────────┬──────────────────────────┘
                                    │ Plan(steps, risk, source)
                ┌───────────────────▼──────────────────────────┐
                │  agent/       AgentEngine (orchestration)      │
                │               TaskRunner · StateManager        │  HOW
                │               ElementResolver · RiskManager    │
                │               RecoveryManager · ScreenDiag.    │
                │  executor/    ActionExecutor · AgentAction     │
                │               ExpectationEvaluator             │
                └───────────────────┬──────────────────────────┘
                                    │ ports (interfaces only)
                ┌───────────────────▼──────────────────────────┐
                │  port/        InteractionEngine · ScreenSource │
                │               AppDirectory · VisionService     │
                │               DeviceSession · ExecContext      │
                └──────┬──────────────────────────┬────────────┘
                       │                          │
        ┌──────────────▼───────────┐   ┌──────────▼──────────────┐
        │ platform/  (Android)     │   │ sandbox/  (no Android)  │
        │ AccessibilityService     │   │ SimulatedDevice         │
        │ gestures · node tree     │   │ 23 screens, demo flows  │
        │ OverlayController        │   └─────────────────────────┘
        │ AppDirectory · Prefs     │
        └──────────────────────────┘

   cross-cutting:  core/model (data) · core/util (pure helpers)
                   core/json (codecs) · storage (atomic JSON files)
                   workflow (record/replay) · vision (OCR adapter)
                   ui + ui/overlay (Views) · di (composition root)
```

### 2.1 The JVM-purity rule

| Package | May import Android? | Why |
|---|---|---|
| `core/model`, `core/util`, `core/json` | **No** | Pure data + algorithms. `org.json` is the only external dependency and it has a JVM artifact for tests. |
| `agent/**` (incl. `executor`, `planner`, `sandbox`) | **No** | The brain and the engine. Talks to the device only through `port/`. |
| `workflow`, `storage` | **No** | Recording normalisation and persistence are pure; only the *file system* is touched, via `java.io`. |
| `platform`, `vision`, `ui`, `di` | Yes | Adapters: accessibility service, gestures, overlay window, OCR, Activities, composition root. |

`tools/verify_project.py` enforces this statically (it fails if `android.*`,
`androidx.*` or `java.awt` appears in a pure package), because the rule is easy to
break by accident and expensive to notice later.

### 2.2 File inventory (69 Kotlin files, ~14.0k lines)

Largest files, for orientation: `agent/AgentEngine.kt` (1109), `agent/TaskRunner.kt`
(720), `platform/AccessibilityInteractionEngine.kt` (730),
`agent/sandbox/SimulatedDevice.kt` (720), `agent/planner/RuleBasedPlanner.kt` (534),
`agent/executor/ActionExecutor.kt` (517), `agent/ElementResolver.kt` (406).

---

## 3. Ports: the only boundary to the device

`agent/port/Ports.kt` and `agent/port/DeviceSession.kt` define four interfaces.
Nothing in `agent/` or `core/` references an Android type; everything reaches the
phone through these.

```kotlin
interface InteractionEngine {          // the "hands"
    suspend fun tap(point: Point, node: UiNode?): ActionResult
    suspend fun longPress(point: Point, durationMs: Long, node: UiNode?): ActionResult
    suspend fun swipe(from: Point, to: Point, durationMs: Long): ActionResult
    suspend fun type(text: String, node: UiNode?, clearFirst: Boolean): ActionResult
    suspend fun pressEnter(node: UiNode?): ActionResult
    suspend fun globalAction(action: GlobalAction): ActionResult   // BACK/HOME/RECENTS/…
    val canPerformGestures: Boolean
}

interface ScreenSource {               // the "eyes"
    suspend fun capture(reason: String, maxNodes: Int): ScreenSnapshot
    fun startObserving(onChange: (ScreenEvent) -> Unit)
    fun stopObserving()
}

interface AppDirectory {               // the "phone book"
    suspend fun findApp(label: String): AppInfo?
    suspend fun launch(packageName: String): Boolean
    suspend fun installedApps(): List<AppInfo>
}

interface VisionService {              // optional Level 2/3 understanding
    suspend fun describe(screen: ScreenSnapshot): VisionResult?
    val available: Boolean
}
```

A `DeviceSession` bundles one implementation of each plus `available`, and is
swapped as a single unit on the main thread behind a `@Volatile` holder. That is
the fix for the classic accessibility-service leak: the engine never keeps a
reference to a destroyed service, so toggling the service off cannot crash a
running task — it degrades to "I cannot act right now".

Two implementations exist:

* `platform/AccessibilityDeviceSession` — the real device.
* `agent/sandbox/SimulatedDevice` — a 23-screen fake phone in pure Kotlin
  (settings, browser, files, chat, banking with `FLAG_SECURE`, a permission
  dialog, a scrollable list, an OTP field, …). It implements all four ports, so
  the *entire* pipeline — planner → runner → resolver → executor → verification →
  recovery → report — runs end-to-end in a JVM test with no emulator. This is how
  the engine was developed without a device in the loop, and it is the regression
  harness for everything below.

`ExecContext` is the per-task policy snapshot (allow screenshots, allow password
entry, block sensitive screens, confirmation threshold, redaction on/off, task and
step ids). It is computed **once per task** from user settings, so the executor
never reads `SharedPreferences` — i.e. never does disk I/O — inside an
accessibility callback.

---

## 4. Screen understanding: cheapest level first

Requirement: no polling, no screenshot loops, no continuous CV. A 4 GB device must
not fan its battery.

```
Level 0  accessibility tree   free, event-driven, structural      ← default, always
Level 1  screenshot           rate-limited by Android (API 30+)   ← only on demand
Level 2  light OCR            on-device, opt-in, NoOcrEngine by default
Level 3  vision model         optional, off by default, never uploads silently
```

* **Event-driven capture.** `AccessibilityScreenSource` keeps an `active` flag.
  When no task is running it does nothing at all: no timers, no captures, no
  wakeups. It listens for `TYPE_WINDOW_STATE_CHANGED` /
  `TYPE_WINDOW_CONTENT_CHANGED` and coalesces bursts with a debounce, so a
  scrolling list produces one snapshot, not sixty.
* **Bounded trees.** A snapshot is capped (`maxNodes`, default ~400) and marked
  `truncated` when the cap is hit, so a pathological app (a 10k-node WebView)
  cannot OOM the agent. `NodeTable` recycles the previous generation of
  `AccessibilityNodeInfo` objects — leaking them drains the framework's shared
  node pool on API 26–32 and silently breaks *other* accessibility apps.
* **Signatures, not pixels.** `core/util/ScreenSignature.kt` derives a compact
  structural hash (package, window, sorted node metadata). "Did the screen change?"
  is answered by comparing signatures — no image diffing, no CPU cost.
* **Escalation is explicit.** `RecoveryManager.Strategy.ESCALATE_TO_VISION` is the
  only path from Level 0 to Level 1/2, it requires `ctx.allowScreenshots`, and it
  is bounded by the step's retry budget.
* `vision/OcrVisionService` adapts an `OcrEngine` port; the default binding is
  `NoOcrEngine`, so OCR is a plug-in, not a dependency. When present, OCR results
  become `MatchTier.OCR` candidates — ranked *below* every structural tier and
  *above* raw coordinates.

---

## 5. Targeting: coordinates are the last resort

`ElementQuery` is **data, not code**: it can be persisted, replayed, diffed, shown
to the user, and produced by either planner without either being able to execute
arbitrary logic.

```kotlin
ElementQuery(
    viewId, idName, text, textContains, textStartsWith,
    contentDescription, descriptionContains, className,
    editable, clickable, scrollable, checked, focused, enabled,
    index, nearText, relativePosition,          // structural relations
    bounds, ocrText, point,                     // fallbacks, in that order
    label, matchPasswordFields
)
```

`agent/ElementResolver.kt` scores every node against the query and reports the
winning `MatchTier`:

| Tier | Name | Notes |
|---|---|---|
| 1 | `VIEW_ID` | `resource-id`, stable across layout changes — always preferred |
| 2 | `EXACT_TEXT` | normalised (case, unicode dashes/quotes, whitespace) |
| 3 | `CONTENT_DESCRIPTION` | icon buttons with no text |
| 4 | `TEXT_CONTAINS` | tolerates truncation and minor copy changes |
| 5 | `SEMANTIC` | "the editable field", "the scrollable list", "the checked switch" |
| 6 | `RELATIVE` | `nearText`, `BELOW/ABOVE/LEFT_OF/RIGHT_OF/NEAREST/INSIDE`, `index` |
| 7 | `APPROX_BOUNDS` | recorded bounds, tolerated with slack — **brittle** |
| 8 | `OCR` | only when the tree has nothing — **brittle** |
| 9 | `COORDINATES` | never chosen by a planner; only from a recorded fallback — **brittle** |

Guarantees enforced in code and asserted in `TargetingTest`:

* A node that matches by `resource-id` beats one that matches by text, even if the
  text match is "better" linguistically. Structure wins.
* **Password fields are never targeted** unless the query explicitly sets
  `matchPasswordFields = true` (which only the user can turn on in settings).
* Disabled and off-screen nodes are skipped.
* A text query that matches a node never falls through to coordinates.
* Falling to coordinates logs a warning and marks the step brittle, which the
  Inspector surfaces.
* Ambiguity is declared when the top score ties across **more than two** matching
  nodes; then recovery asks the user instead of picking one at random.

`UiNode.toQuery(allowBoundsFallback)` is the inverse direction: it builds the most
semantic query possible from a live node, and only keeps bounds when the node has
no text, no description and no id at all. This is what makes workflow recording
semantic (see §9).

---

## 6. Actions: a sealed, extensible hierarchy

`agent/executor/AgentAction.kt` — 20 subtypes, all data classes or objects:

```
navigation   LaunchApp · Back · Home · Recents · OpenNotifications · OpenQuickSettings
             Relaunch · WakeAndUnlock(ask)
manipulation Tap · LongPress · DoubleTap · Swipe · Scroll · DragAndDrop
text         Type · Paste · PressKey
observation  ReadScreen · Screenshot · FindElement · Report
control      Wait · Conditional · Repeat · AskUser
```

Every action declares:

* `label` — the stable string used in JSON (`"tap"`, `"long_press"`, …), so an old
  APK can read a new file and vice versa;
* `baseRisk` — a **floor**, never a ceiling: `RiskManager` can raise it based on
  the live screen and label text (§8);
* `observesOnly` — true for `ReadScreen`/`Screenshot`/`FindElement`/`Report`/`Wait`.
  The runner uses it to skip the "wait for the screen to change" verification,
  which would otherwise block for the full timeout on an action that cannot change
  anything;
* `describe()` — human text for the chat and the debug panel;
* `withTarget(query)` / `targetQuery()` — used by recovery to re-aim a step at a
  relaxed locator without rebuilding the plan.

`Conditional` and `Repeat` nest, with a hard depth bound, so a planner can express
small loops without the runner becoming a Turing machine.

`ActionExecutor` is the only place that converts an `AgentAction` + a resolved
`UiNode` into port calls. It computes the tap point from the node's bounds
(centre, clamped to the screen), splits a `Scroll(times = n)` into bounded
dispatches with settle waits in between, and turns every port result into an
`ActionOutcome` with a `FailureReason`.

---

## 7. Verification: no action is trusted

Every step runs the same seven-part cycle in `agent/TaskRunner.kt`:

```
1  budget      time / actions / retries / steps still available?   else fail closed
2  risk        RiskManager gate → maybe WAITING_FOR_USER           else refuse
3  precondition  evaluate against a fresh snapshot                 else recover
4  resolve     ElementResolver → node + MatchTier                  else recover
5  execute     ActionExecutor → port call                          else recover
6  postcondition  wait for the screen to settle, then evaluate     else recover
7  record      StepRecord + LoopDetector + persist + project to UI
```

`ExpectationEvaluator` interprets a small algebra of expectations:
`ElementPresent`, `ElementAbsent`, `TextPresent`, `TextAbsent`, `ScreenChanged`,
`ScreenUnchanged`, `PackageIs`, `WindowIs`, `FieldCleared`, `FieldContains`,
`AnyOf`, `AllOf`, `Until`, `Always`. Expectations are data, so a planner can emit
them and the user can read them in the Inspector.

Retry policy is per step (`RetryPolicy(maxAttempts, backoffMs, backoffMultiplier,
maxBackoffMs, reObserveBeforeRetry)`) and **re-observes before retrying** by
default: re-tapping a stale node is how agents double-tap "Send".

Hard budgets (`TaskBudget`) are non-negotiable and checked before every action:
`maxActions` 60, `maxDurationMs` 10 min, `maxRetriesPerStep` 3, `maxTotalRetries`
12, `maxSteps` 40, plus loop detection (`loopWindow` 16, `loopRepeatThreshold` 3).
Exceeding any of them ends the task with a reason; it never silently continues.

### 7.1 Loop detection

`core/util/LoopDetector.kt` distinguishes two failure shapes:

* **Cycle** — the same `(screen signature, action)` pair repeats `repeatThreshold`
  times inside a sliding window. "Stuck between two screens."
* **No progress** — N actions in a row without a *new* screen signature, even if
  the actions differ. "The taps are not landing, or this screen needs you."

Paging through a list (same action, new signature each time) is explicitly *not* a
loop. Both verdicts carry a user-readable message, because the message is the
product: the agent has to explain why it stopped.

### 7.2 State machine

`TaskState`: `CREATED → PLANNING → RUNNING → {WAITING_FOR_UI, VERIFYING,
RECOVERING, WAITING_FOR_USER, PAUSED} → {COMPLETED, FAILED, CANCELLED}`.
Transitions are whitelisted in `TaskState.ALLOWED` and `StateManager` refuses
illegal ones (logging rather than throwing, so a UI race can never kill a task).

State is persisted after every transition, so the task survives process death. On
load, `TaskRepository.loadCurrent()` rewrites `RUNNING`/`VERIFYING`/`RECOVERING`/
`WAITING_FOR_UI` to `PAUSED` with `stopReason = "interrupted by process restart"`
— a task that was running when the process died is, by definition, not running any
more, and the UI offers a resume instead of pretending.

The UI never sees `AgentTask`. It receives immutable `TaskView` / `StepView` /
`AgentStatus` projections (`core/model/Projections.kt`), so a background mutation
cannot leak into a RecyclerView mid-bind.

---

## 8. Safety: risk, gating, and things we refuse to do

`agent/RiskManager.kt` computes an effective risk per step from four inputs:

1. `action.baseRisk` (floor);
2. the **label text** of the resolved node and the step title, matched against
   curated term lists — `HIGH_RISK_TERMS` ("pay now", "transfer", "delete
   account", "factory reset", "add card", "kyc", "uninstall", …) and
   `MEDIUM_RISK_TERMS` ("send", "submit", "delete", "install", "allow", "confirm",
   …);
3. the **screen**: a banking/wallet package hint raises the floor, an OTP-shaped
   field raises a `Type` to HIGH, `screenshot` is at least MEDIUM;
4. the **task's** own ceiling (`TaskConstraints.maxRisk`) and the user's
   confirmation threshold (`ExecContext.confirmAtOrAbove`, default MEDIUM).

Refusals (not confirmations — the agent will not proceed even if the user says
"yes" in chat):

* `screen.isSecure` (`FLAG_SECURE`) → refuse. We cannot see it, so we will not
  poke at it.
* A system permission/install dialog on screen
  (`SYSTEM_GUARD_PACKAGES`: `com.android.permissioncontroller`,
  `com.google.android.packageinstaller`, `com.android.systemui`, …) → refuse and
  ask the human. **Silently bypassing Android security prompts is out of scope by
  design**, and `RecoveryManager` routes those blockers to `ASK_USER` with
  `denyStopsTask = true`.

Risk gates are one-way per run: `TaskRunner` keeps a `clearedSteps` set so an
approved step is not re-gated on `RETRY_STEP` (the V1 bug that made approval loop
forever). The set is per-run and deliberately *not* persisted — a restart re-asks.

`core/util/Redactor.kt` masks secrets before anything is logged, persisted, shown
in chat, or copied to the Inspector clipboard: OTP-shaped strings, password-field
content, and values whose label is sensitive (`password`, `pin`, `otp`, `cvv`,
`card`, `ssn`, …). Ordinary text passes through untouched, and redaction can be
switched off in settings for debugging — but never for password fields, which the
recorder does not capture at all.

---

## 9. Planning

`agent/planner/`:

* `ControlGrammar` — the shared vocabulary for out-of-band commands (`stop`,
  `pause`, `resume`, `cancel`, `yes/no`, `status`, `what do you see`). Checked
  **before** the "I'm busy" refusal, so STOP always works mid-task.
* `RuleBasedPlanner` — the V1 brain: ~20 patterns (open/launch, tap, long-press,
  type-into, scroll with direction and count, back/home/wait, read screen, search
  "X in Y", rename, move "A to B/C" which walks every folder, find-and-tap,
  conditional/loop phrasing, sequencing on "then/and then/after that"). It also
  knows what it does **not** understand and returns `NotCapable` rather than
  guessing. It never emits coordinates.
* `PlannerProvider` — an ordered fallback chain: `LocalModelPlanner` →
  `RemotePlanner` → `RuleBasedPlanner`. Both model planners are honest stubs in V1
  (no `INTERNET` permission is declared, so `RemotePlanner` cannot phone home even
  if someone binds it); the chain is the extension point.
* `PlanResult` is sealed: `Plan`, `Clarify(question)`, `Rejected(reason)`,
  `NotCapable(reason)`. A clarification is depth-limited to 2 so the agent cannot
  interrogate the user forever.

Explicit workflow requests ("run workflow X", "replay X") are intercepted only when
the request is *explicit* — the word "workflow"/"replay" is present, or a saved
workflow name matches. This was a real bug: a broad verb-prefix regex turned
"start the timer" into a workflow lookup.

---

## 10. Workflow record & replay

`workflow/WorkflowRecorder.kt` turns raw `RecordedInteraction` events into
replayable `TaskStep`s. The recording is **semantic**, which is the difference
between a macro and a workflow:

* Our own UI is ignored (`ownApp`), so pressing STOP is not recorded as a step.
* Consecutive text changes in the same field collapse into **one** `Type` step
  holding the final text, with a `FieldContains` postcondition. Recording every
  keystroke would replay character by character — slow, and wrong after a retry.
* Consecutive same-direction scrolls collapse into one `Scroll(times = n)`; a
  direction change starts a new step.
* A package change becomes an explicit `LaunchApp` step with a `PackageIs`
  postcondition, so replay works even when the app is not in the foreground.
* Text typed into a password field is **never captured** — not redacted, absent.
* Targets come from `UiNode.toQuery()`: id → text → description → semantic
  properties, with bounds only when a node has no metadata at all.
* A tap that merely focuses a field immediately before typing is merged away
  (`Type` focuses the field itself); the extra tap is only an extra failure point.
* Risk is deliberately **not** baked into the recording. The same "Save" button is
  harmless in one app and a purchase confirmation in another, so `RiskManager`
  re-assesses against the live screen at replay time.

`workflow/WorkflowRepository.kt` persists workflows as JSON, exposes a
`StateFlow<List<Workflow>>` for the UI, supports fuzzy `findByName`,
`recordRun(success)` statistics, and `instantiate()` which returns **fresh,
PENDING** steps with new ids — replaying a workflow must not inherit the statuses
of the run that created it.

---

## 11. Recovery

Two pieces, deliberately split:

* `agent/ScreenDiagnostics.kt` answers **"what is on screen that is blocking me?"**
  → `Blocker`: `NONE`, `PERMISSION_DIALOG`, `PAYMENT_SHEET`, `CAPTCHA`,
  `SECURE_SCREEN`, `LOGIN_REQUIRED`, `APP_NOT_INSTALLED`, `CRASH_DIALOG`,
  `NETWORK_ERROR`, `UPDATE_PROMPT`, `COOKIE_CONSENT`, `TUTORIAL`, `STORAGE_FULL`,
  `BATTERY_OPTIMISATION`, `RATE_LIMITED` — each with evidence and a suggested
  question for the user.
* `agent/RecoveryManager.kt` answers **"what do I do about it?"** → `RecoveryPlan`
  with a `Strategy`: `RE_OBSERVE`, `SCROLL_THEN_RETRY`, `SWITCH_LOCATOR`,
  `DISMISS_BY_BACK`, `RELAUNCH_APP`, `WAIT_AND_RETRY`, `ESCALATE_TO_VISION`,
  `ASK_USER`, `GIVE_UP`, plus optional `preActions`, `retryStep`, `relaxedQuery`
  and `ask`.

Rules:

* A diagnosed **blocker always wins** over the generic failure reason: the failure
  is the symptom, the blocker is the cause.
* `ELEMENT_NOT_FOUND` escalates in a fixed order — re-observe (0) → scroll once
  and relax the locator (1) → switch to a looser locator (2) → ask the human (3+).
* Budget, cancellation and "service went away" failures map to `GIVE_UP` with
  `retryStep = false`. Recovery must never resurrect a task the user stopped.
* **A recovery plan may never be more dangerous than the step it recovers.**
  Recovery never taps something it has not seen, never dismisses a system dialog,
  and every strategy's attempt count is bounded by the caller.

---

## 12. Orchestration, threading, lifecycle

* `agent/EngineControl.kt` is the STOP path: a `volatile` flag checked before every
  action, a `CompletableDeferred` for the answer to a pending question, and a
  `MutableStateFlow<AgentStatus>` for the UI. STOP wins before any action is
  dispatched, and `CancellationException` is **rethrown explicitly** by the
  main-thread hop helper — `runCatching` would otherwise swallow it and make STOP
  silently fail.
* `AgentEngine` keeps the task job separate from command jobs, so a command
  ("what do you see?") can be answered while a task runs, and so cancelling a
  command never cancels the task.
* Everything runs on coroutines. The accessibility service's main thread is never
  blocked: gesture dispatch uses `suspendCancellableCoroutine` +
  `withTimeoutOrNull` rather than a `CountDownLatch` (a latch whose callback is
  posted to the same looper deadlocks the service — this was found and fixed).
* No foreground service in V1. Work happens while the accessibility service is
  bound and the app is in use; a task that must survive the app being swiped away
  is a V2 concern (see DECISIONS).
* `di/AgentContainer.kt` is a hand-written composition root (no Hilt/Dagger/Koin).
  ~280 lines, one place to read to see the whole object graph, and no annotation
  processor on a 4 GB device's build. Property initialisers are ordered before any
  `init {}` block that reads them — Kotlin executes both in declaration order, and
  getting that wrong is an NPE at startup.

---

## 13. Persistence

`storage/JsonFileStore.kt`: atomic writes (`name.tmp` → rename over `name`, with
the previous copy kept as `name.bak`), and reads that fall back to the backup when
the primary is corrupt. A truncated write costs one record, never the store.

`storage/TaskRepository.kt`: current task, bounded history (compacted), chat
transcript. The transcript is saved with `saveMessages` (**replace**) rather than
`appendMessages`, because the engine holds the whole conversation in memory and
re-saves on a debounce — appending would duplicate the file on every write.

`core/json/`: hand-written codecs (`ActionCodecs`, `TaskCodecs`, `ScreenCodecs`)
with an explicit `FORMAT_VERSION`, tolerant readers (`intOr`, `strOrNull`,
`enumOrNull`) and **unknown values decoding to `null` instead of throwing**. That
is what makes forward/backward compatibility possible without a migration
framework: an old APK reads a new file and skips the step it does not understand.

Screen dumps are stored un-redacted (they are diagnostic artefacts) but only in
app-private storage, with `allowBackup = false` in the manifest so they never
reach a cloud backup, and no `INTERNET` permission at all.

---

## 14. UI and the overlay

Views + XML + viewBinding (no Compose): the overlay must inflate in milliseconds
from a service context on a low-end device, and Views are the only UI toolkit an
`AccessibilityService` can host without a `ComponentActivity`.

* `platform/OverlayController` owns the `SYSTEM_ALERT_WINDOW` window: a 34 dp
  capsule ("Dynamic Island"-style) that expands to a 304 dp panel. `STOP` is the
  largest touch target. One repeating `ObjectAnimator` provides the pulse (a
  recursive `withEndAction` leaks), and tint is animated with `backgroundTintList`
  because `setBackgroundColor` on an oval drawable draws a square.
  `FLAG_LAYOUT_NO_LIMITS` is *not* used — its behaviour varies too much across OEM
  builds. The window is `NOT_FOCUSABLE`; the chat field uses click-to-focus.
* `ui/overlay/OverlayPresenter` is the Android-free view logic; `OverlayCallbacks`
  is the seam back to the engine.
* `MainActivity` (chat + task progress), `WorkflowsActivity` (recorded workflows),
  `SettingsActivity` (permissions, risk threshold, capture, redaction),
  `InspectorActivity` (debug: package, node table, last action and outcome, state,
  retries, tree dump — clipboard copies are redacted).

---

## 15. Extension points (V2–V9)

Everything below is a seam that already exists; none of it is implemented in V1.

| Later version | Seam to use |
|---|---|
| On-device model planner | `PlannerProvider` chain + `LocalModelPlanner` |
| Cloud planner (opt-in) | `RemotePlanner` + add `INTERNET` only if the user enables it |
| Real OCR | bind an `OcrEngine` implementation in `AgentContainer`; `MatchTier.OCR` already ranks it |
| Vision-model screen description | `VisionService.describe()` |
| Foreground-service long tasks | `EngineControl` + `TaskRunner` are already service-agnostic |
| Cross-app workflows / conditionals | `Conditional`, `Repeat`, `Until` already in the action algebra |
| Preferences & habits memory | `storage/` + `PreferencesStore`; add a repository, not a schema change |
| Multi-language grammar | `RuleBasedPlanner` patterns are data-driven tables per language |
| Replay hardening / self-healing locators | `MatchTier` + `relaxedQuery` in `RecoveryPlan` |
