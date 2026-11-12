# Overnight battery A/B measurement (debug and benchmark)

The screen is available in debug and benchmark builds. Benchmark retains release
shrinking/obfuscation and is non-debuggable. Both builds use the same `.test`
application ID and local debug signing key, so switching between them updates
the existing install and retains its settings. They cannot be installed side by
side. Stop any measurement before switching builds. Normal release builds do
not include this feature.

In **Settings → Tailscale → Battery A/B measurement**, keep protection and the
embedded Tailscale connected first. WireGuard and SOCKS5 must be disabled.
Allow **Alarms & reminders** when prompted, then reserve again.

- A = protection on, Tailscale off; B = protection on, Tailscale on.
- Choose A → B or B → A, and 1–4 hours per condition (default: 3).
- Unplug all charging sources and turn the screen off within 30 minutes.
- Each condition starts with 15 minutes of settling, excluded from consumption.
  The default run takes roughly 6½ hours, plus connection setup time.
- Leave the phone untouched. Screen use, charging, underlying-network changes,
  relevant settings changes, protection loss or a Tailscale failure interrupt it.
  Native reconfiguration during settling restarts the settling period; during
  measurement it invalidates the run.
- On completion, the original Tailscale setting is restored. Explicit user
  changes or a user protection stop take priority over restoration.

The phone schedules boundary alarms locally. There is no polling, test traffic,
continuous wake lock, root requirement, or battery-statistics reset. A bounded
wake lock is used only during condition setup. Exact-alarm restrictions and OEM
background limits can delay or prevent execution; planned and actual times are
included in the result. A killed/restarted process never resumes a measurement.
Recovery/restoration runs when the app next starts, including after force-stop.

Open the screen in the morning to see the latest ten runs, or save a selected
run as JSON/CSV using the system document picker. Results measure **whole-device**
consumption, not the Tailscale process. Rates use real elapsed time including
sleep. Charge-counter values are in µAh, converted to mAh/hour. Missing or
increasing charge/percentage values produce a dash, not a fabricated zero.
Interrupted runs cannot produce a valid B−A comparison. Exports contain no
authentication material, Tailnet addresses/names, SSIDs, or backend error text.
Use this export flow with benchmark; its non-debuggable build does not allow
`adb run-as` access to private app files.

Repeat on another night in the opposite order before drawing a conclusion.
Background traffic, temperature, Doze progression and battery-gauge resolution
can affect the difference. CPU/wake-lock attribution and throughput tests are
outside this feature; collect system diagnostics separately if a repeatable
difference appears.

## Developer verification

Run the `BatteryExperimentEngineTest` and `BatteryExperimentAndroidTest` JVM
tests (Robolectric SDK 36 requires Java 21 or newer), the existing Tailscale
tests, GitHub debug/benchmark compilation/lint, and both APK builds. Run the
shared experiment tests with `:app:testGithubDebugUnitTest` and
`:app:testGithubBenchmarkUnitTest` using `--tests '*BatteryExperiment*'`.
Verify the minified benchmark APK keeps the reflected controller and its
`initialize`/`configurePreference` methods. Check debug and benchmark manifests
include the internal activity/receiver and alarm permission, and check merged
release manifests for absence of the experiment
activity/receiver and `SCHEDULE_EXACT_ALARM`. Follow
`agents/docs/device-testing.md` before any real-device installation or ADB use:
back up the existing debug/benchmark configuration with permission and announce the VPN
consent side effect. Validate both overnight orders with USB disconnected; JVM
tests do not establish screen-off energy cost or OEM alarm delivery reliability.
