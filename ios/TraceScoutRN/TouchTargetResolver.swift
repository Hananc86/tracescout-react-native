#if canImport(UIKit)
import UIKit

/// CLASSIFYING WHAT WAS TOUCHED. The recognizer reports mechanics; this answers
/// the policy-relevant questions about the view UIKit named.
///
/// EVERY UNCERTAIN ANSWER RESOLVES TO THE MORE RESTRICTIVE ONE. A nil view,
/// an unrecognised class, a view we could not walk -- all become `.unknown`,
/// which the shared policy treats exactly as it treats a redacted target. The
/// tempting alternative (unrecognised means ordinary, therefore safe) makes the
/// permissive branch the default for precisely the views nobody thought about.
public enum TouchTargetResolver {

    /// Windows UIKit uses for system input surfaces. A touch delivered from one
    /// of these is not the app's own content, and a coordinate on the system
    /// keyboard is a key press.
    public static let nonContentWindowClassMarkers = [
        "UIRemoteKeyboardWindow",
        "UITextEffectsWindow",
    ]

    public static func isContentWindow(_ window: UIWindow?) -> Bool {
        guard let window else { return false }   // no window: not provably content
        let name = String(describing: type(of: window))
        return !nonContentWindowClassMarkers.contains(name)
    }

    public static func isSecure(_ view: UIView?) -> Bool {
        if let f = view as? UITextField, f.isSecureTextEntry { return true }
        if let i = view as? UITextInput, i.isSecureTextEntryCompat { return true }
        return false
    }

    public static func isEditable(_ view: UIView?) -> Bool {
        guard let view else { return false }
        if view is UITextField || view is UITextView { return true }
        if let s = view as? UISearchBar { _ = s; return true }
        return false
    }

    public static func classify(_ view: UIView?) -> InteractionPolicy.TargetClass {
        guard let view else { return .unknown }
        if isSecure(view)   { return .secure }
        if isEditable(view) { return .editable }
        // A view we can name and that is neither secure nor editable is an
        // ordinary content view. It is still only POSITION-safe if it is
        // declared so -- see `positionTreatment`.
        return .safe
    }

    /// THE DECLARATION IS THE ONLY THING THAT GRANTS COORDINATES, AND IT IS
    /// READ FROM THE TOUCHED VIEW AND ITS ANCESTORS -- never from a sibling and
    /// never from the window. Walking upward is what lets a declared row grant
    /// its own children; walking anywhere else would let an unrelated declared
    /// view launder a coordinate for a view nobody declared.
    public static func positionTreatment(_ view: UIView?) -> InteractionPolicy.PositionTreatment {
        var v = view
        var hops = 0
        while let cur = v, hops < 24 {
            if InteractionPolicy.isPositionDeclared(declaration(on: cur)) { return .allowed }
            v = cur.superview; hops += 1
        }
        return .redacted
    }

    /// an earlier change — DID THE FINGER LAND ON WEB CONTENT? The iOS twin of Android's
    /// an earlier change `Resolved.insideWebContent`.
    ///
    /// `view` is the view UIKit hit-tested for the touch (`UITouch.view`, fixed
    /// for the touch's whole life), so its superview chain IS the descent path
    /// Android records root → hit; walking it up asks the same question of the
    /// same set of views. Each one is asked of the ONE
    /// `HierarchySnapshotter.isWebContent` home — no class-name list here.
    ///
    /// A FACT FROM THE HIT TEST, NOT A CARRIER. It names no segment; it only
    /// decides whether the host's carrier is ASKED for this tap. Every
    /// uncertain answer is `false` — a nil view, or a chain longer than the
    /// bound — so an unresolved hit makes no WebView claim: the row stays
    /// UNATTRIBUTED, never WEBVIEW.
    public static let webContentWalkBound = 64

    public static func insideWebContent(_ view: UIView?) -> Bool {
        var v = view
        var hops = 0
        while let cur = v {
            if hops >= webContentWalkBound { return false }
            if HierarchySnapshotter.isWebContent(cur) { return true }
            v = cur.superview; hops += 1
        }
        return false
    }

    /// THE DECLARATION LIVES IN `nativeID`, NOT IN `accessibilityIdentifier`.
    /// React Native maps `testID` to `accessibilityIdentifier` and `nativeID`
    /// to its own property, and the fixture deliberately keeps them separate:
    /// the identifier is the element's test handle, the nativeID carries the
    /// position-safe token. Reading only the identifier meant the token was
    /// invisible to this resolver and every approved control resolved
    /// `redacted` — a permission that could never be granted, on a control the
    /// guard confirms is correctly declared.
    ///
    /// `accessibilityIdentifier` is still consulted as a fallback so a host
    /// that declares it there is not silently ignored.
    static func declaration(on view: UIView) -> String? {
        // THE PROPERTY IS SPELLED DIFFERENTLY IN THE TWO ARCHITECTURES.
        // The legacy view exposes `nativeID`; Fabric's component view exposes
        // `nativeId`. Checking only one silently returns nothing on the other,
        // which looks exactly like a control that was never declared.
        for key in ["nativeID", "nativeId"] {
            if view.responds(to: NSSelectorFromString(key)),
               let native = (view as NSObject).value(forKey: key) as? String,
               !native.isEmpty {
                return native
            }
        }
        return view.accessibilityIdentifier
    }

    /// Any editable responder currently holding focus, anywhere. Deliberately
    /// NOT "is the touched view editable": the whole point is that a tap on an
    /// ordinary button WHILE a keyboard is up is part of a text-entry sequence,
    /// and its coordinate stream is keystroke-adjacent.
    public static func textEntryActive(in root: UIView?) -> Bool {
        guard let root else { return false }
        var queue: [UIView] = [root]
        var i = 0
        while i < queue.count {
            let v = queue[i]; i += 1
            if v.isFirstResponder && isEditable(v) { return true }
            queue.append(contentsOf: v.subviews)
        }
        return false
    }
}

private extension UITextInput {
    var isSecureTextEntryCompat: Bool {
        (self as? UITextField)?.isSecureTextEntry ?? false
    }
}
#endif
