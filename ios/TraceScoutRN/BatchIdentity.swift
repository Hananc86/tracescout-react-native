import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// Content-addressed identity for one submitted batch, and the ONLY thing
/// entitled to authorise a dequeue when it matches the server's ack.
public struct BatchIdentity {
    public let sessionId: String
    public let chunkId: String
    public let firstSequence: String
    public let lastSequence: String
    public let recordCount: Int
    private let digests: [String]

    /// Every field the server echoed must equal what we sent.
    public func matches(_ a: Ack) -> Bool {
        a.sessionId == sessionId &&
        a.chunkId == chunkId &&
        a.firstSequence == firstSequence &&
        a.lastSequence == lastSequence &&
        a.recordCount == recordCount
    }

    /// Re-check that the store's head is still the exact prefix submitted —
    /// records appended while the request was in flight must survive.
    public func prefixMatches(_ current: [Data]) -> Bool {
        guard current.count == digests.count else { return false }
        for i in digests.indices where BatchIdentity.sha256Hex(current[i]) != digests[i] {
            return false
        }
        return true
    }

    public static func sha256Hex(_ d: Data) -> String {
        #if canImport(CryptoKit)
        return SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
        #else
        return Sha256.hex(d)
        #endif
    }

    static func sequenceOf(_ d: Data) -> Int64 {
        guard let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return -1 }
        if let n = o["sequence"] as? Int { return Int64(n) }
        if let n = o["sequence"] as? Int64 { return n }
        return -1
    }

    static func stringField(_ d: Data, _ key: String) -> String? {
        guard let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return nil }
        return o[key] as? String
    }

    public static func of(sessionId: String, records: [Data]) -> BatchIdentity {
        let digests = records.map { sha256Hex($0) }
        let seqs = records.map { sequenceOf($0) }.filter { $0 >= 0 }
        // Content-addressed over the batch, so a retry of the same bytes is the
        // same chunk and the server answers `duplicate` instead of storing a
        // second copy. 32 hex chars satisfies the canonical grammar the API
        // REQUIRES — a malformed id is a permanent refusal, never a silent
        // server-side substitution.
        let basis = sessionId + "|" + digests.joined(separator: "|")
        let chunkId = String(sha256Hex(Data(basis.utf8)).prefix(32))
        return BatchIdentity(sessionId: sessionId,
                             chunkId: chunkId,
                             firstSequence: String(seqs.min() ?? 0),
                             lastSequence: String(seqs.max() ?? 0),
                             recordCount: records.count,
                             digests: digests)
    }

    /// The contiguous, session-owned prefix of the queue, BOUNDED by the actual
    /// encoded body it would produce. A batch whose sequences are
    /// non-contiguous is refused permanently by the server
    /// (`batch_sequence_not_contiguous`) — a RANGE cannot identify a batch
    /// with gaps — so the slice must never span a discontinuity.
    ///
    /// THE COUNT CAP WAS NOT ENOUGH, and that is the fix. 64 records of
    /// ordinary size are small; 64 records of large ones are not, and the
    /// deployed API answers a body over 2 MiB with a 413 that used to
    /// quarantine — i.e. DELETE — the prefix. The bound is now on the encoded
    /// body, envelope included, as well as on the count; see `ChunkBounds`.
    ///
    /// Ordering and contiguity are UNCHANGED. The bound only decides where the
    /// contiguous run is CUT, never which records go in it or in what order.
    public static func sessionOwnedPrefix(
        _ records: [Data],
        maxRecords: Int = ChunkBounds.maxRecordsPerChunk,
        budgetBytes: Int = ChunkBounds.defaultBodyBudgetBytes
    ) -> (sessionId: String, slice: [Data])? {
        guard let head = records.first,
              let owner = stringField(head, "sessionId") else { return nil }
        var slice: [Data] = []
        var expected: Int64? = nil
        for r in records {
            if slice.count >= maxRecords { break }
            guard stringField(r, "sessionId") == owner else { break }
            let s = sequenceOf(r)
            guard s >= 0 else { break }
            if let e = expected, s != e { break }
            expected = s + 1
            slice.append(r)
        }
        if slice.isEmpty { return nil }
        // The SIZE bound is applied to the contiguous run, never before it:
        // cutting for size first could make the remaining head start at a
        // sequence the server reads as a gap. Contiguity decides WHAT may be in
        // a batch; the bound decides only HOW MUCH of it fits.
        let admitted = ChunkBounds.boundedCount(
            sessionIdUtf8Len: ChunkBounds.encodedSessionIdLength(owner),
            recordSizes: slice.map { $0.count },
            budgetBytes: budgetBytes,
            maxRecords: maxRecords)
        return (owner, Array(slice.prefix(max(1, min(admitted, slice.count)))))
    }
}

/// Pure-Swift SHA-256 so the protocol core builds and its bytes can be emitted
/// on a platform without CryptoKit (the on-host wire gate never runs on Darwin).
enum Sha256 {
    private static let k: [UInt32] = [
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]

    static func hex(_ data: Data) -> String {
        var h: [UInt32] = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                           0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]
        var msg = [UInt8](data)
        let bitLen = UInt64(msg.count) * 8
        msg.append(0x80)
        while msg.count % 64 != 56 { msg.append(0) }
        for i in (0..<8).reversed() { msg.append(UInt8truncating(bitLen >> (UInt64(i) * 8))) }
        var chunkStart = 0
        while chunkStart < msg.count {
            var w = [UInt32](repeating: 0, count: 64)
            for i in 0..<16 {
                let o = chunkStart + i * 4
                w[i] = (UInt32(msg[o]) << 24) | (UInt32(msg[o+1]) << 16) |
                       (UInt32(msg[o+2]) << 8) | UInt32(msg[o+3])
            }
            for i in 16..<64 {
                let s0 = rotr(w[i-15], 7) ^ rotr(w[i-15], 18) ^ (w[i-15] >> 3)
                let s1 = rotr(w[i-2], 17) ^ rotr(w[i-2], 19) ^ (w[i-2] >> 10)
                w[i] = w[i-16] &+ s0 &+ w[i-7] &+ s1
            }
            var a = h[0], b = h[1], c = h[2], d = h[3]
            var e = h[4], f = h[5], g = h[6], hh = h[7]
            for i in 0..<64 {
                let S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
                let ch = (e & f) ^ (~e & g)
                let t1 = hh &+ S1 &+ ch &+ k[i] &+ w[i]
                let S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
                let maj = (a & b) ^ (a & c) ^ (b & c)
                let t2 = S0 &+ maj
                hh = g; g = f; f = e; e = d &+ t1
                d = c; c = b; b = a; a = t1 &+ t2
            }
            h[0] = h[0] &+ a; h[1] = h[1] &+ b; h[2] = h[2] &+ c; h[3] = h[3] &+ d
            h[4] = h[4] &+ e; h[5] = h[5] &+ f; h[6] = h[6] &+ g; h[7] = h[7] &+ hh
            chunkStart += 64
        }
        return h.map { String(format: "%08x", $0) }.joined()
    }

    private static func rotr(_ x: UInt32, _ n: UInt32) -> UInt32 { (x >> n) | (x << (32 - n)) }
    private static func UInt8truncating(_ v: UInt64) -> UInt8 { UInt8(v & 0xff) }
}
