import Foundation
import CryptoKit

/// THE VISIBLE-STATE DIGEST — what the hierarchy fingerprint structurally
/// cannot see.
///
/// ## The defect this exists for, measured
///
/// Drive `ts_1789734713254_cd1c7274959c` (the retained Lab baseline) scrolled
/// the bundled help document five times. Attributing every frame to its cause
/// gives, per scroll:
///
/// swipe moves 62.526. 62.833 -> frames 62.827. 64.312, then STOP
/// swipe moves 64.690. 64.995 -> frames 64.984. 66.442, then STOP
/// swipe moves 66.652. 66.982 -> frames 66.973. 68.420, then STOP
///
/// The last frame of each burst lands **1.479 / 1.447 / 1.438 s** after the
/// last touch — i.e. exactly `FrameCadenceDriver.Budgets.trailingMs`. The
/// window closes on a clock while the document is still decelerating, so the
/// end of every flick is missing. The holes at 64.312→64.984 (673 ms),
/// 66.442→66.976 (534 ms) and 68.420→68.907 (487 ms) are that, and nothing
/// else: they are bounded below by the trailing expiry and above by the next
/// finger.
///
/// AND THE SECOND HALF IS WORSE THAN A GAP. Once the window is IDLE the
/// shared 10 s same-subject floor applies, and the subject key is
/// `routeId + CaptureCoherence.fingerprint`. A `webview_help` frame digests the
/// WebView as an ALLOWED OPAQUE LEAF — one node, one rectangle, no children —
/// so **every scroll position of the document produces a byte-identical
/// fingerprint**. The resting position a flick settles into is therefore "the
/// same subject" and is refused `cadence_budget` for up to ten seconds. A
/// structural digest cannot establish that pixels stayed still, and this
/// producer was using it as though it could.
///
/// ## What this type reads, and what it cannot read
///
/// ONLY scroll geometry: `contentOffset` and `contentSize`, rounded to whole
/// points, per `UIScrollView` in the OWNED capture root, in traversal order.
///
/// IT IS NOT DOM TRAVERSAL AND CANNOT BECOME ONE. `WKWebView.scrollView` is
/// a UIKit object and its `contentOffset` is a UIKit property; reading it says
/// how far the page is scrolled and says nothing whatever about what the page
/// contains. There is no text, no accessibility label, no image, no URL and no
/// element identity in this type — not by convention, but because the sample is
/// four integers and an ordinal.
///
/// THE DIGEST IS NEVER SERIALISED. It exists to answer one in-process
/// question — "did the picture move since the last candidate?" — and no field
/// of it reaches the wire.
public struct VisibleState: Equatable {

    /// One scroll surface, as four integers and its position in the walk.
    public struct Sample: Equatable {
        public let ordinal: Int
        public let offsetX: Int
        public let offsetY: Int
        public let contentW: Int
        public let contentH: Int
        public init(ordinal: Int, offsetX: Int, offsetY: Int, contentW: Int, contentH: Int) {
            self.ordinal = ordinal; self.offsetX = offsetX; self.offsetY = offsetY
            self.contentW = contentW; self.contentH = contentH
        }
    }

    /// BOUNDED. A pathological tree must not turn a 125 ms tick into a
    /// full-tree walk; 64 scroll surfaces is far beyond any real screen and the
    /// walk stops dead at it rather than degrading silently.
    public static let maxSamples = 64
    public static let maxDepth = 40

    /// A movement smaller than this is not treated as motion. UIKit offsets are
    /// fractional and a resting scroll view jitters in the sub-point range; a
    /// zero threshold would let that jitter hold an activity window open
    /// forever, which is the "video recorder" this cadence is explicitly not.
    public static let motionThresholdPoints = 1

    public let samples: [Sample]
    /// Order-sensitive digest of `samples`. Empty when nothing scrollable was
    /// found — an honest "no scroll surfaces", distinct from "not sampled",
    /// which is `nil` at the call site.
    public let digest: String

