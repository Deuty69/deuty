# Testing

Three layers, in order of how fast they run and how much they cover:

| Layer | What | Count | Runtime | Needs |
|---|---|---|---|---|
| Static verification | `tools/verify_project.py` — structural checks over all sources and resources | 69 Kotlin + 42 XML files | ~0.3 s | Python 3 only |
| JVM unit tests | `app/src/test` — engine, planner, targeting, safety, recovery, recording, persistence | **108** | a few seconds | JDK 17 |
| On-device manual matrix | §6 below — real accessibility service, real gestures, real apps | ~30 checks | ~20 min | A phone |

There are **no instrumented (Espresso/UIAutomator) tests in V1**. That is a
deliberate gap, listed honestly in §7.

---

## 1. Philosophy

**Real objects, not mocks.** The fixtures in `app/src/test/java/com/deuty/agent/
Fixtures.kt` build the *production* `ElementResolver`, `RiskManager`,
`RecoveryManager`, `ExpectationEvaluator`, `ActionExecutor`, `TaskRunner`,
`RuleBasedPlanner`, `WorkflowRecorder`, `JsonFileStore` and repositories. The only
doubles are:

* `ManualClock` — so no test ever sleeps, and time-based budgets are testable;
* `AgentLog(capacity = 300)` — an in-memory ring buffer;
* `RecordingHost` — a `TaskRunnerHost` that records messages, steps and questions,
  and answers every question with a fixed `UserChoice`;
* `SimulatedDevice` — the fake phone (and it is production code in `agent/sandbox`,
  not test scaffolding, because it is also the offline demo mode).

A test that mocks the component it is supposed to verify is worse than no test: it
passes forever and tells you nothing. So the rule here is *mock the device, never
the brain*.

**Test the invariant, not the implementation.** Every test name states a promise
(`passwordFieldsAreNeverTargetedByDefault`, `stopWinsBeforeAnyActionRuns`,
`aBudgetOrCancellationFailureGivesUpWithoutRetrying`). If a refactor breaks a test,
the test should say which guarantee was lost, not which line moved.

**Assertions carry messages.** `assertTrue("two identical labels must be flagged", …)`
— because the failure text is the only thing you see in CI.

---

## 2. Running

```bash
./gradlew testDebugUnitTest                              # all 108
./gradlew testDebugUnitTest --tests "*SandboxPipelineTest*"
./gradlew testDebugUnitTest --tests "*recording*"
python3 tools/verify_project.py                          # static, no JDK needed
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`.

Conventions when adding a test:

* No `Thread.sleep`, ever. Advance `ManualClock`.
* No Android imports (`android.*`, `androidx.*`). If a test needs one, the code
  under test is in the wrong package — see ARCHITECTURE §2.1.
* Use the fixture builders (`uiNode`, `screenOf`, `taskOf`, `execCtx`, `tempDir`)
  instead of hand-rolling models; they keep default values consistent.
* `tempDir()` marks directories `deleteOnExit()` — never write into the repo.
* Suspend code runs under `kotlinx.coroutines.test.runTest`.

---

## 3. What each suite defends

### `SandboxPipelineTest` — 10 tests · end-to-end through the whole engine

Runs natural-language requests against `SimulatedDevice` through
planner → runner → resolver → executor → verification → recovery → report.

| Test | Guarantee |
|---|---|
| `plansAndRunsTheMvpCommand` | "Open Settings" plans, executes, verifies and completes — the MVP acceptance criterion, as a test |
| `neverTouchesASecureScreen` | A `FLAG_SECURE` banking screen is refused; no action is dispatched at all |
| `renameFlowIsVerifiedAgainstTheNewName` | Postconditions check the *new* state, not just "something changed" |
| `moveFlowWalksFoldersAndVerifiesTheDestination` | Multi-step navigation verifies at each level, including the destination |
| `deadElementFailsInsteadOfLoopingForever` | A node that stops matching fails within its retry budget |
| `missingAppIsReportedNotCrashed` | An uninstalled app produces `APP_NOT_FOUND` and a message, not an exception |
| `actionBudgetStopsARunawayTask` | `TaskBudget.maxActions` ends the task with a reason |
| `stopWinsBeforeAnyActionRuns` | STOP is checked before dispatch — the single most important behaviour in the app |
| `readScreenIsAnObservationNotAnAction` | `observesOnly` actions do not wait for a screen change or count as interactions |
| `sandboxDemoDrivesTheWholePipeline` | The shipped demo path stays honest |

### `TargetingTest` — 11 tests · the resolver and the tier ladder

