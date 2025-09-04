package media_engine.media_engine;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Element;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.io.File;


public class Sender extends Thread {
     
    // Dinamik kamera device tespiti
    private static String device = detectCameraDevice();
    
    // Kamera device'ı otomatik tespit et
    private static String detectCameraDevice() {
        for (int i = 0; i <= 5; i++) {
            File deviceFile = new File("/dev/video" + i);
            if (deviceFile.exists()) {
                System.out.println("🎥 Kamera bulundu: /dev/video" + i);
                return "/dev/video" + i;
            }
        }
        System.out.println("⚠️ Kamera bulunamadı, /dev/video0 kullanılacak");
        return "/dev/video0";  // Fallback
    }
    private static  int WIDTH = 1280;
    private static  int HEIGHT = 720;
    private volatile int fps = 30;                              // ADAPTIVE FPS (network-based)
    private static final int MIN_FPS = 15;                      // Minimum FPS (güvenli alt limit)
    private static final int MAX_FPS = 30;                      // Maximum FPS (quality limit)
    private static int key_int_max = 30;
    
    private volatile int current_bitrate_kbps = 2000;  
    private static final int MIN_BITRATE_KBPS = 1000;
    private static final int MAX_BITRATE_KBPS = 12000;
    private static final int BITRATE_STEP_KBPS = 150;   
    
    private volatile int current_audio_bitrate_bps = 128000;  // 128 kbps default
    private static final int MIN_AUDIO_BITRATE_BPS = 64000;   // 64 kbps minimum
    private static final int MAX_AUDIO_BITRATE_BPS = 256000;  // 256 kbps maximum
    private static final int AUDIO_BITRATE_STEP_BPS = 16000;  // Yumuşak geçişler (32000->16000)
    
    private static final double RTT_THRESHOLD_MS = 40.0;     // Daha düşük threshold (50->40)
    private static final double RTT_GOOD_MS = 15.0;         // Daha agresif artış (20->15)
    private long lastBitrateChange = 0;
    private static final long BITRATE_CHANGE_INTERVAL_MS = 300;  // Ultra responsive (800->300ms) 
    
    // SRT Buffer ve Network Parametreleri (GÜVENLİ başlangıç)
    private volatile int currentSendBuffer = 512000;       // Başlangıç: 512KB (güvenli)
    private volatile int currentRecvBuffer = 512000;       // Başlangıç: 512KB (güvenli)
    private static final int MIN_BUFFER = 128000;          // Minimum: 128KB (güvenli alt limit)
    private static final int MAX_BUFFER = 4000000;         // Maximum: 4MB (burst'lara karşı)
    private static final int SRT_MSS = 1500;               // Standard MTU
    private volatile int currentOverhead = 15;              // Başlangıç: %15 (dengeli), min %5
    
    // Queue Buffer Parametreleri (GERÇEK DÜNYA değerleri)
    private volatile int videoQueueTime = 50000000;   // Başlangıç: 50ms (gaming optimal)
    private volatile int audioQueueTime = 50000000;   // Başlangıç: 50ms (gaming optimal) 
    private static final int MIN_QUEUE_TIME = 20000000;      // 20ms minimum (gaming alt limit)
    private static final int MAX_QUEUE_TIME = 200000000;     // 200ms maximum (broadcast üst limit)
    
    private String targetIP;
    private int targetPort;
    private String LOCAL_HOST;
    private int LOCAL_PORT;          // SRT streaming için
    private int LOCAL_RTT_PORT;      // RTT ölçümü için ayrı port
    private int latency;
    private int ECHO_PORT;
    private String srtpKey;  // SRTP anahtarı

    Pipe pipe;
    Pipe.SourceChannel src;
    private Pipeline pipeline;
    private Element x264encoder;
    private Element aacEncoder;  // AAC encoder referansı
    private media_engine.Receiver receiver;  // Receiver referansı (buffer senkronizasyonu için)

    public Sender(String LOCAL_HOST, int LOCAL_PORT, int LOCAL_RTT_PORT, int ECHO_PORT, String targetIP, int targetPort, int latency, String srtpKey, Pipe pipe, media_engine.Receiver receiver) {
        this.LOCAL_HOST = LOCAL_HOST;
        this.LOCAL_PORT = LOCAL_PORT;
        this.LOCAL_RTT_PORT = LOCAL_RTT_PORT;
        this.ECHO_PORT = ECHO_PORT;
        this.targetIP = targetIP;
        this.targetPort = targetPort;
        this.latency = latency;
        this.srtpKey = srtpKey;
        this.pipe = pipe;
        this.src = pipe.source();
        this.receiver = receiver;  // Receiver referansını sakla
        
        // Dengeli başlangıç - EWMA'ya göre kademeli optimizasyon
        System.out.println("Dengeli Mod: 20ms queue, 512KB buffer → EWMA optimizasyonu aktif");
        System.out.println("RTT Client kullanacağı ayrı port: " + LOCAL_RTT_PORT);
    }