    public init(samples: [Sample]) {
        self.samples = samples
        if samples.isEmpty {
            self.digest = ""
        } else {
            let pre = samples
                .map { "\($0.ordinal):\($0.offsetX),\($0.offsetY),\($0.contentW),\($0.contentH)" }
                .joined(separator: "\u{1F}")
            self.digest = SHA256.hash(data: Data(pre.utf8))
                .map { String(format: "%02x", $0) }.joined()
        }
    }

    /// Did the picture MOVE between two samples?
    ///
    /// A DIGEST COMPARISON WOULD BE THE WRONG ANSWER HERE. The digest is the
    /// right key for the cadence subject, where any difference means "a state
    /// this session has not captured". For motion it is too eager: a scroll
    /// surface appearing, disappearing or re-laying-out changes the digest
    /// without anything moving, and sub-point jitter changes it every tick.
    /// Motion is decided on the OFFSETS, against a threshold.
    ///
    /// A different SET of scroll surfaces is reported as motion deliberately:
    /// content arriving or leaving is a visible change, and it is bounded by
    /// the same trailing window as any other.
    public static func moved(from a: VisibleState?, to b: VisibleState?) -> Bool {
        guard let a = a, let b = b else { return false }   // unknown is not motion
        if a.samples.count != b.samples.count { return true }
        for (x, y) in zip(a.samples, b.samples) {
            if x.ordinal != y.ordinal { return true }
            if abs(x.offsetX - y.offsetX) >= motionThresholdPoints { return true }
            if abs(x.offsetY - y.offsetY) >= motionThresholdPoints { return true }
            if x.contentW != y.contentW || x.contentH != y.contentH { return true }
        }
        return false
    }
}

#if canImport(UIKit)
import UIKit

/// THE PROBE. It reads four numbers per scroll view and owns nothing.
///
/// NO DELEGATE, NO KVO, NO SWIZZLE. Taking `UIScrollView.delegate` would
/// steal the host's; KVO on `contentOffset` adds an observer whose lifetime we
/// would have to manage across a view tree we do not own, and a leaked observer
/// is a crash in the host app. The driver already ticks while a window is open,
/// so the sample is taken on that existing tick — no new mechanism, no new
/// lifetime, and nothing to unregister.
///
/// THE CONSEQUENCE IS STATED RATHER THAN HIDDEN: sampling on the driver's
/// own tick can EXTEND an open window, and cannot OPEN one. A pixel change with
/// no preceding gesture anywhere in the session — a self-animating page on a
/// screen nobody has touched — is still not captured. Every scroll begins with
/// a touch, so the acceptance drive is covered; a change with no gesture at all
/// is not, and that is a separate signal source with its own cost.
public enum VisibleStateProbe {

    /// Sample the owned capture root. MAIN THREAD ONLY — UIView geometry read
    /// off the main thread is undefined, and returning `nil` is the honest
    /// answer rather than a number that may be torn.
    public static func sample(root: UIView) -> VisibleState? {
        guard Thread.isMainThread else { return nil }
        var out: [VisibleState.Sample] = []
        var ordinal = 0
        func walk(_ v: UIView, depth: Int) {
            if out.count >= VisibleState.maxSamples || depth > VisibleState.maxDepth { return }
            // Hidden or fully transparent content is not visible, so its
            // position cannot be a visible change.
            if v.isHidden || v.alpha <= 0.01 { return }
            if let sv = v as? UIScrollView {
                out.append(VisibleState.Sample(
                    ordinal: ordinal,
                    offsetX: Int(sv.contentOffset.x.rounded()),
                    offsetY: Int(sv.contentOffset.y.rounded()),
                    contentW: Int(sv.contentSize.width.rounded()),
                    contentH: Int(sv.contentSize.height.rounded())))
            }
            ordinal += 1
            for sub in v.subviews { walk(sub, depth: depth + 1) }
        }
        walk(root, depth: 0)
        return VisibleState(samples: out)
    }
}
#endif
