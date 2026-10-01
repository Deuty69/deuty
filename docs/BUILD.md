# Building, installing and running Deuty Agent

Everything here is written for a normal development machine with internet access.
The project was authored in a sandbox with no JDK and no Android SDK, so the static
verifier in `tools/` is the only check that has been run over the sources — **your
first `./gradlew build` is the authority**, and the troubleshooting table at the end
covers what to do if it disagrees.

---

## 1. What you need

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** (exactly) | AGP 8.13 requires JDK 17. JDK 21 works for Gradle but AGP 8.13 is tested against 17; JDK 11 or 8 will fail immediately. |
| Android SDK Platform | **36** | `compileSdk = 36`, `targetSdk = 36`. |
| Android SDK Build-Tools | 36.x | Installed automatically by Android Studio; with `sdkmanager` install `platforms;android-36` and `build-tools;36.0.0`. |
| Gradle | **8.13** (via wrapper) | Do not install Gradle yourself; `./gradlew` downloads 8.13 into `~/.gradle/wrapper/dists`. |
| Kotlin | 2.2.20 | Comes from the plugin, no separate install. |
| Disk | ~3 GB free | Gradle distribution (~130 MB) + AGP/Kotlin/AndroidX caches + build outputs. |
| RAM | 4 GB for the build | `org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m`. |
| Device | Android **8.0 (API 26)** or newer | `minSdk = 26`. An emulator works for most things; see §7 for what needs a real device. |

No annotation processors, no NDK, no CMake, no Compose compiler, no KSP. The build
is a single module (`:app`) with two plugins.

---

## 2. First build from the command line

```bash
cd deuty

# 1. Point Gradle at your SDK (skip this if you use Android Studio, which sets it)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties      # Linux
# echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
# echo "sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk" > local.properties  # Windows

# 2. Sanity-check the sources without a JDK/SDK (optional, instant)
python3 tools/verify_project.py

# 3. Compile + unit tests
./gradlew clean assembleDebug testDebugUnitTest --stacktrace

# 4. Install on the connected device/emulator
./gradlew installDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`.

### Expected output

```
> Task :app:generateDebugResValues
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
> Task :app:assembleDebug
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 2m 30s
```

* **First run**: 3–8 minutes. Gradle downloads the distribution, AGP 8.13.2,
  Kotlin 2.2.20 and the AndroidX artifacts (~600 MB into `~/.gradle/caches`).
* **Later runs**: 10–30 s incremental; `testDebugUnitTest` alone is a few seconds.
* Warnings are expected and benign: unused-parameter warnings in stub planners, and
  deprecation warnings for `AccessibilityNodeInfo.recycle()` (still required at
  `minSdk` 26 — see DECISIONS ADR-020).
* **`BUILD SUCCESSFUL` with 108 tests passing** is the goal. Test counts per class
  are listed in [TESTING.md](TESTING.md).

APK output: `app/build/outputs/apk/debug/app-debug.apk`
(applicationId `com.deuty.agent.debug`, versionName `0.1.0-mvp-debug`).

---

## 3. Android Studio

1. **File → Open** → select the `deuty` directory (the one containing
   `settings.gradle.kts`). Do not import `app/` directly.
2. Let it sync. If it offers to install missing SDK packages, accept.
3. **File → Project Structure → SDK Location**: confirm JDK 17 and the SDK path.
   Studio writes `local.properties` for you.
4. Run configuration `app` → pick a device → Run.

If the sync fails with *"The project is using an incompatible version of AGP"*,
your Studio is older than AGP 8.13.2 — update Studio, or temporarily lower
`agp` in `gradle/libs.versions.toml` to the version your Studio ships with
(8.7+ will work; below that, `compileSdk = 36` will also need to drop).

---

## 4. Version stack and why

| Piece | Version | Why this one |
|---|---|---|
| Gradle | 8.13 | Minimum for AGP 8.13; pinned by the wrapper so every machine builds identically. |
| AGP | 8.13.2 | Latest 8.x line: supports `compileSdk` 36 and JDK 17. AGP 9.x was **not** used — it requires Gradle 9.1+ and has breaking DSL changes (built-in Kotlin support), which is churn without benefit here. |
| Kotlin | 2.2.20 | K2 compiler, compatible with AGP 8.13.2. |
| `compileSdk` / `targetSdk` | 36 | Required to call the API 30+ screenshot and API 33+ accessibility methods with correct annotations. |
| `minSdk` | 26 | Android 8.0. Below that, `TYPE_APPLICATION_OVERLAY` and several `AccessibilityNodeInfo` actions do not exist, and the agent would need a second code path nobody can test. |
| Coroutines | 1.9.0 | Structured concurrency for the step loop; matches `kotlinx-coroutines-test` used by the unit tests. |

