import Foundation

/// **FIXTURE-ONLY, NATIVE, READ-ONLY UAT evidence.**
///
/// It exists because iOS has no adb: a physical-device claim about queue
/// durability, relaunch continuity or offline→reconnect behaviour is otherwise
/// unprovable, and an unprovable claim is one nobody should accept.
///
/// Hard boundary (lead-ruled 2026-08-20), enforced by SHAPE, not convention:
/// - not exposed through `NativeModules.TraceScoutRn`, and no method here is
/// reachable from general JS application code;
/// - every member is a getter. There is NO mutator: no queue write, no
/// dequeue, no park write, no park delete/clear/reactivate, no disconnect;
/// - it can return counts, byte sizes, states, sanitized trace lines and a
/// 12-hex fingerprint — never the ingest key, never the encryption key,
/// never a record body, never a route argument.
///
/// Anything that needs more than this needs a different design, not a wider
/// diagnostics surface.
public enum Diagnostics {

    public struct Snapshot {
        public let configured: Bool
        public let projectId: String?
        public let environment: String?
        public let endpoint: String?
        /// 12 hex chars of SHA-256(ingest key). Proves WHICH key is installed;
        /// useless for authenticating as it.
        public let keyFingerprint: String?
        public let transportState: String
        public let queueRecords: Int
        public let queueBytes: Int
        /// Whether the durable queue can be TRUSTED — and when it cannot, the
        /// class of damage.
        ///
        /// THE COUNTS ALONE CANNOT SHOW A WEDGE. Under the fail-closed rule
        /// a corrupt store keeps serving reads and refuses every destructive
        /// operation, so `queueRecords` and `queueBytes` stay perfectly
        /// plausible while nothing drains. A value other than `ok` is the
        /// difference between "the queue is busy" and "this queue can never
        /// advance again without intervention".
        public let queueIntegrity: String
        /// The same fact for the visual-frame store, which refuses eviction,
        /// prioritisation and settlement under exactly the same rule.
        public let frameStoreIntegrity: String
        public let parkedBatches: Int
        public let parkedRecords: Int
        public let parkedBytes: Int64
        public let appIdentifier: String
        public let sdkFamily: String
        public let sdkVersion: String
        public let hostPlatform: String
        /// F2: iOS has NO durable sequence authority — its
        /// sequence is reconstructed from the surviving queue at open — so the
        /// only honest statement about loss accounting on this platform is
        /// `checkpointDisabled`. A value that claimed a durable sequence this
        /// platform does not hold would be the defect D4-2 exists to refuse.
        /// Not a memberwise-init parameter: it cannot be set to anything else.
        public let lossAuthority: String = Diagnostics.lossAuthorityState
    }

    /// The one value `Snapshot.lossAuthority` can carry on iOS until a Swift
    /// authority exists (a separate track, ported after Android is proven).
    public static let lossAuthorityState = "checkpointDisabled"

    public static func snapshot() -> Snapshot {
        let status = SecureConfig.status()
        let q = RnTransport.shared.queueStatus()
        let p = RnTransport.shared.parkStatus()
        return Snapshot(configured: status.configured,
                        projectId: status.projectId,
                        environment: status.environment,
                        endpoint: status.endpoint,
                        keyFingerprint: status.keyFingerprint,
                        transportState: RnTransport.shared.state().rawValue,
                        queueRecords: q.count,
                        queueBytes: q.bytes,
                        queueIntegrity: q.integrity.rawValue,
                        frameStoreIntegrity: RnTransport.shared.framesStatus().integrity.rawValue,
                        parkedBatches: p.batches,
                        parkedRecords: p.records,
                        parkedBytes: p.bytes,
                        appIdentifier: RnTransport.appIdentifier(),
                        sdkFamily: Producer.SDK_FAMILY,
                        sdkVersion: Producer.SDK_VERSION,
                        hostPlatform: Producer.HOST_PLATFORM_IOS)
    }

    /// Pending local state, for a confirmation dialog that must state what it
    /// is about to destroy. Counts only — no record bodies, no identities.
    public static func pending() -> (queueRecords: Int, queueBytes: Int,
                                     parkedBatches: Int, parkedRecords: Int) {
        let q = RnTransport.shared.queueStatus()
        let p = RnTransport.shared.parkStatus()
        return (q.count, q.bytes, p.batches, p.records)
    }

    /// The three on-disk objects a fixture UAT reset must remove, in the order
    /// they must be removed. Exposed as PATHS, not as a delete function: the
    /// destructive act stays in the fixture, so nothing in the producer can be
    /// asked to erase customer data.
    /// THE PROCESS'S OWN DURABLE RECORDS, VERBATIM.
    ///
    /// It exists because a certification must be able to prove a record is
    /// ACTUALLY DURABLE rather than merely in memory, and the only honest way
    /// to do that is to read the queue the production sink wrote to. The
    /// alternative — handing the observer a test sink — is exactly the defect
    /// this API was added to make detectable: a harness-supplied sink made a
    /// producer with no production path look green.
    ///
    /// It returns the app's own bytes to the app itself; `RecordStore` is
    /// already a public protocol and `pending` already exposes the counts.
    public static func durableRecords() -> [Data] {
        RnTransport.shared.durableRecordSnapshot()
    }

    public static func fixtureResetPaths() -> [URL] {
        let dir = RnTransport.supportDirectory()
        return ["tracescout-rn-queue.bin",
                "tracescout-rn-parked.bin",
                "tracescout-rn-trace.log"].map { dir.appendingPathComponent($0) }
    }

    /// The sanitized durable-trace tail. Every line was written by
    /// `RecoveryTrace`, which is itself an allowlist — this adds no new
    /// disclosure, it only makes the existing one readable on a device with no
    /// debugger attached.
    public static func traceTail(lines: Int = 40) -> [String] {
        RecoveryTrace.shared.tail(lines: lines)
    }
}
