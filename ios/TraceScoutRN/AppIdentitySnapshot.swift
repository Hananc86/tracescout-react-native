#if canImport(UIKit)
import UIKit
#endif
import Foundation

/// THE ARTIFACT THAT PRODUCED A SESSION, RESOLVED ONCE.
///
/// A CERTIFICATION MUST BE SELF-IDENTIFYING. The first physical run could not
/// say which build produced it: `app_version`, `app_build_number`, `sdk_version`
/// and `os_version` were all NULL, so "is this Build 20?" could only be argued
/// from upload timing. An argument is not an identity.
///
/// RESOLVED ONCE AND CARRIED, NEVER RE-READ. A long-lived session must not
/// suddenly claim a different build because the process was asked again after
/// some mutation; a retry of the opener must serialize the same facts it did
/// the first time. The server additionally protects the row with first-non-null
/// semantics, but a producer that can contradict itself is a producer whose
/// evidence has to be argued about.
public struct AppIdentitySnapshot: Equatable {
    /// CFBundleShortVersionString (iOS) / versionName (Android).
    public let appVersion: String?
    /// CFBundleVersion (iOS) / versionCode (Android).
    public let appBuildNumber: String?
    public let sdkVersion: String
    public let osName: String?
    public let osVersion: String?
    /// The device CLASS — `UIDevice.current.model`, which is `"iPhone"`,
    /// `"iPad"` or `"iPod touch"`.
    ///
    /// THIS IS A CLASS, NOT A HARDWARE IDENTIFIER, AND THE DISTINCTION IS
    /// THE WHOLE PRIVACY ARGUMENT. `UIDevice.current.model` returns one of a
    /// handful of values shared by tens of millions of devices.
    /// `utsname.machine` / `modelIdentifier` (`"iPhone15,2"`) is a different
    /// fact — a hardware identifier — and `identifierForVendor` /
    /// `advertisingIdentifier` are different facts again. **None of those four
    /// appears anywhere in this SDK and none may be substituted here**: the
    /// protocol's own `device` description forbids "advertising ids, hardware
    /// serials, [or] anything requiring an Apple required-reason declaration
    /// we have not made", and a hardware identifier is exactly what a
    /// three-value class is not.
    ///
    /// It exists because the server derives `device_type` from it: the
    /// deployed `deriveDeviceType` matches `/\b(ipad|tablet)\b/i` → `tablet`
    /// and `/\b(iphone|ipod)\b/i` → `mobile`. Without it the column is NULL on
    /// every RN iOS session, which is what the S8 filter audit measured.
    public let model: String?

    /// `model` HAS NO DEFAULT, DELIBERATELY. A snapshot carrying a device
    /// fact must say what it is, the same reason `Envelope.viewHierarchy` takes
    /// its privacy evidence with no default: a field that can be silently
    /// forgotten is how `allowedViewCount` reached the wire as 0 from every
    /// real device while its own unit test passed. There are exactly two
    /// construction sites, so requiring it costs nothing and removes the
    /// failure mode entirely.
    public init(appVersion: String?, appBuildNumber: String?, sdkVersion: String,
                osName: String?, osVersion: String?, model: String?) {
        self.appVersion = appVersion
        self.appBuildNumber = appBuildNumber
        self.sdkVersion = sdkVersion
        self.osName = osName
        self.osVersion = osVersion
        self.model = model
    }

    /// WHERE ONE IDENTITY FACT COMES FROM.
    ///
    /// `nil` CANNOT EXPRESS THREE STATES, AND THAT COST A RED CI. A test
    /// injecting `nil` to mean "this cannot be resolved" got the PRODUCTION
    /// fallback instead — correctly, because `nil` there means "no override".
    /// "No override" and "authoritative resolution failed" are different facts
    /// and an Optional collapses them, so a fixture could not construct the
    /// very state it was written to test.
    ///
    /// This is an internal testing seam. It is NOT a customer-visible switch:
    /// the shipping call site passes nothing and gets `.automatic` throughout.
    public enum Source<T>: Equatable where T: Equatable {
        /// Use the real platform source.
        case automatic
        /// A deterministic injected value.
        case value(T)
        /// Authoritative resolution FAILED. The field is omitted — never
        /// `"unknown"`, `"0"` or `"dev"`.
        case unavailable
    }

