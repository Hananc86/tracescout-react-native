import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// WHY a durable read may not be trusted — ONE typed fact.
///
/// A REFUSAL THAT COLLAPSES INTO A BARE `0` IS UNDIAGNOSABLE. The
/// fail-closed rule below deliberately wedges destructive mutation on a store
/// it cannot read, and an operator who can see only "nothing was removed"
/// cannot tell that from an empty queue, a shifted prefix or a bug. Every
/// refusal therefore names the class of damage that caused it.
public enum StoreIntegrity: String {
    /// Every physical frame was read AND authenticated. Destructive mutation
    /// is allowed.
    case ok = "ok"
    /// The backing file exists and could not be read at all.
    case unreadableFile = "unreadable_file"
    /// A sealed frame failed GCM authentication. Its plaintext is gone.
    case authFailure = "auth_failure"
    /// A length prefix no `append` could have written (zero, or absurdly
    /// large) — a corrupted prefix rather than a torn write.
    case invalidLengthPrefix = "invalid_length_prefix"
    /// A plausible length running past the end of the file, or trailing bytes
    /// too short to carry a prefix. A torn write.
    case tornTail = "torn_tail"

    public var isTrustworthy: Bool { self == .ok }
}

/// The durable FIFO the pipeline drains. Deliberately tiny: read the records,
/// remove a PREFIX. Nothing here can reorder, rewrite or selectively delete.
public protocol RecordStore {
    func readRecords() -> [Data]

    /// The store's integrity, as one typed fact.
    ///
    /// THERE IS DELIBERATELY NO DEFAULT. A protocol extension returning
    /// `.ok` would be FAIL-OPEN, and a future adapter would inherit it by
    /// saying nothing — silently re-opening the deletion defect this whole
    /// contract exists to close. Every conformer must state its own integrity,
    /// so the compiler is the guard rather than a review convention.
    func integrity() -> StoreIntegrity

    @discardableResult func removeFirst(_ count: Int) -> Int
    /// Returns whether the record is DURABLY on disk. It used to return Void
    /// and swallow every failure, which made "the opener is durably
    /// established" an unassertable claim — the property the session invariant
    /// now rests on.
    @discardableResult func append(_ record: Data) -> Bool
    var byteSize: Int { get }
    var count: Int { get }

    /// Reorder / drop by index, for the frame freshness policy.
    ///
    /// THE DEFAULT REFUSES. A store that cannot express the operation must
    /// say so, because the caller's contract is "make room, THEN append" — a
    /// default that silently returned true would let the append push the store
    /// past the very cap the policy exists to hold.
    @discardableResult func rewrite(keepingIndices order: [Int]) -> Bool
}

public extension RecordStore {
    @discardableResult func rewrite(keepingIndices order: [Int]) -> Bool { false }

    /// Can an ABSENCE observed in `readRecords` be trusted?
    ///
    /// `false` means the read was lossy — the backing file could not be read,
    /// or a sealed frame failed authentication and was `compactMap`-ed away —
    /// so "record X is not in this list" is NOT proof that record X does not
    /// exist.
    ///
    /// LOAD-BEARING FOR THE TERMINAL CLASSIFIER. An opener lost to
    /// corruption is absent from the list in exactly the same way as an opener
    /// that never existed; only this flag separates them, and parking a slice
    /// on the first is destroying recoverable state on a guess.
    ///
    /// DERIVED, AND NOT A PROTOCOL REQUIREMENT. It is computed from
    /// `integrity` and cannot be overridden through the protocol, so the two
    /// answers can never disagree. One fact, one name — a conformer that
    /// reported `true` here while its integrity said otherwise is precisely the
    /// divergence this replaces.
    func readIsComplete() -> Bool { integrity().isTrustworthy }
}

/// A batch parked because the BUILD that produced it is refused.
public struct ParkedBatch {
    public let sessionId: String
    public let chunkId: String
    public let recordCount: Int
    public let code: String?
    public let parkedAtMillis: Int64
    public init(sessionId: String, chunkId: String, recordCount: Int,
                code: String?, parkedAtMillis: Int64) {
        self.sessionId = sessionId; self.chunkId = chunkId
        self.recordCount = recordCount; self.code = code
        self.parkedAtMillis = parkedAtMillis
    }
}

