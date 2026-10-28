import Foundation

/// BUILD 27 — no arbitrary diagnostic value reaches `NSJSONSerialization`
/// unless it has been PROVEN JSON-safe.
///
/// ## The defect this exists for, measured
///
/// RN iOS 0.3.0 (26) terminated the app twice on a physical device the moment
/// Diagnostics was opened — `EXC_CRASH / SIGABRT`, incidents
/// `70413190-FC00-41C8-B127-B25FA4FFDB39` (a 19 m 37 s-old process) and
/// `7E2B69D1-214D-41CC-8E1F-D2916E0A9511` (a fresh 2.3 s-old one), both with
/// the identical stack:
///
/// objc_exception_throw
/// _writeJSONValue
/// _writeJSONObject x4
/// -[_NSJSONWriter dataWithRootObject:options:]
/// +[NSJSONSerialization dataWithJSONObject:options:error:]
/// <app> frameDeliverySection -> viewDidLoad
///
/// The invalid value, reproduced on Darwin in CI and named by path and type:
///
/// $.trace.stageTimings.<stage>.max
///: ((Int64) throws -> Bool) throws -> Optional<Int64>
/// NSInvalidArgumentException: Invalid type in JSON write (__SwiftValue)
///
/// `sorted.last ?? -1` inside a `[String: Any]` literal resolved to the
/// UNAPPLIED `last(where:)` METHOD, not the `last` PROPERTY: with `Any` as the
/// contextual type both overloads are viable, the method value is implicitly
/// promoted to an optional, and `?? -1` therefore never fires. A closure was
/// boxed as `__SwiftValue` and handed to Foundation. It compiled, it read
/// correctly, and it meant something else — the same shape as every other trap
/// this programme has paid for.
///
/// ## Why `try?` is not a defence
///
/// Foundation raises an Objective-C `NSInvalidArgumentException`. That is not a
/// Swift `Error`; `try?` never sees it and the process aborts. The only
/// defences available in pure Swift are to NOT hand it an invalid object, and
/// to check before calling. Both are used here.
///
/// NO BROAD EXCEPTION-SWALLOWING LAYER IS ADDED. There is no `@try/@catch`
/// bridge and no global handler: a layer that absorbed ObjC exceptions across
/// the SDK would convert future crashes into silence, which is strictly worse
/// than a crash for a diagnostic surface.
///
/// IT NAMES STRUCTURE, NEVER CONTENT. An offender is reported as
/// `path: TypeName`. No value is ever rendered — a diagnostic that printed
/// the thing it could not serialize would be a new leak on the one screen
/// whose whole purpose is to be safe to paste into a chat.
public enum DiagnosticsJSON {

    /// Every leaf Foundation cannot represent, as `path: TypeName`.
    ///
    /// Deliberately explicit rather than one boolean: `isValidJSONObject` tells
    /// you THAT the object is unusable, and a diagnostic that can only say
    /// "something is wrong" is how a five-minute fix becomes a device round
    /// trip. It also independently rejects non-finite doubles, which
    /// `isValidJSONObject` has historically admitted while the writer refuses
    /// them — so the two checks are complementary, not redundant.
    public static func offenders(_ value: Any, path: String = "$") -> [String] {
        if value is NSNull { return [] }
        if value is String { return [] }
        if value is Bool { return [] }
        if let d = value as? Double { return d.isFinite ? [] : ["\(path) :: non-finite Double"] }
        if let f = value as? Float { return f.isFinite ? [] : ["\(path) :: non-finite Float"] }
        if value is Int || value is Int8 || value is Int16 || value is Int32 || value is Int64
            || value is UInt || value is UInt8 || value is UInt16
            || value is UInt32 || value is UInt64 { return [] }
        if let arr = value as? [Any] {
            return arr.enumerated().flatMap { offenders($1, path: "\(path)[\($0)]") }
        }
        if let dict = value as? [String: Any] {
            return dict.keys.sorted().flatMap { offenders(dict[$0]!, path: "\(path).\($0)") }
        }
        // A dictionary whose keys are not strings is refused by Foundation, and
        // the KEY TYPE is the defect — reporting "unserializable dictionary"
        // would send the next reader looking at the values.
        if let anyDict = value as? [AnyHashable: Any] {
            let keyTypes = Set(anyDict.keys.map { String(describing: type(of: $0.base)) })
                .sorted().joined(separator: "|")
            return ["\(path) :: dictionary with non-String keys (\(keyTypes))"]
        }
        return ["\(path) :: \(String(describing: type(of: value)))"]
    }

    /// Render one diagnostics section, or a truthful section-scoped error.
    ///
    /// A FAILING SECTION IS NAMED, NEVER OMITTED. Silently dropping the
    /// section that could not be built is how "capture stopped" became
    /// unanswerable in the first place; the reader must be able to tell
    /// "there was nothing" from "we could not render it".
    public static func render(_ object: [String: Any], label: String) -> String {
        let bad = offenders(object)
        guard bad.isEmpty else {
            return "\(label) ERROR: non-JSON diagnostic value\n"
                + bad.map { "  " + $0 }.joined(separator: "\n")
        }
        // Belt and braces: ask Foundation itself before handing it the object,
        // because the walker is OUR model of its rules and the writer is the
        // authority. A disagreement is reported, not risked.
        guard JSONSerialization.isValidJSONObject(object) else {
            return "\(label) ERROR: Foundation refused the object (walker found none)"
        }
        guard let data = try? JSONSerialization.data(
                withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "\(label) ERROR: serialization produced no readable text"
        }
        return text
    }
}
