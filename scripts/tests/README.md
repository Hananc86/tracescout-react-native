# Focused native regressions

These checks exercise identity ordering, session ownership and queue recovery.
They do not establish device behavior, server acceptance or package release readiness.

* `pr3-regressions.py --compiler-lib <Kotlin compiler jars> --out <temporary directory>`
  extracts the production Kotlin admission, session-end, file confinement and recovery
  methods and executes them with small IO/admission doubles. The identity test pauses a
  producer while another thread changes identity. `--mutate-identity` deliberately
  restores the stale snapshot and must fail. The transport-publication check is a
  source-order assertion, not an injected Android startup failure.
* `pr3-swift-probe.py > <temporary directory>/main.swift`, followed by
  `(cd <temporary directory> && swiftc main.swift -o probe && ./probe)`, executes the production transport methods
  with a controlled callback/identify interleaving and stale-session refusals.
* `Pr3PipelineRegressionTest.kt` and `Pr3HostBoundaryTest.kt` run in the Android
  module's JVM test source set, with JUnit 4.13.2 and `org.json:json:20250107`.
  Configure `android.testOptions.unitTests.returnDefaultValues = true` for framework
  stubs. The pipeline uses real encrypted queue/authority files; HTTP responses are
  controlled inputs. The host tests cover throwing callbacks and session-bound
  WebView attribution, not WebView rendering.

The smaller-delivery test retains the frozen claim through an unsuccessful request,
restart and byte-identical event-only retry. Event ACKs cannot settle it. An invalid
late loss ACK cannot settle it, and a duplicate old ACK cannot clear a successor.
The legacy fixture deliberately has no project evidence, so its empty queue remains
refused. It is not a server-acceptance proof.

`Pr3InteractionAdmissionTest` pauses the actual host gate, replaces the session,
and requires a truthful `no_session` refusal. The extracted Kotlin probe also
checks same-session identify/sequence ordering; its mutation control must fail.

An empty record queue with a transient pending-checkpoint carrier failure retains
its frozen claim and reports retry owed; RN maps that result to the existing worker
retry state even though no record response exists. The regression covers transport
exceptions, HTTP 503/429, a success without a loss ACK, a definite 422 refusal and
genuine settlement. It proves the pipeline and state mapping, not Android worker
execution on a device.

An inherited session with no authority on disk is adopted and bound before its
first POST (`inheritedSessionWithoutAuthorityBindsBeforePostAndDelivers` checks the
binding file exists when the request is made). A binding refusal sends nothing and
does not count toward the no-progress bound. `SUBMITTED` means the append task was
scheduled, and a refused navigation leaves the interaction route unchanged; the
stale-session half of that is exercised by the extracted probe's admission callback.

`CommittedRecoveryIntegrationTest` requires the isolated real API/PostgreSQL harness
in `TraceScout-API/test/mobile-loss-authority-forwarding-db.test.cjs`. Set
`MLOSS_DB_URL` (synthetic database), `MLOSS_SDK_PROJECT` (Gradle test project),
`MLOSS_GRADLE` (Gradle executable), `ANDROID_HOME`, and run that Node suite.
The fixture starts a loopback-only controller endpoint and executes Kotlin with
real encrypted files and actual server ACKs: all 6–15 queued, split delivery,
crash after commit, byte-identical retry, loss-response omission, restart and
no-new-events settlement. No deployed runtime or iOS package proof is implied.

The explicit candidate wire addendum is owned by the API repository:
`docs/mobile/COMMITTED_EVENT_LOSS_RECOVERY.md`. It preserves the original frozen
claim and requires committed event coverage; it does not cancel or rebase it.

## Pull request CI

`.github/workflows/pr-tests.yml` checks out the exact PR head with read-only
permissions. It neither runs the separate publication workflow nor publishes.

The host Gradle project in `scripts/ci/android` pins Gradle 8.14.3 (installed by
CI), AGP 8.7.3, Kotlin 2.1.20, React Android 0.88.1, Java 17 and compile SDK 35.
From a clean checkout with Java 17, that Gradle version, and Android SDK
platform 35 / build-tools 34.0.0 installed (`ANDROID_HOME` set), run:

```sh
gradle -p scripts/ci/android :tracescout:testDebugUnitTest probeCompiler --no-daemon
python3 scripts/ci/check-junit.py
python3 scripts/ci/kotlin-controls.py
```

Only the three `Pr3*Test` classes are staged into the host test source set.
The JUnit verifier derives their test names from committed source, requires every
case to execute without errors/failures/skips, and prints actual per-suite counts.
The Kotlin probe runs with the pinned compiler dependencies, then compiles and
executes a deliberately stale-identity variant; only its specific runtime
assertion failure counts as the negative control. Missing dependencies or compile
failures cannot satisfy it. CI retains JUnit XML, reports, probe logs and the
checked-out SHA. `CommittedRecoveryIntegrationTest` is deliberately outside this
offline job; it still needs the real isolated server/database described above.

The package job uses the committed npm lock and `npm ci --legacy-peer-deps
--ignore-scripts`, followed by explicit typecheck/build/surface/packed-file checks.
React Native 0.88.1 is a pinned development dependency for its real type declarations; no React Native application is launched. The Swift job uses the Linux
Swift 6.0.3 image and the portable probe above; it proves transport interleavings,
not Apple SDK compilation, CocoaPods integration, rendering or device execution.
