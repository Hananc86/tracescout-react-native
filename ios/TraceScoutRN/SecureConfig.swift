import Foundation
#if canImport(Security)
import Security
#endif

/// Keychain-backed configuration.
///
/// **There is NO function here that returns the ingest key or the encryption
/// key to a caller outside this module.** `readKeyInternal` is `internal` and
/// reachable only by the transport that must send the header; everything a
/// fixture, a diagnostics view or JS can reach is fingerprint-only. The Android
/// counterpart's doc comment once said "JS-callable" about the configure entry
/// point — a comment like that is an instruction to the next reader, so this
/// one states the opposite explicitly: configuration is installed NATIVELY.
public enum SecureConfig {

    public struct Status {
        public let configured: Bool
        public let projectId: String?
        public let environment: String?
        public let endpoint: String?
        /// 12 hex chars of SHA-256(key). Enough to prove WHICH key is installed,
        /// useless for authenticating as it.
        public let keyFingerprint: String?
    }

    struct Config {
        let projectId: String
        let ingestKey: String
        let environment: String
        let endpoint: String
        let storeKey: Data
    }

    /// The raw OSStatus of the last Keychain write. Diagnostic only — an
    /// unsigned simulator build has no keychain-access-group entitlement and
    /// SecItemAdd returns -34018, which otherwise surfaces only as the far less
    /// informative "configured=false".
    public private(set) static var lastAddStatus: OSStatus = 0

    private static let service = "com.tracescout.rn.config"
    private static let account = "primary"

    public static func fingerprint(_ key: String) -> String {
        String(BatchIdentity.sha256Hex(Data(key.utf8)).prefix(12))
    }

    #if canImport(Security)
    /// Install configuration natively. The raw key is written straight to the
    /// Keychain and never returned, logged or handed to JS.
    @discardableResult
    public static func configure(projectId: String, ingestKey: String,
                                 environment: String, endpoint: String) -> Status {
        var storeKey = Data(count: 32)
        _ = storeKey.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        if let existing = load() { storeKey = existing.storeKey }   // never re-key a live queue
        let payload = RnEnvelope.json([
            ("projectId", .string(projectId)),
            ("ingestKey", .string(ingestKey)),
            ("environment", .string(environment)),
            ("endpoint", .string(endpoint)),
            ("storeKey", .string(storeKey.base64EncodedString())),
        ])
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = Data(payload.utf8)
        // Device-only, after-first-unlock: the queue must drain from a
        // background launch, which cannot wait for an unlocked screen, but the
        // credential must never sync to iCloud or leave this device.
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        lastAddStatus = SecItemAdd(add as CFDictionary, nil)
        return status()
    }

