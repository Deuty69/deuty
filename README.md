# Deuty Agent

A computer-use agent for Android. You tell it what you want in plain language
("open Settings and turn on Wi-Fi", "rename timetable.pdf to College Timetable",
"move that file into College/Assignments"); it looks at the screen through the
accessibility tree, works out a plan, taps/types/scrolls like a user would, checks
that each action actually worked, recovers when it doesn't, asks you when it
shouldn't decide alone, and reports what it did.

It operates **only through the normal Android UI**. No app-specific APIs or SDKs,
no MCP servers, no root, no `adb`. One implementation, every app.

> Status: **V1 / MVP, feature-complete in source, not yet compiled on a real
> toolchain.** It was authored in a sandbox with no JDK, no Android SDK and no
> network access to Maven. Read [docs/BUILD.md](docs/BUILD.md) before your first
> build, and expect to fix small compile errors. 108 JVM unit tests and a static
> verifier cover as much as could be checked without a compiler.

---

## The design in one sentence

**AI decides WHAT. A deterministic Android engine decides HOW.**

Planners emit typed steps with declarative targets and declarative expectations.
Everything below that line — resolving targets, dispatching gestures, verifying
postconditions, retrying, recovering, enforcing budgets, gating risk — is
deterministic Kotlin that never consults a model. Consequences: the app is fully
functional offline with no model bound at all, and the engine is testable on a JVM.

```
user text → planner (WHAT)  →  TaskRunner → ElementResolver → ActionExecutor  →  ports
                                                                      ↓
              report ← chat/overlay UI ← projections ← verify ← screen snapshot
```

## What works today

* **Enable the accessibility service → floating capsule → "Open Settings"** — the
  MVP acceptance path, end to end.
* Observe and report the current screen without touching anything.
* Tap · long-press · double-tap · type · swipe · scroll (with direction/count) ·
  drag · back · home · recents · notifications · wait · read screen · find element ·
  report — 20 action types, each verified with precondition → action →
  postcondition → timeout → bounded retry.
* **Targeting by structure, not coordinates**: `resource-id` → exact text →
  content description → partial text → semantic properties → relative position →
  approximate bounds → OCR → coordinates (last resort, flagged brittle).
* **Task state machine** (11 states, whitelisted transitions) persisted after every
  change, so a task survives process death and comes back paused with a reason.
* **Error recovery as a ladder**: re-observe → scroll and relax the locator → switch
  locator → dismiss by back → relaunch → wait → escalate to vision → ask you →
  give up. Screen blockers (permission dialogs, payment sheets, CAPTCHAs, login,
  crash dialogs, update prompts, cookie banners) are diagnosed and handled.
* **Loop detection and hard budgets**: no infinite automation, ever. Paging a list
  is not a loop; being stuck between two screens is.
* **Risk gating**: LOW/MEDIUM/HIGH computed from the action *and* the live screen
  and label text. Payment, transfers, account deletion, OTP entry and banking apps
  escalate. Confirmation is required at or above your threshold.
* **Things it refuses, not asks about**: `FLAG_SECURE` screens and Android system
  permission dialogs. Consent comes from a human.
* **Workflow recording**: semantic, not a coordinate macro. Keystrokes collapse into
  one `Type`, scrolls merge, a package change becomes an explicit launch, password
  text is never captured. Save, list, replay, delete.
* **Agent chat UI**, a Dynamic-Island-style floating capsule with a real STOP
  button, and a debug **Inspector** (package, node table, last action + outcome,
  state, retries, tree dump — clipboard copies redacted).
* **Sandbox demo**: a 23-screen simulated phone in pure Kotlin implements the same
  ports, so the entire pipeline runs with no device and no emulator.
* **Privacy**: local-only, no telemetry, **no `INTERNET` permission declared**,
  `allowBackup = false`, redaction of OTPs/passwords/sensitive labels before
  anything is logged or stored.

## What is deliberately not done yet

* Model planners are stubs behind a working interface (`LocalModelPlanner`,
  `RemotePlanner`); the V1 brain is a ~20-pattern `RuleBasedPlanner`.
* OCR binds to `NoOcrEngine` by default; the seam exists, no engine is bundled.
* No foreground service: tasks run while the service is bound, and an interrupted
  task resumes rather than continuing in the background.
* R8/minification is off on purpose (see `app/build.gradle.kts` and
  [docs/BUILD.md](docs/BUILD.md) §8).
* No instrumented UI tests; the on-device matrix in
  [docs/TESTING.md](docs/TESTING.md) §6 is manual.

## Quick start

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or let Android Studio write it
python3 tools/verify_project.py                       # static check, no JDK needed
./gradlew assembleDebug testDebugUnitTest             # JDK 17, SDK 36, Gradle 8.13 wrapper
./gradlew installDebug
```

Then: enable the accessibility service (Android 13+: *Allow restricted settings*),
grant "Display over other apps", and type **`Open Settings`**.

Full instructions, expected output and a troubleshooting table:
[docs/BUILD.md](docs/BUILD.md).

## Documentation

| Doc | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, ports, screen-understanding ladder, targeting tiers, action algebra, verification, state machine, safety, planning, recording, recovery, threading, persistence, UI, extension points for V2–V9 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 22 ADRs with rejected alternatives and consequences, plus the Android platform limitations that shaped the design |
| [docs/BUILD.md](docs/BUILD.md) | Toolchain, build/install/sign, first-run checklist, troubleshooting |
| [docs/TESTING.md](docs/TESTING.md) | Test philosophy, suite-by-suite coverage, the simulated device, the static verifier, the on-device manual matrix, known gaps, bugs that became tests |

## Layout

```
app/src/main/java/com/deuty/agent/
├── core/{model,util,json}   pure data, algorithms, codecs      — no Android imports
├── agent/                   engine: runner, resolver, risk, recovery, diagnostics
│   ├── executor/            20 actions, executor, expectation evaluator
│   ├── planner/             control grammar, rule planner, provider, model stubs
│   ├── port/                the ONLY boundary to the device (4 interfaces)
│   └── sandbox/             SimulatedDevice (23 screens) + demo
├── workflow/                semantic recorder + repository
├── storage/                 atomic JSON store + task repository
├── platform/                accessibility service, gestures, overlay, app directory
├── vision/                  OCR adapter (NoOcrEngine by default)
├── ui/                      chat, workflows, settings, inspector, overlay UI
└── di/                      AgentContainer — hand-written composition root
```

69 Kotlin files (~14.0k lines), 42 XML resources, 108 JVM unit tests.
The JVM-purity rule (no Android imports in `core/`, `agent/`, `workflow/`,
`storage/`) is enforced statically by `tools/verify_project.py` — it is what makes
the engine testable without an emulator.

## Dependencies

Seven runtime libraries, all first-party AndroidX/Kotlin (coroutines, core-ktx,
appcompat, recyclerview, lifecycle-runtime, material, stdlib). Test-only: JUnit,
coroutines-test, and `org.json` (the framework's copy in `android.jar` is a throwing
stub). No Room, no Hilt, no Compose, no Retrofit, no ML Kit — each with a written
justification in `gradle/libs.versions.toml` and [docs/DECISIONS.md](docs/DECISIONS.md).
