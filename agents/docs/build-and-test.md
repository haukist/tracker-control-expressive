# Building, testing, linting

The everyday commands are in AGENTS.md. This file covers the prerequisites, the
flavour matrix, the native builds, and the reproducibility flags.

## Prerequisites

JDK 17, Android SDK (compile/target SDK 37, min SDK 23), NDK `29.0.14206865`,
CMake. Native builds also need Rust ≥ 1.95 with the four Android targets;
the WireGuard bridge additionally needs `cargo-ndk`. Gradle wires both Rust
builds in but deliberately does not install tools or fetch crates. See
`wgbridge-rs/README.md` for setup and F-Droid build metadata.

Tailscale bindings also require Go 1.27.3, Python 3.10+, and the pinned
gomobile/gobind version documented in `tailscale-go/README.md`. The regular
compile/test/APK tasks run `tailscaleBuild` to build its AAR from source;
tools must already be installed. Override tool paths with `-PgoBin=...`,
`-PgomobileBin=...`, `-PgobindBin=...`, `-PpythonBin=...`, and optionally
`-PandroidNdkPath=...`. The binding builder checks API23, four ABIs, and
16 KB ELF load-segment alignment. See `scripts/build_tailscale_poc.py`.
Tool inputs are resolved from the repository root and include the executables
and Go compiler tools, so replacing a tool at the same path invalidates the AAR.
`python scripts/test_tailscale_build_inputs.py` tests these Gradle cache rules
using temporary fixtures; it needs a JDK but does not build an Android AAR.

## Flavours

Three product flavours — **github**, **fdroid**, **play** — differ only in the
update-check API; **github** is the normal local dev and real-device flavour.
Debug builds install side-by-side (`applicationIdSuffix ".test"`). Before
updating an existing GitHub debug install on a real device, back up its
configuration, especially WireGuard profiles and the Mullvad login — see
`agents/docs/device-testing.md`.

## Performance profiling build

The **benchmark** build type inherits release code shrinking, obfuscation, and
resource shrinking, remains non-debuggable, and enables shell profiling through
its own manifest on Android 10 (API 29) and newer. It uses the local debug signing
key and the same `.test` application ID suffix as debug. It replaces the debug
install while retaining its configuration when signed with the same local key;
debug and benchmark cannot coexist. It can coexist with the release install.
Do not switch builds during an active battery experiment.

Build with `./gradlew :app:assembleGithubBenchmark -q`, or select
`githubBenchmark` in Android Studio's Build Variants panel. The APK is written to
`app/build/outputs/apk/github/benchmark/TrackerControl-githubBenchmark-latest.apk`.
Use Android Studio Profiler's low-overhead profiling mode for CPU sampling and
system traces. Shrunk-code mappings are in
`app/build/outputs/mapping/githubBenchmark/mapping.txt`.

Both debug and benchmark include the overnight battery experiment screen from
the shared `src/batteryExperiment` Java/resource directories, enabled through
`BuildConfig.BATTERY_EXPERIMENT_ENABLED`. Benchmark keeps the reflected
controller entry points through variant-specific R8 rules. Release does not
include the experiment UI, alarm receiver, or alarm permission.
See `docs/BATTERY_MEASUREMENT.md` for the measurement procedure and exporting
results through the screen; benchmark is non-debuggable, so `adb run-as` is
unavailable. The shared experiment tests run with either
`:app:testGithubDebugUnitTest` or `:app:testGithubBenchmarkUnitTest`.

This build is intended for local
profiling, not distribution. Like other variants, enabling its VPN revokes the
VPN consent of the app currently holding it; coexistence does not allow two
active VPNs.

## Commands

```bash
# From the repo root. Use ./gradlew (the wrapper).

# Full debug APK (also triggers the native C + Rust + Go builds):
./gradlew assembleGithubDebug

# Fast compile check while iterating on Java (what most PRs verify against):
./gradlew :app:compileGithubDebugJavaWithJavac -q

# JVM unit tests (Robolectric); swap the flavour as needed:
./gradlew :app:testGithubDebugUnitTest
# One test class/method:
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest'
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest.someMethod'

# Android lint (MissingTranslation / ExtraTranslation are disabled on purpose):
./gradlew :app:lintGithubDebug
```

**Rust host tests** (config/DNS/key parsing — no device needed):

```bash
cd wgbridge-rs && cargo test --workspace
```

## Native code builds automatically with the app

- The **C engine** builds via CMake through AGP's `externalNativeBuild` and
  calls `tc-dns` through the C ABI exported by `libwgbridge.so`. CMake
  configure/build tasks depend on `wgbridgeBuild`; JVM compilation and unit
  tests remain Rust-free.
- The **Rust WireGuard bridge** builds via the `wgbridgeBuild` Gradle task, which
  runs `cargo ndk` for all four ABIs and is needed only by JNI-library packaging
  tasks. It also tracks the shared `tc-dns` source because the bridge links the
  same core.
  If Gradle can't find `cargo` (Android Studio sanitizes `PATH`), pass
  `-PcargoBin=/path/to/cargo` or put `~/.cargo/bin` on `PATH`.

## Reproducibility

Reproducibility is deliberately protected (path remapping, stripped `.comment`/
build-id, 16 KB page alignment) in both `build.gradle` and `CMakeLists.txt` —
don't undo those flags casually; IzzyOnDroid green-list depends on them.
