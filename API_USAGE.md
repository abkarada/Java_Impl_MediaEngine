# Media Engine Core Library 🎥🎤

Real-time video + audio streaming engine with adaptive bitrate control.

## 📦 Installation

### Option 1: JAR Dependency
```bash
# Build the library
gradle jar

# Copy JAR to your project
cp build/libs/media-engine-core-1.0.0.jar /your-project/libs/
```

### Option 2: Gradle Dependency
```gradle
dependencies {
    implementation 'com.mediaengine:media-engine-core:1.0.0'
}
```

## 🚀 Quick Start

```java
import media_engine.api.MediaEngineAPI;
import media_engine.api.MediaEngineImpl;

public class MyApp {
    public static void main(String[] args) {
        // Create Media Engine instance
        MediaEngineAPI engine = new MediaEngineImpl();
        
        // Start streaming (video + audio sender)
        engine.startStreaming("127.0.0.1", 7000, "192.168.1.100", 7001, 250);
        
        // Start receiving (video + audio receiver) 
        engine.startReceiving(7001, 250);
        
        // Monitor status
        System.out.println("Video Bitrate: " + engine.getCurrentVideoBitrate() + " kbps");
        System.out.println("Audio Bitrate: " + engine.getCurrentAudioBitrate() + " bps");
        System.out.println("Current RTT: " + engine.getCurrentRTT() + " ms");
        
        // Cleanup
        engine.stopStreaming();
        engine.stopReceiving();
    }
}
```

## 🎯 Features

- ✅ **H.264 Video Encoding** with dynamic bitrate
- ✅ **AAC Audio Encoding** with dynamic bitrate  
- ✅ **SRT Low-latency Transport**
- ✅ **RTT-based Adaptive Bitrate**
- ✅ **Echo Cancellation**
- ✅ **MPEG-TS Multiplexing**
- ✅ **Thread-safe API**

## 📊 API Reference

### MediaEngineAPI Methods

| Method | Description | Parameters |
|--------|-------------|------------|
| `startStreaming()` | Start video+audio streaming | localHost, localPort, targetIP, targetPort, latency |
| `startReceiving()` | Start video+audio receiving | localPort, latency |
| `stopStreaming()` | Stop streaming | - |
| `stopReceiving()` | Stop receiving | - |
| `getCurrentVideoBitrate()` | Get current video bitrate | Returns: int (kbps) |
| `getCurrentAudioBitrate()` | Get current audio bitrate | Returns: int (bps) |
| `getCurrentRTT()` | Get current RTT | Returns: double (ms) |
| `isActive()` | Check if engine is active | Returns: boolean |

## 🔧 System Requirements

- **Java 17+**
- **GStreamer 1.x** with plugins:
  - gst-plugins-base
  - gst-plugins-good  
  - gst-plugins-bad
  - gst-plugins-ugly
- **Linux/Ubuntu** (tested)

## 📝 Example Projects

See `media_engine/examples/MediaEngineUsageExample.java` for complete usage example.

## 🎮 Integration Examples

### Spring Boot Integration
```java
@Service
public class StreamingService {
    private final MediaEngineAPI engine = new MediaEngineImpl();
    
    @PostMapping("/start-stream")
    public ResponseEntity<String> startStream(@RequestBody StreamRequest request) {
        boolean started = engine.startStreaming(
            request.getLocalHost(), request.getLocalPort(),
            request.getTargetIP(), request.getTargetPort(), 
            request.getLatency()
        );
        return started ? ResponseEntity.ok("Started") : ResponseEntity.badRequest().body("Failed");
    }
}
```

### Android Integration
```java
public class MediaStreamActivity extends AppCompatActivity {
    private MediaEngineAPI engine;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new MediaEngineImpl();
        
        // Start streaming on button click
        startButton.setOnClickListener(v -> {
            engine.startStreaming("0.0.0.0", 8000, targetIP, 8001, 200);
        });
    }
}
```

## 📄 License

MIT License - see LICENSE file for details.