Full dependency list with per-library justification: `gradle/libs.versions.toml`
(the header comment lists what is deliberately **not** included, and
[DECISIONS.md](DECISIONS.md) ADR-021 explains why).

---

## 5. Running the tests

```bash
./gradlew testDebugUnitTest                       # all 108 JVM tests
./gradlew testDebugUnitTest --tests "*TargetingTest*"   # one class
./gradlew testDebugUnitTest --tests "*recording*"       # by name pattern
```

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`.

These are **JVM tests**: no emulator, no device, no `INTERNET`. They work because
`core/`, `agent/`, `workflow/` and `storage/` contain zero Android imports
(enforced by `tools/verify_project.py`). `testOptions.unitTests.isReturnDefaultValues
= true` is set so any accidental framework call returns a default instead of
throwing — if you see a test pass that should have failed, that setting is why.

The static verifier is a separate, faster check that does not need a JDK:

```bash
python3 tools/verify_project.py     # ~0.3 s, 69 Kotlin + 42 XML files
```

---

## 6. First run on a device: the MVP checklist

The app does nothing until two permissions are granted. `MainActivity` shows the
status of both and deep-links to the right screen.

1. **Install and open** Deuty Agent.
2. **Enable the accessibility service**
   * Settings → Accessibility → *Downloaded services* / *Installed services* →
     **Deuty Agent** → toggle on → accept the system warning.
   * **Android 13+**: if the toggle is greyed out or you see *"Restricted setting"*,
     go to App info → ⋮ menu → **Allow restricted settings**, then retry. This is
     an OS-level anti-sideloading measure, not a bug in the app.
3. **Grant "Display over other apps"** (`SYSTEM_ALERT_WINDOW`) — the floating
   capsule. This is the STOP control, so it is treated as mandatory.
4. **Sanity check**: the capsule appears; the app's home screen shows
   *Accessibility: on* and *Overlay: on*.
5. **The MVP acceptance test**: type **`Open Settings`** in the chat and send.
   Expected: the plan appears as steps, the Settings app comes to the foreground,
   and the agent reports what it did.
6. **Observe/report**: type `what do you see` — the agent describes the current
   screen (package, window title, notable elements) without touching anything.
7. **Basic interaction**: `tap Wi-Fi`, `scroll down`, `go back`, `open home`.
8. **Record a workflow**: Workflows → *Record* → perform a few taps → *Stop* →
   name it → save. Then replay it and confirm the steps are semantic
   (labels/ids, not coordinates) in the Inspector.
9. **STOP**: start a long task (`scroll down 20 times`) and hit **STOP** on the
   capsule. It must stop before the next action, and the capsule must say so.
10. **Sandbox demo** (no device state required): Settings → *Run sandbox demo*
    drives the entire pipeline against `SimulatedDevice` — useful for verifying a
    build on an emulator where no real apps are installed.
11. **Inspector**: open it during/after a task to see package, node table, last
    action + outcome, task state, retries and a tree dump.

---

## 7. What needs a real device

An emulator is fine for the UI, the pipeline and the sandbox demo. Use a physical
device to validate:

* **Gestures in third-party apps** — some OEM builds and some apps reject injected
  input; the agent reports `GESTURE_FAILED` and asks, but you need the real thing
  to see it.
* **`FLAG_SECURE` screens** (banking, OTP) — emulator behaviour varies.
* **Screenshot rate limiting** (API 30+) and `canTakeScreenshot` availability.
* **OEM accessibility quirks** — MIUI/ColorOS/One UI kill background services and
  sometimes reset accessibility permissions after an update.
* **Battery/RAM behaviour** — the whole point of the event-driven design; measure
  with `adb shell dumpsys meminfo com.deuty.agent.debug` and Battery Historian.

---

## 8. Release build and signing

R8/shrinking is **off** in V1 on purpose (see `app/build.gradle.kts`): the agent's
job is calling framework accessibility APIs and being reachable from the system via
a manifest-declared service, and a bad keep rule fails at *runtime on a device*,
not at build time. Turn it on only after the on-device matrix in TESTING.md is
green.

```bash
# Unsigned release (checks that the release variant compiles)
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

To sign locally, create `keystore.properties` in the repo root (**git-ignored**,
never commit it):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`app/build.gradle.kts` picks it up automatically and defines the `local` signing
config; `assembleRelease` then produces a signed APK. Without that file, release
builds stay unsigned and debug builds use the standard debug keystore.