    /// NO INVENTED FALLBACKS. An unresolvable value is ABSENT, not `"0"`,
    /// `"unknown"` or `"dev"` — a placeholder is indistinguishable downstream
    /// from a real answer, and the whole point of these fields is that a NULL
    /// must be visible as a gap rather than dressed up as a value.
    public static func resolve(bundle: Source<[String: String]> = .automatic,
                               osName: Source<String> = .automatic,
                               osVersion: Source<String> = .automatic,
                               model: Source<String> = .automatic) -> AppIdentitySnapshot {
        let info = resolveBundle(bundle)
        let name = resolveString(osName, platform: platformOsName())
        // The protocol's `device.osVersion` is the bare version string
        // ("15", "18.6.2") -- see the Android fixtures.
        // `operatingSystemVersionString` would emit
        // "Version 18.6.2 (Build 22G.)", a different representation of the
        // same fact, and two representations is how a consumer ends up parsing
        // one of them.
        let ver = resolveString(osVersion, platform: platformOsVersion())
        // RESOLVED THROUGH THE SAME SEAM AND THE SAME BOUND AS EVERY OTHER
        // FIELD. `bounded` is what makes an empty platform answer ABSENT rather
        // than `""` on the wire, and the 64-character prefix is the protocol's
        // own `device.model` maxLength — so a value this producer emits cannot
        // be the reason a chunk is refused.
        let mdl = resolveString(model, platform: platformDeviceModel())
        return AppIdentitySnapshot(
            appVersion: bounded(info?["CFBundleShortVersionString"]),
            appBuildNumber: bounded(info?["CFBundleVersion"]),
            sdkVersion: Producer.SDK_VERSION,
            osName: bounded(name),
            osVersion: bounded(ver),
            model: bounded(mdl))
    }

    // ── the three states, resolved explicitly ───────────────────────────────

    private static func resolveBundle(_ src: Source<[String: String]>) -> [String: String]? {
        switch src {
        case .automatic: return platformBundle()
        case .value(let v): return v
        case .unavailable: return nil
        }
    }

    private static func resolveString(_ src: Source<String>, platform: @autoclosure () -> String?)
    -> String? {
        switch src {
        case .automatic: return platform()
        case .value(let v): return v
        case .unavailable: return nil
        }
    }

    /// A VALUE THAT IS EMPTY IS NOT A VALUE. An empty string would serialize
    /// as `""`, which is a present-but-meaningless answer — the same failure as
    /// `"unknown"`, one character shorter.
    private static func bounded(_ v: String?) -> String? {
        guard let s = v, !s.isEmpty else { return nil }
        return String(s.prefix(64))
    }

    private static func platformBundle() -> [String: String]? {
        #if canImport(UIKit)
        guard let info = Bundle.main.infoDictionary else { return nil }
        var out: [String: String] = [:]
        for k in ["CFBundleShortVersionString", "CFBundleVersion"] {
            if let v = info[k] as? String { out[k] = v }
        }
        return out.isEmpty ? nil : out
        #else
        return nil
        #endif
    }

    private static func platformOsName() -> String? {
        #if canImport(UIKit)
        return UIDevice.current.systemName
        #else
        return nil
        #endif
    }

    private static func platformOsVersion() -> String? {
        #if canImport(UIKit)
        return UIDevice.current.systemVersion
        #else
        return nil
        #endif
    }

    /// THE SAME OBJECT, FROM THE SAME CALL SITE, AS THE TWO FACTS ALREADY ON
    /// THE WIRE. `resolve` is invoked lazily inside `startSession`, which
    /// React calls on `com.meta.react.turbomodulemanager.queue` — OFF the main
    /// thread — and `UIDevice.current.systemName` / `.systemVersion` have
    /// shipped from exactly there since Build 20. So this adds NO new
    /// main-thread surface. It is deliberately NOT `UIScreen` or
    /// `UIApplication.connectedScenes`: the first version of the route-host
    /// wiring read `connectedScenes` from that queue and Main Thread Checker
    /// aborted the process with SIGABRT inside `RnSurfaceLocator.keyWindow`.
    ///
    /// ANSWERED — IN THE SIMULATOR, AND THAT BOUND IS PART OF THE CLAIM.
    /// Whether Main Thread Checker flags `UIDevice.current` from the TurboModule
    /// queue was measured in the app-hosted iOS SIMULATOR venue with MTC PROVEN
    /// ARMED by a control that fired in the same process
    /// (`-[UILabel initWithFrame:]`, 2 reports): ZERO reports named UIDevice and
    /// the read succeeded (model=iPhone, osName=iOS). That control is
    /// load-bearing — MTC is not armed by default for `xcodebuild test` and
    /// `-enableMainThreadChecker` does not exist on Xcode 26.3, so without the
    /// injected dylib "no warning" would have meant "no checker".
    ///
    /// IT IS NOT A PHYSICAL-DEVICE RESULT AND NOT RN APP CERTIFICATION: no
    /// Debug build of the RN app on physical hardware has been measured, and a
    /// Simulator process is not a device runtime. The Simulator answer is what
    /// retires the earlier "NOT ESTABLISHED" on this read; a physical Debug
    /// measurement remains the venue debt an earlier change / an earlier change own.
    private static func platformDeviceModel() -> String? {
        #if canImport(UIKit)
        return UIDevice.current.model
        #else
        return nil
        #endif
    }
}