`resourceIdBeatsTextWhenBothArePresent` · `exactTextBeatsPartialText` ·
`passwordFieldsAreNeverTargetedByDefault` · `disabledNodesAreSkipped` ·
`coordinatesAreTheLastResortAndSaySo` · `aTextQueryNeverFallsBackToCoordinatesWhenANodeMatches` ·
`identicalLabelsAreReportedAsAmbiguous` · `recordedQueriesKeepSemanticsAndDropCoordinates` ·
`textExpectationsEvaluateAgainstTheSnapshot` · `screenChangedNeedsADifferentSignature` ·
`signaturesAreStableButSensitiveToContent`.

Two details worth knowing: ambiguity requires **more than two** equally-top-scoring
matches (a lone duplicate is not a choice the user has to make), and screen
signatures must be stable for identical trees but change when content changes —
that is what "did the screen change?" is built on.

### `SafetyTest` — 19 tests · risk, refusal, redaction, diagnostics

Risk rating and gating (`paymentTargetsAreHighRiskAndGated`,
`sendTargetsAreMediumRiskAndGated`, `plainNavigationIsLowRiskAndNotGated`,
`riskNeverDropsBelowTheActionFloor`, `bankingPackagesEscalateToHigh`,
`typingAnOtpIsHighRisk`, `screenshotsAreTreatedAsPrivacySensitive`,
`aPaymentScreenEscalatesEvenAnOrdinaryTap`, `aTaskCeilingRefusesAnythingAboveIt`,
`theConfirmationThresholdIsUserConfigurable`); hard refusals
(`secureScreensAreRefusedOutright`, `systemPermissionDialogsAreNeverAnswered`);
redaction (`secretsAreMaskedBeforeTheyCanBeStored`,
`ordinaryTextSurvivesRedaction`, `redactionCanBeSwitchedOffForLocalDebugging`,
`sensitiveFieldLabelsAreRecognised`); and `ScreenDiagnostics`
(`diagnosticsSpotSecureScreens`, `diagnosticsSpotPermissionDialogs`,
`anOrdinaryScreenIsNotABlocker`).

`ordinaryTextSurvivesRedaction` matters as much as the masking tests: an agent that
redacts everything is unusable in the chat, and the failure is invisible until a
user complains that the agent "won't say what it did".

### `RuleBasedPlannerTest` — 17 tests · the offline brain

Grammar coverage (open/tap/type-into/quoted text/compound instructions/sequencing/
move/scroll with direction and count/navigation/wait/read/search "X in Y"), plus
the discipline tests:

* `controlWordsNeverBecomeTasks` — "stop", "pause", "cancel" are commands, not goals.
* `anOrdinarySentenceStartingWithAVerbIsNotAWorkflowRequest` — regression for a real
  bug where "start the timer" was intercepted as *run workflow "timer"*.
* `anExplicitWorkflowRequestIsRejectedWithoutStorage` — no workflow storage bound →
  a clean `Rejected`, not a crash.
* `nonsenseIsNotGuessedAt` / `emptyInputIsNotGuessedAt` — the planner must return
  `NotCapable` rather than invent a task. Guessing is the worst failure mode of a
  rule-based planner: it looks like competence right up until it taps "Delete".
* `plansAreTypedStepsFromTheUserTextSource` — plan provenance is preserved for the UI.

### `RecoveryTest` — 13 tests · the recovery ladder

`elementNotFoundEscalatesInAFixedOrder` (re-observe → scroll+relax → switch
locator → ask) · `relaxedQueriesDropCoordinatesAndKeepSemantics` ·
`anAmbiguousMatchGoesStraightToTheUser` · `aPostconditionFailureRetriesTwiceThenAsks` ·
`aBudgetOrCancellationFailureGivesUpWithoutRetrying` ·
`aBlockedScreenAsksAndStopsOnDeny` · `aCrashedAppIsRelaunchedOnce` ·
`aPermissionDialogOnScreenBeatsTheGenericFailureReason` ·
`aNetworkErrorWaitsAndRetriesWithinABound` · `aRejectedGestureIsNotReAttemptedForever` ·
`failedTextInputTapsTheFieldAgain` · `anUnreadableScreenEscalatesOnlyIfCaptureIsAllowed` ·
`everyPlanCarriesAMessageForTheDebugPanel`.

The invariant: **recovery is never more dangerous than the step it recovers**, and
a budget/cancellation failure can never be retried.

### `WorkflowRecorderTest` — 12 tests · semantic recording

Keystroke collapse · password text never recorded · same-direction scroll merging ·
direction change starts a new step · package change becomes `LaunchApp` · our own UI
ignored (STOP is not a step) · targets are semantic, not coordinates · an unlabelled
node keeps bounds as a last resort · focus-tap merged into the following `Type` ·
live preview · cancel discards everything · interactions before `start()` ignored.