---

## 9. Repo layout

```
deuty/
├── app/
│   ├── build.gradle.kts          module config, deps, build types
│   ├── proguard-rules.pro        keep rules (unused while R8 is off)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/deuty/agent/     69 Kotlin files
│       │   │   ├── core/{model,util,json}  pure data, helpers, codecs
│       │   │   ├── agent/                  engine + orchestration
│       │   │   │   ├── executor/           actions, executor, expectations
│       │   │   │   ├── planner/            rule planner, provider, stubs
│       │   │   │   ├── port/               the only boundary to the device
│       │   │   │   └── sandbox/            SimulatedDevice + demo
│       │   │   ├── workflow/               recorder + repository
│       │   │   ├── storage/                atomic JSON store + repositories
│       │   │   ├── platform/               accessibility service, overlay, apps
│       │   │   ├── vision/                 OCR adapter (NoOcrEngine by default)
│       │   │   ├── ui/                     4 activities, adapters, overlay UI
│       │   │   └── di/                     AgentContainer (composition root)
│       │   └── res/                        7 layouts, 24 drawables, xml/, values/
│       └── test/java/com/deuty/agent/      108 JVM unit tests + fixtures
├── docs/                         ARCHITECTURE · DECISIONS · BUILD · TESTING
├── tools/verify_project.py       static project verifier (no JDK needed)
├── gradle/libs.versions.toml     version catalog + dependency policy
└── gradlew · gradlew.bat · gradle/wrapper/
```

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Could not determine java version from '21.0.x'` / `Unsupported class file major version` | Gradle 8.13 running on a too-new JDK, or AGP on a too-old one | Use JDK 17: `export JAVA_HOME=/path/to/jdk17`, or set *Gradle JDK* in Studio. |
| `SDK location not found` | Missing `local.properties` / `ANDROID_HOME` | Create `local.properties` with `sdk.dir=...` (§2). |
| `Failed to install the following SDK components: platforms;android-36` | SDK 36 not installed | `sdkmanager "platforms;android-36" "build-tools;36.0.0"` or Studio → SDK Manager. |
| `Could not resolve com.android.tools.build:gradle:8.13.2` | No network, or a corporate proxy/mirror | Check connectivity to `dl.google.com` and `repo.maven.apache.org`; `settings.gradle.kts` uses `google()` + `mavenCentral()` with `FAIL_ON_PROJECT_REPOS`. |
| Wrapper download stalls | `services.gradle.org` blocked | Download `gradle-8.13-bin.zip` manually into `~/.gradle/wrapper/dists/gradle-8.13-bin/<hash>/`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A differently-signed build of the same id is installed | `adb uninstall com.deuty.agent.debug` then reinstall. |
| Accessibility toggle greyed / "Restricted setting" (Android 13+) | Sideloading protection | App info → ⋮ → **Allow restricted settings** → retry. |
| Service turns itself off after a reboot or OS update | OEM battery/accessibility policy | Re-enable; on MIUI/ColorOS also disable battery optimisation for the app and enable "Autostart". |
| Agent says *"I cannot act right now"* | The service is not bound (toggled off, or the process was restarted) | Re-enable the service; the app shows the status. |
| Taps land in the wrong place | An app that rescales or offsets its window (rare OEM behaviour) | Open the Inspector, compare node bounds with what is on screen, and file it — `APPROX_BOUNDS`/`COORDINATES` tiers log a warning when used. |
| `dispatchGesture` always fails | `canPerformGestures()` false — the service config was not applied | Reinstall; confirm `res/xml/agent_accessibility_service.xml` has `canPerformGestures="true"`. |
| Overlay does not appear | `SYSTEM_ALERT_WINDOW` not granted | Settings → Apps → Deuty Agent → *Display over other apps*. |
| Unit tests fail with `Method ... not mocked` | A framework class leaked into a pure package | Run `python3 tools/verify_project.py` — it reports Android imports in `core/`, `agent/`, `workflow/`, `storage/`. |
| Build is slow / OOM | Default heap too small for your machine | Raise `org.gradle.jvmargs` in `gradle.properties`; `org.gradle.parallel` and `caching` are already on. Configuration cache is deliberately off. |

---

## 11. Clean-slate commands

```bash
./gradlew clean                     # build outputs
rm -rf ~/.gradle/caches/build-cache-1   # shared build cache (safe)
adb uninstall com.deuty.agent.debug
adb shell pm clear com.deuty.agent.debug   # wipes tasks, workflows, dumps, prefs
python3 tools/verify_project.py     # static re-check, no toolchain required
```
