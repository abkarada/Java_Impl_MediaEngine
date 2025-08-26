# Java Media Engine 🎥🎤

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://openjdk.java.net/)
[![GStreamer](https://img.shields.io/badge/GStreamer-1.x-green.svg)](https://gstreamer.freedesktop.org/)

A high-performance, real-time video and audio streaming engine built with Java and GStreamer. Features adaptive bitrate control, echo cancellation, and ultra-low latency streaming over SRT protocol.

## ✨ Features

- 🎥 **H.264 Video Encoding** with dynamic bitrate adaptation (1-15 Mbps)
- 🎤 **AAC Audio Encoding** with dynamic bitrate adaptation (64-256 kbps)
- 🚀 **SRT Low-Latency Transport** for reliable streaming
- 📊 **RTT-based Adaptive Bitrate Control** - automatically adjusts quality based on network conditions
- 🔇 **Echo Cancellation** and noise suppression
- 📦 **MPEG-TS Multiplexing** for synchronized audio/video
- ⚡ **Ultra-Low Latency** (~20-50ms end-to-end)
- 🧵 **Thread-Safe API** for easy integration

## 🚀 Quick Start

### Prerequisites

- **Java 17+**
- **GStreamer 1.x** with required plugins:
  ```bash
  # Ubuntu/Debian
  sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
                   gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
                   gstreamer1.0-plugins-ugly gstreamer1.0-libav
  
  # Additional SRT support
  sudo apt install gstreamer1.0-plugins-bad
  ```

### Build and Run

```bash
# Clone the repository
git clone https://github.com/abkarada/Java_Impl_MediaEngine.git
cd Java_Impl_MediaEngine

# Build the project
gradle build

# Run the demo
gradle run
```

## 📦 Using as Library

### Add Dependency

```gradle
dependencies {
    implementation files('libs/media-engine-core-1.0.0.jar')
}
```

### Simple Usage

```java
import media_engine.api.MediaEngineAPI;
import media_engine.api.MediaEngineImpl;

public class StreamingApp {
    public static void main(String[] args) {
        MediaEngineAPI engine = new MediaEngineImpl();
        
        // Start streaming to remote host
        engine.startStreaming("0.0.0.0", 7000, "192.168.1.100", 7001, 250);
        
        // Start receiving stream
        engine.startReceiving(7001, 250);
        
        // Monitor performance
        System.out.printf("Video: %d kbps | Audio: %d bps | RTT: %.1f ms%n",
            engine.getCurrentVideoBitrate(),
            engine.getCurrentAudioBitrate(),
            engine.getCurrentRTT()
        );
        
        // Cleanup
        engine.stopStreaming();
        engine.stopReceiving();
    }
}
```

## 🎯 API Reference

### Core Methods

| Method | Description | Parameters |
|--------|-------------|------------|
| `startStreaming()` | Begin video+audio streaming | `localHost, localPort, targetIP, targetPort, latency` |
| `startReceiving()` | Begin video+audio receiving | `localPort, latency` |
| `stopStreaming()` | Stop streaming session | - |
| `stopReceiving()` | Stop receiving session | - |

### Monitoring Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getCurrentVideoBitrate()` | `int` | Current video bitrate (kbps) |
| `getCurrentAudioBitrate()` | `int` | Current audio bitrate (bps) |
| `getCurrentRTT()` | `double` | Current round-trip time (ms) |
| `isActive()` | `boolean` | Engine status |

## 🛠️ Architecture

### Components

- **Sender**: Video capture, encoding, and transmission
- **Receiver**: Stream reception, decoding, and playback  
- **RTT_Client**: Network latency monitoring
- **Echo**: RTT measurement server
- **MediaEngine**: Main orchestrator

### Pipeline Flow

```
Video: v4l2src → jpegdec → x264enc → h264parse → mpegtsmux → srtsink
Audio: pulsesrc → audioconvert → volume → avenc_aac → aacparse → mux
Network: RTT monitoring → Adaptive bitrate adjustment
```

### Adaptive Bitrate Logic

- **RTT > 50ms**: Reduce bitrate (network congestion)
- **RTT < 20ms**: Increase bitrate (network stable)
- **Adjustment interval**: 3 seconds minimum
- **Video range**: 1-15 Mbps
- **Audio range**: 64-256 kbps

## 📊 Performance

- **Video Resolution**: 1280x720 @ 30fps
- **Video Codecs**: H.264 (hardware accelerated when available)
- **Audio Codecs**: AAC-LC
- **Transport Protocol**: SRT with configurable latency
- **Typical Latency**: 20-50ms end-to-end
- **CPU Usage**: ~15-25% on modern hardware

## 🔧 Configuration

### Default Settings

```java
// Video settings
int WIDTH = 1280;
int HEIGHT = 720;
int fps = 30;
int initial_bitrate = 2000; // kbps

// Audio settings  
int initial_audio_bitrate = 128000; // bps

// Network settings
int latency = 250; // ms
```

### Custom Configuration

See `MediaEngineImpl` constructor for custom parameter support.

## 🧪 Testing

```bash
# Run unit tests
gradle test

# Run integration tests
gradle integrationTest

# Performance benchmarks
gradle benchmark
```

## 📋 Requirements

### System Requirements

- **OS**: Linux (Ubuntu 20.04+ recommended)
- **CPU**: Multi-core x86_64 (ARM64 support planned)
- **RAM**: 4GB minimum, 8GB recommended
- **Network**: Stable connection for real-time streaming

### Development Requirements

- **JDK**: OpenJDK 17+ or Oracle JDK 17+
- **Gradle**: 8.0+
- **GStreamer**: 1.16+
- **Camera**: V4L2 compatible video device
- **Audio**: PulseAudio or ALSA

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **GStreamer Team** - For the powerful multimedia framework
- **SRT Alliance** - For the low-latency transport protocol
- **Java Community** - For continuous language improvements

## 📞 Support

- 🐛 **Issues**: [GitHub Issues](https://github.com/abkarada/Java_Impl_MediaEngine/issues)
- 📧 **Email**: [Contact](mailto:abkarada@example.com)
- 💬 **Discussions**: [GitHub Discussions](https://github.com/abkarada/Java_Impl_MediaEngine/discussions)

---

⭐ **Star this repository if it helped you!**
