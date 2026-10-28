import Foundation

/// THE FRAME CADENCE BUDGET, KEYED ON THE SUBJECT INSTEAD OF THE CLOCK ALONE.
///
/// ## The defect this exists for, measured
///
/// Session `ts_1788531095320_4833f8e079dd` (RN iOS 0.3.0 (18), Mobile Replay
/// Lab) visited six screens in 13.35 s and stored **two** frames, both on
/// `profile`. Every other screen requested a snapshot and obtained a full
/// coherent hierarchy — `markets` 196 nodes, `activity` 198 — so nothing was
/// failing to settle and `hierarchy_unavailable` never fired. They were refused
/// `cadence_budget`, because `RnBridge.lastFrameWallMs` is SESSION-scoped and
/// `FramePolicy.minIntervalMs` is 10 000 ms:
///
/// span 13.35 s / 10 s session throttle -> max 2 frames. Observed: 2.
///
/// The arithmetic leaves no residual. `profile` was not eligible for any
/// property of its own; it is simply the screen entered first after each 10 s
/// boundary. (`capture_reason` is the compile-time constant `explicit_allow` on
/// every RN frame and is not a per-screen eligibility signal — do not read it
/// as one.)
///
/// A session-wide wall clock cannot tell *"the same screen again"* from
/// *"a different screen for the first time"*, so it suppresses the second while
/// solving the first. The storm control was right; its KEY was wrong.
///
/// ## What changed, and what deliberately did not
///
/// `FramePolicy.decide` is UNTOUCHED — it is a shared contract pinned to the
/// Kotlin twin by `parity-vectors/frame-vectors.json`, and moving it would move
/// Android's certified vc9 output. This type only decides what `since` MEANS
/// for a subject the session has not captured yet, and it hands the answer to
/// the same `decide` as before. `maxFramesPerSession` is not consulted here at
/// all: the 40-frame ceiling stays absolute and stays where it is.
///
/// THE STATE IS DELIBERATELY ONE KEY AND ONE TIMESTAMP, not a map of every
/// screen ever seen. A session-long map is unbounded in a process that must not
/// grow without limit, and it buys nothing the product needs: `A -> B -> A`
/// already yields a frame on each settled visit, because returning to `A` finds
/// the last accepted key is `B`.
public enum FrameCadence {

    /// Everything the decision may read. One key, one timestamp — see above.
    public struct State: Equatable {
        /// The subject of the last frame that ACTUALLY entered the Phase-2
        /// delivery path. `nil` = no frame has been accepted this session.
        public let lastAcceptedSubjectKey: String?
        /// Wall time of that same acceptance. Negative = none.
        public let lastAcceptedWallMs: Int64

        public init(lastAcceptedSubjectKey: String? = nil,
                    lastAcceptedWallMs: Int64 = -1) {
            self.lastAcceptedSubjectKey = lastAcceptedSubjectKey
            self.lastAcceptedWallMs = lastAcceptedWallMs
        }

        public static let empty = State()
    }