### `PersistenceTest` — 11 tests · round-trips and graceful degradation

Every action type survives a JSON round-trip (this is the test that catches codec
drift when a new action is added) · unknown action types decode to `null` instead of
crashing · a full task round-trips including budget, context, step statuses and
attempts · the running task survives process death and comes back **PAUSED with a
reason** · a corrupt primary file falls back to the `.bak` · history is bounded ·
saving the transcript replaces it rather than duplicating it · chat messages
round-trip · workflows round-trip through the repository (including
`findByName` fuzzy match, `instantiate` producing fresh PENDING steps, `recordRun`
counters and delete) · a screen dump can be loaded back for offline replay · the
store reports what it is holding.

### `CoreUtilTest` — 15 tests · loop detection and text matching

Loop detection: a repeated `(signature, action)` pair is a loop, but the same action
across *progressing* screens is not (paging a list), no-progress-with-differing-
actions is flagged, progress resets the counter, `reset()` clears everything, and the
window is bounded so history cannot grow. Text matching: normalisation (case,
whitespace, unicode dashes/quotes, zero-width characters), loose equality, null-safe
contains, similarity ordering (exact > partial > unrelated), no NaN on empty input,
bounded Levenshtein, OTP-shaped detection, tokenisation.

---

## 4. `SimulatedDevice`: the fake phone

`agent/sandbox/SimulatedDevice.kt` (~720 lines) implements all four ports in pure
Kotlin. 23 screens including: system settings and its sub-screens, a browser, a file
manager with nested folders, a chat app, a search field, a scrollable/paging list, a
rename dialog, a banking screen with `isSecure = true`, a system permission dialog,
an OTP field, and a "missing app" case.

Behaviours that make it a useful adversary rather than a rubber stamp:

* typed text is stored per field and reflected in the next snapshot, so
  `FieldContains` postconditions actually verify something;
* `Back` walks a real history stack;
* lists page, so scrolling changes the signature;
* one screen removes an element after it is tapped (the "dead element" case);
* the secure screen returns an empty node list with `isSecure = true`;
* node metadata can be sparse, forcing the resolver down its tier ladder.

`node()` in its companion object is public so tests can build custom worlds, and
`SandboxDemo` drives the whole pipeline from a request string — which is both a demo
feature and an integration test.

**Limitation:** the simulator models *our* understanding of Android, not Android.
It cannot catch OEM gesture rejection, `FLAG_SECURE` capture behaviour, real
accessibility event ordering, or an app whose tree is a single opaque WebView.
That is what §6 is for.

---

## 5. The static verifier

`tools/verify_project.py` exists because the project was authored in a sandbox with
no JDK, no Android SDK and no Maven access. It is **not a compiler**.

Checks: XML well-formedness · Kotlin brace/paren/bracket balance using a
template-aware scanner (Kotlin string templates contain braces a regex cannot strip
— the first regex-only version produced 339 false positives) · every import
resolves to a real file or a known generated package (extension-function aware) ·
**no Android imports in pure packages** · every manifest component class exists ·
every `R.*` reference resolves · every XML `@drawable/@string/@color/@dimen/@style/
@layout/@id` reference resolves · every viewBinding layout and id used from Kotlin
exists · no duplicate ids within a layout · wrapper/version-catalog consistency.

What it cannot catch: types, member names, overload resolution, nullability,
exhaustive `when`, resource *value* errors, or anything semantic. Those are found by
`./gradlew build` and by the unit tests.

---

## 6. On-device manual matrix

Run after every change that touches `platform/`, `ui/`, the manifest, or resources.

**Setup & permissions**
1. Fresh install → both permission prompts are reachable from `MainActivity`.
2. Accessibility toggle enables the service; status flips to *on* without a restart.
3. Android 13+: "Restricted setting" path works (App info → ⋮ → Allow).
4. Overlay permission granted → capsule appears within ~1 s.
5. Toggling the service **off mid-task** does not crash: the task parks and the UI
   says the agent cannot act.
6. Reboot → service state and permissions are as the OS dictates; the app reports
   the truth, not a stale "on".

**MVP flow**
7. `Open Settings` → Settings foregrounds, steps show progress, completion reported.
8. `what do you see` → a description with no interaction.
9. `tap Wi-Fi` / `scroll down` / `go back` / `open home` each verified.
10. `type hello into search` in a real app → text appears, keyboard behaves.
11. A long task + capsule **STOP** → stops before the next action; state → CANCELLED.
12. Killing the app mid-task (`adb shell am force-stop`) → reopening shows the task
    PAUSED with "interrupted by process restart" and offers resume.