public struct ParkStatus {
    public let batches: Int
    public let records: Int
    public let bytes: Int64
    /// The manifest's own trust class. A diagnostic reporting `batches: 0` for
    /// a store it could not read is the defect this exists to remove, repeated
    /// where an operator would believe it.
    public let integrity: StoreIntegrity
    public init(batches: Int, records: Int, bytes: Int64,
                integrity: StoreIntegrity = .ok) {
        self.batches = batches; self.records = records; self.bytes = bytes
        self.integrity = integrity
    }
}

/// The third durable fate.
///
/// **Deliberately WRITE-AND-READ ONLY.** There is no `delete`, no `clear` and
/// no `reactivate`: automatic reactivation must be UNREPRESENTABLE, not merely
/// undone, and nothing that can destroy preserved customer records may exist
/// behind an API a caller could reach. Mirrors `ParkStore.kt`.
/// Twin of the Kotlin `ParkOutcome`. See that type for the full reasoning: a
/// boolean collapsed "capacity exhausted" into "transient failure", and the
/// pipeline then owed a bounded retry to a condition that can never clear.
public enum ParkOutcome {
    case parked
    /// Transient write/verification failure — a bounded local retry is owed.
    case retryableFailure
    /// Cannot succeed without an external change. Bytes retained, and **no
    /// automatic retry of any kind**.
    case blocked
}

/// A manifest read that can say it FAILED, rather than only that it found
/// nothing. Twin of the Kotlin `ParkManifestRead`.
///
/// **ONE VALUE THE KOTLIN TWIN HAS AND THIS DELIBERATELY DOES NOT:**
/// `entry_undecodable`. On Android the manifest is its OWN file holding nothing
/// but entries, so a frame that decrypts and does not parse is lost knowledge.
/// Here the parked RECORDS and their markers share ONE file by design, so a
/// frame that is not JSON is the ordinary case — reporting it as damage would
/// make every healthy read look corrupt. The asymmetry is real and is stated
/// rather than smoothed into a shared lie.
public struct ParkManifestRead {
    public let entries: [ParkedBatch]
    public let integrity: StoreIntegrity
    public var isTrustworthy: Bool { integrity.isTrustworthy }
    public init(entries: [ParkedBatch], integrity: StoreIntegrity) {
        self.entries = entries
        self.integrity = integrity
    }
}

public protocol ParkStore {
    func park(_ batch: ParkedBatch, records: [Data]) -> ParkOutcome

    /// **A REQUIREMENT WITH NO DEFAULT — `entries` ALONE COULD NOT TELL
    /// "NOTHING IS PARKED" FROM "I COULD NOT READ".** Both answered with an
    /// empty array, and the callers acted on that emptiness: the idempotency
    /// check appended a SECOND copy of a batch already preserved, the
    /// reconcile returned as though nothing was parked and left a duplicate
    /// active prefix unsettled, and the terminal lookup re-asked the server
    /// for a fact the device had already durably learned.
    ///
    /// No default, for the same reason `integrity` has none: a conformer
    /// that says nothing must not be recorded as having claimed a trustworthy
    /// read.
    func entriesRead() -> ParkManifestRead

    func status() -> ParkStatus
}

public extension ParkStore {
    /// DERIVED, never a requirement — it cannot disagree with `entriesRead`,
    /// because there is only one thing to disagree with. A caller about to
    /// WRITE or REMOVE must use `entriesRead`.
    func entries() -> [ParkedBatch] { entriesRead().entries }
}

/// Fail-closed default: REFUSES to park, so a caller that has not wired a real
/// store keeps retain-in-place behaviour rather than silently losing a slice.
public final class NoParkStore: ParkStore {
    public init() {}
    // BLOCKED, not retryable: an unwired store never becomes wired at
    // runtime, so its refusal can no more clear by retrying than a full one.
    public func park(_ batch: ParkedBatch, records: [Data]) -> ParkOutcome { .blocked }

    /// `.ok`, NOT an unreadable class. There is genuinely nothing parked
    /// and that is genuinely knowable: this store has no container to fail to
    /// read. Reporting it untrustworthy would wedge the reconcile path for
    /// every caller that has not wired a park store — a fail-closed answer to
    /// a question that has a certain one.
    public func entriesRead() -> ParkManifestRead {
        ParkManifestRead(entries: [], integrity: .ok)
    }
    public func status() -> ParkStatus { ParkStatus(batches: 0, records: 0, bytes: 0) }
}

