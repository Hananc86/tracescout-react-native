import Foundation

/// A SANITIZED, durable, append-only checkpoint log.
///
/// It exists because in-memory state does not cross a process boundary: on
/// Android an `am instrument` read reported `uploadAttempted:false` about a
/// process that never ran the code, and only a FILE-backed trace could answer
/// what the capturing process actually did. iOS has the same boundary after a
/// termination/relaunch, and no adb — so this file IS the evidence.
///
/// **Allowlist, never a dump.** Every value written here is a checkpoint name,
/// a count, a status, a typed code or a digest prefix. Never a record body,
/// never a route argument, never a key, never a header.
public final class RecoveryTrace {
    public static let shared = RecoveryTrace()
    private var url: URL?
    private let lock = NSLock()
    private let maxBytes = 256 * 1024

    private init() {}

    /// Must be the FIRST statement of `start`, before the queue opens and
    /// NOT behind a started-once latch: on Android `RnTransport` called `log`
    /// in five places without ever installing, so every checkpoint went nowhere
    /// and two device runs could not be explained.
    public func install(directory: URL) {
        lock.lock(); defer { lock.unlock() }
        url = directory.appendingPathComponent("tracescout-rn-trace.log")
    }

    public func log(_ event: String, _ fields: [(String, String)] = []) {
        lock.lock(); defer { lock.unlock() }
        guard let u = url else { return }
        var pairs: [(String, RnEnvelope.Value)] = [
            ("ts", .int64AsString(Int64(Date().timeIntervalSince1970 * 1000))),
            ("event", .string(event)),
        ]
        for (k, v) in fields { pairs.append((k, .string(v))) }
        let line = RnEnvelope.json(pairs) + "\n"
        let attrs = try? FileManager.default.attributesOfItem(atPath: u.path)
        if let size = attrs?[.size] as? Int, size > maxBytes {
            // Bounded: keep the newest half. A trace that grows without limit
            // becomes a disk problem the SDK caused.
            if let raw = try? String(contentsOf: u, encoding: .utf8) {
                let lines = raw.split(separator: "\n")
                let keep = lines.suffix(lines.count / 2).joined(separator: "\n") + "\n"
                try? keep.write(to: u, atomically: true, encoding: .utf8)
            }
        }
        if let h = try? FileHandle(forWritingTo: u) {
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: u, options: .completeFileProtection)
        }
    }

    /// Read-only tail for the fixture diagnostics surface.
    public func tail(lines: Int = 40) -> [String] {
        lock.lock(); defer { lock.unlock() }
        guard let u = url, let raw = try? String(contentsOf: u, encoding: .utf8) else { return [] }
        return raw.split(separator: "\n").suffix(lines).map(String.init)
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        guard let u = url else { return }
        try? FileManager.default.removeItem(at: u)
    }
}
