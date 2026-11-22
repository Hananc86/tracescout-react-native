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