    // Adaptive buffer yönetimi - EWMA tabanlı kademeli optimizasyon
    private void adaptBuffers(double packetLoss, double jitter, double rtt) {
        try {
            // FIX 2: Eşikleri ~2x gevşet ve EWMA RTT bazlı karar (stabil sınıflandırma)
            boolean networkExcellent = (packetLoss < 0.005 && jitter < 6.0 && rtt < 15.0);   // Gevşetildi: 15ms RTT, 6ms jitter
            boolean networkGood = (packetLoss < 0.015 && jitter < 15.0 && rtt < 30.0);       // Gevşetildi: 30ms RTT, 15ms jitter
            boolean networkFair = (packetLoss < 0.03 && jitter < 25.0 && rtt < 60.0);        // Gevşetildi: 60ms RTT, 25ms jitter
            boolean networkBad = (packetLoss > 0.05 || jitter > 35.0 || rtt > 80.0);         // Gevşetildi: 80ms RTT, 35ms jitter
            boolean burstDetected = (packetLoss > 0.1 || jitter > 50.0);                     // Gevşetildi: 50ms jitter
            
            // DEBUG: Network kategorizasyonu (EWMA bazlı, stabil)
            System.out.printf("🔍 STABIL KATEGORI - Loss:%.3f%% Jitter:%.1fms EWMA:%.1fms → ", 
                packetLoss*100, jitter, rtt);
            
            // FIX 2: burstDetected öncelik sırası düzeltildi (exclusive kontrol)
            if(burstDetected) System.out.print("🔥 BURST");
            else if(networkExcellent) System.out.print("⭐ EXCELLENT");
            else if(networkGood) System.out.print("✅ GOOD"); 
            else if(networkFair) System.out.print("⚠️ FAIR");
            else if(networkBad) System.out.print("❌ BAD");
            else System.out.print("🔶 NORMAL");
            System.out.println();
            
            // FIX 2: Önce burst kontrolü (exclusive)
            if (burstDetected) {
                // Burst: Broadcast seviyesine çık (200ms)
                currentSendBuffer = Math.min(MAX_BUFFER, currentSendBuffer * 2);
                currentRecvBuffer = Math.min(MAX_BUFFER, currentRecvBuffer * 2);
                currentOverhead = Math.min(25, currentOverhead + 5);
                
                // ADAPTIVE FPS: Burst'ta minimum FPS (acil bandwidth tasarrufu)
                fps = MIN_FPS;  // Hemen minimum FPS'e düş
                
                videoQueueTime = Math.min(MAX_QUEUE_TIME, videoQueueTime * 2);
                audioQueueTime = Math.min(MAX_QUEUE_TIME, audioQueueTime * 2);
                
                System.out.println("🔴 BURST - Broadcast seviye: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms) FPS: " + fps + " (Emergency)");
                    
            } else if (networkExcellent) {
                // Mükemmel ağ: Queue'ları KONTROLLÜ azalt (gaming seviyesine)
                currentSendBuffer = Math.max(MIN_BUFFER, (int)(currentSendBuffer * 0.98)); // %2 azalt
                currentRecvBuffer = Math.max(MIN_BUFFER, (int)(currentRecvBuffer * 0.98));
                currentOverhead = Math.max(5, currentOverhead - 1);
                
                // ADAPTIVE FPS: Mükemmel ağda maximum FPS
                fps = Math.min(MAX_FPS, fps + 1);  // Kademeli FPS artışı
                
                // Queue'ları gaming seviyesine çek (50ms → 33ms → 20ms)
                videoQueueTime = Math.max(MIN_QUEUE_TIME, (int)(videoQueueTime * 0.95));
                audioQueueTime = Math.max(MIN_QUEUE_TIME, (int)(audioQueueTime * 0.95));
                
                System.out.println("🟢 MÜKEMMEL AĞ - Gaming optimize: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms) FPS: " + fps);
                    
            } else if (networkGood) {
                // İyi ağ: SRT'yi biraz azalt, queue'yu sabit tut
                currentSendBuffer = Math.max(MIN_BUFFER, (int)(currentSendBuffer * 0.99)); // %1 azalt
                currentRecvBuffer = Math.max(MIN_BUFFER, (int)(currentRecvBuffer * 0.99));
                currentOverhead = Math.max(5, currentOverhead);
                
                // ADAPTIVE FPS: İyi ağda stabil FPS
                fps = Math.min(MAX_FPS, Math.max(25, fps));  // 25-30 FPS arası stabil
                
                // Queue'ları çok az azalt (video conferencing seviyesi)
                videoQueueTime = Math.max(MIN_QUEUE_TIME, (int)(videoQueueTime * 0.98));
                audioQueueTime = Math.max(MIN_QUEUE_TIME, (int)(audioQueueTime * 0.98));
                
                System.out.println("🟡 İYİ AĞ - Konferans optimize: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms) FPS: " + fps);
                    
            } else if (networkFair) {
                // Orta ağ: Streaming seviyesinde tut (50ms-100ms)
                // ADAPTIVE FPS: Orta ağda conservative FPS
                fps = Math.max(20, Math.min(25, fps));  // 20-25 FPS arası (bandwidth koruması)
                
                System.out.println("🟠 ORTA AĞ - Streaming sabit: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms) FPS: " + fps);
                    
            } else if (networkBad) {
                // Kötü ağ: Buffer'ları arttır
                currentSendBuffer = Math.min(MAX_BUFFER, (int)(currentSendBuffer * 1.1));
                currentRecvBuffer = Math.min(MAX_BUFFER, (int)(currentRecvBuffer * 1.1));
                currentOverhead = Math.min(25, currentOverhead + 2);
                
                // ADAPTIVE FPS: Kötü ağda minimum FPS (bandwidth tasarrufu)
                fps = Math.max(MIN_FPS, fps - 1);  // Kademeli FPS düşürme
                
                videoQueueTime = Math.min(MAX_QUEUE_TIME, (int)(videoQueueTime * 1.2));
                audioQueueTime = Math.min(MAX_QUEUE_TIME, (int)(audioQueueTime * 1.2));
                
                System.out.println("🔴 KÖTÜ AĞ - Buffer arttır: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms) FPS: " + fps);
            }
            
            // Queue elementlerini güncelle + Receiver'a senkronize et
            updateQueueBuffers();
            
        } catch (Exception e) {
            System.err.println("Buffer adaptasyonu hatası: " + e.getMessage());
        }
    }
    
