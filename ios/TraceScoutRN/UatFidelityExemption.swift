import Foundation

/// THE ONE CONTROLLED RELAXATION OF THE NATIVE INPUT FLOOR — UAT only.
///
/// ## Why it exists
///
/// The fidelity ladder is supposed to start from MAXIMUM VISIBILITY and close
/// one category at a time, so the first frame answers a product question:
/// *what would the replay look like if we preserved essentially all ordinary
/// application pixels?* Build 15 could not answer it. `HierarchySnapshotter`
/// applies a platform floor that masks EVERY `UITextField` / `UITextView`
/// regardless of the rung, so at rung A the synthetic ordinary input was
/// already masked — and rung B, whose entire job is to close that category,
/// was a measured pixel no-op (A and B produced identical painted geometry:
/// 2 rects, same roles, 29557 vs 29558 bytes).
///
/// So rung A was not the baseline it was labelled as. This makes it one.
///
/// ## Why it is safe
///
/// Every condition below is REQUIRED, and the function returns false on any
/// absence, any doubt, and any unrecognised input. It is deliberately written
/// as one `guard` chain in one file so the whole relaxation can be read at once
/// and grepped for — a relaxation spread across a walker is a relaxation nobody
/// can audit.
///
/// 1. `ladderMode` — the native, persisted, UAT-only switch. Default false, no
/// bridge method, no `@objc` export. A production install can never satisfy
/// this, which is what makes every other condition a second line rather than
/// the only one.
/// 2. `routeId == uatScreen` — the deterministic SYNTHETIC fixture screen. Not
/// "any screen in ladder mode": the Fidelity Lab's content is generated,
/// marked and disposable, and no other route has that property.
/// 3. `category ==.editableInput` — the subject must be EXPLICITLY TAGGED
/// `ts-sem:editable_input` by the fixture. An undeclared field (`.unknown`)
/// is never exempted, so the exemption cannot spread by omission.
/// 4. `!isSecureTextEntry` — a password-style field is BLOCKED, at every rung,
/// in every mode. This is checked here as well as at the call site, because
/// a boundary worth having is worth stating twice.
///
/// ## What it does NOT touch
///
/// Outside ladder mode the input floor is applied exactly as before, so
/// production masking is byte-for-byte unchanged. It never affects
/// `secureTextEntry`, web content, camera previews, Never-Record, or any real
/// credential — those are separate branches this function cannot reach.
public enum UatFidelityExemption {

    /// The single deterministic synthetic screen the exemption may apply to.
    public static let uatScreen = "uat_fidelity"

    /// Whether the ORDINARY-input platform floor is lifted for this node.
    /// Every condition is required; anything unexpected answers `false`.
    public static func allowsOrdinaryInput(ladderMode: Bool,
                                           routeId: String,
                                           category: SemanticCategory?,
                                           isSecureTextEntry: Bool) -> Bool {
        guard ladderMode else { return false }
        guard routeId == uatScreen else { return false }
        guard let c = category, c == .editableInput else { return false }
        guard !isSecureTextEntry else { return false }
        return true
    }

    /// The platform floor for a text-input view, AFTER the exemption.
    /// `nil` means no floor — the rung decides on its own.
    ///
    /// ONE implementation, used by the walker AND by the oracle that proves
    /// rung A is open. A second copy for testing would be the same author's
    /// belief tested twice, and the ladder has already paid for that once
    /// (`PostureProjectionTest` proved a projection by calling it directly
    /// while nothing in production ever supplied a value to it).
    public static func inputFloor(isTextInput: Bool,
                                  isSecureTextEntry: Bool,
                                  ladderMode: Bool,
                                  routeId: String,
                                  category: SemanticCategory?) -> (Treatment, BlockedReason?)? {
        guard isTextInput else { return nil }
        // A secure entry is blocked outright and its subtree is never inspected.
        // This branch precedes the exemption and is unreachable from it.
        if isSecureTextEntry { return (.blocked, .secureTextEntry) }
        if allowsOrdinaryInput(ladderMode: ladderMode, routeId: routeId,
                               category: category, isSecureTextEntry: isSecureTextEntry) {
            return nil
        }
        return (.masked, nil)
    }

