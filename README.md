# FpvApp
# FpvApp 🚀

**A phone-to-phone FPV streaming app for Android.**

FpvApp is an Android application project aiming to enable direct, low-latency video streaming between two Android devices without requiring an internet connection.

The goal is to use one phone as a camera transmitter and another as a live video viewer, with potential applications in DIY FPV cars, robotics, and remote-control projects.

> **Status:** In development. Features are subject to implementation and testing.

## ✨ Features & Goals

- 📡 Wi-Fi Direct connectivity between Android devices.
- 🎥 Real-time camera video streaming.
- ⚡ Low-latency communication using WebRTC.
- 🎙️ Potential microphone streaming support.
- 📱 Native Android interface built with Jetpack Compose.
- 🤖 Designed with DIY robotics and FPV applications in mind.

## 🛠️ Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **IDE:** Android Studio
- **Connectivity:** Wi-Fi Direct
- **Media Streaming:** WebRTC
- **Build System:** Gradle

## 📂 Project Components

The application is being developed around these core components:

- **Transmitter:** Captures camera footage.
- **Viewer:** Displays the incoming video stream.
- **Wi-Fi Direct Manager:** Handles peer discovery and connection setup.
- **WebRTC Manager:** Manages real-time media communication.
- **Signaling Manager:** Coordinates WebRTC connection negotiation.
- **Settings:** Provides configuration options.

## 🚀 Getting Started

### Requirements

- Android Studio
- Android SDK and a compatible JDK
- Gradle dependencies required by the project
- Two Android devices for physical-device streaming tests

### Clone the repository

```bash
git clone https://github.com/Pranishtiwary/FpvApp.git
cd FpvApp
```

Open the project folder in Android Studio and allow Gradle to sync.

### Build a debug APK

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

On Linux or macOS:

```bash
./gradlew assembleDebug
```

If the build succeeds, the APK will normally be available at:

`app/build/outputs/apk/debug/app-debug.apk`

A successful build does not necessarily mean that phone-to-phone streaming is fully functional.

## 🗺️ Development Roadmap

- [ ] Complete transmitter and viewer screens.
- [ ] Finish navigation and runtime permission handling.
- [ ] Complete Wi-Fi Direct connectivity.
- [ ] Integrate WebRTC signaling and media streaming.
- [ ] Test video streaming between two physical devices.
- [ ] Measure latency and connection stability.
- [ ] Test reconnection and error handling.
- [ ] Publish a tested APK release.

## 🎯 Vision

The long-term goal is to make FpvApp a useful building block for DIY FPV systems, robotics, and remote-controlled vehicles using Android phones as camera and viewing devices.

## 🤝 Contributing

Suggestions, bug reports, and constructive feedback are welcome. Feel free to open an issue or submit a pull request.

## 📄 License

No license has been specified yet. Please contact the repository owner regarding reuse or redistribution until a license is added.
