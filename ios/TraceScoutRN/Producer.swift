import Foundation

/// Compile-time producer identity. There is no initialiser, no setter and no
/// value here that JS can reach — the server's capability boundary only means
/// something if a producer cannot misreport which capture code produced its
/// bytes. Mirrors `rn-core/Producer.kt` value-for-value.
public enum Producer {
    public static let PROTOCOL_VERSION = "mobile-1.0.0"
    public static let PLATFORM = "react_native"
    /// The HOST platform. The Android producer's constant is "android"; this is
    /// the ONLY identity field that legitimately differs between the two RN
    /// hosts, and the deployed server accepts both (`HOST_PLATFORMS`).
    public static let HOST_PLATFORM_IOS = "ios"
    public static let SDK_FAMILY = "tracescout-react-native"
    /// The server's `FAMILY_POLICY` completeFrom floor for this family.
    public static let SDK_VERSION = "1.0.0"
}
