package media_engine.examples;

import media_engine.api.MediaEngineAPI;
import media_engine.api.MediaEngineImpl;

/**
 * Media Engine Kullanım Örneği
 * 
 * Bu örnek başka projelerinizde nasıl kullanacağınızı gösterir.
 */
public class MediaEngineUsageExample {
    
    public static void main(String[] args) {
        
        // 🚀 Media Engine API'sini oluştur
        MediaEngineAPI engine = new MediaEngineImpl();
        
        try {
            System.out.println("=== Media Engine Demo ===");
            
            // 📡 Streaming başlat (Video + Audio gönder)
            boolean streamingStarted = engine.startStreaming(
                "127.0.0.1",  // Local IP
                7000,         // Local Port  
                "127.0.0.1",  // Target IP
                7001,         // Target Port
                250           // Latency
            );
            
            if (streamingStarted) {
                System.out.println("✅ Streaming aktif!");
            }
            
            // 📺 Receiving başlat (Video + Audio al)
            boolean receivingStarted = engine.startReceiving(
                7001,  // Listen Port
                250    // Latency
            );
            
            if (receivingStarted) {
                System.out.println("✅ Receiving aktif!");
            }
            
            // 📊 Status monitoring
            Thread statusMonitor = new Thread(() -> {
                try {
                    while (engine.isActive()) {
                        System.out.printf("📊 Status - Video: %d kbps | Audio: %d bps | RTT: %.2f ms%n",
                            engine.getCurrentVideoBitrate(),
                            engine.getCurrentAudioBitrate(), 
                            engine.getCurrentRTT()
                        );
                        Thread.sleep(5000); // Her 5 saniyede status
                    }
                } catch (InterruptedException e) {
                    System.out.println("Status monitoring durduruldu");
                }
            });
            statusMonitor.start();
            
            // 🎮 Demo için 30 saniye çalıştır
            System.out.println("Demo 30 saniye çalışacak...");
            Thread.sleep(30000);
            
            // 🛑 Cleanup
            engine.stopStreaming();
            engine.stopReceiving();
            statusMonitor.interrupt();
            
            if (engine instanceof MediaEngineImpl) {
                ((MediaEngineImpl) engine).shutdown();
            }
            
        } catch (InterruptedException e) {
            System.out.println("Demo interrupted");
        }
    }
}