/// AES-GCM encrypted, length-prefixed append-only file store.
///
/// The key lives in the Keychain (`SecureConfig`), never in the file, never in
/// JS and never in a log line. Records are individually sealed so a torn write
/// costs one record rather than the whole queue.
#if canImport(CryptoKit)
public final class EncryptedFileStore: RecordStore, ParkStore {
    private let url: URL
    private let key: SymmetricKey
    private let lock = NSLock()

    public init(url: URL, key: Data) {
        self.url = url
        self.key = SymmetricKey(data: key)
    }

    /// A length prefix larger than this cannot have come from `append`: the
    /// frame store caps a single frame at 512 KiB and a serialised record is
    /// orders of magnitude smaller. It is a plausibility bound for the
    /// DIAGNOSTIC only — a corrupted prefix above or below it refuses either
    /// way, the constant only decides which reason is reported.
    static let maxPlausibleFrameBytes = 64 * 1024 * 1024

    /// Refusal diagnostics, deduped per operation.
    ///
    /// A WEDGED STORE IS RETRIED FOREVER BY DESIGN, so a line per attempt
    /// would flood the 256 KB trace and evict the evidence that explains it.
    /// The same (operation, reason) pair stays quiet until the reason CHANGES,
    /// which is also the only moment the answer carries new information.
    private var lastRefusal: [String: StoreIntegrity] = [:]

