import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var viewModel: BLEViewModel
    @State private var liveCallStatusEnabled: Bool = false
    @State private var appleHealthSyncEnabled: Bool = false

    var body: some View {
        NavigationStack {
            List {
                // Features
                Section {
                    NavigationLink {
                        QuickReplySettingsView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Quick Reply Messages")
                                Text("Decline calls with a preset message")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "text.bubble")
                                .foregroundColor(.blue)
                        }
                    }

                    Toggle(isOn: $liveCallStatusEnabled) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Live Call Status")
                                Text("Push real-time call state to watch via BLE. Requires this app to be running.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "phone.connection")
                                .foregroundColor(.green)
                        }
                    }
                    .onChange(of: liveCallStatusEnabled) { newValue in
                        viewModel.peripheralManager.settingsStore.liveCallStatus = newValue
                        viewModel.peripheralManager.updateCallStateObserving()
                    }
                }

                // Apple Health
                Section {
                    Toggle(isOn: $appleHealthSyncEnabled) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Apple Health Sync")
                                Text("Save steps, heart rate, sleep and workouts from the watch to the Health app.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        } icon: {
                            Image(systemName: "heart.fill")
                                .foregroundColor(.pink)
                        }
                    }
                    .disabled(!HealthKitWriter.isAvailable)
                    .onChange(of: appleHealthSyncEnabled) { newValue in
                        viewModel.peripheralManager.settingsStore.appleHealthSync = newValue
                        if newValue {
                            Task { try? await viewModel.peripheralManager.healthWriter.requestAuthorization() }
                        }
                    }

                    if let lastSync = viewModel.peripheralManager.settingsStore.lastHealthSync {
                        HStack {
                            Text("Last Sync")
                            Spacer()
                            Text(lastSync, style: .relative)
                                .foregroundColor(.secondary)
                        }
                    }
                } header: {
                    Text("Health")
                } footer: {
                    Text("Data is buffered on the watch and sent when this app is running nearby and the iPhone is unlocked. Workouts must be recorded with the Workout button in WearBridge on the watch.")
                }

                // About
                Section {
                    NavigationLink {
                        HowItWorksView()
                    } label: {
                        Label("How it Works", systemImage: "questionmark.circle")
                    }

                    HStack {
                        Text("Version")
                        Spacer()
                        Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                            .foregroundColor(.secondary)
                    }
                } header: {
                    Text("About")
                } footer: {
                    Text("WearBridge uses Apple's ANCS to forward notifications over Bluetooth. All data stays local.")
                }
            }
            .navigationTitle("Settings")
            .onAppear {
                liveCallStatusEnabled = viewModel.peripheralManager.settingsStore.liveCallStatus
                appleHealthSyncEnabled = viewModel.peripheralManager.settingsStore.appleHealthSync
            }
        }
    }
}
