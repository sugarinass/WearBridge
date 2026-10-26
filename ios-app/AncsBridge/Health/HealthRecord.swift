import Foundation

/// A health sample received from the watch over BLE.
///
/// Batch wire format (little-endian), must match wearos-app `HealthProtocol.kt`:
///   [version u8][batchId u16][record × N]
///   record (26 bytes): [type u8][subtype u8][startEpochSec u32][durationSec u32][value f64][value2 f64]
struct HealthRecord {

    enum Kind: UInt8 {
        case steps = 1       // value = count
        case distance = 2    // value = meters
        case floors = 3      // value = floors
        case heartRate = 4   // value = bpm
        case sleep = 5       // interval only
        case workout = 16    // subtype = activity, value = kcal, value2 = meters
    }

    /// Workout activity (subtype for `.workout`)
    enum Activity: UInt8 {
        case other = 0, walking = 1, running = 2, cycling = 3, strength = 4
    }

    static let protocolVersion: UInt8 = 1
    static let headerSize = 3
    static let recordSize = 26

    let kind: Kind
    let subtype: UInt8
    let start: Date
    let duration: TimeInterval
    let value: Double
    let value2: Double

    var end: Date { start.addingTimeInterval(duration) }

    /// Stable across resends so HealthKit deduplicates (HKMetadataKeySyncIdentifier).
    var syncIdentifier: String {
        "wearbridge-\(kind.rawValue)-\(subtype)-\(Int(start.timeIntervalSince1970))"
    }

    var activity: Activity { Activity(rawValue: subtype) ?? .other }

    /// Parses one batch. Unknown record types are skipped; returns nil if malformed.
    static func parseBatch(_ data: Data) -> (batchId: UInt16, records: [HealthRecord])? {
        let bytes = [UInt8](data)
        guard bytes.count >= headerSize, bytes[0] == protocolVersion,
              (bytes.count - headerSize) % recordSize == 0 else { return nil }

        let batchId = UInt16(bytes[1]) | UInt16(bytes[2]) << 8
        var records: [HealthRecord] = []
        var offset = headerSize
        while offset + recordSize <= bytes.count {
            defer { offset += recordSize }
            guard let kind = Kind(rawValue: bytes[offset]) else { continue }
            records.append(HealthRecord(
                kind: kind,
                subtype: bytes[offset + 1],
                start: Date(timeIntervalSince1970: TimeInterval(readUInt32(bytes, offset + 2))),
                duration: TimeInterval(readUInt32(bytes, offset + 6)),
                value: Double(bitPattern: readUInt64(bytes, offset + 10)),
                value2: Double(bitPattern: readUInt64(bytes, offset + 18))
            ))
        }
        return (batchId, records)
    }

    static func ackPayload(batchId: UInt16, saved: Bool) -> Data {
        Data([UInt8(batchId & 0xFF), UInt8(batchId >> 8), saved ? 0x00 : 0x01])
    }

    private static func readUInt32(_ b: [UInt8], _ i: Int) -> UInt32 {
        (0..<4).reduce(UInt32(0)) { $0 | UInt32(b[i + $1]) << (8 * UInt32($1)) }
    }

    private static func readUInt64(_ b: [UInt8], _ i: Int) -> UInt64 {
        (0..<8).reduce(UInt64(0)) { $0 | UInt64(b[i + $1]) << (8 * UInt64($1)) }
    }
}
