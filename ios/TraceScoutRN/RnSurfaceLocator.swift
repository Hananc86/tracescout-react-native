#if canImport(UIKit)
import UIKit

/// FINDING THE RN SURFACE THE REPLAY IS ABOUT, AND SAYING SO WHEN WE COULD NOT.
///
/// THE FALLBACK IS DISCLOSED, NEVER SILENT. If no React root view can be
/// found we observe the key window instead -- which is the view the replay
/// frame is drawn from anyway, so coordinates stay truthful -- but the result
/// records `fellBackToWindow`. A locator that silently returned the window
/// would be indistinguishable from one that found the surface, and "we are
/// observing the right view" would be an assumption nobody could check.
public enum RnSurfaceLocator {

    public struct Located {
        public let view: UIView
        /// The class name we matched, or the window's, for evidence. Never used
        /// as a screen id: a UIKit class name is not replay identity.
        public let matchedClassName: String
        public let fellBackToWindow: Bool
    }

    /// React root views across the architectures we support. Matched by NAME
    /// rather than by type so this module keeps compiling without linking
    /// React -- the SDK must not drag the host's React version into its own
    /// build graph.
    public static let rootViewClassMarkers = [
        "RCTFabricSurfaceHostingProxyRootView",
        "RCTSurfaceHostingProxyRootView",
        "RCTSurfaceHostingView",
        "RCTSurfaceView",
        "RCTRootView",
    ]

    public static func isRootViewClassName(_ name: String) -> Bool {
        rootViewClassMarkers.contains { name == $0 }
    }

    /// MAIN THREAD ONLY. `UIApplication.shared` and `connectedScenes` are
    /// main-thread-only APIs; calling this from React's TurboModule queue
    /// aborted the process under Main Thread Checker and would have raced the
    /// view hierarchy in Release. It deliberately does NOT hop threads itself —
    /// a locator that silently dispatched would return a window to a caller
    /// that is about to touch the hierarchy from the wrong thread anyway, and
    /// would hide the contract from the next reader. Callers marshal.
    public static func keyWindow() -> UIWindow? {
        if #available(iOS 13.0, *) {
            let scened = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
            if let k = scened.first(where: { $0.isKeyWindow }) { return k }
        }
        return UIApplication.shared.windows.first(where: { $0.isKeyWindow })
            ?? UIApplication.shared.windows.first
    }

    /// Breadth-first: the OUTERMOST React root wins. A depth-first walk would
    /// return whichever nested surface happened to be encountered first, which
    /// on a screen hosting more than one surface is an arbitrary choice that
    /// would look stable until the view tree changed.
    /// The predicate is injectable so the TRAVERSAL can be tested independently
    /// of the NAMING. The name match is deliberately EXACT -- a prefix match
    /// would claim unrelated classes -- which also means no subclass a test can
    /// declare will ever satisfy it. Testing the walk with a fabricated
    /// `FakeRCTRootView` therefore asserted something the production rule
    /// forbids by construction. Two properties, two tests.
    public static func findRootView(in root: UIView,
                                    isRoot: (UIView) -> Bool = { isRootViewClassName(String(describing: type(of: $0))) }) -> UIView? {
        var queue: [UIView] = [root]
        var i = 0
        while i < queue.count {
            let v = queue[i]; i += 1
            if isRoot(v) { return v }
            queue.append(contentsOf: v.subviews)
        }
        return nil
    }

    /// THE ONE WINDOW-IDENTITY QUESTION, in the testable target for the same
    /// reason as `bindSurfaceScoped`. The basis must be a real window AND the
    /// very window whose pixels `FrameRasterizer` captures. A different window
    /// or a different scene is not a near-miss to be corrected — it is a
    /// coordinate about pixels nobody photographed.
    public static func basisIsCapturedWindow(_ basis: UIWindow?,
                                             captured: UIWindow? = RnSurfaceLocator.keyWindow())
    -> Bool {
        guard let basis, let captured else { return false }
        return basis === captured
    }

    /// MAIN THREAD ONLY — see `keyWindow`.
    public static func locate() -> Located? {
        guard let window = keyWindow() else { return nil }
        if let surface = findRootView(in: window) {
            return Located(view: surface,
                           matchedClassName: String(describing: type(of: surface)),
                           fellBackToWindow: false)
        }
        return Located(view: window,
                       matchedClassName: String(describing: type(of: window)),
                       fellBackToWindow: true)
    }
}
#endif
