import Foundation
import CryptoKit

/// FRAME-COHERENCE — the invariant that precedes `masked_before_capture`.
///
/// Every redaction rectangle applied to a stored frame must describe the
/// same stable UI presentation that produced the raster bytes. If the
/// hierarchy subject changes during capture, no frame may be encoded or
/// stored.
///
/// A frame may be LABELLED `masked_before_capture` only after coherence has
/// been established. Until 2026-08-31 nothing asserted this on either platform,
/// and the RN iOS build-14 ladder proved the gap is reachable: the walker read
/// the current tree (the Fidelity Lab) while `drawHierarchy(afterScreenUpdates:
/// false)` rendered the last committed screen (Home). Seven frames were stored
/// as `masked_before_capture` showing a screen whose content no rectangle
/// covered — opaque rectangles landing correctly relative to the WRONG image.
///
/// This type is PURE and UIKit-free so it can be tested without a device, and
/// so the fingerprint cannot accidentally reach for a live view.
///
/// PRIVACY: the fingerprint consumes STRUCTURE ONLY — route id, a
/// per-process window token, root bounds, and per node the canonical id, the
/// role, the privacy treatment and the bounds, plus the masked/blocked
/// geometry. It never reads a text VALUE, an accessibility label, an image, or
/// any node content; `textLength` is deliberately excluded as well, because a
/// character count is content-derived and coherence does not need it.
public enum CaptureCoherence {

    /// The identity of the thing being captured, resolved twice and compared.
    public struct Subject: Equatable {
        public let routeId: String
        /// Stable within THIS process only, and hashed. Never serialised to the
        /// wire; it exists so "a different window" is distinguishable from
        /// "the same window, changed".
        public let windowToken: String
        public let rootW: Int
        public let rootH: Int
        public init(routeId: String, windowToken: String, rootW: Int, rootH: Int) {
            self.routeId = routeId; self.windowToken = windowToken
            self.rootW = rootW; self.rootH = rootH
        }
    }

    /// A completed prepare/verify observation: what was captured, and the
    /// structural digest of it.
    public struct Observation: Equatable {
        public let subject: Subject
        public let fingerprint: String
        public init(subject: Subject, fingerprint: String) {
            self.subject = subject; self.fingerprint = fingerprint
        }
    }

    /// Typed refusal reasons. These are CAPTURE REFUSALS — an expected state
    /// while the UI is changing — never quarantine conditions and never a
    /// record-level rejection. A navigation transition is a normal thing for an
    /// app to be doing.
    public enum Mismatch: String {
        case routeChanged      = "route_changed"
        case windowChanged     = "window_changed"
        case dimensionsChanged = "dimensions_changed"
        case geometryChanged   = "geometry_changed"
        /// The second observation could not be taken at all (no window, left
        /// the foreground, walk refused). Unknown is not coherent.
        case subjectUnstable   = "subject_unstable"
    }

    private static let sep = "\u{1F}"

    /// Canonical, order-sensitive structural digest.
    ///
    /// Order matters on purpose: two trees with the same multiset of nodes in a
    /// different arrangement are a different presentation, and a set-based
    /// digest would call them equal.
    public static func fingerprint(subject: Subject,
                                   hierarchy: HierarchyResult,
                                   redaction: FramePolicy.Redaction) -> String {
        var parts: [String] = [
            "r:" + subject.routeId,
            "w:" + subject.windowToken,
            "d:\(subject.rootW)x\(subject.rootH)",
        ]
        func node(_ n: CanonicalNode) {
            let b = n.bounds
            parts.append("n:" + n.id + "|" + n.role + "|" + n.privacy + "|"
                         + (b.map { "\($0.x),\($0.y),\($0.w),\($0.h)" } ?? "-"))
            n.children.forEach(node)
        }
        hierarchy.nodes.forEach(node)
        // The painted geometry is digested EXPLICITLY as well as implicitly.
        // The rectangles are the thing the invariant is about; deriving them a
        // second time from the same nodes would make the digest agree with
        // itself rather than with what will be painted.
        for b in redaction.masked  { parts.append("m:\(b.x),\(b.y),\(b.w),\(b.h)") }
        for b in redaction.blocked { parts.append("b:\(b.x),\(b.y),\(b.w),\(b.h)") }
        let pre = parts.joined(separator: sep)
        return SHA256.hash(data: Data(pre.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Compare the prepare-phase observation with the verify-phase one.
    /// `nil` == coherent. The most specific difference is reported first so the
    /// refusal names what actually moved.
    public static func verify(prepared: Observation, verified: Observation?) -> Mismatch? {
        guard let v = verified else { return .subjectUnstable }
        if prepared.subject.routeId != v.subject.routeId { return .routeChanged }
        if prepared.subject.windowToken != v.subject.windowToken { return .windowChanged }
        if prepared.subject.rootW != v.subject.rootW
            || prepared.subject.rootH != v.subject.rootH { return .dimensionsChanged }
        if prepared.fingerprint != v.fingerprint { return .geometryChanged }
        return nil
    }
}
