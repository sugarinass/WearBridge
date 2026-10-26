import HealthKit
import os.log

/// Writes watch health records into Apple Health.
///
/// HealthKit is unavailable while the iPhone is locked, so `save` throws and
/// the caller acks "retry" — the watch keeps the batch and resends later.
final class HealthKitWriter {

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "HealthKitWriter")
    private let store = HKHealthStore()

    private let device = HKDevice(
        name: "Wear OS Watch",
        manufacturer: nil,
        model: nil,
        hardwareVersion: nil,
        firmwareVersion: nil,
        softwareVersion: nil,
        localIdentifier: nil,
        udiDeviceIdentifier: nil
    )

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    private static let shareTypes: Set<HKSampleType> = [
        HKQuantityType(.stepCount),
        HKQuantityType(.distanceWalkingRunning),
        HKQuantityType(.distanceCycling),
        HKQuantityType(.flightsClimbed),
        HKQuantityType(.heartRate),
        HKQuantityType(.activeEnergyBurned),
        HKCategoryType(.sleepAnalysis),
        HKObjectType.workoutType()
    ]

    func requestAuthorization() async throws {
        try await store.requestAuthorization(toShare: Self.shareTypes, read: [])
    }

    func save(_ records: [HealthRecord]) async throws {
        // Samples whose type the user didn't allow are dropped, not retried forever
        let samples = records
            .filter { $0.kind != .workout }
            .compactMap(sample(for:))
            .filter { isAuthorized($0.sampleType) }
        if !samples.isEmpty {
            try await store.save(samples)
        }

        for workout in records where workout.kind == .workout {
            guard isAuthorized(HKObjectType.workoutType()) else { continue }
            try await saveWorkout(workout)
        }
        logger.info("Saved \(records.count) records to HealthKit")
    }

    // MARK: - Samples

    private func sample(for record: HealthRecord) -> HKSample? {
        let metadata = syncMetadata(record)
        switch record.kind {
        case .steps:
            return quantity(.stepCount, .count(), record.value, record, metadata)
        case .distance:
            return quantity(.distanceWalkingRunning, .meter(), record.value, record, metadata)
        case .floors:
            return quantity(.flightsClimbed, .count(), record.value, record, metadata)
        case .heartRate:
            return quantity(.heartRate, .count().unitDivided(by: .minute()), record.value, record, metadata)
        case .sleep:
            return HKCategorySample(
                type: HKCategoryType(.sleepAnalysis),
                value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue,
                start: record.start,
                end: record.end,
                device: device,
                metadata: metadata
            )
        case .workout:
            return nil
        }
    }

    private func quantity(
        _ id: HKQuantityTypeIdentifier,
        _ unit: HKUnit,
        _ value: Double,
        _ record: HealthRecord,
        _ metadata: [String: Any]
    ) -> HKQuantitySample {
        HKQuantitySample(
            type: HKQuantityType(id),
            quantity: HKQuantity(unit: unit, doubleValue: value),
            start: record.start,
            end: record.end,
            device: device,
            metadata: metadata
        )
    }

    // MARK: - Workouts

    private func saveWorkout(_ record: HealthRecord) async throws {
        // A lost ack makes the watch resend — skip workouts already saved
        let existing = HKSampleQueryDescriptor(
            predicates: [.workout(HKQuery.predicateForObjects(
                withMetadataKey: HKMetadataKeySyncIdentifier,
                allowedValues: [record.syncIdentifier]
            ))],
            sortDescriptors: [],
            limit: 1
        )
        let alreadySaved = try await existing.result(for: store)
        if !alreadySaved.isEmpty {
            logger.info("Workout \(record.syncIdentifier) already saved")
            return
        }

        let configuration = HKWorkoutConfiguration()
        configuration.activityType = activityType(record.activity)
        configuration.locationType = .unknown

        let builder = HKWorkoutBuilder(healthStore: store, configuration: configuration, device: device)
        try await builder.beginCollection(at: record.start)

        var samples: [HKSample] = []
        if record.value > 0 {
            // Health Services reports total calories (basal + active)
            samples.append(quantity(.activeEnergyBurned, .kilocalorie(), record.value, record, [:]))
        }
        if record.value2 > 0 {
            let distanceType: HKQuantityTypeIdentifier =
                record.activity == .cycling ? .distanceCycling : .distanceWalkingRunning
            samples.append(quantity(distanceType, .meter(), record.value2, record, [:]))
        }
        if !samples.isEmpty {
            try await builder.addSamples(samples)
        }

        try await builder.addMetadata(syncMetadata(record))
        try await builder.endCollection(at: record.end)
        _ = try await builder.finishWorkout()
    }

    private func activityType(_ activity: HealthRecord.Activity) -> HKWorkoutActivityType {
        switch activity {
        case .walking: return .walking
        case .running: return .running
        case .cycling: return .cycling
        case .strength: return .traditionalStrengthTraining
        case .other: return .other
        }
    }

    // MARK: - Helpers

    private func syncMetadata(_ record: HealthRecord) -> [String: Any] {
        [
            HKMetadataKeySyncIdentifier: record.syncIdentifier,
            HKMetadataKeySyncVersion: 1
        ]
    }

    private func isAuthorized(_ type: HKObjectType) -> Bool {
        store.authorizationStatus(for: type) == .sharingAuthorized
    }
}