**Safety**
13. A step whose target label contains "Pay"/"Send"/"Delete" → confirmation gate
    appears; denying skips or stops as configured; approving does **not** re-ask on
    retry.
14. A system permission dialog → the agent refuses and asks; it never taps Allow.
15. A banking/`FLAG_SECURE` screen → refused, nothing dispatched.
16. Typing into a password field is refused by default and never recorded.
17. Inspector copy-to-clipboard is redacted; the chat never shows an OTP.

**Workflows**
18. Record 5–8 interactions across two apps → steps are semantic (labels/ids).
19. Replay → succeeds with the apps in a different starting state.
20. Move a button/reorder a list in the target app (or rotate/re-theme) → replay
    still resolves, or reports ambiguity rather than tapping the wrong thing.
21. Deleting a workflow removes it from disk (reinstall-free check: clear app data).

**Resource behaviour**
22. Idle for 10 minutes with the service on → no captures, no wakeups
    (`adb shell dumpsys cpuinfo`, Battery Historian).
23. `dumpsys meminfo com.deuty.agent.debug` while idle and mid-task → native/view
    counts stable; no growth across 20 tasks (node recycling, ADR-020).
24. A very long list (>1000 rows) → snapshots stay capped, `truncated` shows in the
    Inspector, no ANR.
25. Airplane mode → everything still works (no `INTERNET` permission is declared).

**Diagnostics**
26. Inspector shows package, window, node count, last action + outcome, state,
    retries, and a tree dump that matches the screen.
27. Logs (`adb logcat -s deuty`) contain no passwords, OTPs or card numbers.

---

## 7. Known gaps

Stated plainly, because an unlisted gap is a landmine:

* **No instrumented UI tests.** `MainActivity`/overlay behaviour is verified by hand
  (§6). Espresso on a service-hosted overlay window is genuinely awkward, and V1
  spent the budget on engine coverage instead.
* **No real-device CI.** Everything automated runs on a JVM. Gesture dispatch,
  node recycling under framework pressure, and OEM quirks are only covered manually.
* **The simulator is a model of Android, not Android.** Behaviours it does not
  implement cannot be tested by it.
* **OCR/vision paths are tested only through `NoOcrEngine`.** A real OCR binding
  needs its own tests plus the on-device matrix.
* **Model planners are stubs.** When they are implemented, they need contract tests
  (a planner must never emit a coordinate-only query, must respect `maxSteps`, and
  must return `NotCapable` rather than guess) — the seam is `Planner`/`PlanResult`.
* **Concurrency stress is not tested.** One task at a time by design; two tasks
  racing is prevented by `EngineControl`, not exercised by a test.
* **No performance benchmarks.** Budgets and caps are reasoned about and asserted
  structurally, not measured. Measure with §6 items 22–24 on the target device.

---

## 8. Bugs that became tests

Every one of these was real, found during development, fixed, and then pinned by a
test so it cannot come back:

| Bug | Fix | Test |
|---|---|---|
| Approval loop: after the user approved a risky step, `RETRY_STEP` hit the same gate forever | Per-run `clearedSteps` set (never persisted) | `SafetyTest`, `SandboxPipelineTest` |
| `ReadScreen`/`Report` blocked for their full timeout waiting for a screen change that cannot happen | `observesOnly` on the action, checked before the change-wait | `readScreenIsAnObservationNotAnAction` |
| "start the timer" was intercepted as *run workflow "timer"* | Workflow interception requires an explicit keyword or a saved-name match | `anOrdinarySentenceStartingWithAVerbIsNotAWorkflowRequest` |
| STOP silently did nothing: `runCatching` swallowed `CancellationException` | The main-thread hop rethrows cancellation explicitly | `stopWinsBeforeAnyActionRuns` |
| Gesture dispatch deadlocked the accessibility looper (`CountDownLatch` awaiting a callback posted to the same thread) | `suspendCancellableCoroutine` + `withTimeoutOrNull` | ADR-019; manual §6 item 11 |
| Re-saving the chat transcript appended the whole in-memory list, duplicating it on every debounce | `TaskRepository.saveMessages` (replace) alongside `appendMessages` | `savingTheTranscriptReplacesItRatherThanDuplicatingIt` |
| A lone duplicate label was declared "ambiguous", blocking simple taps | Ambiguity requires more than two tied matches | `identicalLabelsAreReportedAsAmbiguous` |
| The simulator filtered a just-moved item out of its destination screen, so the verify step could never pass | Filter base nodes, then append the moved item to the destination | `moveFlowWalksFoldersAndVerifiesTheDestination` |
| A leaked `event.source` node drained the framework's shared 50-node pool | Recycle in a `finally` block; `NodeTable` recycles the previous generation | ADR-020; manual §6 item 23 |
