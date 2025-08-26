package media_engine.api;

/**
 * Media Engine API - Diğer projelerde kullanılmak için
 * 
 * Bu interface sayesinde Media Engine'i diğer projelerinizde kolayca kullanabilirsiniz.
 */
public interface MediaEngineAPI {
    
    /**
     * Video + Audio streaming başlatır
     * 
     * @param localHost Local IP adresi
     * @param localPort Local port
     * @param targetIP Hedef IP adresi  
     * @param targetPort Hedef port
     * @param latency Latency (ms)
     * @return StreamSession başarıyla başlatıldıysa true
     */
    boolean startStreaming(String localHost, int localPort, String targetIP, int targetPort, int latency);
    
    /**
     * Video + Audio alma başlatır
     * 
     * @param localPort Dinlenecek port
     * @param latency Latency (ms)
     * @return ReceiveSession başarıyla başlatıldıysa true
     */
    boolean startReceiving(int localPort, int latency);
    
    /**
     * Streaming'i durdur
     */
    void stopStreaming();
    
    /**
     * Receiving'i durdur
     */
    void stopReceiving();
    
    /**
     * Mevcut bitrate'i al
     * @return Video bitrate (kbps)
     */
    int getCurrentVideoBitrate();
    
    /**
     * Mevcut audio bitrate'i al
     * @return Audio bitrate (bps)
     */
    int getCurrentAudioBitrate();
    
    /**
     * Son RTT değerini al
     * @return RTT (ms)
     */
    double getCurrentRTT();
    
    /**
     * Engine durumunu kontrol et
     * @return Aktif ise true
     */
    boolean isActive();
}
