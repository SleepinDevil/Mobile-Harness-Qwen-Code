# Repository Guidelines

## Project Overview

**Mobile Harness** (applicationId `com.jarves.mh`, in-app product name "PocketDev") is a complete autonomous AI development workspace for Android. It runs coding-agent CLIs — Claude Code, `dsh` (DeepSeek Harness), `agy` (Google Antigravity), `qwen` (Qwen Code) — inside a userspace **PRoot** Ubuntu 20.04 arm64 guest built entirely on-device (no root). Chat with agents, edit projects, run real Linux commands in a terminal, preview live dev servers, and build/install Android apps on the phone.

Hard platform constraints: **arm64-v8a only** (CMake hard-fails otherwise), **Android 9+ (API 28)**, PRoot is isolation-not-security (see README security notice). MIT licensed.

## Architecture & Data Flow

Single Gradle module `:app`. 100% Jetpack Compose UI (only non-Compose view: preview `WebView`). No Navigation library, no DI framework.

Flow: **Compose UI → `MainViewModel` → `RuntimeBridge` → `RuntimeInstaller.process()` → `NativeSpawnProcess` → JNI `libpocketspawn` (`fork`/`execvpe` of `libproot.so`) → guest agent process.**

- **UI**: `ui/PocketDevApp.kt` collects `viewModel.state` via `collectAsStateWithLifecycle()`. Navigation is two private enums switched in composables: `RootScreen { PROJECTS, AGENT, SETTINGS }`, `WorkspaceTab { CHAT, FILES, TERMINAL, CHANGES, PREVIEW }`.
- **State**: one immutable `AppUiState` data class behind `MutableStateFlow`, updated only via `_state.update { it.copy(...) }`.
- **DI**: manual — `MainViewModel` (an `AndroidViewModel`) eagerly constructs all collaborators (`ApiKeyVault`, `AppPreferences`, the four bridges, `RuntimeInstaller`, `AgentRegistry`, `ProviderApiClient`).
- **Agent abstraction**: `runtime/AgentDriver.kt` defines `AgentDriver` + `AgentCapability` + `AgentRegistry` keyed by `AgentKind.stableId`; each bridge implements `RuntimeBridge` and exposes `events: Flow<RuntimeEvent>` (`MutableSharedFlow`). ViewModel reduces events into state (chat deltas, `FilesChanged`, `PreviewStarted`). **Adding an agent = new bridge + registry entry; `AgentRegistry.init` requires every `AgentKind` registered — no scattered UI switch edits.**
- **Services**: `RuntimeExecutionService` (agent tasks/Android builds) and `RuntimeSetupService` (first-run install), both `specialUse` foreground services. Cross-component control via singleton controllers: `RuntimeTaskController` (`@Volatile stopAction` lambda) and `RuntimeSetupController` (persisted `MutableStateFlow<RuntimeSetupSnapshot>`).
- **IPC app↔guest**: (a) stream-json over pipe/PTY stdin/stdout; (b) file-based permission bridge — guest writes `files/runtime-bridge/*.request`, bridge polls ~50 ms and answers `<id>.response`; (c) loopback TCP for model gateways and preview. Process output is captured to a **file** polled with tail reads (`BoundedFileReads.readTailText`, 256 KiB cap, ANSI stripped) — not a live stream.
- **Gateways**: `LocalFormatGateway` translates Anthropic↔OpenAI on `127.0.0.1` (OpenAI-format providers require it); `OpenRouterRoutingGateway` rewrites the `provider` body field. Provider→env mapping (`ANTHROPIC_BASE_URL`, `ANTHROPIC_*_MODEL`) lives in `RuntimeBridge.kt` `RuntimeLaunchConfigBuilder`.
- **Preview**: no app-side server; agent starts a dev server in-guest, `MainViewModel.detectPreviewUrl` regex-scans terminal output for loopback URLs; Preview `WebView` is hard-restricted to loopback.
- **Persistence**: `AppPreferences` = SharedPreferences scalars + `projects_json` blob; chats as JSON files `files/chats/<projectId>/<chatId>.json` (atomic tmp+rename); secrets in `ApiKeyVault` (AndroidKeyStore AES/GCM key pools).
- **Native** (`app/src/main/cpp/CMakeLists.txt`): builds `libpocketspawn.so` (JNI spawn/waitFor/kill, PTY via `posix_openpt`), PRoot (termux fork from `third_party/proot`), `libtalloc.so.2` (vendored), `libandroid-shmem`, and a freestanding `prootloader`. **Carrier trick:** AGP only packages shared libs, so `proot`/`prootloader` executables are POST_BUILD-copied over stub `.so` carriers (`libproot.so`, `libprootloader.so`); requires `useLegacyPackaging = true`. All targets link `-Wl,-z,max-page-size=16384`.
- **Runtime delivery flavors** (dimension `runtimeDelivery`): `online` downloads rootfs/bundles at install time; `offline` embeds identical checksum-verified `.tar.zst` bundles as assets. Differentiation is BuildConfig + assets only — no flavor-specific Kotlin source sets.

