import Foundation

/// ONE PHYSICAL TOUCH, ONE STICKY CLASSIFICATION.
///
/// ## What this exists for
///
/// Before the movement lane a gesture that travelled was suppressed
/// `unsupported_gesture` at its release point, so a real swipe left no trace at
/// all. Simply admitting swipes at the policy would have produced the opposite
/// defect: every UIKit `touchesMoved` callback becoming a stored row, and every
/// ordinary tap becoming a multi-row gesture because a finger never holds
/// perfectly still.
///
/// So the classification is a property of the LIFECYCLE, decided once and never
/// revisited:
///
/// touch began -> gestureId minted, ORIGINAL begin remembered
/// -> TAP CANDIDATE, nothing persisted
/// moves within slop -> still a tap candidate, nothing persisted
/// first move beyond slop -> SWIPE, permanently
/// -> emit BEGIN from the ORIGINAL observation
/// -> emit a bounded move sample
/// later meaningful moves -> bounded move samples
/// end (tap candidate) -> the EXISTING tap row, byte-semantics intact
/// end (swipe) -> swipe END
/// cancel -> swipe CANCEL, or the existing tap semantics
///
/// TWO AUTHORITIES, NOT ONE. Build 23 conflated them and dropped the
/// terminal phase of every physical swipe:
///
/// * LIFECYCLE AUTHORITY — screen, capture permission, window identity. A
/// property of the GESTURE. It must be identical from begin to end, and a
/// change fails closed.
/// * PER-SAMPLE TREATMENT — the target under the finger and whether that
/// sample may carry coordinates. A property of the MOMENT. It changes
/// constantly during a real swipe, and its only consequence is a
/// coordinate authority that can tighten but never relax.
///
/// STICKINESS IS THE POINT. A scroll that ends where it began must not be
/// reported as a tap, so once a lifecycle is a swipe it never becomes a tap
/// again. And exactly one row-producing class is ever attached to a gesture:
/// there is no path that emits BOTH a tap and a swipe for one touch.
///
/// THE TAP PATH IS UNCHANGED, DELIBERATELY. A tap candidate persists nothing
/// at `begin` and nothing at `move` — exactly as before, when those phases were
/// suppressed as unsupported gestures — and produces one row at `end`. The
/// movement lane is additive; it must not rewrite bytes that are already
/// certified.
///
/// NO UIKit, NO HIT-TESTING, NO SECOND PRIVACY ENGINE. This type reads only
/// the mechanical facts the recognizer already reported and hands observations
/// to the SHARED `InteractionPolicy` through `InteractionProducer`. It never
/// re-resolves a target along the finger's path to obtain more coordinates.
public struct InteractionGestureLifecycle {

    public typealias Observation = InteractionProducer.NativeTouchObservation
    public typealias GestureClass = InteractionProducer.GestureClass

    /// One observation to hand to the producer, with the class the lifecycle
    /// has settled on. A pair rather than a mutated observation: the mechanical
    /// record stays truthful (a `begin` genuinely had not moved), and only the
    /// classification travels beside it.
    public struct Persist {
        public let observation: Observation
        /// `nil` MEANS "THIS LIFECYCLE HAS NO CLASSIFICATION TO OFFER", NOT
        /// "tap". It is the honest answer for a touch whose BEGIN we never
        /// observed — the observer can be attached mid-gesture by a remount, so
        /// the first callback we see may be a move or an end. The producer then
        /// applies its pre-movement-lane inference, which reproduces today's
        /// behaviour EXACTLY in both directions: a stationary end is a tap row,
        /// a travelled one is suppressed `unsupported_gesture`.
        ///
        /// Inventing `.tap` here would emit a tap for a gesture that travelled;
        /// inventing `.swipe` would emit an END for a path with no beginning.
        public let gestureClass: GestureClass?
    }

    public enum Classification: String, Equatable { case tapCandidate, swipe, invalidated }

    private struct Live {
        let gestureId: String
        let begin: Observation
        var classification: Classification
        /// Wall-clock and point of the last PERSISTED sample. `-1` means
        /// nothing has been persisted for this gesture yet.
        var lastPersistedMs: Int64
        var lastPersistedX: Double
        var lastPersistedY: Double
        /// The strongest coordinate treatment seen anywhere in this gesture so
        /// far. Monotonic: it may tighten, never relax.
        var coordinateAuthority: InteractionPolicy.PositionTreatment
    }

    private var live: Live?

    public init() {}

    /// The class this lifecycle has settled on, or nil between gestures.
    /// Exposed so a test can assert on the STATE, not only on the output.
    public var currentClassification: Classification? { live?.classification }
    public var currentGestureId: String? { live?.gestureId }