    /// The subject a frame would describe: screen identity + the structural
    /// coherence digest of the tree that would be redacted.
    ///
    /// BOTH HALVES ARE REQUIRED. Screen identity alone cannot distinguish a
    /// screen whose content changed; the fingerprint alone cannot distinguish
    /// two screens that happen to digest identically, and it is the route the
    /// product's coverage requirement is written in.
    ///
    /// IT REUSES THE DIGEST THE COHERENCE TRANSACTION ALREADY COMPUTED —
    /// `CaptureCoherence.fingerprint`, taken in phase A and compared in phase C.
    /// There is deliberately no second hash: a parallel digest would be free to
    /// disagree with the one that actually gates the frame.
    /// THE THIRD COMPONENT EXISTS BECAUSE THE SECOND ONE STRUCTURALLY
    /// CANNOT SEE A SCROLL. `CaptureCoherence.fingerprint` digests canonical
    /// ids, roles, privacy treatments and window-space bounds. An ALLOWED
    /// WebView is an opaque leaf — one node, one rectangle, no children — so
    /// every scroll position of the document inside it digests IDENTICALLY.
    /// Keying the cadence on that alone declares "the same subject" about a
    /// screen whose entire visible content has moved, and the 10 s idle floor
    /// then refuses the frame that shows where the flick actually came to rest.
    ///
    /// A hierarchy fingerprint cannot, by itself, establish that pixels stayed
    /// unchanged. `visibleStateDigest` is the part it cannot answer, and it is
    /// deliberately the ONLY thing added: scroll geometry, four integers per
    /// scroll surface, no content of any kind.
    ///
    /// IT IS ADDED TO THE CADENCE KEY AND NOWHERE ELSE. `CaptureCoherence`
    /// is untouched — its prepare/verify comparison still governs whether a
    /// redaction rectangle describes the raster it was measured against, and a
    /// document scrolling inside a rectangle that did not move is NOT a
    /// coherence mismatch. Folding this into the fingerprint would start
    /// refusing coherent captures.
    ///
    /// `nil` FALLS BACK TO THE CERTIFIED TWO-COMPONENT KEY, byte for byte,
    /// so an unsampled or unresolvable root can only ever be more conservative.
    public static func subjectKey(routeId: String,
                                  coherenceFingerprint: String?,
                                  visibleStateDigest: String? = nil) -> String? {
        guard let fp = coherenceFingerprint, !fp.isEmpty else { return nil }
        let base = routeId + "\u{1F}" + fp
        guard let vs = visibleStateDigest, !vs.isEmpty else { return base }
        return base + "\u{1F}v:" + vs
    }

    /// The value to hand `FramePolicy.decide` as `sinceLastFrameMs`.
    ///
    /// `-1` is `decide`'s existing spelling of "there is no previous frame", and
    /// that is precisely true of a subject this session has never captured. The
    /// meaning is not stretched: it is scoped from the session to the subject.
    ///
    /// - Parameters:
    /// - labActive: `ResolvedFidelityPolicy.labScopeActive` — the resolved
    /// Mobile Replay Lab maximum-visibility scope. **FALSE takes the
    /// certified build-18 path, byte for byte.** The correction is scoped to
    /// the Lab because widening the customer cadence is a separate decision
    /// with its own evidence, not a side effect of a TestFlight fix.
    /// - subjectKey: `nil` when no coherence digest was available (frames not
    /// opted in, walk refused). **`nil` is never treated as new** — an
    /// unknown subject falls back to the session clock, so a missing digest
    /// can only ever be more conservative, never a way to bypass the storm
    /// control.
    public static func effectiveSinceMs(labActive: Bool,
                                        subjectKey: String?,
                                        state: State,
                                        nowMs: Int64) -> Int64 {
        let sessionSince: Int64 =
            state.lastAcceptedWallMs < 0 ? -1 : nowMs - state.lastAcceptedWallMs
        guard labActive else { return sessionSince }
        guard let key = subjectKey else { return sessionSince }
        // A subject the session has not accepted a frame for — a different
        // screen, or the same screen in a structurally different state.
        if key != state.lastAcceptedSubjectKey { return -1 }
        // The same subject: the 10 s storm control applies unchanged.
        return sessionSince
    }

    /// Whether this call is bypassing the same-subject interval, for the
    /// durable trace. Diagnostics only — it decides nothing.
    ///
    /// HONEST SEMANTIC, per the frozen wording: a NEW SUBJECT KEY means
    /// structurally/coherently new state, NOT "every material pixel change was
    /// detected". `CaptureCoherence.fingerprint` is deliberately content-free —
    /// it excludes `textLength` and never reads a text value — so it proves a
    /// route change, a structural change, and a bounds/role/privacy/canonical-id
    /// change. It cannot be claimed to detect a text VALUE changing inside
    /// identical geometry. The product requirement here is first useful frame
    /// coverage on each major screen, which this fully addresses.
    public static func isNewSubject(labActive: Bool,
                                    subjectKey: String?,
                                    state: State) -> Bool {
        guard labActive, let key = subjectKey else { return false }
        return key != state.lastAcceptedSubjectKey
    }
}