    static func load() -> Config? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let projectId = o["projectId"] as? String,
              let ingestKey = o["ingestKey"] as? String,
              let environment = o["environment"] as? String,
              let endpoint = o["endpoint"] as? String,
              let storeB64 = o["storeKey"] as? String,
              let storeKey = Data(base64Encoded: storeB64)
        else { return nil }
        return Config(projectId: projectId, ingestKey: ingestKey, environment: environment,
                      endpoint: endpoint, storeKey: storeKey)
    }

    /// What a Save should do, decided BEFORE anything is written.
    ///
    /// `preserveCredential` IS THE ABSENCE OF A WRITE, not a read-then-rewrite.
    /// The raw key is never retrieved, never held in a variable the UI can see,
    /// and never re-entered: when the credential-binding fields are unchanged
    /// there is simply nothing in the Keychain payload to update, so the
    /// credential path is skipped entirely and only the non-secret fixture
    /// settings are applied. That is strictly stronger than "blank ⇒ keep the
    /// old key", which would still imply the key passes through memory here.
    ///
    /// THE BINDING IS PART OF THE CREDENTIAL. A key is issued for one
    /// project, and it addresses one environment on one endpoint. Carrying it
    /// silently across any of those would be using a credential somewhere it
    /// was never authorised for — so a blank key plus ANY binding change is a
    /// refusal, never a convenience.
    public enum SaveDecision: Equatable {
        /// A key was entered: replace the credential, exactly as before.
        case writeCredential
        /// Blank key, binding identical, a credential already installed:
        /// update non-secret settings only.
        case preserveCredential
        /// Blank key with no credential, or with a changed binding.
        case refuse(String)
    }

    /// Pure, UIKit-free, so the rule can be tested without a view controller.
    /// `environment`/`endpoint` must already be RESOLVED to the values a write
    /// would use — comparing a nil field against a stored default would read as
    /// "changed" and refuse a Save that changes nothing.
    public static func decideSave(enteredKey: String,
                                  projectId: String,
                                  environment: String,
                                  endpoint: String,
                                  existing: Status) -> SaveDecision {
        if projectId.isEmpty { return .refuse("project id is required") }
        if !enteredKey.isEmpty { return .writeCredential }
        guard existing.configured else {
            // First-time configuration always needs the key. There is no
            // credential to preserve, and inventing one is not an option.
            return .refuse("no credential is installed — enter the ingest key")
        }
        var changed: [String] = []
        if existing.projectId != projectId { changed.append("project id") }
        if existing.environment != environment { changed.append("environment") }
        if existing.endpoint != endpoint { changed.append("endpoint") }
        if !changed.isEmpty {
            return .refuse("re-enter the ingest key: " + changed.joined(separator: ", ")
                           + " changed, and a credential is bound to those")
        }
        return .preserveCredential
    }

    public static func status() -> Status {
        guard let c = load() else {
            return Status(configured: false, projectId: nil, environment: nil,
                          endpoint: nil, keyFingerprint: nil)
        }
        return Status(configured: true, projectId: c.projectId, environment: c.environment,
                      endpoint: c.endpoint, keyFingerprint: fingerprint(c.ingestKey))
    }

    public static func disconnect() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    /// Phase 2 visual frames — OPT-IN. A native, persisted, default-false switch
    /// the host app flips from its own settings surface. No bridge method
    /// exists for it: a JS-settable capture switch would make "opt-in" a claim
    /// rather than a property.
    private static let framesKey = "tracescout.rn.framesEnabled"
    public static func setFramesEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: framesKey)
    }
    public static func framesEnabled() -> Bool {
        UserDefaults.standard.bool(forKey: framesKey)   // absent == false
    }

    /// EXPLORATORY FIDELITY RUNG — Lab fixture only.
    ///
    /// Same shape as the frames opt-in above, and for the same reason: this
    /// is a NATIVE, persisted switch with no bridge method, because a
    /// JS-settable visibility control would make "the default is the certified
    /// posture" a claim rather than a property. An absent value reads as
    /// `.h_allMasked`, which is byte-for-byte today's behaviour, so a build
    /// that never touches this is indistinguishable from the certified one.
    ///
    /// An UNRECOGNISED stored value also reads as `.h_allMasked` — a corrupted
    /// or downgraded preference must fail towards masking, never away from it.
    private static let rungKey = "tracescout.rn.fidelityRung"
    public static func setFidelityRung(_ r: FidelityRung) {
        UserDefaults.standard.set(r.rawValue, forKey: rungKey)
    }
    public static func fidelityRung() -> FidelityRung {
        guard let raw = UserDefaults.standard.string(forKey: rungKey),
              let r = FidelityRung(rawValue: raw) else { return .h_allMasked }
        return r
    }

    /// UAT-ONLY LADDER MODE — whether the FIXTURE launches straight into its
    /// Fidelity Lab. It says nothing about privacy.
    ///
    /// It exists because overloading `.h_allMasked` to mean BOTH "the
    /// certified privacy posture" AND "the fixture route is not enabled" made
    /// the ladder's terminal rung structurally unmeasurable: H is the default,
    /// so `rung !=.h_allMasked` was false and the app started on Home
    /// (build-14 FINDING C). One flag, one meaning.
    ///
    /// Default false, no bridge method, no `@objc` export — same shape and same
    /// reason as the frames opt-in and the rung: a JS-settable startup route
    /// would be a JS-settable capture subject. With it off, the default rung
    /// starts the app on Home exactly as a consumer build does.
    private static let ladderKey = "tracescout.rn.fidelityLadderMode"
    public static func setFidelityLadderMode(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: ladderKey)
    }
    public static func fidelityLadderMode() -> Bool {
        UserDefaults.standard.bool(forKey: ladderKey)   // absent == false
    }

    /// INTERACTION CAPTURE — OPT-IN, NATIVE, DEFAULT FALSE, DERIVED FROM NOTHING.
    ///
    /// ITS OWN KEY AND ITS OWN DEFAULT. Interaction capture is a SEPARATE
    /// consent from visual replay, hierarchy, frames, the fidelity rung, ladder
    /// mode, the renderer and the server's own interaction gate. Nothing
    /// derives it and no migration may copy another preference into it: an SDK
    /// upgrade alone must capture ZERO taps. The Android twin
    /// (`interaction_capture_enabled_v1`) is the same conceptual consent; the
    /// storage is platform-native.
    ///
    /// NO BRIDGE METHOD AND NO `@objc` EXPORT, for the same reason as the
    /// frames opt-in: a JS-settable capture switch would make "opt-in" a claim
    /// rather than a property. The ONLY automatic enablement is the exact
    /// synthetic Lab posture, applied at launch by `LabPostureAutoPersist`.
    private static let interactionKey = "tracescout.rn.interactionCaptureEnabled"
    public static func setInteractionCaptureEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: interactionKey)
    }
    public static func interactionCaptureEnabled() -> Bool {
        UserDefaults.standard.bool(forKey: interactionKey)   // absent == false
    }

    /// NETWORK CAPTURE — the host app's native opt-in. ABSENT == FALSE.
    ///
    /// BEFORE THIS EXISTED THE ONLY CALLERS OF
    /// `RnTransport.setNetworkCaptureEnabled` WERE THE S4 SIMULATOR HARNESSES,
    /// which compile only under their CI flags. So a signable build had a
    /// network producer with NO way to be switched on: default-off was true, but
    /// only because "on" was unreachable — which is not a configuration.
    ///
    /// Same rules as the interaction and frames opt-ins: no bridge method, no
    /// `@objc` export, nothing React Native can set, and no migration may derive
    /// it from another preference — an SDK upgrade alone captures ZERO requests.
    /// UNLIKE the interaction flag, `LabPostureAutoPersist` does NOT enable
    /// it: standing Lab capture never implies network capture.
    private static let networkKey = "tracescout.rn.networkCaptureEnabled"
    public static func setNetworkCaptureEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: networkKey)
    }
    public static func networkCaptureEnabled() -> Bool {
        UserDefaults.standard.bool(forKey: networkKey)   // absent == false
    }
    /// The SAME persisted value, under a name that says what it is for.
    /// `RnTransport.start` is the only reader of `networkCaptureEnabled` —
    /// pinned by a source test, because a second reader is how a mid-process
    /// apply comes back. Status/display code reads THIS one and may only show it.
    public static func networkCaptureOptInPersisted() -> Bool {
        UserDefaults.standard.bool(forKey: networkKey)   // absent == false
    }

    /// THE HOST-DECLARED WEBVIEW ORIGIN ALLOWLIST — the S5 origin-policy
    /// contract of 2026-10-21.
    ///
    /// ABSENT IS NOT EMPTY, AND THE DISTINCTION IS THE WHOLE POINT.
    /// `UserDefaults.stringArray` answers nil for an absent key and `[]` for a
    /// stored empty array, and those are different host intents: "nothing was
    /// configured" versus "a reviewed decision that no origin may correlate".
    /// `bool(forKey:)`'s absent-is-false convention used by every switch above
    /// would collapse them, so this accessor deliberately returns an OPTIONAL
    /// and the absent case stays nil all the way to the bind, where it fails
    /// closed as `origin_policy_absent`.
    ///
    /// NATIVE-OWNED: no bridge method, no `@objc` export, nothing React
    /// Native can set, and no migration derives it from another preference. The
    /// 11-method `RCT_EXTERN_METHOD` cap forbids a twelfth JS-callable method
    /// outright, and the separate rule against a JS-callable name carrying a
    /// native-owned fact would forbid this one even if the cap were raised: a
    /// policy a page's own runtime could supply is a policy the page could
    /// widen.
    ///
    /// STORED VERBATIM, VALIDATED AT THE BIND. This accessor does not admit,
    /// repair or filter — `RnWebViewOriginPolicy.admit` is the one admission
    /// rule, and validating in two places is how the two answers drift. A
    /// malformed entry therefore persists and is refused BY NAME every time it
    /// is read, which is what lets an operator see the entry they typed.
    private static let webViewOriginsKey = "tracescout.rn.webViewOriginAllowlist"
    public static func setWebViewOriginAllowlist(_ origins: [String]?) {
        if let origins = origins {
            UserDefaults.standard.set(origins, forKey: webViewOriginsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: webViewOriginsKey)
        }
    }
    public static func webViewOriginAllowlist() -> [String]? {
        UserDefaults.standard.stringArray(forKey: webViewOriginsKey)   // absent == nil
    }

    /// The projectId currently configured, for the fixture's project-switch
    /// guard. NON-SECRET: `status` already publishes it. Separate accessor so
    /// the guard cannot accidentally reach `load` and the key with it.
    public static func currentProjectId() -> String? { status().projectId }

    /// INTERNAL ONLY — the transport needs the header value. Not public, not
    /// exposed through the React module, not reachable from the fixture.
    static func readKeyInternal() -> String? { load()?.ingestKey }
    #else
    public static func status() -> Status {
        Status(configured: false, projectId: nil, environment: nil, endpoint: nil, keyFingerprint: nil)
    }
    #endif
}
