import Foundation

/// BUILD 27 — the rendered diagnostics block, persisted BEFORE it is displayed.
///
/// THE FAILURE THIS EXISTS FOR. On Build 26 the Diagnostics screen was the
/// ONLY reader of the durable capture evidence, and opening it terminated the
/// app twice on a physical device. Whatever the crash turns out to be —
/// rendering, layout, memory, a watchdog — the observable consequence was the
/// same: the operator could not obtain the block, so the evidence a whole
/// physical drive existed to produce was unreachable.
///
/// This does NOT excuse the crash and is not a fix for it. The root cause is
/// pinned from the device crash report and fixed separately. What this changes
/// is that the reader can no longer be the thing that destroys the output: the
/// text is committed to disk first, so the NEXT process can serve it even if
/// this one never finishes drawing it.
///
/// BOUNDED, NOT A LOG. One file, one generation, replaced in place, capped.
/// An append-only diagnostics log would grow with every Refresh and become a
/// second storage problem on the device it is meant to diagnose.
public enum DiagnosticsRenderArchive {

    /// Hard cap on the persisted text. The block is bounded by construction
    /// (a bounded header, four bounded summaries, 40 trace lines, one
    /// aggregate), so this is a backstop against a future unbounded section
    /// rather than an expected path — and when it fires it says so IN the
    /// artifact instead of silently storing a prefix that reads as complete.
    public static let maxBytes = 256 * 1024

    public struct Meta: Equatable {
        public var exists: Bool
        public var byteCount: Int
        public var modifiedAtMs: Int64
        public var truncated: Bool
        public init(exists: Bool, byteCount: Int, modifiedAtMs: Int64, truncated: Bool) {
            self.exists = exists; self.byteCount = byteCount
            self.modifiedAtMs = modifiedAtMs; self.truncated = truncated
        }
        public static let absent = Meta(exists: false, byteCount: 0,
                                        modifiedAtMs: -1, truncated: false)
    }

    static let truncationMarker = "\n[render archive truncated at \(maxBytes) bytes]\n"

    /// The text that WOULD be stored, so the bound is testable without a disk.
    public static func bounded(_ text: String) -> String {
        let data = Data(text.utf8)
        guard data.count > maxBytes else { return text }
        let keep = maxBytes - truncationMarker.utf8.count
        guard keep > 0 else { return truncationMarker }
        // Cut on a UTF-8 boundary: a torn scalar is not text.
        var cut = keep
        let bytes = [UInt8](data)
        while cut > 0 && (bytes[cut] & 0xC0) == 0x80 { cut -= 1 }
        let head = String(decoding: bytes[0..<cut], as: UTF8.self)
        return head + truncationMarker
    }

    /// Read the CURRENT artifact's metadata. Call this BEFORE `save` in a
    /// render pass: reporting after the write would describe this render rather
    /// than the previous one, which is the only one that carries information
    /// about a render that did not survive.
    public static func meta(url: URL?) -> Meta {
        guard let u = url,
              let attrs = try? FileManager.default.attributesOfItem(atPath: u.path)
        else { return .absent }
        let size = (attrs[.size] as? Int) ?? 0
        let ms: Int64
        if let d = attrs[.modificationDate] as? Date {
            ms = Int64(d.timeIntervalSince1970 * 1000)
        } else { ms = -1 }
        let text = (try? String(contentsOf: u, encoding: .utf8)) ?? ""
        return Meta(exists: true, byteCount: size, modifiedAtMs: ms,
                    truncated: text.hasSuffix(truncationMarker))
    }

    /// Persist atomically. Never throws into the render path: a diagnostics
    /// artifact that could crash the diagnostics screen would be self-defeating.
    @discardableResult
    public static func save(_ text: String, url: URL?) -> Bool {
        guard let u = url else { return false }
        do { try bounded(text).write(to: u, atomically: true, encoding: .utf8); return true }
        catch { return false }
    }

    public static func load(url: URL?) -> String? {
        guard let u = url else { return nil }
        return try? String(contentsOf: u, encoding: .utf8)
    }
}