### Critical invariants (do not break)

1. `targetSdk = if (playBuild) 36 else 28` and **literal** `versionCode`/`versionName` in `app/build.gradle.kts` (F-Droid static manifest parser). `versionCode` = upstream's code × 10,000 (upstream 11 → `110000`; bump by exactly 10,000 per upstream release); rolling debug betas take `+1…9999` inside the current bucket via `-PappVersionCode`, so stable/beta transitions are always upgrades.
2. arm64-only assumptions end-to-end (abiFilters, CMake guard, bundles, `RuntimeCompatibility`).
3. NDK ≥ 28 default + 16 KiB page linker flag on every native target (NDK 26 output crashes on 16 KiB-page devices).
4. Carrier-`.so` copy + `useLegacyPackaging` + `androidResources.noCompress += "zst"`.
5. Runtime-bundle identity (filename/sha256/size) is **triplicated**: `app/build.gradle.kts` Sync tasks ↔ `dist/runtime-bundles/manifest.json` ↔ `RuntimeInstaller.kt` constants — change all three together. Never commit `*.tar.zst` (gitignored; published as GitHub release assets).
6. `mobile-harness-update.json` (repo root) must bump in lockstep with version + per-flavor url/sha256/sizeBytes.
7. Release minify stays OFF; signing stays env-driven (`MH_UPLOAD_*`); no keystores/secrets in repo.
8. README's NDK version and `app/build/outputs/apk/debug/app-debug.apk` path are **stale** — `app/build.gradle.kts` is ground truth.

## Key Directories

| Path | Purpose |
|---|---|
| `app/src/main/java/com/jarves/mh/ui/` | All Compose screens, `MainViewModel`, i18n (`LocaleHelper`, `AppStrings`), theme |
| `app/src/main/java/com/jarves/mh/runtime/` | PRoot launch/install, 4 agent bridges, gateways, native spawn, on-device Android build/install |
| `app/src/main/java/com/jarves/mh/network/` | Provider API client, GitHub device flow, cleartext-URL policy (`LocalNetwork`) |
| `app/src/main/java/com/jarves/mh/data/` | `AppPreferences`, `ApiKeyVault` |
| `app/src/main/java/com/jarves/mh/model/` | `Models.kt` — all enums/data classes/`RuntimeEvent` sealed interface |
| `app/src/main/java/com/jarves/mh/update/` | `AppUpdater` self-update (HTTPS-only manifest, sha256-verified APK) |
| `app/src/main/cpp/` | JNI + CMake: `pocket_spawn.c`, `executable_carrier.c`, `CMakeLists.txt`, `GenerateLoaderInfo.cmake` |
| `app/src/test/java/com/jarves/mh/` | JVM unit tests (see Testing & QA) |
| `app/src/main/res/` | Strings split by domain file, mirrored in `values-{zh,ru,hi,es,ar}` |
| `third_party/` | `proot` + `libandroid-shmem` (git submodules), `talloc` (vendored in-tree) |
| `scripts/` | Device install, OTA-test server, Play release pipeline, `runtime-bundles/` build kit |
| `dist/runtime-bundles/` | Tracked `manifest.json` (bundle versions + SHA-256); tarballs gitignored |
| `docs/` | `update-testing.md`, `PLAY_STORE_CHECKLIST.md`, `play/` store declarations |
| `fastlane/`, `fdroid/` | Store metadata only (no Fastfile); F-Droid staging config |

## Development Commands

