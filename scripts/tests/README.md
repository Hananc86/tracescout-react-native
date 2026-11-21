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
  `swiftc main.swift -o probe && ./probe`, executes the production transport methods
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
When all remaining smaller records have been acknowledged and no new events exist,
the claim can remain pending. This is containment, not complete accounting recovery;
no claim cancellation, rebasing or synthetic settlement is implemented.
