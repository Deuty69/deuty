# Decision log

Each entry: **context** → **decision** → **why** → **what was rejected** →
**consequences**. Numbered so they can be referenced from code comments.
Entries are appended, never rewritten; when a decision is reversed, the new entry
says which one it supersedes.

Toolchain baseline for all of them: Gradle 8.13, AGP 8.13.2, Kotlin 2.2.20,
JDK 17, `compileSdk`/`targetSdk` 36, `minSdk` 26.

---

## ADR-001 — Operate through the UI, never through app APIs

**Context.** The agent has to work in apps we do not control and cannot modify.

**Decision.** Every effect on the device goes through standard Android user-input
mechanisms: the accessibility node tree for reading, `dispatchGesture` /
`performAction` / global actions for writing. No app-specific SDKs, no MCP
servers, no intents-into-private-activities, no root, no `adb`.

**Why.** It is the only approach that generalises: one implementation works in
every app, including apps that have never heard of us. It is also the only one a
user can audit — "it taps what you would tap" is a sentence a non-engineer can
verify by watching the screen.

**Rejected.** Per-app integrations (fast, precise, and a maintenance treadmill that
grows with every app and every release); root/`adb shell input` (excludes almost
all real devices and every Play-Store user); screen-scraping-only (loses structure,
forces coordinates).

**Consequences.** We inherit the UI's limitations: `FLAG_SECURE` screens are
invisible, some apps block injected gestures, and a WebView with no accessibility
metadata is a wall. Those cases are detected and reported, not worked around.

---

## ADR-002 — AI decides WHAT, a deterministic engine decides HOW

**Context.** The obvious design is "send the screen to a model, get the next
action". That makes every tap dependent on a network round-trip and a model's mood.

**Decision.** A strict plan/execute split. Planners emit typed steps with
declarative targets (`ElementQuery`) and declarative expectations (`Expectation`).
Resolution, gesture geometry, verification, retry, recovery, budgets and risk
gating are deterministic Kotlin that never consults a model.

**Why.** Latency, cost, offline operation, auditability, and testability. It also
bounds the blast radius of a bad model output: a planner can propose a step, but it
cannot invent a gesture, cannot invent coordinates, and cannot bypass the risk gate.

**Rejected.** Model-in-the-loop per action; model-generated code/scripts; model
choosing pixel coordinates.

**Consequences.** The engine must be genuinely capable on its own — hence
`RuleBasedPlanner` is a real planner, and the app is fully usable with no model
bound at all.

---

## ADR-003 — Ship a rule-based planner as the V1 brain

**Context.** V1 has to work on a 4 GB device, offline, with no API keys.

