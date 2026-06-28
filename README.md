# USB DeckRec

**Low-Latency USB Digital Audio Recorder for Android**

An open-source USB audio recorder compatible with class-compliant DJ mixers that expose multi-channel audio over USB.

## Features

- Bit-perfect 24-bit/48 kHz recording from USB class-compliant DJ mixers via USB OTG
- Automatic mixer profile detection for supported devices
- Dynamic channel extraction based on mixer configuration
- Sub-10 ms round-trip latency via Oboe/AAudio
- FLAC and WAV output formats
- Foreground service for background recording
- MIDI/HID fader automation and track markers
- Lock-free audio pipeline — no allocations on audio callback

## Supported Devices

Profiles are included for the following mixers. This project is not affiliated with or endorsed by their manufacturers.

| Mixer | Channels | Notes |
|-------|----------|-------|
| DJM-900Nexus | 4 | Master on channels 1-2 |
| DJM-900NXS2 | 10 | Dedicated rec bus on channels 9-10 |
| DJM-900SRT | 2 | Serato interface |
| DJM-750MK2 | 4 | Master on channels 1-2 |
| DJM-A9 | 10 | Dedicated rec bus on channels 9-10 |
| DJM-V10 | 10 | Dedicated rec bus on channels 9-10 |
| DJM-S11 | 10 | Serato DJ mixer |

## Project Structure

```
usbdeckrec/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/usbdeckrec/
│   │   │   │   ├── ui/                 # Jetpack Compose UI
│   │   │   │   ├── service/            # Recording foreground service
│   │   │   │   ├── audio/              # Audio engine bridge & mixer profiles
│   │   │   │   ├── midi/               # MIDI/HID parser
│   │   │   │   ├── data/               # Room database & repositories
│   │   │   │   ├── usb/                # USB device management
│   │   │   │   └── model/              # Domain models
│   │   │   ├── jni/                    # Native C++ audio engine
│   │   │   └── res/                    # Android resources
│   │   └── test/                       # Unit tests
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── docker/                             # Docker build container
├── .github/workflows/                  # CI/CD pipeline
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Building

### Prerequisites

- Docker and Docker Compose v2

### Quick Start

```bash
./docker/build-apk.sh
```

## Disclaimer

This is an independent open-source project. All product names, logos, brands, and trademarks mentioned are property of their respective owners. Use of these names does not imply endorsement or affiliation.

## License

MIT License — see [LICENSE](LICENSE) for details.