    public var count: Int { readRecords().count }
    public var byteSize: Int {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int) ?? 0
    }

    @discardableResult
    public func append(_ record: Data) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let sealed = try? AES.GCM.seal(record, using: key).combined else { return false }
        var frame = Data()
        var len = UInt32(sealed.count).bigEndian
        withUnsafeBytes(of: &len) { frame.append(contentsOf: $0) }
        frame.append(sealed)
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                let h = try FileHandle(forWritingTo: url)
                defer { try? h.close() }
                // RECORD THE PRE-APPEND OFFSET. A write that fails partway
                // leaves a FRAGMENT of a new frame at the tail, which the next
                // read classifies as a torn tail — and under the fail-closed
                // rule below that wedges every destructive operation on this
                // store for the life of the install. Truncating back to the
                // exact offset prevents CREATING corruption.
                //
                // THIS RECLAIMS NOTHING. It cannot repair a tail that is
                // already torn, and it is deliberately not a step toward doing
                // so: reclamation needs identity and policy work this phase
                // does not have.
                let before = try h.seekToEnd()
                do {
                    try h.write(contentsOf: frame)
                    // THE DURABILITY BARRIER THE ANDROID TWIN HAS ALWAYS HAD
                    // AND THIS PATH DID NOT. `FileHandle.write` returns once the
                    // bytes are in the page cache; without an explicit
                    // `synchronize` this function returned `true` — "stored"
                    // for a record a process kill could still lose. The store
                    // exists to survive process death, so returning before the
                    // barrier made the one claim it is for a lie.
                    //
                    // NOT A POWER-LOSS CERTIFICATION. `synchronize` is
                    // `fsync(2)`; it is NOT `F_FULLFSYNC`, so it does not claim
                    // the device flushed its own write cache. What it buys is
                    // survival of PROCESS death, which is the failure this
                    // queue is built around.
                    try h.synchronize()
                } catch {
                    // Best effort. If the truncate ALSO fails the tail stays
                    // torn and the store fails closed — the safe direction, and
                    // the reason this is not reported as success.
                    try? h.truncate(atOffset: before)
                    noteRefusal("append", .tornTail)
                    return false
                }
            } else {
                // Foundation's write is not a durability barrier either. Open
                // the file we just created and commit it, so the FIRST record
                // has the same guarantee as every later one rather than being
                // the one that silently does not.
                try frame.write(to: url, options: .completeFileProtection)
                if let h = try? FileHandle(forWritingTo: url) {
                    defer { try? h.close() }
                    do { try h.synchronize() } catch { return false }
                } else {
                    return false
                }
            }
            return true
        } catch {
            return false
        }
    }

    public func readRecords() -> [Data] {
        lock.lock(); defer { lock.unlock() }
        return scanLocked().records
    }

    /// THE `compactMap` ABOVE USED TO ERASE ITS OWN LOSSES SILENTLY.
    ///
    /// A frame whose GCM authentication fails is dropped and the caller sees a
    /// shorter list with no indication anything is missing. For the terminal
    /// classifier that is fatal: an opener destroyed by corruption becomes
    /// indistinguishable from an opener that never existed, and the slice gets
    /// parked on ignorance.
    ///
    /// `false` also covers a backing file that could not be read at all, where
    /// `readFramesLocked` yields nothing and an EMPTY store and an
    /// UNREADABLE one would otherwise look identical.
    public func integrity() -> StoreIntegrity {
        lock.lock(); defer { lock.unlock() }
        return scanLocked().integrity
    }

    /// Reorder / drop by index, for the freshness policy.
    ///
    /// SAME MECHANISM AS `removeFirst`, deliberately. That method is already
    /// a whole-file read-modify-write, so expressing eviction and drain
    /// prioritisation through the same path introduces no new durability class
    /// it is the operation the store has always performed, given a different
    /// list of survivors. An out-of-range index rewrites NOTHING: a partial
    /// application of a freshness plan is worse than none.
    ///
    /// TRUST IS DECIDED HERE, FROM THE SAME LOCKED SCAN THAT PRODUCES THE
    /// SURVIVORS. The caller must not have to remember to ask first: a rewrite
    /// is a WHOLE-FILE replacement, so on an untrustworthy read it does not
    /// merely keep the wrong records — it destroys every frame that did not
    /// survive the lossy traversal, permanently, with no copy anywhere.
    ///
    /// AND `rewrite([])` IS THE SHARPEST CASE. An empty keep-list is a legal
    /// request on a healthy store (evict everything) and an irreversible
    /// truncation-to-empty on a store we cannot read. The gate is BEFORE the
    /// index validation precisely so the empty list cannot slip past it.
    @discardableResult
    public func rewrite(keepingIndices order: [Int]) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let scan = scanLocked()
        guard scan.integrity.isTrustworthy else {
            noteRefusal("rewrite", scan.integrity)
            return false
        }
        guard order.allSatisfy({ $0 >= 0 && $0 < scan.frames.count }),
              Set(order).count == order.count else { return false }
        // ATOMIC. This is a WHOLE-FILE replacement, and a direct write meant a
        // process death partway through could destroy every record the rewrite
        // intended to KEEP. Session-coherent eviction will lean on exactly this
        // primitive, so it has to be old-file-or-new-file with nothing between.
        return Self.writeFramesAtomically(order.map { scan.frames[$0] }, to: url)
    }

    @discardableResult
    /// Remove the first `count` records, returning how many were ACTUALLY
    /// removed.
    ///
    /// THIS USED TO LIE, AND IT WAS NOT ATOMIC. `try? out.write(.)`
    /// swallowed every failure and the method returned `count` regardless — so
    /// a failed durable rewrite reported success and the upload pipeline could
    /// settle its in-flight descriptor and report `terminalMoved` while the
    /// active prefix was still on disk. And the write was a DIRECT replacement,
    /// so a process death partway through could destroy the trailing healthy
    /// records it was supposed to keep.
    ///
    /// Now: atomic replacement, and `count` ONLY when it committed.
    ///
    /// AND THE COMPLETENESS RESULT COMES FROM THE SAME LOCKED SCAN THAT
    /// PRODUCES THE FRAMES, not from a separate `readIsComplete` the caller
    /// might forget or might take at a different moment. `count` here is a
    /// position in the DECRYPTED record list the caller read, while the
    /// physical frame list is what gets rewritten; the instant one frame fails
    /// authentication those two lists have different lengths and every
    /// position in one means something else in the other. Refusing is what
    /// keeps the two spaces from ever having to be reconciled.
    public func removeFirst(_ count: Int) -> Int {
        lock.lock(); defer { lock.unlock() }
        let scan = scanLocked()
        guard scan.integrity.isTrustworthy else {
            noteRefusal("removeFirst", scan.integrity)
            return 0
        }
        guard count > 0, count <= scan.frames.count else { return 0 }
        return Self.writeFramesAtomically(Array(scan.frames.dropFirst(count)), to: url)
            ? count : 0
    }

    private func noteRefusal(_ operation: String, _ reason: StoreIntegrity) {
        if lastRefusal[operation] == reason { return }
        lastRefusal[operation] = reason
        // Identity of the STORE and the class of damage. No record, no frame,
        // no digest preimage, no key material — the same rule the park manifest
        // keeps.
        RecoveryTrace.shared.log("store_mutation_refused", [
            ("operation", operation),
            ("reason", reason.rawValue),
            ("store", url.lastPathComponent),
        ])
    }

    /// Whole-file replacement that is either the OLD file or the NEW file,
    /// never a half-copied destination.
    ///
    /// `.atomic` IS THE POINT. Foundation writes to a temporary file and
    /// renames it into place, so a crash mid-write leaves the previous contents
    /// intact. Without it a session-coherent eviction — which rewrites the
    /// whole file — could lose every record it intended to keep.
    static func writeFramesAtomically(_ frames: [Data], to url: URL) -> Bool {
        var out = Data()
        for f in frames {
            var len = UInt32(f.count).bigEndian
            withUnsafeBytes(of: &len) { out.append(contentsOf: $0) }
            out.append(f)
        }
        do {
            try out.write(to: url, options: [.atomic, .completeFileProtection])
            // `.atomic` IS ATOMICITY, NOT DURABILITY. Foundation writes a
            // temporary file and renames it, so a crash cannot leave a
            // half-written store — but neither the contents nor the resulting
            // directory entry are committed to disk before this returns. This
            // function's answer AUTHORISES REMOVING RECORDS (it is what
            // `removeFirst` and `rewrite` report), so returning true for a
            // replacement that a power loss could undo is the same defect the
            // Android rewrite just had its parent-directory fsync added for.
            if let h = try? FileHandle(forWritingTo: url) {
                defer { try? h.close() }
                do { try h.synchronize() } catch { return false }
            } else {
                return false
            }
            return true
        } catch {
            // The OLD file remains authoritative.
            return false
        }
    }

    /// ONE traversal, THREE facts: the physical frames, the records that
    /// authenticated, and — when something was lost — WHAT.
    ///
    /// AUTHENTICATION IS PART OF THE ANSWER, AND THAT IS THE WHOLE POINT.
    /// `readRecords` drops a frame whose GCM tag does not verify, so the
    /// moment one exists the decrypted list is SHORTER than the physical frame
    /// list. A destructive operation that trusted the framing alone would be
    /// acting on physical positions the caller never saw, and every index in
    /// the two lists would silently mean a different record. Producing both
    /// lists and the verdict in a single locked pass is what makes it
    /// impossible for them to describe different moments of the file.
    ///
    /// THE FIRST OBSERVED DAMAGE WINS, deterministically. A file can be both
    /// auth-damaged and torn; reporting whichever was reached first keeps the
    /// answer stable across reads, and every one of them refuses identically.
    private func scanLocked() -> (frames: [Data], records: [Data], integrity: StoreIntegrity) {
        guard let raw = readRawLocked() else { return ([], [], .unreadableFile) }
        var frames: [Data] = []
        var records: [Data] = []
        var integrity: StoreIntegrity = .ok
        var i = 0
        while i + 4 <= raw.count {
            let len = raw.subdata(in: i..<(i+4)).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            // A length no `append` could have written. Sealed AES-GCM output is
            // never empty, and never remotely approaches the plausibility
            // bound, so either value is a CORRUPTED PREFIX rather than a write
            // that was cut short.
            if len == 0 || Int(len) > Self.maxPlausibleFrameBytes {
                if integrity == .ok { integrity = .invalidLengthPrefix }
                break
            }
            let start = i + 4
            let end = start + Int(len)
            // A plausible length running past the end of the file is a TORN
            // WRITE: everything from here on is unrecoverable, and an absence
            // beyond this point proves nothing.
            if end > raw.count {
                if integrity == .ok { integrity = .tornTail }
                break
            }
            let sealed = raw.subdata(in: start..<end)
            frames.append(sealed)
            if let box = try? AES.GCM.SealedBox(combined: sealed),
               let plain = try? AES.GCM.open(box, using: key) {
                records.append(plain)
            } else if integrity == .ok {
                // RECORDED, AND THE WALK CONTINUES. The records that DID
                // authenticate stay readable — a lossy store must still serve
                // reads, it must only refuse to be MUTATED. Stopping here would
                // turn one bad frame into a total read outage.
                integrity = .authFailure
            }
            i = end
        }
        // Trailing bytes too short to carry a length prefix are also a torn
        // write, and were once invisible.
        if integrity == .ok && i < raw.count { integrity = .tornTail }
        return (frames, records, integrity)
    }

    /// `nil` ONLY when the file exists and could not be read.
    ///
    /// An ABSENT file is genuinely empty and genuinely complete — the store has
    /// simply never been written — and must not be confused with an unreadable
    /// one. The two were once the same `[]`.
    private func readRawLocked() -> Data? {
        if !FileManager.default.fileExists(atPath: url.path) { return Data() }
        return try? Data(contentsOf: url)
    }

    // ---- ParkStore. Write-and-read only: no delete/clear/reactivate exists.
    /// THE COMMIT MARKER IS WRITTEN **LAST**, AND THAT ORDER IS THE WHOLE
    /// SAFETY PROPERTY.
    ///
    /// This used to append the header FIRST and the records after it. Because
    /// `entries` recognises a parked batch from the header ALONE, a crash
    /// between the two produced a manifest entry for a batch whose records had
    /// not been copied — and `reconcileParked` removes the ACTIVE prefix
    /// whose chunkId matches a manifest entry. So:
    ///
    /// append header -> succeeds
    /// crash mid record copy
    /// restart: entries sees a "complete" batch of N records
    /// reconcile matches the active prefix and REMOVES it
    /// => the only surviving copy is INCOMPLETE
    ///
    /// A fix whose entire purpose is "preserve, never delete" would then have
    /// been the thing that deleted. Records are now written first and verified,
    /// and only then is the marker appended.
    ///
    /// A crash before the marker leaves ORPHAN parked ciphertext — bytes with
    /// no manifest entry. That is accepted deliberately: orphan ciphertext is
    /// inert and costs space, while a marker that outruns its records costs
    /// customer data. `entries` ignores anything with no marker, so the
    /// active prefix stays exactly where it is and the next drain re-parks it.
    public func park(_ batch: ParkedBatch, records: [Data]) -> ParkOutcome {
        // IDEMPOTENT BY chunkId — AND iOS DID NOT HAVE THIS.
        //
        // The crash window between "parked copy durable" and "removeFirst"
        // is settled by the next drain RE-PARKING the same batch. Without
        // idempotency that second park appends a DUPLICATE copy of every
        // record, so the crash-recovery path silently doubles the parked
        // store. Android's store has carried this contract from the start;
        // the Swift twin never did, and no test looked until the A5 crash
        // window was written against the REAL store rather than a memory fake.
        // AN UNREADABLE STORE MAY NOT AUTHORISE A WRITE. Every decision
        // below rests on "is this batch already parked?", and the old code
        // turned an unknown into NO, then appended a duplicate copy of every
        // record of a batch that may already be preserved in full. The parked
        // store has no delete path, so that duplication is permanent.
        //
        // RETRYABLE, not BLOCKED: a torn tail is a condition a later write or
        // read can resolve, unlike exhausted capacity. The active bytes are
        // retained either way, which is the safe direction.
        let manifest0 = entriesRead()
        guard manifest0.isTrustworthy else {
            noteRefusal("park", manifest0.integrity)
            return .retryableFailure
        }
        if manifest0.entries.contains(where: { $0.chunkId == batch.chunkId }) { return .parked }
        let before = readRecords().count
        for r in records {
            // A single failed append must not be followed by a commit marker
            // claiming the batch is complete.
            guard append(r) else { return .retryableFailure }
        }
        // VERIFY the preserved copy is durably readable BEFORE the marker that
        // authorises removing the active bytes exists at all.
        let mid = readRecords()
        guard mid.count >= before + records.count else { return .retryableFailure }
        let tail = Array(mid.suffix(records.count))
        guard tail.count == records.count else { return .retryableFailure }
        for (i, r) in records.enumerated() where tail[i] != r {
            // The bytes on disk are not the bytes we were asked to preserve.
            return .retryableFailure
        }
        let marker = RnEnvelope.json([
            ("parkedBatch", .string(batch.chunkId)),
            ("sessionId", .string(batch.sessionId)),
            ("recordCount", .int(batch.recordCount)),
            ("code", .string(batch.code ?? "")),
            ("parkedAtMillis", .int64AsString(batch.parkedAtMillis)),
        ])
        guard append(Data(marker.utf8)) else { return .retryableFailure }
        // And the marker itself must be readable back, because the caller
        // removes active bytes on the strength of this answer.
        return entries().contains { $0.chunkId == batch.chunkId } ? .parked : .retryableFailure
    }

    public func entriesRead() -> ParkManifestRead {
        lock.lock()
        let scan = scanLocked()
        lock.unlock()
        // THE INTEGRITY COMES FROM THE SAME SCAN THAT PRODUCED THE FRAMES,
        // not from a second read that could observe a different file.
        return ParkManifestRead(entries: Self.decodeMarkers(scan.records),
                                integrity: scan.integrity)
    }

    private static func decodeMarkers(_ records: [Data]) -> [ParkedBatch] {
        records.compactMap { d in
            guard let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
                  let chunk = o["parkedBatch"] as? String,
                  let session = o["sessionId"] as? String,
                  let n = o["recordCount"] as? Int else { return nil }
            let at = Int64((o["parkedAtMillis"] as? String) ?? "0") ?? 0
            let code = o["code"] as? String
            return ParkedBatch(sessionId: session, chunkId: chunk, recordCount: n,
                               code: (code?.isEmpty ?? true) ? nil : code, parkedAtMillis: at)
        }
    }

    public func status() -> ParkStatus {
        let r = entriesRead()
        return ParkStatus(batches: r.entries.count,
                          records: r.entries.reduce(0) { $0 + $1.recordCount },
                          bytes: Int64(byteSize),
                          // SURFACED, NOT SWALLOWED. `batches: 0` for a store
                          // that could not be read is exactly the conflation
                          // this change removes; repeating it on the surface an
                          // operator reads is worse than saying nothing.
                          integrity: r.integrity)
    }
}
#endif

