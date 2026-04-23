import SwiftUI

func panelChipButton(label: String, icon: String?, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        HStack(spacing: 4) {
            if let icon = icon {
                Image(systemName: icon)
                    .font(.system(size: 12))
                    .foregroundColor(.white.opacity(0.7))
            }
            Text(label)
                .font(.system(size: 12))
                .foregroundColor(.white.opacity(0.7))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.white.opacity(0.1))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
    .buttonStyle(.plain)
}

func addonFilterChip(label: String, isSelected: Bool, isLoading: Bool, hasError: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        HStack(spacing: 6) {
            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(0.5)
                    .frame(width: 12, height: 12)
            }
            Text(label)
                .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                .foregroundColor(
                    hasError ? Color.red :
                    isSelected ? .white :
                    .white.opacity(0.7)
                )
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(isSelected ? Color.white.opacity(0.15) : Color.white.opacity(0.06))
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(
                    isSelected ? Color.white.opacity(0.3) : Color.white.opacity(0.1),
                    lineWidth: 1
                )
        )
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
    .buttonStyle(.plain)
}

struct GestureFeedbackPill: View {
    let feedback: GestureFeedbackState

    var body: some View {
        let bgColor = feedback.isDanger ? Color(red: 0.365, green: 0.122, blue: 0.122).opacity(0.88) : Color.black.opacity(0.75)
        let iconBgColor = feedback.isDanger ? Color(red: 1.0, green: 0.541, blue: 0.502).opacity(0.22) : Color.white.opacity(0.15)
        let iconTint = feedback.isDanger ? Color(red: 1.0, green: 0.757, blue: 0.757) : Color.white

        let iconName: String = {
            switch feedback.icon {
            case .speed: return "speedometer"
            case .volume: return "speaker.wave.2.fill"
            case .volumeMuted: return "speaker.slash.fill"
            case .seekForward: return "forward.fill"
            case .seekBackward: return "backward.fill"
            }
        }()

        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(iconBgColor)
                    .frame(width: 28, height: 28)
                Image(systemName: iconName)
                    .font(.system(size: 14))
                    .foregroundColor(iconTint)
            }
            Text(feedback.message)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(bgColor)
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }
}

struct OpeningOverlayContent: View {
    let logo: String?
    let title: String

    @State private var contentAlpha: Double = 0
    @State private var pulseScale: CGFloat = 1.0

    var body: some View {
        ZStack {
            if let logoUrl = logo, !logoUrl.isEmpty {
                if #available(macOS 12.0, *) {
                    AsyncImage(url: URL(string: logoUrl)) { image in
                        image.resizable().aspectRatio(contentMode: .fit)
                    } placeholder: {
                        Color.clear
                    }
                    .frame(width: 300, height: 180)
                    .scaleEffect(pulseScale)
                    .opacity(contentAlpha)
                } else {
                    Text(title)
                        .font(.system(size: 42, weight: .heavy))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .scaleEffect(pulseScale)
                        .opacity(contentAlpha)
                }
            } else if !title.isEmpty {
                Text(title)
                    .font(.system(size: 42, weight: .heavy))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.horizontal, 24)
                    .scaleEffect(pulseScale)
                    .opacity(contentAlpha)
            } else {
                ProgressView()
                    .progressViewStyle(.circular)
                    .scaleEffect(1.8)
                    .colorMultiply(Color(red: 0.898, green: 0.035, blue: 0.078))
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                withAnimation(.easeInOut(duration: 0.7)) {
                    contentAlpha = 1.0
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                    pulseScale = 1.04
                }
            }
        }
    }
}
