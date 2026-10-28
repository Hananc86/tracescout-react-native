import Foundation

/// The canonical loss-family vocabulary, Swift side.
///
/// THIS IS A TWIN, NOT A SECOND SOURCE OF TRUTH. The authority is
/// `mobile-protocol/src/loss-families.cjs`. This file exists for exactly the
/// reason its Kotlin counterpart (`mobile-loss-core` `LossFamilies.kt`) gives
/// for existing — a compiled language cannot `require` a `.cjs` — and it is
/// pinned to that authority by `parity-vectors/loss-family-vectors.json`,
/// which is GENERATED from the canonical map. `LossFamilyParityTests` fails if
/// the two ever disagree, so a change made on one side alone is caught rather
/// than shipped.
///
/// ## What this is, in F-series terms
///
/// Charter the specification fixes the order: Phase 0 → **F1 (pure core)** → F2 (durable
/// sequence authority) → F3 (durable ledger) → F4 → F5 (ack parser) → F6
/// (emitter) → F7, and *"future-stage features must remain absent until their
/// stage — their symbols are checked for, not assumed"*.
///
/// The vocabulary is **F1**. It is the earliest stage, not a future one, so
/// carrying it is in-order rather than ahead of order.
///
/// AND EVERYTHING AFTER F1 IS DELIBERATELY ABSENT HERE. This type holds no
/// ledger, no durable authority, no ack parser, no emitter and no checkpoint —
/// iOS holds no durable sequence authority at all, which is why
/// `Diagnostics.lossAuthorityState` reports `checkpointDisabled` and why
/// `LossAuthorityStateTests` (D4) scans this directory for the F2/F6 symbols
/// and requires them to be absent. That guard scans THIS file too.
/// `LossFamilyParityTests` additionally pins the deferral as a positive
/// assertion, so the absence is checked rather than assumed.
///
/// THE VECTOR FILE CARRIES SECTIONS THIS TYPE MUST NOT IMPLEMENT.
/// `loss-family-vectors.json` also describes `lossAccountingVersion`,
/// `lossCheckpointAckProtocol`, `validateLossCounts` and `mergeCumulative`.
/// On the Kotlin side those belong to `LossLedger` / `CheckpointAckBinder`
/// (F3 / F5), **not** to `LossFamilies` — measured: the Kotlin family parity
/// test reads exactly `families`, `eventTypeToFamily` and `unknownReturnsNull`,
/// while the ledger test is what reads the version and the ack protocol. This
/// twin mirrors the family half and stops there.
public enum LossFamilies {

    /// `logs` is RESERVED: no current event type maps to it, and nothing may
    /// be mapped to it as a fallback. A counter with no producer is allowed to
    /// stay zero — that is honest. Making it non-empty by pointing something
    /// else at it would be inventing evidence.
    ///
    /// Order is part of the contract: the vectors are an ordered array and the
    /// parity test compares the sequence, not a set.
    public static let all: [String] = [
        "crashes",
        "hangs",
        "hierarchy",
        "interactions",
        "lifecycle",
        "logs",
        "mutations",
        "navigation",
        "network",
        "performance",
        "screenSnapshots",
        "sessionEvents",
        "visualFrames",
    ]

    /// TOTAL over the protocol's event types. The parity test proves it.
    public static let eventTypeToFamily: [String: String] = [
        "mobile_session_start": "sessionEvents",
        "mobile_screen_snapshot": "screenSnapshots",
        "mobile_view_hierarchy": "hierarchy",
        "mobile_view_mutation": "mutations",
        "mobile_interaction": "interactions",
        "mobile_navigation": "navigation",
        "mobile_lifecycle": "lifecycle",
        "mobile_visual_frame": "visualFrames",
        "mobile_network_request": "network",
        "mobile_performance": "performance",
        "mobile_crash": "crashes",
        "mobile_hang": "hangs",
        "mobile_session_end": "sessionEvents",
    ]

    /// RETURNS nil FOR AN UNKNOWN TYPE, NEVER A FALLBACK FAMILY.
    ///
    /// A guessed family is a fabricated attribution: reporting "1 interaction
    /// dropped" when a frame was lost is worse than reporting nothing at all.
    /// Callers MUST fail closed.
    ///
    /// A FAMILY NAME IS NOT AN EVENT TYPE. `"sessionEvents"` is in the
    /// vocabulary and is NOT a key here, so it returns nil — the vectors name
    /// that exact case, because a lookup that accepted its own output would
    /// make a round trip look total when it is not.
    public static func lossFamily(forEventType eventType: String?) -> String? {
        guard let t = eventType else { return nil }
        return eventTypeToFamily[t]
    }

    /// All families at zero. The starting point a cumulative session ledger
    /// would begin from — this type does not keep one, and holding a ledger is
    /// F3, not F1.
    public static func emptyCounts() -> [String: Int64] {
        var out: [String: Int64] = [:]
        for f in all { out[f] = 0 }
        return out
    }
}