    private void updateQueueBuffers() {
        try {
            // Receiver'a buffer güncellemelerini gönder (ADAPTIVE SYNC)
            if (receiver != null) {
                receiver.updateBuffers(currentSendBuffer, currentRecvBuffer, videoQueueTime, audioQueueTime);
            }
            
            // Pipeline elementlerine erişim GStreamer query sistemi ile
            System.out.println("🔄 SYNC - Queue buffer'ları güncellendi - Video: " + 
                (videoQueueTime/1000000) + "ms, Audio: " + (audioQueueTime/1000000) + "ms");
        } catch (Exception e) {
            System.err.println("Queue güncelleme hatası: " + e.getMessage());
        }
    }

    private void adjustBitrate(double currentEwmaRtt) {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastBitrateChange < BITRATE_CHANGE_INTERVAL_MS) {
            return;
        }
        
        int oldBitrate = current_bitrate_kbps;
        int oldAudioBitrate = current_audio_bitrate_bps;
        
        if (currentEwmaRtt > RTT_THRESHOLD_MS) {
            double reduction_factor = Math.min(0.9, 1.0 - (currentEwmaRtt - RTT_THRESHOLD_MS) / 200.0);
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, 
                                          (int)(current_bitrate_kbps * reduction_factor));
            current_audio_bitrate_bps = Math.max(MIN_AUDIO_BITRATE_BPS,
                                                current_audio_bitrate_bps - AUDIO_BITRATE_STEP_BPS);
            