    /// WHICH SUBJECT IS BEING OBSERVED, AND WHETHER OBSERVING IT IS
    /// PERMITTED AT ALL. These are properties of the GESTURE: they identify the
    /// screen, the window and the capture permission the touch began under. A
    /// change here means there is no coherent gesture left to describe, and the
    /// lifecycle fails closed.
    ///
    /// `targetClass` AND `positionTreatment` ARE DELIBERATELY ABSENT, AND
    /// THIS IS THE BUILD-23 DEFECT. They are resolved PER SAMPLE from whatever
    /// view is under the finger, so on a real device they change constantly
    /// during a swipe — that is what a swipe IS. Treating them as lifecycle
    /// authority made every physical swipe's terminal phase fail the equality
    /// and be dropped: begin and moves stored, no end, an unterminated path in
    /// replay. The synthetic drives never caught it because they hold one
    /// target for the whole gesture.
    ///
    /// Per-sample treatment governs ONLY whether that sample may carry
    /// coordinates, and it is handled by `coordinateAuthority`, which can
    /// tighten but never relax.
    ///
    /// CONSENT IS NOT LISTED BECAUSE IT IS NOT REACHABLE FROM HERE, NOT
    /// BECAUSE IT IS UNCHECKED. `consentEnabled` is a parameter of every
    /// `recordInteraction` call and `InteractionProducer.produce` applies it to
    /// every step independently, so consent being withdrawn suppresses each
    /// row on its own. Restating it here would be a second, weaker copy of a
    /// check that already cannot be bypassed.
    private static func lifecycleAuthority(_ o: Observation) -> String {
        [o.screenId ?? "",
         String(o.screenNeverRecord),
         String(o.textEntryActive),
         String(o.fromContentWindow),
         String(o.basisIsCapturedWindow)].joined(separator: "|")
    }

    /// Restriction rank. A `switch` with no `default` on purpose: a new
    /// treatment must fail to COMPILE rather than silently receive a rank.
    private static func rank(_ t: InteractionPolicy.PositionTreatment) -> Int {
        switch t {
        case .allowed: return 0
        case .redacted: return 1
        case .blocked: return 2
        }
    }

    /// MONOTONIC, ONE DIRECTION ONLY. Coordinate authority may stay the same
    /// or DOWNGRADE; it may never upgrade. A finger that crossed content the
    /// resolver would not grant coordinates for does not earn them back by
    /// finishing somewhere permissive.
    private static func strongest(_ a: InteractionPolicy.PositionTreatment,
                                  _ b: InteractionPolicy.PositionTreatment)
    -> InteractionPolicy.PositionTreatment {
        rank(a) >= rank(b) ? a : b
    }

    /// The observation the producer should see: mechanically identical, with the
    /// gesture's running coordinate authority substituted for this sample's own.
    ///
    /// THIS GRANTS NOTHING. The substituted value is by construction at least
    /// as restrictive as the sample's own, so the SHARED `InteractionPolicy` —
    /// still the only thing that decides what a row may carry — can only answer
    /// the same or more restrictively than it would have. The lifecycle holds no
    /// policy; it can only tighten the input to one.
    private static func withAuthority(_ o: Observation,
                                      _ authority: InteractionPolicy.PositionTreatment)
    -> Observation {
        guard authority != o.positionTreatment else { return o }
        return Observation(
            monotonicNanos: o.monotonicNanos, wallMillis: o.wallMillis,
            x: o.x, y: o.y,
            viewportWidth: o.viewportWidth, viewportHeight: o.viewportHeight,
            screenId: o.screenId, phase: o.phase, pointerCount: o.pointerCount,
            gestureId: o.gestureId, targetClass: o.targetClass,
            positionTreatment: authority,
            fromContentWindow: o.fromContentWindow,
            screenNeverRecord: o.screenNeverRecord,
            textEntryActive: o.textEntryActive,
            basisIsCapturedWindow: o.basisIsCapturedWindow,
            movedBeyondSlop: o.movedBeyondSlop,
            // an earlier change — a copy that dropped this would strip the carrier from a
            // web drag the moment its authority tightened.
            insideWebContent: o.insideWebContent)
    }

