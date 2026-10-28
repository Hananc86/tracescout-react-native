import Foundation

/// Semantic privacy categories and the FIDELITY LADDER that maps them to a
/// treatment.
///
/// WHY THIS EXISTS, AND WHAT IT DELIBERATELY IS NOT.
///
/// The certified production classifier answers "unknown ⇒ mask" — correct, and
/// it produced frames that are ~37 % opaque mask fill. That is the maximum
/// masking a viewer will tolerate. The question this file exists to answer is
/// the opposite one: **what is the MINIMUM masking privacy actually needs?**
/// You cannot find that by loosening a fully-masked frame one guess at a time;
/// you find it by starting from a highly visible SYNTHETIC screen and closing
/// categories until the privacy oracle stops finding anything.
///
/// So this is an EXPLORATORY instrument for the controlled Lab fixture. It is
/// NOT a production posture and must never be described as one. The default
/// rung is `.h_allMasked`, which is byte-for-byte today's certified behaviour;
/// every other rung has to be asked for explicitly.
///
/// VISIBILITY HERE IS THE ABSENCE OF A MASK, NEVER THE EXTRACTION OF DATA.
/// A rung changes only which rectangles the rasterizer paints. No rung reads a
/// string, and the hierarchy stays text-free at every rung — a node that
/// becomes `allowed` still carries no text on the wire, only geometry. That is
/// what makes the experiment safe to run at all: the most it can do is decline
/// to paint over pixels that are already on the user's own screen.
public enum SemanticCategory: String, CaseIterable {
    /// Chrome the app itself authored: section headers, button captions, nav
    /// titles. Not user data under any reading.
    case safeLabel = "safe_label"
    /// Synthetic instrument/product names in the fixture catalogue.
    case syntheticInstrument = "synthetic_instrument"
    case syntheticName = "synthetic_name"
    case syntheticEmail = "synthetic_email"
    case syntheticPhone = "synthetic_phone"
    case syntheticAccountId = "synthetic_account_id"
    case syntheticBalance = "synthetic_balance"
    case syntheticOrderAmount = "synthetic_order_amount"
    /// Anything the user can type into that is not a secure entry.
    case editableInput = "editable_input"
    /// Passwords, one-time codes, tokens, API keys. A RED LINE.
    case secureInput = "secure_input"
    /// Not declared. The fail-closed default and the thing rung G closes.
    case unknown = "unknown"
}

/// The rungs, from maximum useful visibility down to today's certified posture.
///
/// The ORDER is the experiment: each rung closes exactly one semantic category
/// so a before/after pair attributes the visual cost to one decision. A rung
/// that closed two categories at once would make the ladder unreadable.
public enum FidelityRung: String, CaseIterable {
    case a_maxVisibility = "A_max_visibility"
    case b_maskEditableInputs = "B_mask_editable_inputs"
    case c_maskContact = "C_mask_email_phone"
    case d_maskAccountIds = "D_mask_account_ids"
    case e_maskAmounts = "E_mask_balances_amounts"
    case f_maskUserSpecific = "F_mask_user_specific"
    case g_maskUnknown = "G_mask_unknown"
    case h_allMasked = "H_all_masked"
}

public enum SemanticPrivacy {

    /// THE RED LINES. These are floors, not defaults: the resolver takes the
    /// STRONGEST of the rung's answer and this, so no rung — present or future,
    /// and no typo in the table below — can open one. `secureInput` is blocked
    /// rather than masked because a blocked node's subtree is never even walked.
    ///
    /// Passwords, auth tokens, API keys and credentials all arrive here as
    /// `secureInput`: on iOS a secure entry is ALSO caught natively by
    /// `isSecureTextEntry`, so the protection is doubled and neither half
    /// depends on the app declaring anything.
    public static func redLineFloor(_ c: SemanticCategory) -> Treatment? {
        switch c {
        case .secureInput: return .blocked
        default: return nil
        }
    }

    /// Category → treatment for a rung, BEFORE the red-line floor is applied.
    public static func rawPolicy(_ rung: FidelityRung, _ c: SemanticCategory) -> Treatment {
        // Categories that are open at rung A and close one at a time.
        let closesAt: [SemanticCategory: FidelityRung] = [
            .editableInput: .b_maskEditableInputs,
            .syntheticEmail: .c_maskContact,
            .syntheticPhone: .c_maskContact,
            .syntheticAccountId: .d_maskAccountIds,
            .syntheticBalance: .e_maskAmounts,
            .syntheticOrderAmount: .e_maskAmounts,
            .syntheticName: .f_maskUserSpecific,
            .unknown: .g_maskUnknown,
        ]
        if rung == .h_allMasked { return .masked }
        if c == .secureInput { return .blocked }
        // Chrome the app authored stays visible for the whole ladder — it is
        // what makes the replay legible, and it is not user data at any rung.
        if c == .safeLabel || c == .syntheticInstrument { return .allowed }
        guard let closes = closesAt[c] else { return .masked }
        return rungIndex(rung) >= rungIndex(closes) ? .masked : .allowed
    }

    public static func rungIndex(_ r: FidelityRung) -> Int {
        FidelityRung.allCases.firstIndex(of: r) ?? FidelityRung.allCases.count
    }

    /// The effective JS-side treatment: the rung's answer, never weaker than the
    /// red-line floor. Native platform facts tighten this again downstream
    /// (`RawNode.effectiveTreatment`), so there are three independent chances to
    /// close something and none to open it.
    public static func treatment(_ rung: FidelityRung, _ c: SemanticCategory) -> Treatment {
        let raw = rawPolicy(rung, c)
        guard let floor = redLineFloor(c) else { return raw }
        return Treatment.strongest(raw, floor)
    }

    // ------------------------------------------------------------ declaration

    /// The token an app puts in a view's `testID` to declare a category:
    /// `ts-sem:safe_label`. On iOS RN maps `testID` to
    /// `accessibilityIdentifier`.
    ///
    /// ONLY THE TOKEN IS READ, and only when it matches the closed enum
    /// above. A testID is a code identifier — but nothing stops an app putting
    /// data in one, so the parser returns a CATEGORY or nothing, never the
    /// remaining characters, and the raw identifier is never copied, stored or
    /// serialised.
    public static let prefix = "ts-sem:"

    public static func parse(_ identifier: String?) -> SemanticCategory? {
        guard let id = identifier, let r = id.range(of: prefix) else { return nil }
        let rest = id[r.upperBound...]
        // Category tokens are [a-z_] by construction, so the scan stops at the
        // first character that cannot belong to one. `ts-sem:safe_label-hero`
        // yields `safe_label`; everything after it is discarded unread.
        let token = rest.prefix(while: { ($0 >= "a" && $0 <= "z") || $0 == "_" })
        return SemanticCategory(rawValue: String(token))
    }
}