**Decision.** `RuleBasedPlanner` implements ~20 intent patterns (open, tap,
long-press, type-into, scroll n times, back/home/wait, read screen, search "X in
Y", rename, move "A to B/C", find-and-tap, sequencing on "then"). `PlannerProvider`
chains `LocalModelPlanner → RemotePlanner → RuleBasedPlanner`, and both model
planners are stubs in V1.

**Why.** A chain of fallbacks means adding a model later is a binding change in
`AgentContainer`, not a rewrite. And the rule planner gives us something to test
the engine against from day one.

**Rejected.** Shipping only model planners (nothing works offline); hardcoding one
provider (locks the architecture to a vendor, which the brief forbids).

**Consequences.** Coverage is bounded and must be honest about it: when no pattern
matches, the planner returns `NotCapable` and the agent says so, rather than
guessing. Guessing at "make me a coffee" and tapping something is worse than
refusing.

---

## ADR-004 — `ElementQuery` is data, not code; coordinates are tier 9

**Context.** Targeting is where UI automation breaks: layouts change, text is
localised, ids are stable-ish, coordinates are never stable.

**Decision.** A declarative query resolved through an ordered `MatchTier` ladder
(`VIEW_ID → EXACT_TEXT → CONTENT_DESCRIPTION → TEXT_CONTAINS → SEMANTIC →
RELATIVE → APPROX_BOUNDS → OCR → COORDINATES`). Tiers 7–9 are flagged `isBrittle`.
No planner may emit a coordinate-only query; only a recorded fallback can carry
bounds, and only when a node has no id, text or description.

**Why.** Tolerating minor layout change is a hard requirement. Ranking structure
above appearance above geometry is what makes a workflow recorded today survive an
app update.

**Rejected.** XPath/selector expressions (require a query language in the plan,
which means a parser and an injection surface); always-coordinates (breaks on the
first reflow); OCR-first (slow, battery-hungry, and worse than the tree).

**Consequences.** Ambiguity becomes a real state: when the top score ties across
more than two nodes, the resolver says so and recovery asks the user instead of
picking one. That is a feature — a silent wrong tap is the worst failure mode an
agent has.

---

## ADR-005 — Verify every action; never trust a dispatch

**Context.** `dispatchGesture` returning `true` means "the gesture was accepted",
not "the button was pressed".

**Decision.** Every step carries a precondition and a postcondition, evaluated
against a fresh screen snapshot, with a timeout and a bounded retry policy that
**re-observes before retrying**. Actions that cannot change the screen
(`ReadScreen`, `Screenshot`, `FindElement`, `Report`, `Wait`) declare
`observesOnly = true` and skip the change-wait.

**Why.** Without this, a failed tap becomes a silently skipped step and the agent
reports success for something that never happened.

**Rejected.** Fire-and-forget execution; trusting `performAction` return values;
fixed `Thread.sleep` between steps.

**Consequences.** Each step costs at least one extra snapshot. That is affordable
because capture is event-driven and structural (a few hundred nodes), not pixel
based. The `observesOnly` flag exists because the naive version of this rule made
every `ReadScreen` block for its full timeout.

---

## ADR-006 — Hard budgets and loop detection, fail closed

**Context.** An agent that retries forever is worse than one that stops: it burns
battery, and in the worst case it keeps tapping.

**Decision.** `TaskBudget` (`maxActions` 60, `maxDurationMs` 10 min,
`maxRetriesPerStep` 3, `maxTotalRetries` 12, `maxSteps` 40) is checked **before**
every action. `LoopDetector` flags (a) the same `(signature, action)` pair repeated
3× inside a 16-entry window and (b) 8 actions with no new screen signature. Budget
and loop failures map to `RecoveryManager.Strategy.GIVE_UP` with `retryStep =
false`.

**Why.** Recovery must never resurrect a task the user stopped or a budget that was
exhausted. Both verdicts carry a human-readable message, because the explanation is
part of the product.

**Rejected.** Unbounded retries with exponential backoff; heuristics that "try
something different" after N failures (that is how agents end up in Settings).

**Consequences.** Legitimate long tasks (>60 actions) need the budget raised in
settings or a workflow split in two. Paging a long list is explicitly *not* treated
as a loop, because each page produces a new signature.

---

## ADR-007 — Refuse security dialogs; never bypass them

**Context.** Every automation framework eventually grows a "dismiss permission
dialog" helper. That helper is how an agent talks a user into granting permissions
they did not mean to grant.

**Decision.** If `ScreenDiagnostics` sees a system guard package
(`com.android.permissioncontroller`, `com.google.android.packageinstaller`,
`com.android.systemui`, …) or a `FLAG_SECURE` screen, the step is **refused** and
routed to `ASK_USER` with `denyStopsTask = true`. The user must touch it.

**Why.** Consent has to come from a human. This is a bright line, not a policy
knob: there is no setting that lets the agent tap "Allow" on a system permission
dialog.

**Rejected.** Auto-dismissing dialogs to "keep the flow going"; treating permission
prompts as ordinary UI.

**Consequences.** Tasks that need a new permission pause and wait. The agent
explains what it needs and why, which is the correct behaviour anyway.

---

## ADR-008 — Risk is a floor computed from the live screen, not a static label

**Context.** "Delete" is harmless in a photo album and catastrophic in a bank app.
A static per-action risk level cannot express that.

**Decision.** `RiskManager` starts from `action.baseRisk` and raises it using the
resolved node's label text, the step title, the package (banking/wallet hints), the
field type (OTP-shaped text → HIGH) and the screen state. Confirmation gating uses
`ExecContext.confirmAtOrAbove` (default MEDIUM) and the task's own `maxRisk`
ceiling. Approved steps are recorded in a per-run `clearedSteps` set.

**Why.** Risk is a property of *(action, target, screen)*, not of the action alone.
The per-run set exists because re-gating an approved step on `RETRY_STEP` produced
an infinite approval loop in the first implementation.

**Rejected.** Static per-action risk; risk inferred from the plan text only; a
global "trust mode".

**Consequences.** The term lists are curated data and will need maintenance per
language. `clearedSteps` is deliberately **not** persisted: after a restart the
agent asks again.

---

## ADR-009 — Privacy: local-only, no telemetry, no `INTERNET` permission

**Context.** The agent sees everything on screen, including passwords and OTPs.

**Decision.** The manifest declares **no** `INTERNET` permission. `allowBackup =
false` so screen dumps never reach a cloud backup. `Redactor` masks OTP-shaped
strings, password-field content and values with sensitive labels before anything is
logged, persisted or shown. Password-field text is never *captured* by the
recorder — not redacted, absent. The Inspector redacts clipboard copies.

**Why.** A permission we do not declare cannot be misused by a future dependency,
a future feature, or a compromised build. Redaction-at-write is the only place it
can be relied on, because logs get shared.

**Rejected.** Opt-out telemetry; uploading screenshots for "better planning";
logging raw trees at info level.

**Consequences.** `RemotePlanner` cannot work until a user explicitly enables
network access, which would be a separate, visible manifest change. Screen dumps
are stored un-redacted in app-private storage for debugging; that is a deliberate
trade-off, documented in `SettingsActivity`.

---

## ADR-010 — Event-driven observation; no polling, no screenshot loops

**Context.** Continuous capture is the fastest way to kill a low-end device's
battery and get the app uninstalled.

**Decision.** `AccessibilityScreenSource` has an `active` flag: idle costs nothing
(no timers, no captures, no wakeups). While a task runs, window/content-change
events are debounced and coalesced into one snapshot. Snapshots are capped
(`maxNodes`, `truncated` flag). Screenshots are on-demand only, and Android itself
rate-limits them on API 30+.

**Why.** Screen understanding is a ladder (tree → screenshot → OCR → vision model)
and we only climb it when the level below fails, from inside `RecoveryManager`.

**Rejected.** Periodic polling; continuous screenshot diffing; always-on OCR.

**Consequences.** Very fast UI transitions can be missed between events; the runner
compensates with a bounded settle-wait before verifying a postcondition.

---

## ADR-011 — JSON files with atomic writes, not Room

**Context.** Persistence needs: current task (survive process death), bounded task
history, chat transcript, workflows, screen dumps. Volumes are small (tens of KB),
access is single-writer, and there are no relational queries.

**Decision.** `JsonFileStore` — `name.tmp` → rename over `name`, previous copy kept
as `name.bak`, reads fall back to the backup on parse failure. Hand-written codecs
with a `FORMAT_VERSION` and tolerant readers; unknown enum values and unknown
action types decode to `null` instead of throwing.

**Why.** It removes KSP and the Kotlin-version coupling that comes with it from the
critical path, keeps the build fast on a small machine, and gives forward/backward
compatibility for free: an old APK reads a new file and skips what it does not
understand. A corrupt write costs one record, never the store.

**Rejected.** Room (schema migrations, KSP toolchain, and a dependency whose value
is queries we do not make); `SharedPreferences` for anything but small settings
(not atomic for large blobs, and it blocks the main thread on first read);
DataStore (fine, but adds a dependency for something 120 lines of `java.io` does).

**Consequences.** No SQL, no indexes, no concurrent writers. If V2+ needs to query
history by app/date at scale, this is the piece to replace — and the codecs are the
seam (models stay, storage swaps).

---

## ADR-012 — Views + XML + viewBinding, not Compose

**Context.** The most latency-sensitive UI in the app is an overlay window inflated
from a *service* context while the user is doing something else.

**Decision.** Classic Views with viewBinding; Material 3 DayNight theme; the
overlay is a `WindowManager` view.

**Why.** Smaller resident footprint and predictable inflation cost on a 4 GB
device, no Compose runtime in a service-hosted window, and viewBinding gives
compile-time id safety without reflection or a plugin.

**Rejected.** Compose (excellent for the Activities, but a heavier resident
footprint and more moving parts for a `SYSTEM_ALERT_WINDOW` view in V1).

**Consequences.** More XML, manual `RecyclerView` adapters, and imperative view
updates. Mitigated by the projection model: the UI receives immutable
`TaskView`/`StepView`/`AgentStatus` values, so there is no shared mutable state to
race with.

---

## ADR-013 — Hand-written DI container

**Context.** The object graph is ~30 objects with a clear construction order.

**Decision.** `di/AgentContainer.kt` (~280 lines) builds the graph explicitly;
`AgentApplication` owns it.

**Why.** One readable place that shows the whole graph, no annotation processor, no
generated code, no startup reflection cost, and no dependency whose major version
moves with Kotlin's.

**Rejected.** Hilt/Dagger (build-time cost and complexity far beyond this graph);
Koin (service-locator runtime resolution, which turns wiring errors into runtime
crashes).

**Consequences.** Wiring is manual, so a new collaborator has to be added by hand.
Kotlin initialises properties and `init` blocks in declaration order — the
container had an NPE from exactly that, and the ordering is now deliberate.

---

## ADR-014 — The engine lives in the AccessibilityService; no foreground service in V1

**Context.** Only an `AccessibilityService` can read the node tree and dispatch
gestures. A foreground service would be needed to keep working with the app swiped
away.

**Decision.** The engine is owned by the container and driven while the service is
bound. No foreground service, no notification channel, no wake lock in V1.

**Why.** Smallest possible resident footprint, no persistent notification, and one
fewer permission/UX hurdle. Task state is persisted after every transition, so an
interrupted task comes back as `PAUSED` with a resume offer instead of vanishing.

**Rejected.** Foreground service from day one (costs a notification and battery for
a capability V1 does not need); `WorkManager` (wrong tool: agent steps are
interactive and sub-second, not deferrable).

**Consequences.** Swiping the app away can interrupt a long task. Resume is
persisted and tested; unattended background automation is explicitly a V2 feature
(see ARCHITECTURE §15).

---

## ADR-015 — Semantic workflow recording

**Context.** A recorded macro of coordinates breaks the first time the app moves a
button.

**Decision.** `WorkflowRecorder` normalises raw interactions into semantic steps:
keystrokes collapse into one `Type` with a `FieldContains` postcondition; same-
direction scrolls collapse into `Scroll(times = n)`; a package change becomes an
explicit `LaunchApp` with `PackageIs`; our own UI is ignored; a focus-tap before
typing is merged away; targets come from `UiNode.toQuery()` (id → text →
description → semantic → bounds only as a last resort).

**Why.** Replay has to survive layout change, and the recording has to be readable
by the user — a workflow they cannot read is a workflow they cannot trust.

**Rejected.** Raw event replay (coordinates + timings); storing screenshots as
templates (storage, battery, and brittle).

**Consequences.** Risk is *not* baked into a recording: `RiskManager` re-assesses at
replay time, because the same "Save" button changes meaning between apps.

---

## ADR-016 — Passwords are never captured

**Context.** Recording a login flow is one of the first things users try.

**Decision.** `InteractionMapper` never creates a `RecordedInteraction` for a text
change in a password field, and `ElementResolver` will not target a password field
unless the query sets `matchPasswordFields = true` (a user-visible setting, off by
default).

**Why.** The strongest guarantee is that the secret never enters the process. There
is nothing to leak, nothing to redact, nothing to delete.

**Rejected.** Capture-and-redact (a bug away from being capture-and-store);
capture-and-encrypt (key management on a device the user does not control).

**Consequences.** The agent cannot log the user in. It asks, and waits — which is
also what ADR-007 requires for `LOGIN_REQUIRED`.

---

## ADR-017 — A simulated device, so the pipeline is testable without an emulator

**Context.** The engine's interesting behaviour (resolve → verify → recover →
budget → report) cannot be exercised by unit tests that mock the thing under test,
and the development environment has no Android SDK.