    /// Feed one mechanical observation; receive the zero, one or two
    /// observations that should be handed to the producer, in wire order.
    ///
    /// Two is the retroactive case and only that case: the moment a touch
    /// crosses the slop, the BEGIN that was withheld is materialized from the
    /// original observation and the crossing move follows it.
    public mutating func observe(_ o: Observation) -> [Persist] {
        switch o.phase {
        case .begin:
            live = Live(gestureId: o.gestureId, begin: o,
                        classification: .tapCandidate,
                        lastPersistedMs: -1, lastPersistedX: o.x, lastPersistedY: o.y,
                        coordinateAuthority: o.positionTreatment)
            // Nothing persisted: identical to the pre-movement-lane behaviour,
            // where a `begin` was suppressed as an unsupported gesture.
            return []

        case .move:
            guard var s = live, s.gestureId == o.gestureId else { return [] }
            switch s.classification {
            case .invalidated:
                return []

            case .tapCandidate:
                guard o.movedBeyondSlop else { return [] }
                // THE SUBJECT IS PINNED AT THE MOMENT OF PROMOTION, against
                // the ORIGINAL begin. If the subject already differs, there is
                // no coherent gesture to authorize and nothing is persisted at
                // all — not even a begin.
                guard Self.lifecycleAuthority(s.begin) == Self.lifecycleAuthority(o) else {
                    s.classification = .invalidated; live = s; return []
                }
                s.classification = .swipe
                // The retroactive BEGIN carries the authority as it stood at the
                // begin — that moment was genuinely authorized under it, and
                // back-dating a later tightening would misdescribe it.
                var out = [Persist(observation: Self.withAuthority(s.begin, s.coordinateAuthority),
                                   gestureClass: .swipe)]
                // The BEGIN is now the last persisted sample, so the crossing
                // move is bounded against the begin's own time and point — not
                // against a zero baseline that would persist it unconditionally.
                s.lastPersistedMs = s.begin.wallMillis
                s.lastPersistedX = s.begin.x
                s.lastPersistedY = s.begin.y
                s.coordinateAuthority = Self.strongest(s.coordinateAuthority, o.positionTreatment)
                if Self.shouldPersistMove(o, s) {
                    out.append(Persist(observation: Self.withAuthority(o, s.coordinateAuthority),
                                       gestureClass: .swipe))
                    s.lastPersistedMs = o.wallMillis; s.lastPersistedX = o.x; s.lastPersistedY = o.y
                }
                live = s
                return out

            case .swipe:
                // FAIL CLOSED ON AN INCOMPATIBLE SUBJECT CHANGE. Carrying the
                // begin's authority across a SCREEN or WINDOW boundary would
                // attribute coordinates to a subject that never granted them. No
                // further persisted evidence for this gesture — including its end.
                //
                // A change of TARGET is not that: the finger is expected to pass
                // over other views, and the consequence is a tightened coordinate
                // authority, not a destroyed gesture.
                guard Self.lifecycleAuthority(s.begin) == Self.lifecycleAuthority(o) else {
                    s.classification = .invalidated; live = s; return []
                }
                s.coordinateAuthority = Self.strongest(s.coordinateAuthority, o.positionTreatment)
                guard Self.shouldPersistMove(o, s) else { live = s; return [] }
                s.lastPersistedMs = o.wallMillis; s.lastPersistedX = o.x; s.lastPersistedY = o.y
                live = s
                return [Persist(observation: Self.withAuthority(o, s.coordinateAuthority),
                                gestureClass: .swipe)]
            }

        case .end, .cancel:
            guard var s = live, s.gestureId == o.gestureId else {
                // AN ORPHAN END IS NOT NOTHING — IT IS A REAL END OF TOUCH.
                // Dropping it would silently regress a case the product already
                // handles (an observer attached mid-gesture). With no observed
                // begin there is no classification to offer, so the producer's
                // legacy inference decides and today's bytes are unchanged.
                live = nil
                return [Persist(observation: o, gestureClass: nil)]
            }
            live = nil
            switch s.classification {
            case .invalidated:
                return []
            case .swipe:
                // NEVER DROPPED BY THROTTLING, AND — SINCE BUILD 23 — NEVER
                // DROPPED BY A TARGET CHANGE EITHER. An endpoint is what a
                // gesture is identified by; losing it left an unterminated path
                // in replay for EVERY physical swipe, because the finger ends
                // over a different view than it started on essentially always.
                // The subject must still match; the treatment merely tightens.
                guard Self.lifecycleAuthority(s.begin) == Self.lifecycleAuthority(o) else {
                    return []
                }
                s.coordinateAuthority = Self.strongest(s.coordinateAuthority, o.positionTreatment)
                return [Persist(observation: Self.withAuthority(o, s.coordinateAuthority),
                                gestureClass: .swipe)]
            case .tapCandidate:
                // The existing tap path, unchanged: one row, at the end,
                // decided by the end observation's own context exactly as
                // before. No begin, no moves, no second row.
                return [Persist(observation: o, gestureClass: .tap)]
            }

        case .instant:
            // An instant has no begin by definition, so there is no lifecycle
            // to classify it: the legacy inference owns it, exactly as before.
            live = nil
            return [Persist(observation: o, gestureClass: nil)]
        }
    }

    /// Drop any in-flight gesture. Used when the observer is detached or the
    /// surface remounts, so a new touch can never inherit a stale begin.
    public mutating func reset() { live = nil }

    private static func shouldPersistMove(_ o: Observation, _ s: Live) -> Bool {
        let dx = o.x - s.lastPersistedX, dy = o.y - s.lastPersistedY
        return InteractionProducer.MoveSampling.shouldPersist(
            phase: .move,
            sinceLastPersistedMs: s.lastPersistedMs < 0 ? -1 : o.wallMillis - s.lastPersistedMs,
            distanceFromLastPersisted: (dx * dx + dy * dy).squareRoot())
    }
}
