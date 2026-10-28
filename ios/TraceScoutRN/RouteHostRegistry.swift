import Foundation

/// THE ROUTE-HOST REGISTRY — the Swift twin of `rn-core`'s Kotlin registry, and
/// the facts the specification requires.
///
/// WHY THIS HAS TO BE AN INTEGRATION CONTRACT, NOT A DETECTION.
/// The protocol's two reasons are about the HOST OF A REQUESTED ROUTE. React
/// Native routes are JavaScript components; the native layer can see a scene, a
/// window and a React root — and none of those is associated with a particular
/// route. `keyWindow` is not even a reliable *window* answer: an attached route
/// can live in a non-key window or another scene entirely. Every "detection"
/// available is a guess, and a guess dressed as a measurement is exactly what
/// this axis exists to prevent. So the host TELLS us, and if nobody tells us we
/// say nothing.
///
/// UNINTEGRATED APPLICATIONS ARE UNCHANGED, BY CONSTRUCTION. An app that never
/// registers a host produces `.unknown` for every attempt and no reason is ever
/// emitted — precisely today's behaviour. Integration ADDS the ability to report.
///
/// NO CLOCK OF ITS OWN, AND NO SLEEP ANYWHERE. The budget is evaluated against
/// an INJECTED time source. A registry that slept would prove "we waited"
/// rather than "the budget elapsed", and the second is what the protocol asks.
///
/// A CALLBACK FOR AN OLD ROUTE MUST NEVER RESOLVE THE CURRENT ONE. Every
/// attempt carries a generation; a late `ready` from a screen the user already
/// left is DISCARDED and counted, never applied.
public final class RouteHostRegistry {

    /// Longer than a normal mount+layout, shorter than a user would wait before
    /// deciding the screen is broken. A DEFAULT, not a law.
    public static let defaultAttachBudgetMs: Int64 = 2_000

    /// `attached` AND `pending` ARE DIFFERENT HOSTS, AND CONFLATING THEM WAS A
    /// REAL CONTRACT ERROR. `route_surface_not_ready` covers a qualifying host
    /// whose "DECLARED readiness never became true" — which includes one that
    /// PROMISED to report readiness and never did. A host with no readiness
    /// contract has promised nothing, so its silence is not a broken promise and
    /// cannot be timed out.
    ///
    /// attached "I am here." No contract. Silence is UNKNOWN forever.
    /// pending "I am here and I WILL A contract. Silence past the budget IS
    /// report readiness." `route_surface_not_ready`.
    private enum HostState { case attached, pending, ready, failed }
    private struct Host {
        let hostId: String
        var state: HostState
        /// THE CAPTURE ROOT, AND IT IS A SEPARATE FACT FROM READINESS.
        /// A readiness declaration establishes that the host is ready; by
        /// itself it establishes nothing about which views are its. Without a
        /// capture root there is no way to distinguish this host's content from
        /// whatever else the window happens to be showing.
        ///
        /// Opaque here ON PURPOSE — this type must not name a UIView. The
        /// platform resolves the handle and verifies it at capture time.
        var rootHandle: Int?
        /// Identity of the CURRENT binding. Re-binding, or any detach and
        /// re-attach, issues a new one, so a binding read before a walk can be
        /// proven still current after it. A handle alone cannot do that: the
        /// platform may hand the same tag back for a different view.
        var bindingId: Int64
    }

    /// What the platform needs in order to walk a route's OWN content.
    public struct BoundRoot: Equatable {
        public let hostId: String
        public let rootHandle: Int
        public let bindingId: Int64
    }

    /// An in-flight capture attempt. Resolvable exactly once.
    public final class Attempt {
        public let routeId: String
        public let sessionId: String
        fileprivate let generation: Int64
        fileprivate let startedMs: Int64
        fileprivate var resolved = false
        fileprivate var cancelled = false
        /// Content-free, stable for the attempt's life: generation, the
        /// start time modulo 1e6, and 4 hex of the route id's hash. Mirrors the
        /// Kotlin twin exactly so a cross-platform trace reads the same way.
        public let attemptId: String
        fileprivate init(routeId: String, sessionId: String, generation: Int64, startedMs: Int64) {
            self.routeId = routeId; self.sessionId = sessionId
            self.generation = generation; self.startedMs = startedMs
            var h: UInt32 = 0
            for u in routeId.utf8 { h = h &* 31 &+ UInt32(u) }
            self.attemptId = "a\(generation)_\(startedMs % 1_000_000)_\(String(h, radix: 16).prefix(4))"
        }
        /// Settled = it can never emit again, so a queue may drop it.
        public var isSettled: Bool { resolved || cancelled }
    }

    private let attachBudgetMs: Int64
    private let nowMs: () -> Int64
    private let lock = NSLock()

    private var hosts: [String: [Host]] = [:]
    private var generation: Int64 = 0
    private var openAttempt: Attempt?
    public private(set) var coalescedAttempts = 0
    private var currentRoute: String?
    private var currentSession: String?

