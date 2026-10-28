import Foundation

/// The durable FRAME record — Swift twin of rn-core `FrameRecord.kt`, byte-
/// identical framing: `[4 bytes big-endian header length][header JSON][bytes]`.
/// The header carries only what the frame endpoint needs plus retry
/// bookkeeping. No screen text, no node content.
public struct FrameHeader: Equatable {
    public let visualFrameId: String
    public let sessionId: String
    public let screenId: String
    public let contentHash: String
    public let width: Int
    public let height: Int
    public let byteSize: Int
    public let format: String
    public let attempts: Int

    public init(visualFrameId: String, sessionId: String, screenId: String, contentHash: String,
                width: Int, height: Int, byteSize: Int, format: String, attempts: Int) {
        self.visualFrameId = visualFrameId; self.sessionId = sessionId; self.screenId = screenId
        self.contentHash = contentHash; self.width = width; self.height = height
        self.byteSize = byteSize; self.format = format; self.attempts = attempts
    }

    func toJson() -> String {
        "{\"visualFrameId\":\"\(visualFrameId)\",\"sessionId\":\"\(sessionId)\",\"screenId\":\"\(screenId)\"," +
        "\"contentHash\":\"\(contentHash)\",\"width\":\(width),\"height\":\(height),\"byteSize\":\(byteSize)," +
        "\"format\":\"\(format)\",\"attempts\":\(attempts)}"
    }

    static func parse(_ json: String) -> FrameHeader? {
        guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let vf = o["visualFrameId"] as? String, let sid = o["sessionId"] as? String,
              let sc = o["screenId"] as? String, let ch = o["contentHash"] as? String,
              let w = o["width"] as? Int, let h = o["height"] as? Int,
              let bs = o["byteSize"] as? Int, let f = o["format"] as? String
        else { return nil }
        return FrameHeader(visualFrameId: vf, sessionId: sid, screenId: sc, contentHash: ch,
                           width: w, height: h, byteSize: bs, format: f,
                           attempts: o["attempts"] as? Int ?? 0)
    }
}

public enum FrameRecord {
    public static let maxHeader = 4096

    public static func encode(_ header: FrameHeader, bytes: Data) -> Data {
        let h = Data(header.toJson().utf8)
        precondition(h.count <= maxHeader)
        var out = Data(capacity: 4 + h.count + bytes.count)
        let n = UInt32(h.count)
        out.append(contentsOf: [UInt8(n >> 24), UInt8((n >> 16) & 0xff), UInt8((n >> 8) & 0xff), UInt8(n & 0xff)])
        out.append(h); out.append(bytes)
        return out
    }

    /// The header and the payload's SIZE, without copying the payload.
    ///
    /// IT EXISTS FOR A MEASURED REASON. `decode` copies the frame bytes out
    /// with `subdata`, and both the windowed drain's admission walk and
    /// `FrameDelivery.settle` need only the IDENTITY of every record — session,
    /// visualFrameId, contentHash and byte count. Decoding a full 4 MiB store to
    /// answer that copies megabytes per pass, several times a second, for
    /// nothing. Same framing checks, same nil-means-unreadable contract.
    public static func peek(_ record: Data) -> (FrameHeader, Int)? {
        guard record.count >= 4 else { return nil }
        let b = [UInt8](record.prefix(4))
        let n = (Int(b[0]) << 24) | (Int(b[1]) << 16) | (Int(b[2]) << 8) | Int(b[3])
        guard n > 0, n <= maxHeader, 4 + n <= record.count else { return nil }
        guard let json = String(data: record.subdata(in: 4..<(4 + n)), encoding: .utf8),
              let header = FrameHeader.parse(json) else { return nil }
        let payload = record.count - (4 + n)
        guard payload == header.byteSize else { return nil }
        return (header, payload)
    }

    /// The identity a settlement and an acknowledgement are both bound to.
    /// ONE construction, so the drain, the descriptor and the settlement search
    /// cannot disagree about what a frame is called.
    public static func identity(_ header: FrameHeader, payloadBytes: Int) -> String {
        "\(header.sessionId)|\(header.visualFrameId)|\(header.contentHash)|\(payloadBytes)"
    }

    /// nil = unreadable (torn/foreign record) — never an empty frame.
    public static func decode(_ record: Data) -> (FrameHeader, Data)? {
        guard record.count >= 4 else { return nil }
        let b = [UInt8](record.prefix(4))
        let n = (Int(b[0]) << 24) | (Int(b[1]) << 16) | (Int(b[2]) << 8) | Int(b[3])
        guard n > 0, n <= maxHeader, 4 + n <= record.count else { return nil }
        guard let json = String(data: record.subdata(in: 4..<(4 + n)), encoding: .utf8),
              let header = FrameHeader.parse(json) else { return nil }
        let bytes = record.subdata(in: (4 + n)..<record.count)
        guard bytes.count == header.byteSize else { return nil }
        return (header, bytes)
    }
}
