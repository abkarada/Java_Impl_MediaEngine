package media_engine.api;

import media_engine.Receiver;
import media_engine.media_engine.Sender;

import java.io.IOException;
import java.nio.channels.Pipe;

/**
 * Media Engine API Implementation
 * 
 * Kullanım örneği:
 * 
 * MediaEngineAPI engine = new MediaEngineImpl();
 * engine.startStreaming("127.0.0.1", 7000, "192.168.1.100", 7001, 250);
 * engine.startReceiving(7001, 250);
 */
public class MediaEngineImpl implements MediaEngineAPI {
    
    private Sender sender;
    private Receiver receiver;
    private volatile boolean isStreamingActive = false;
    private volatile boolean isReceivingActive = false;
    
    @Override
    public boolean startStreaming(String localHost, int localPort, String targetIP, int targetPort, int latency) {
        try {
            if (isStreamingActive) {
                stopStreaming(); // Önceki stream'i durdur
            }
            
            // RTT için pipe oluştur
            Pipe pipe = Pipe.open();
            
            // Sender başlat
            sender = new Sender(localHost, localPort, targetIP, targetPort, latency, pipe);
            sender.start();
            
            isStreamingActive = true;
            System.out.println("✅ Media Engine Streaming başlatıldı: " + targetIP + ":" + targetPort);
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Streaming başlatılamadı: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean startReceiving(int localPort, int latency) {
        try {
            if (isReceivingActive) {
                stopReceiving(); // Önceki receive'i durdur
            }
            
            // Receiver başlat
            receiver = new Receiver("127.0.0.1", 0, localPort, latency);
            receiver.start();
            
            isReceivingActive = true;
            System.out.println("✅ Media Engine Receiving başlatıldı - Port: " + localPort);
            return true;
            
        } catch (IOException e) {
            System.err.println("❌ Receiving başlatılamadı: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void stopStreaming() {
        if (sender != null && isStreamingActive) {
            sender.interrupt();
            sender = null;
            isStreamingActive = false;
            System.out.println("🛑 Media Engine Streaming durduruldu");
        }
    }
    
    @Override
    public void stopReceiving() {
        if (receiver != null && isReceivingActive) {
            receiver.interrupt();
            receiver = null;
            isReceivingActive = false;
            System.out.println("🛑 Media Engine Receiving durduruldu");
        }
    }
    
    @Override
    public int getCurrentVideoBitrate() {
        // Sender'dan video bitrate'i al (reflection veya getter ile)
        return sender != null ? 2000 : 0; // Placeholder
    }
    
    @Override
    public int getCurrentAudioBitrate() {
        // Sender'dan audio bitrate'i al
        return sender != null ? 128000 : 0; // Placeholder
    }
    
    @Override
    public double getCurrentRTT() {
        // RTT değerini al
        return sender != null ? 0.0 : -1.0; // Placeholder
    }
    
    @Override
    public boolean isActive() {
        return isStreamingActive || isReceivingActive;
    }
    
    /**
     * Cleanup resources
     */
    public void shutdown() {
        stopStreaming();
        stopReceiving();
        System.out.println("🔧 Media Engine kapatıldı");
    }
}