            System.out.printf("⬇ NETWORK CONGESTION (EWMA: %.2f ms) - Smooth reduction (%.1f%%):%n", 
                            currentEwmaRtt, (1.0 - reduction_factor) * 100);
            System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                            oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
        } 
        else if (currentEwmaRtt < RTT_GOOD_MS) {
            // EWMA bazlı yumuşak bitrate artırma
            double increase_steps = Math.max(1.0, (RTT_GOOD_MS - currentEwmaRtt) / 5.0);
            current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, 
                                          current_bitrate_kbps + (int)(BITRATE_STEP_KBPS * increase_steps));
            // Audio bitrate artır - yumuşak geçiş
            current_audio_bitrate_bps = Math.min(MAX_AUDIO_BITRATE_BPS,
                                                current_audio_bitrate_bps + AUDIO_BITRATE_STEP_BPS);
            
            System.out.printf("⬆ NETWORK EXCELLENT (EWMA: %.2f ms) - Smooth increase (%.1fx steps):%n", 
                            currentEwmaRtt, increase_steps);
            System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                            oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
        }
        
        if (oldBitrate != current_bitrate_kbps || oldAudioBitrate != current_audio_bitrate_bps) {
            lastBitrateChange = currentTime;
            
            // Video bitrate güncelle
            if (x264encoder != null && oldBitrate != current_bitrate_kbps) {
                try {
                    x264encoder.set("bitrate", current_bitrate_kbps);
                    System.out.printf(" ✓ Video bitrate updated to %d kbps%n", current_bitrate_kbps);
                } catch (Exception e) {
                    System.out.printf(" ✗ Failed to update video bitrate: %s%n", e.getMessage());
                }
            }
            
            // Audio bitrate güncelle (AAC için)
            if (aacEncoder != null && oldAudioBitrate != current_audio_bitrate_bps) {
                try {
                    aacEncoder.set("bitrate", current_audio_bitrate_bps);
                    System.out.printf(" ✓ Audio bitrate updated to %d bps%n", current_audio_bitrate_bps);
                } catch (Exception e) {
                    System.out.printf(" ✗ Failed to update audio bitrate: %s%n", e.getMessage());
                }
            }
        }
    }

	public  void run() {
        System.out.println("Media Engine Sender Started");
        System.out.println("Target: " + targetIP + ":" + targetPort);
        Gst.init("MediaEngine", new String[]{});
    
        String pipelineStr =
            "v4l2src device=" + device + " io-mode=2 do-timestamp=true ! " +
            "image/jpeg,width=" + WIDTH + ",height=" + HEIGHT + ",framerate=" + fps + "/1 ! " +
            "jpegdec ! videoconvert ! videoflip method=horizontal-flip ! " +
            "x264enc name=encoder tune=zerolatency bitrate=" + current_bitrate_kbps + " key-int-max=" + key_int_max + " ! " +
            "h264parse config-interval=1 ! queue max-size-time=" + videoQueueTime + " ! " +     
            "mpegtsmux name=mux alignment=7 ! queue ! " +
            "srtsink uri=\"srt://" + targetIP + ":" + targetPort +
                "?mode=caller&localport=" + LOCAL_PORT +
                "&latency=" + latency + "&rcvlatency=" + latency +
                "&peerlatency=" + latency + "&tlpktdrop=1&oheadbw=" + currentOverhead +
                "&sndbuf=" + currentSendBuffer + "&rcvbuf=" + currentRecvBuffer + 
                "&maxbw=0&inputbw=0&mss=" + SRT_MSS + "\" " +

            "pulsesrc do-timestamp=true ! audioconvert ! audioresample ! " +
            "volume volume=0.8 ! " + 
            "audioconvert ! audioresample ! " +
            "queue max-size-time=" + audioQueueTime + " ! " +
            "avenc_aac name=aacencoder compliance=-2 bitrate=" + current_audio_bitrate_bps + " ! aacparse ! queue max-size-time=" + audioQueueTime + " ! mux.";
                       
        new Thread(()->{
            ByteBuffer buf = ByteBuffer.allocate(32);  // Daha fazla veri için buffer büyüttük
            try{
            while(src.read(buf) > 0){
                buf.flip();
                double rttMs = buf.getDouble();       // 1) Ham RTT (diagnostic)
                double ewmaRtt = buf.getDouble();     // 2) EWMA RTT (decision-making)
                double packetLoss = buf.remaining() >= 8 ? buf.getDouble() : 0.0;  // 3) Packet loss oranı
                double jitter = buf.remaining() >= 8 ? buf.getDouble() : 0.0;      // 4) Jitter
                
                System.out.printf("🔍 METRICS - Raw RTT: %.2f ms | EWMA: %.2f ms | Loss: %.3f%% | Jitter: %.2f ms | Video: %d kbps%n", 
                                rttMs, ewmaRtt, packetLoss*100, jitter, current_bitrate_kbps);
                
                // FIX 2: Kararları EWMA RTT ile ver (stabil ve tutarlı)
                adjustBitrate(ewmaRtt);
                
                // FIX 2: adaptBuffers'a da EWMA RTT ver (stabil sınıflandırma için)
                adaptBuffers(packetLoss, jitter, ewmaRtt);
                
                buf.clear();
            }
        }catch(Exception e ){
            System.err.println("NIO pipe Error: " + e);
        }
        }).start();         
        RTT_Client rtt_analysis = new RTT_Client(targetIP, ECHO_PORT, LOCAL_HOST, LOCAL_RTT_PORT, pipe);
        rtt_analysis.start();
        
        System.out.println("Pipeline: " + pipelineStr);
        pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        
        x264encoder = pipeline.getElementByName("encoder");
        aacEncoder = pipeline.getElementByName("aacencoder");
        
        if (x264encoder != null) {
            System.out.println("x264encoder found - dynamic video bitrate enabled");
        } else {
            System.out.println("x264encoder element not found - dynamic video bitrate disabled");
        }
        
        if (aacEncoder != null) {
            System.out.println("AAC encoder found - dynamic audio bitrate enabled");
        } else {
            System.out.println("AAC encoder element not found - dynamic audio bitrate disabled");
        }
        
        pipeline.play();
        
        try {
            while (pipeline.isPlaying()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            System.out.println("Sender interrupted");
        } finally {
            pipeline.stop();
        }
    }
}