**Decision.** `agent/sandbox/SimulatedDevice` implements all four ports in pure
Kotlin: 23 screens, typed-text state, history-based back, paging lists, a
`FLAG_SECURE` banking screen, a permission dialog, an OTP field, a rename flow and
a move-between-folders flow. `SandboxDemo` drives the full pipeline from a natural
language request.

**Why.** It makes the whole engine runnable in a JVM test in milliseconds, which is
how 108 unit tests exist at all, and it doubles as an offline demo mode.

**Rejected.** Robolectric (heavy, and it simulates the framework, not an app's UI);
instrumented tests only (need a device/emulator, slow, and unrunnable in this
environment); mocking the ports in every test (tests the mock).

**Consequences.** The simulator can drift from real device behaviour. It is a
regression harness for *our* logic, not a substitute for on-device testing — see
TESTING.md for the manual checklist that covers the rest.

---

## ADR-018 — Stop is a first-class path, not an exception

**Context.** The most important button in the app has to work while a gesture is in
flight, while a question is pending, and while the UI is animating.

**Decision.** `EngineControl` = a `volatile` stop flag checked before every action
+ a `CompletableDeferred` that answers any pending `askUser` + a
`MutableStateFlow<AgentStatus>` for the UI. `ControlGrammar` is checked *before*
the "I'm busy" refusal, so typing "stop" mid-task works. The main-thread hop helper
rethrows `CancellationException` explicitly.

**Why.** `runCatching` swallows `CancellationException`, which made STOP fail
silently in the first implementation — the coroutine would cancel and the runner
would keep going.

**Rejected.** Cancelling the job only (leaves a pending question hanging); a
`CountDownLatch`-based wait (deadlocks the accessibility looper — see ADR-020).

**Consequences.** Every long-running helper in the engine must be cancellation-
aware, and any `runCatching` around suspending code has to rethrow cancellation.

---

## ADR-019 — Never block the accessibility main thread

**Context.** `AccessibilityService` callbacks run on the main thread, and
`dispatchGesture` delivers its callback to the same looper.

**Decision.** Gesture dispatch uses `suspendCancellableCoroutine` +
`withTimeoutOrNull`. No `CountDownLatch`, no `join`, no blocking wait anywhere on
the service's thread.

**Why.** Waiting on a latch whose completion is posted to the looper you are
blocking is a guaranteed deadlock: the service freezes, the overlay stops
responding, and the user's only option is force-stop.

**Rejected.** `CountDownLatch.await()` (the original implementation, which
deadlocked); posting to a background `HandlerThread` (accessibility APIs are not
thread-safe and node objects do not survive the hop).

**Consequences.** Timeouts must be handled as ordinary outcomes (`TIMEOUT` →
`WAIT_AND_RETRY`), which they are.

---

## ADR-020 — Recycle accessibility nodes aggressively

**Context.** `AccessibilityNodeInfo` objects come from a shared framework pool
(50 nodes on API 26–32). Leaking them degrades *every* accessibility app on the
device.

**Decision.** `NodeTable` recycles the previous generation when a new snapshot is
built; `InteractionMapper` recycles `event.source` in a `finally` block; snapshots
hold immutable `UiNode` data classes, never live node references.

**Why.** The agent is a guest in a system-wide facility. Draining the pool is not
our bug to leave behind.

**Rejected.** Keeping node references for later use (they go stale and can throw);
ignoring recycling because it is deprecated on API 33+ (still required on
`minSdk` 26).

**Consequences.** Snapshots must be self-contained value types, which is also why
`UiNode` is a data class that can be persisted and diffed.

---

## ADR-021 — Minimal, justified dependencies

**Context.** Every dependency is build time, APK size, and a version-coupling risk
on a small machine.

**Decision.** Seven runtime dependencies, all first-party AndroidX/Kotlin:
`kotlinx-coroutines-android`, `core-ktx`, `appcompat`, `recyclerview`,
`lifecycle-runtime-ktx`, `material`, `kotlin-stdlib`. Test-only: `junit`,
`kotlinx-coroutines-test`, and `org.json` (the framework's `org.json` in
`android.jar` is a throwing stub, so JVM tests of the codecs need the reference
implementation; it is not in the APK).

**Why.** Each one removes more code or risk than it adds, and each is documented in
`gradle/libs.versions.toml` next to the list of things deliberately *not* included.

**Rejected.** Room/KSP, Compose, ML Kit, Retrofit/OkHttp, Hilt, Koin, Timber,
Moshi/kotlinx-serialization (see ADR-011 to ADR-013 for the reasoning).

**Consequences.** More hand-written code (codecs, DI, logging) — ~300 lines each —
in exchange for a build that has no annotation processors and no version matrix.

---

## ADR-022 — A static project verifier, because the environment cannot compile

**Context.** The development sandbox for this project has no JDK, no Android SDK
and no network access to Maven. "Build it and see" was not available.

**Decision.** `tools/verify_project.py` statically checks: XML well-formedness,
Kotlin brace/paren balance with a template-aware scanner (Kotlin string templates
contain braces that a regex cannot strip), that every import resolves to a real
file or a known generated package, that pure packages contain no Android imports,
that manifest components exist, that `R.*` references resolve, that XML `@`-refs
resolve, that every viewBinding layout/id exists, that layout ids are not
duplicated, and that the wrapper/version catalog are self-consistent.

**Why.** It catches the classes of error that would otherwise only appear at
compile time, and it runs in a quarter of a second over 69 Kotlin and 42 XML files.

**Rejected.** Shipping unverified code and hoping; a regex-only string stripper
(the first attempt produced 339 false positives on templates like
`"${list.count { it.y }}"`).

**Consequences.** It is **not** a compiler: it cannot check types, member names or
overload resolution. The first real `./gradlew build` on a proper machine is still
the authority — see BUILD.md for the exact commands and the expected first-run
output.

---

## Known Android platform limitations (design inputs, not bugs)

| Limitation | Effect on the agent |
|---|---|
| Screenshot capture is rate-limited on API 30+ | Level 1 understanding is on-demand only; never a loop |
| `FLAG_SECURE` windows | Snapshot is empty and `isSecure = true` → refuse and ask (ADR-007) |
| Restricted settings on Android 13+ | The user may have to allow "restricted" access before the service can be enabled; `PermissionStatus` detects and explains it |
| `GLOBAL_ACTION_RECENTS` requires API 28+ | Guarded; below that, Recents is reported as unavailable |
| `ACTION_SET_TEXT` replaces content | `Type(clearFirst = true)` is the honest model; appending is a read-modify-write |
| `dispatchGesture` requires `canPerformGestures()` | Checked before dispatch; otherwise `GESTURE_FAILED` → recovery |
| No key-event injection from an accessibility service | `PressKey` maps to `GLOBAL_ACTION_*` / `performAction` only |
| Clipboard reads restricted on Android 10+ | `Paste` is best-effort and risk-rated MEDIUM |
| `scrollDeltaX/Y` requires API 28+ | Below that, scroll is a gesture |
| `HardwareBuffer.close()` requires API 31+ | Guarded with `SDK_INT >= S`; API-30-only helpers annotated `@RequiresApi(R)` |
| Some apps block injected input | `GESTURE_FAILED` → re-observe once → ask the user (never silently retry) |
| WebView/Flutter/Games with no metadata | Falls to `SEMANTIC`/`APPROX_BOUNDS`/`OCR`, flagged brittle in the Inspector |