```bash
# Prerequisite (proot + libandroid-shmem are submodules; native build fails without them)
git submodule update --init --recursive

# Build + install + launch on phone (canonical; flavor-qualified!)
./gradlew :app:installOnlineDebug -x lint
# or: sh scripts/install-phone.sh

# Unit tests / lint
./gradlew testDebugUnitTest            # all debug flavors
./gradlew :app:testOnlineDebugUnitTest # single flavor
./gradlew lintOnlineDebug

# Assemble (note flavor in output path)
./gradlew :app:assembleOnlineDebug     # → app/build/outputs/apk/online/debug/…
./gradlew :app:assembleOfflineRelease

# Gradle property switches
#   -PappVersionCode=N -PappVersionName=X   version override
#   -PplayBuild=true                        targetSdk 36 + IS_PLAY_BUILD (Play mode)
#   -PmhNdkVersion=<ver>                    NDK override

# Signed Play release (macOS Keychain; runs playReadinessCheck + tests + lint gate)
./scripts/build-play-release.sh VERSION_CODE VERSION_NAME

# In-app update testing (see docs/update-testing.md; needs HTTPS tunnel)
./scripts/serve-update-server.sh 8080 https://TUNNEL_URL [versionCode] [versionName]
```

⚠️ Flavor-less tasks (`installDebug`, `assembleDebug`) in `.vscode/tasks.json`/README predate the `online`/`offline` flavors and are stale — always name the flavor.

## Code Conventions & Common Patterns

- **Async**: coroutines + Flow only in Kotlin. Canonical shape: `viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { … } } }`. Bridges run on `Dispatchers.IO + NonCancellation`; raw daemon `Thread`s only for PTY pumping and gateway accepts.
- **Networking/JSON**: raw `HttpURLConnection` + `org.json` hand-rolled parsing. **No OkHttp/Retrofit/kotlinx-serialization/Gson** — do not introduce them.
- **Error handling**: `runCatching{}.onSuccess/.onFailure/getOrElse`; user-facing failures become `toastMessage`/status fields on `AppUiState`, never exceptions to UI; `require`/`check` for internal invariants; `ProviderRuntimeErrorDetector` maps provider errors to friendly text.
- **State**: single `AppUiState` copy-on-write; never mutate; bridges communicate only via `Flow<RuntimeEvent>`.
- **i18n (important)**: every user-visible string is `R.string` with a domain prefix — `tab_`, `action_`, `vm_`, `rt_`, `ws_`, `st_`, `fm_`, `md_`, `an_`/`android_`, `provider_title_*` — mirrored across 6 locales. Outside Compose use `AppStrings.get(context, id, vararg)` (in-app locale). Enum English fields persist for logs; UI translates via `@StringRes` mappers (`ModelLabels.kt`, `AndroidBuildText.kt`).
- **Naming**: one public class per PascalCase file; helpers are top-level `internal fun`/`internal object` in the same file as their primary consumer (e.g. `ProviderRuntimeErrorDetector` inside `ClaudeRuntimeBridge.kt`). Tests mirror subjects: `<Subject>Test.kt`.
- **Security guards**: path traversal via `safeWorkspaceFile` canonical `startsWith` checks; `LocalNetwork.isPlainHttpAllowed` permits cleartext only loopback/RFC1918/Tailscale/`.local`; output capture bounded (256 KiB). Preserve these when touching file/network code.
- **PRoot launch contract** (`RuntimeInstaller.process()`): `-0 -r files/runtime/ubuntu -b /dev -b /proc -b /sys -b <workspace>:/workspace -b files/runtime-bridge:/pocket-bridge -w /workspace`; env `PROOT_NO_SECCOMP=1`, `PROOT_LOADER=libprootloader.so`, `GLIBC_TUNABLES=glibc.pthread.rseq=0`, `--link2symlink`. Workspace paths validated against `^/workspace/[a-z0-9-]{1,64}$`.

## Important Files