    /// Observability: statements we DISCARDED, and why. Never silent.
    public private(set) var discardedStaleStatements = 0
    public private(set) var discardedForeignRoute = 0
    /// Monotonic source of binding identities; never reused.
    private var bindingSeq: Int64 = 0
    /// A host re-bound to a different capture root.
    public private(set) var rebindings = 0
    /// Two hosts bound to one route — refused rather than guessed between.
    public private(set) var ambiguousBindings = 0

    public init(attachBudgetMs: Int64 = RouteHostRegistry.defaultAttachBudgetMs,
                nowMs: @escaping () -> Int64) {
        self.attachBudgetMs = attachBudgetMs
        self.nowMs = nowMs
    }

    private func sync<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

    // ── the integration contract ────────────────────────────────────────────

    /// A host for `routeId` has attached. Idempotent per (route, host).
    ///
    /// ATTACHED IS NOT READY, AND IT IS ALSO NOT PENDING. `hostAttached`
    /// alone creates NO readiness contract, so silence can never be timed out
    /// into `not_ready`. A host that intends to report readiness must say so
    /// with `hostReadinessPending` — that promise is what a timeout can break.
    public func hostAttached(routeId: String, hostId: String, rootHandle: Int? = nil) {
        sync {
            var list = hosts[routeId] ?? []
            if let i = list.firstIndex(where: { $0.hostId == hostId }) {
                // RE-BINDING IS A REPLACEMENT AND MUST INVALIDATE THE OLD
                // BINDING. A capture authorized against the previous root must
                // not be allowed to complete against the new one.
                if let h = rootHandle, h != list[i].rootHandle {
                    bindingSeq += 1
                    list[i].rootHandle = h
                    list[i].bindingId = bindingSeq
                    rebindings += 1
                    hosts[routeId] = list
                }
            } else {
                if rootHandle != nil { bindingSeq += 1 }
                list.append(Host(hostId: hostId, state: .attached,
                                 rootHandle: rootHandle,
                                 bindingId: rootHandle != nil ? bindingSeq : 0))
                hosts[routeId] = list
            }
        }
    }

    /// Is this attempt describing a world that no longer exists?
    ///
    /// CANCELLATION IS AN OPTIMISATION; THIS IS THE GUARANTEE.
    /// `DispatchWorkItem.cancel` cannot retract a callback that has already
    /// been dequeued or is already running, so a deadline for a screen the user
    /// has left can and does still execute. The only thing that stops it
    /// emitting is checking identity AT EXECUTION.
    ///
    /// THIS EXISTED ON BOTH KOTLIN PLATFORMS AND NOT HERE, while an iOS test
    /// calling it had been sitting in the suite unable to compile — so the
    /// guarantee was neither implemented nor, despite appearances, tested. The
    /// first compilation of this target found it.
    public func isStale(_ attempt: Attempt) -> Bool {
        sync {
            attempt.cancelled
                || attempt.generation != generation
                || attempt.sessionId != currentSession
                || attempt.routeId != currentRoute
        }
    }

    /// The route's capture root, or nil when ownership cannot be established.
    ///
    /// NIL IS THE ANSWER FOR THREE DIFFERENT SITUATIONS AND ALL THREE MUST
    /// REFUSE: no host at all, a host that declared no capture root, and TWO
    /// hosts bound to one route. The last is ambiguity, not a tie to be broken
    /// picking one would be a guess about which subtree is the route's
    /// content, and a wrong guess is exactly the misattribution this prevents.
    public func boundRoot(_ routeId: String) -> BoundRoot? {
        sync {
            let bound = (hosts[routeId] ?? []).filter { $0.rootHandle != nil }
            if bound.isEmpty { return nil }
            if bound.count > 1 { ambiguousBindings += 1; return nil }
            let h = bound[0]
            return BoundRoot(hostId: h.hostId, rootHandle: h.rootHandle!, bindingId: h.bindingId)
        }
    }

    /// Is the binding a capture was authorized against STILL the current one?
    ///
    /// ASKED AFTER THE WALK, NOT ONLY BEFORE IT. A host can be replaced or
    /// detached while the tree is being read, and content gathered under a
    /// binding that no longer exists is not that route's content.
    public func bindingIsCurrent(_ routeId: String, _ b: BoundRoot) -> Bool {
        sync {
            guard let h = (hosts[routeId] ?? []).first(where: { $0.hostId == b.hostId })
            else { return false }
            return h.rootHandle == b.rootHandle && h.bindingId == b.bindingId
        }
    }

    /// Does this route have ANY registered host? Distinguishes an integrated
    /// app's unregistered route from an unintegrated app entirely.
    public func hasAnyHost(_ routeId: String) -> Bool {
        sync { !(hosts[routeId] ?? []).isEmpty }
    }

    /// The host declares it is PREPARING and WILL report readiness.
    ///
    /// THIS IS THE READINESS CONTRACT, AND IT IS WHAT MAKES A TIMEOUT
    /// MEANINGFUL. Declaring pending is a promise; failing to keep it within the
    /// budget is exactly "its declared readiness never became true".
    public func hostReadinessPending(routeId: String, hostId: String) {
        setState(routeId, hostId, .pending)
    }