    /// The EFFECTIVE treatment of a declared category at a rung, including the
    /// platform floor. This is what the ladder oracle asks, and it is the same
    /// composition the walker performs.
    public static func effective(rung: FidelityRung,
                                 category: SemanticCategory,
                                 isTextInput: Bool,
                                 isSecureTextEntry: Bool,
                                 ladderMode: Bool,
                                 routeId: String) -> Treatment {
        let js = SemanticPrivacy.treatment(rung, category)
        guard let floor = inputFloor(isTextInput: isTextInput, isSecureTextEntry: isSecureTextEntry,
                                     ladderMode: ladderMode, routeId: routeId,
                                     category: category)?.0 else { return js }
        return Treatment.strongest(js, floor)
    }

    // ----------------------------------------------------- Lab web content

    /// The ONE fixture route whose embedded web view the Lab baseline may show.
    ///
    /// Route-scoped rather than mode-scoped on purpose: the bundled help
    /// document is COMPILED INTO THE APP (`helpdoc.ts` — no network fetch, no
    /// remote origin, no customer content), so it is the only web surface in
    /// this fixture whose bytes are known in advance and disposable.
    public static let labWebRoute = "webview_help"

    /// Whether an embedded web view may be captured as an ALLOWED OPAQUE LEAF.
    ///
    /// THIS LIFTS A PLATFORM BLOCK, SO IT IS THE STRICTEST CHAIN IN THIS
    /// FILE — four required conditions, and it is the CONJUNCTION that makes it
    /// safe rather than any one of them:
    ///
    /// 1. `labScopeQualified` — the resolver already proved project AND bundle
    /// are the exact synthetic fixture. It is passed in EXPLICITLY rather
    /// than inferred from `ladderMode`, even though `ResolvedFidelityPolicy`
    /// sets the two together: an invariant that lives in another file is one
    /// a refactor can break silently, and the caller that forgets this
    /// argument gets the SAFE answer because it defaults to false.
    /// 2. `ladderMode` — the native, persisted, UAT-only switch. No bridge
    /// method, no `@objc` export; a production install cannot satisfy it.
    /// 3. `rung ==.a_maxVisibility` — EXACTLY the approved Lab baseline, never
    /// `rung !=.h_allMasked`, which would admit every exploratory rung.
    /// 4. `routeId == labWebRoute` — not "any route in ladder mode".
    ///
    /// ALLOWED HERE MEANS AN OPAQUE LEAF, AND THAT IS NOT A DETAIL. The
    /// walker returns the web view's RECTANGLE with NO CHILDREN, so its own
    /// internal view tree (`WKScrollView`, `WKContentView`, …) is never
    /// traversed and no structure derived from the document is ever serialised.
    /// The document becomes visible the only way it legitimately can — as
    /// PIXELS in the rasterized frame, which is what a replay is — and the wire
    /// gains one rectangle and nothing else.
    public static func allowsLabWebContent(labScopeQualified: Bool,
                                           ladderMode: Bool,
                                           rung: FidelityRung,
                                           routeId: String) -> Bool {
        guard labScopeQualified else { return false }
        guard ladderMode else { return false }
        guard rung == .a_maxVisibility else { return false }
        guard routeId == labWebRoute else { return false }
        return true
    }

    /// The platform floor for an embedded web view, AFTER the exemption.
    /// `nil` is never returned — web content always carries a floor, because
    /// the DEFAULT must remain the certified block rather than "whatever the
    /// rung says".
    public static func webContentFloor(isWebContent: Bool,
                                       labScopeQualified: Bool,
                                       ladderMode: Bool,
                                       rung: FidelityRung,
                                       routeId: String) -> (Treatment, BlockedReason?)? {
        guard isWebContent else { return nil }
        if allowsLabWebContent(labScopeQualified: labScopeQualified, ladderMode: ladderMode,
                               rung: rung, routeId: routeId) {
            return (.allowed, nil)
        }
        return (.blocked, .thirdPartyWebView)
    }

    /// The ordinary synthetic categories the exploratory baseline must leave
    /// visible. `secureInput` is deliberately absent — it is a red line, not an
    /// ordinary category, and no rung may open it.
    public static let ordinaryCategories: [SemanticCategory] = [
        .safeLabel, .syntheticInstrument, .syntheticName, .syntheticEmail,
        .syntheticPhone, .syntheticAccountId, .syntheticBalance,
        .syntheticOrderAmount, .editableInput, .unknown,
    ]
}