/// The one immutable thing a session is opened from.
///
/// Required order, and it is the contract:
/// load persisted config -> Lab normalization -> resolve fidelity policy
/// -> resolve interaction consent -> construct THIS -> serialize session_start
///
/// Serializing from anything else — re-reading preferences after the session is
/// open and reconstructing what we think happened — would describe a later
/// state, not the opener that created the session.
public struct OpenerSnapshot {
    public let fidelity: FidelityPolicyEvidence
    public let identity: AppIdentitySnapshot
    /// A DECLARATION, AND THE PROTOCOL SAYS SO: "Declares up front what
    /// visual fidelity this session will carry, SO THE RENDERER NEVER GUESSES."
    /// It was hardcoded `metadata_only` on every opener, including a Lab
    /// session that goes on to carry frames — the producer stating a fidelity
    /// its own session contradicts within a second.
    ///
    /// It is resolved from the SAME opener as everything else, so the
    /// declaration and the capture cannot come from two different moments.
    public let captureMode: String
    public init(fidelity: FidelityPolicyEvidence, identity: AppIdentitySnapshot,
                captureMode: String) {
        self.fidelity = fidelity
        self.identity = identity
        self.captureMode = captureMode
    }

    /// What this session will carry, from the resolved posture.
    ///
    /// NEVER A PROMISE THE PRODUCER CANNOT KEEP. Frames are declared only
    /// when the Lab scope is active AND the frames opt-in is on; hierarchy is
    /// always captured when anything is, so `wireframe_only` is the honest
    /// floor rather than `metadata_only`. Claiming frames and then producing
    /// none would make the renderer wait for something that is not coming —
    /// which is the guessing the field exists to prevent, arriving from the
    /// other direction.
    /// THE THIRD INPUT IS THE ONE BUILD 31 DID NOT HAVE, AND IT IS WHY THIS
    /// FIELD LIED. Posture and the opt-in say what the operator ASKED FOR; they
    /// say nothing about whether the active capture path can carry it out. On
    /// RN iOS 0.3.0 (31) the route-host binding made every raster categorically
    /// refusable before cadence, budget or opt-in were consulted — so the
    /// session declared `semantic_with_frames`, stored ZERO frames across six
    /// screens, and every snapshot underneath it read `wireframe_only`. The
    /// renderer was told to expect something the producer could never attempt.
    ///
    /// `capability` comes from the CAPTURE LAYER's own rule
    /// (`HierarchySnapshotter.frameCapability`), not from a second opinion held
    /// here — a boolean computed beside the declaration would be free to
    /// disagree with the path that actually refuses, which is the same
    /// two-sources-for-one-fact shape as `treatment` vs `privacy`.
    ///
    /// `labScopeActive` WAS THE THIRD TERM HERE AND IT IS GONE, BECAUSE
    /// THE CAPTURE PATH NEVER CONSULTED IT AND THE PRODUCT'S OWN BYTES PROVE
    /// IT. Two preserved sessions declared `wireframe_only` with
    /// `labScopeQualified: false`, `rung: H`, `effectivePolicy: strict` — and
    /// uploaded FOUR JPEG visual frames between them:
    ///
    /// ts_1789733591525_8d6fe987a8f3 1 frame 37 845 B
    /// ts_1789733598272_2684eacadbed 3 frames 37 845 / 37 845 / 49 681 B
    ///
    /// The frame REQUEST reads `SecureConfig.framesEnabled` alone, and
    /// `FramePolicy.decide` is called with `enabled: true` — a literal — so
    /// lab scope appears nowhere between the opt-in and a stored frame. Only
    /// the declaration consulted it, and the result was a producer whose two
    /// own statements disagreed.
    ///
    /// THE MIRROR OF THE DEFECT THIS FIELD WAS INTRODUCED FOR, AND THE
    /// REASON THE FIX BELONGS HERE RATHER THAN ON THE CAPTURE PATH. Build 31
    /// promised frames and produced none; this promised none and produced
    /// frames. Both break the same contract — *"declares up front what visual
    /// fidelity this session will carry, SO THE RENDERER NEVER GUESSES"* — and
    /// only one of them can be fixed by changing a string. Making the capture
    /// path honour lab scope instead would SUPPRESS frames the opt-in asked
    /// for and the path can carry, i.e. change what the product does in order
    /// to make a metadata field true. That is fixing the wrong side.
    ///
    /// FIDELITY IS A DIFFERENT AXIS AND IS ALREADY DECLARED SEPARATELY. A
    /// non-Lab session's frames are produced at the strict rung and are fully
    /// masked; `fidelityPolicy` in the same envelope carries that, and it is
    /// unchanged. Folding "how much is visible" into "will there be a picture"
    /// is what made these two fields disagree in the first place. **Nothing
    /// about masking, redaction or the privacy rung moves with this change**,
    /// and nothing here is a claim about what those four frames contained.
    public static func declaredCaptureMode(framesEnabled: Bool,
                                           capability: FrameCapability) -> String {
        guard framesEnabled, capability.canAttempt else { return "wireframe_only" }
        return FrameBudgets.captureModeWithFrames
    }
}