    public func hostReady(routeId: String, hostId: String) { setState(routeId, hostId, .ready) }
    public func hostFailed(routeId: String, hostId: String) { setState(routeId, hostId, .failed) }

    /// The host went away. Its statements stop counting immediately — a detached
    /// host is not a qualifying ACTIVE host.
    public func hostDetached(routeId: String, hostId: String) {
        sync {
            hosts[routeId]?.removeAll { $0.hostId == hostId }
            if hosts[routeId]?.isEmpty == true { hosts.removeValue(forKey: routeId) }
        }
    }

    private func setState(_ routeId: String, _ hostId: String, _ s: HostState) {
        sync {
            guard var list = hosts[routeId],
                  let i = list.firstIndex(where: { $0.hostId == hostId }) else {
                // A statement from a host that never attached, or has already
                // detached. It describes a world that no longer exists.
                discardedStaleStatements += 1
                return
            }
            list[i].state = s
            hosts[routeId] = list
        }
    }

    // ── route and session lifecycle ─────────────────────────────────────────

    public func routeChanged(_ routeId: String?) {
        sync {
            if currentRoute != routeId { currentRoute = routeId; generation += 1 }
        }
    }

    public func sessionChanged(_ sessionId: String?) {
        sync {
            if currentSession != sessionId {
                currentSession = sessionId
                currentRoute = nil
                hosts.removeAll()
                generation += 1
            }
        }
    }

    // ── capture scheduling ──────────────────────────────────────────────────

    public func beginAttempt(routeId: String, sessionId: String) -> Attempt {
        sync {
            if currentSession != sessionId {
                currentSession = sessionId; hosts.removeAll(); generation += 1
            }
            if currentRoute != routeId { currentRoute = routeId; generation += 1 }
            // COALESCE ONTO THE OPEN ATTEMPT (see the Kotlin twin). Same id,
            // start time and deadline while it is unsettled — the bridge's pending
            // entry keeps the attempt and its route, no snapshot identifier (a
            // deferred marker mints its own); a fresh attempt only after
            // settlement. One marker PER ATTEMPT.
            if let o = openAttempt, !o.isSettled, o.generation == generation,
               o.routeId == routeId, o.sessionId == sessionId {
                coalescedAttempts += 1
                return o
            }
            let a = Attempt(routeId: routeId, sessionId: sessionId,
                            generation: generation, startedMs: nowMs())
            openAttempt = a
            return a
        }
    }

    public func cancel(_ attempt: Attempt) { sync { attempt.cancelled = true } }

    /// Evaluate `attempt` against the facts as they stand NOW and against the
    /// budget. Returns the shared rule's INPUT, never a decision — so the rule
    /// stays the single place the two reasons are derived.
    public func evaluate(_ attempt: Attempt) -> CaptureOutcome.RouteHost {
        sync {
            // A STALE ATTEMPT RESOLVES NOTHING.
            if attempt.cancelled || attempt.generation != generation {
                discardedForeignRoute += 1
                return .unknown
            }
            if attempt.sessionId != currentSession || attempt.routeId != currentRoute {
                discardedForeignRoute += 1
                return .unknown
            }

            let list = hosts[attempt.routeId] ?? []
            let budgetExpired = (nowMs() - attempt.startedMs) >= attachBudgetMs

            if list.isEmpty {
                return CaptureOutcome.RouteHost(resolved: false, attachBudgetExpired: budgetExpired)
            }
            // FAILURE outranks readiness: a definite negative statement is not
            // erased by a sibling's success.
            if list.contains(where: { $0.state == .failed }) {
                return CaptureOutcome.RouteHost(resolved: true, hostReportedFailure: true)
            }
            if list.contains(where: { $0.state == .ready }) {
                return CaptureOutcome.RouteHost(resolved: true, readinessDeclaredTrue: true)
            }
            // A BROKEN PROMISE IS A VERDICT; SILENCE WITHOUT A PROMISE IS NOT.
            if list.contains(where: { $0.state == .pending }) {
                return budgetExpired
                    ? CaptureOutcome.RouteHost(resolved: true, readinessDeclaredTrue: false)
                    : CaptureOutcome.RouteHost(resolved: true, readinessDeclaredTrue: nil)
            }
            // Attached with NO readiness contract: it promised nothing, so there
            // is no declaration to have failed and no timeout to apply.
            return CaptureOutcome.RouteHost(resolved: true, readinessDeclaredTrue: nil)
        }
    }

    /// Claim the single resolution of `attempt`.
    ///
    /// THIS IS THE EXACTLY-ONCE GATE — per ATTEMPT, not per queue entry, so a
    /// retry, a late poll and a racing caller collapse to one emission.
    public func claimResolution(_ attempt: Attempt) -> Bool {
        sync {
            if attempt.resolved || attempt.cancelled { return false }
            if attempt.generation != generation { return false }
            attempt.resolved = true
            return true
        }
    }

    /// Test/diagnostic view. Never used to make a decision.
    public func hostCount(_ routeId: String) -> Int { sync { (hosts[routeId] ?? []).count } }
}