/// In-memory store used by the wire-fixture emitter and the unit suite. It is
/// NOT a production path: production always uses the encrypted file store.
///
/// **NON-DURABLE STORE — MAY NOT CERTIFY CORRUPTION BEHAVIOUR.** An array in
/// memory has no framing, no sealed frames and no file, so it cannot be
/// corrupted and therefore cannot demonstrate a corruption refusal. Driving a
/// refusal through a double that can only refuse when TOLD to is the same
/// author's belief tested twice; the corruption controls use the real
/// `EncryptedFileStore` against real damaged bytes.
public final class MemoryRecordStore: RecordStore, ParkStore {
    private var records: [Data] = []
    private var parked: [(ParkedBatch, [Data])] = []
    public init() {}
    public func readRecords() -> [Data] { records }

    /// A fact about this container, not an assumption: an in-memory array
    /// cannot lose a record. Stated explicitly because the protocol has no
    /// default — the compiler would refuse this type otherwise.
    public func integrity() -> StoreIntegrity { .ok }
    @discardableResult public func rewrite(keepingIndices order: [Int]) -> Bool {
        guard order.allSatisfy({ $0 >= 0 && $0 < records.count }),
              Set(order).count == order.count else { return false }
        records = order.map { records[$0] }
        return true
    }
    @discardableResult public func removeFirst(_ count: Int) -> Int {
        let n = min(count, records.count)
        records.removeFirst(n)
        return n
    }
    /// Injectable failure, so the failed-opener path is testable without
    /// contriving a disk fault.
    public var failAppends = false
    @discardableResult
    public func append(_ record: Data) -> Bool {
        if failAppends { return false }
        records.append(record); return true
    }
    public var byteSize: Int { records.reduce(0) { $0 + $1.count } }
    public var count: Int { records.count }
    public func park(_ batch: ParkedBatch, records rs: [Data]) -> ParkOutcome {
        // IDEMPOTENT LIKE THE REAL STORE. A re-park after the crash window
        // must not duplicate, or this double would disagree with production
        // about the one property the crash contract rests on.
        if parked.contains(where: { $0.0.chunkId == batch.chunkId }) { return .parked }
        parked.append((batch, rs)); return .parked
    }
    /// An in-memory array cannot lose a record, so `.ok` is a FACT here, not a
    /// default. Stated explicitly because the protocol has none — the compiler
    /// would refuse this type otherwise.
    public func entriesRead() -> ParkManifestRead {
        ParkManifestRead(entries: parked.map { $0.0 }, integrity: .ok)
    }
    public func status() -> ParkStatus {
        ParkStatus(batches: parked.count,
                   records: parked.reduce(0) { $0 + $1.1.count },
                   bytes: Int64(parked.reduce(0) { $0 + $1.1.reduce(0) { $0 + $1.count } }))
    }
    public func parkedRecords() -> [Data] { parked.flatMap { $0.1 } }
}
