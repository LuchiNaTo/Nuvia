import SwiftUI

struct NuvioSourcesPanel: View {
    @ObservedObject var state: NuvioPlayerState
    var onDismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.52)
                .onTapGesture { onDismiss() }

            VStack(spacing: 0) {
                HStack {
                    Text("Sources")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    HStack(spacing: 8) {
                        panelChipButton(label: "Reload", icon: "arrow.clockwise") {
                            state.sourceReloadRequested = true
                        }
                        panelChipButton(label: "Close", icon: nil) {
                            onDismiss()
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 12)

                let addonGroups = state.sourceAddonGroups
                if addonGroups.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            addonFilterChip(
                                label: "All",
                                isSelected: state.sourceSelectedFilter == nil,
                                isLoading: false,
                                hasError: false
                            ) {
                                state.sourceSelectedFilter = nil
                                state.sourceFilterSelectedValue = nil
                                state.sourceFilterChanged = true
                            }
                            ForEach(addonGroups) { group in
                                addonFilterChip(
                                    label: group.addonName,
                                    isSelected: state.sourceSelectedFilter == group.addonId,
                                    isLoading: group.isLoading,
                                    hasError: group.hasError
                                ) {
                                    state.sourceSelectedFilter = group.addonId
                                    state.sourceFilterSelectedValue = group.addonId
                                    state.sourceFilterChanged = true
                                }
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .padding(.bottom, 12)
                }

                let streams = state.filteredSourceStreams
                if state.sourcesLoading && streams.isEmpty {
                    Spacer()
                    ProgressView()
                        .progressViewStyle(.circular)
                        .scaleEffect(0.8)
                        .frame(height: 80)
                    Spacer()
                } else if streams.isEmpty {
                    Spacer()
                    Text("No streams found")
                        .font(.system(size: 14))
                        .foregroundColor(.white.opacity(0.6))
                        .frame(height: 80)
                    Spacer()
                } else {
                    ScrollView {
                        VStack(spacing: 6) {
                            ForEach(streams) { stream in
                                sourceStreamRow(stream: stream) {
                                    state.sourceStreamSelectedUrl = stream.url
                                    onDismiss()
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                    }
                }
            }
            .frame(maxWidth: 520)
            .frame(maxHeight: 600)
            .background(Color(red: 0.12, green: 0.12, blue: 0.12))
            .clipShape(RoundedRectangle(cornerRadius: 24))
            .overlay(
                RoundedRectangle(cornerRadius: 24)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
        }
    }

    func sourceStreamRow(stream: NuvioStreamInfo, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(stream.label)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        if stream.isCurrent {
                            Text("Playing")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Color.white.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    if let sub = stream.subtitle, !sub.isEmpty, sub != stream.label {
                        Text(sub)
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.6))
                            .lineLimit(2)
                    }
                    Text(stream.addonName)
                        .font(.system(size: 11))
                        .italic()
                        .foregroundColor(.white.opacity(0.6))
                        .lineLimit(1)
                }
                Spacer()
                if stream.isCurrent {
                    Image(systemName: "checkmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(stream.isCurrent ? Color.white.opacity(0.12) : Color.clear)
            .overlay(
                stream.isCurrent ?
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.white.opacity(0.2), lineWidth: 1) : nil
            )
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