| File | Role |
|---|---|
| `app/build.gradle.kts` | Single source of truth: flavors, `targetSdk` switch, NDK, Sync asset tasks, signing gate, all BuildConfig fields |
| `app/src/main/java/com/jarves/mh/ui/MainViewModel.kt` | God-ViewModel (~5.4k lines): `AppUiState`, all actions, runtime event reduction, bootstrap |
| `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt` | Entire Compose app (~7.4k lines): navigation, chat, files, changes, WebView preview |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt` | Rootfs/bundle/agent download + verification, PRoot launcher, all pinned version constants |
| `app/src/main/java/com/jarves/mh/runtime/{Claude,Dsh,Antigravity,Qwen}RuntimeBridge.kt` | Per-agent stream-json protocols, permission bridge, event parsing |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt` | `RuntimeBridge` interface + `RuntimeLaunchConfigBuilder` provider→env mapping |
| `app/src/main/java/com/jarves/mh/runtime/NativeSpawnProcess.kt` | JNI binding (`libpocketspawn`): spawn/wait/kill, PTY vs pipe |
| `app/src/main/java/com/jarves/mh/model/Models.kt` | `ProviderKind/Protocol`, `AgentKind`, `DevStack`, `Project`, `RuntimeEvent` |
| `app/src/main/AndroidManifest.xml` | 1 activity, 2 `specialUse` FGS, FileProvider `${applicationId}.files`, install-package perms |
| `app/src/main/cpp/CMakeLists.txt` | Native build: arm64 guard, PRoot/loader/carrier targets |
| `mobile-harness-update.json` | OTA manifest schema (published as latest-release asset) |
| `dist/runtime-bundles/manifest.json` | Bundle versions + SHA-256 (tracked; keep in sync with build script + `RuntimeInstaller.kt`) |
| `scripts/runtime-bundles/README.md` | Authoritative bundle contract + release procedure |

## Runtime/Tooling Preferences

- **JDK 17**, Gradle **8.14** (wrapper), AGP **8.13.2**, Kotlin **2.2.21** (+ compose plugin), compileSdk 36, minSdk 28.
- **NDK 28.2.13676358** default (override `-PmhNdkVersion`), CMake 3.22.1, ABI `arm64-v8a` only.
- **No version catalog** — dependencies inline in `app/build.gradle.kts`; `RepositoriesMode.FAIL_ON_PROJECT_REPOS` means new repos go in `settings.gradle.kts` only.
- Key deps: Compose BOM 2025.02.00, lifecycle 2.8.7, coroutines 1.9.0, commons-compress + zstd-jni (rootfs extraction). Package manager = Gradle wrapper (`./gradlew`), nothing else.
- Runtime bundle sources: GitHub release `runtime-2026.09.4` **on the SleepinDevil fork** (`RUNTIME_RELEASE_BASE_URL` in `app/build.gradle.kts`; mirror of upstream + the Qwen bundle — upstream `techjarves/runtime-2026.09.4` lacks `pocketdev-qwen`), Ubuntu base from cdimage.ubuntu.com, Android SDK/Gradle/offline Maven from `appdevforall.org/dev-assets/debug` — all SHA-256-pinned in `RuntimeInstaller.kt`.
- Target device: ARM64 Android 9+, 4 GB RAM, ~2.5 GB free. PRoot limits: no Docker/KVM/systemd/full PTY in guest.
- `test-secrets.properties` (gitignored, root): optional `openrouter.apiKey` → debug `BuildConfig.TEST_OPENROUTER_API_KEY` seeds a test provider on first debug launch.

## Testing & QA

- **Framework**: pure-JVM **JUnit 4** (`junit:4.13.2`) + `org.json:json` (real `JSONObject` in unit tests). No Robolectric, no Espresso/Compose UI tests, no `app/src/androidTest`, no mocking libs, **no coverage tooling**, **no CI** — `scripts/build-play-release.sh` (`playReadinessCheck testOnlineDebugUnitTest lintOnlineDebug assembleRelease bundleRelease`) is the effective gate.
- **Layout**: 17 files under `app/src/test/java/com/jarves/mh/` (~110 tests) covering pure logic: bridge parsers, launch-config building, rootfs repair, gateway auth, error detection, Qwen route mapping. Tests exercise `internal` members directly (same-module visibility); no `Context` fakes.
- **Run**: `./gradlew testDebugUnitTest` (flavor-qualified variants as above). Unit tests do **not** need runtime-bundle tarballs; `merge*Assets`/lint tasks **do** (Sync tasks pull `dist/runtime-bundles/*.tar.zst` — copy from the runtime GitHub release if missing).
- **Conventions**: `<Subject>Test.kt` mirroring main packages; camelCase behavior-statement method names; file-local `private fun` helpers (`withTempDirectory`, JSON-RPC builders) — no shared fixtures; temp dirs via `Files.createTempDirectory("mh-…")` with try/finally cleanup; expected failures via `@Test(expected = …)`.
- **Manual QA**: on-device flows (updater, PRoot install) have no automation — follow `docs/update-testing.md`; Play-store pre-upload gate is the `playReadinessCheck` task (asserts play flag, HTTPS privacy URL, all four `MH_UPLOAD_*` env vars).
