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
    private static final int MAX_BITRATE_KBPS = 6000;    // 12K->6K (daha mantıklı)
    private static final int BITRATE_STEP_KBPS = 150;   
    
    private volatile int current_audio_bitrate_bps = 128000;  // 128 kbps default
    private static final int MIN_AUDIO_BITRATE_BPS = 64000;   // 64 kbps minimum
    private static final int MAX_AUDIO_BITRATE_BPS = 192000;  // 256K->192K (daha mantıklı)
    private static final int AUDIO_BITRATE_STEP_BPS = 16000;  // Yumuşak geçişler (32000->16000)
    
    private static final double RTT_THRESHOLD_MS = 60.0;     // Çok daha yüksek threshold (40->60)
    private static final double RTT_GOOD_MS = 25.0;         // Daha konservatif artış (15->25)
    private long lastBitrateChange = 0;
    private static final long BITRATE_CHANGE_INTERVAL_MS = 5000;  // Bitrate için de 5 saniye (2000->5000ms)
    
    // JITTER VE BUFFER STABİLİTE DEĞİŞKENLERİ
    private long lastBufferChange = 0;
    private static final long BUFFER_CHANGE_INTERVAL_MS = 8000;   // Buffer için 8 saniye (5000->8000ms)
    private int stableNetworkCounter = 0;                         // Stabil ağ sayacı
    private static final int STABLE_NETWORK_REQUIRED = 15;       // 15 ölçüm stabil olmalı (10->15)
    private int excellentNetworkCounter = 0;                     // Mükemmel ağ sayacı
    private static final int EXCELLENT_NETWORK_REQUIRED = 25;    // Bitrate artışı için 25 mükemmel ölçüm 
    
    // SRT Buffer ve Network Parametreleri (GÜVENLİ başlangıç)
    private volatile int currentSendBuffer = 512000;       // Başlangıç: 512KB (güvenli)
    private volatile int currentRecvBuffer = 512000;       // Başlangıç: 512KB (güvenli)
    private static final int MIN_BUFFER = 128000;          // Minimum: 128KB (güvenli alt limit)
    private static final int MAX_BUFFER = 4000000;         // Maximum: 4MB (burst'lara karşı)
    private static final int SRT_MSS = 1500;               // Standard MTU
    private volatile int currentOverhead = 15;              // Başlangıç: %15 (dengeli), min %5
    
    // Queue Buffer Parametreleri (ÇOK GÜVENLİ değerler)
    private volatile int videoQueueTime = 100000000;  // Başlangıç: 100ms (çok güvenli)
    private volatile int audioQueueTime = 100000000;  // Başlangıç: 100ms (çok güvenli) 
    private static final int MIN_QUEUE_TIME = 80000000;      // 80ms minimum (çok güvenli alt limit)
    private static final int MAX_QUEUE_TIME = 500000000;     // 500ms maximum (çok yüksek üst limit)
    
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
        
        // ULTRA GÜVENLİ başlangıç - maksimum stabilite
        System.out.println("🔒 ULTRA KONSERVATIF MOD: 100ms queue, 512KB buffer");
        System.out.println("🐌 ÇOK YAVAŞ ADAPTASYON: 5s bitrate, 8s buffer interval");
        System.out.println("🛡️ ULTRA GÜVENLİK: Bitrate max 6K, artış için 25 mükemmel ölçüm");
        System.out.println("📊 KATI KOŞULLAR: RTT<15ms ve 25 art arda ölçüm gerekli");
        System.out.println("RTT Client kullanacağı ayrı port: " + LOCAL_RTT_PORT);
    }

    // Adaptive buffer yönetimi - ÇOK KONSERVATIF ve GÜVENLİ yaklaşım
    private void adaptBuffers(double packetLoss, double jitter, double rtt) {
        try {
            long currentTime = System.currentTimeMillis();
            
            // Buffer değişimi için zaman kontrolü - en az 8 saniye bekle
            if (currentTime - lastBufferChange < BUFFER_CHANGE_INTERVAL_MS) {
                return; // Çok erken, değişiklik yapma
            }
            
            // ULTRA KONSERVATIF eşikler - gerçekten kötü durumda değişiklik yap
            boolean networkExcellent = (packetLoss < 0.001 && jitter < 3.0 && rtt < 20.0);   // Çok katı: %0.1 loss, 3ms jitter
            boolean networkGood = (packetLoss < 0.005 && jitter < 8.0 && rtt < 35.0);       // Katı: %0.5 loss, 8ms jitter
            boolean networkFair = (packetLoss < 0.02 && jitter < 20.0 && rtt < 70.0);       // Normal: %2 loss, 20ms jitter
            boolean networkBad = (packetLoss > 0.08 || jitter > 80.0 || rtt > 150.0);       // Gerçekten kötü: %8 loss, 80ms jitter
            boolean burstDetected = (packetLoss > 0.15 || jitter > 150.0);                  // Çok ciddi: %15 loss, 150ms jitter
            
            // Stabil ağ sayacı - art arda iyi ölçümler gerekli
            if (networkGood || networkExcellent) {
                stableNetworkCounter++;
            } else {
                stableNetworkCounter = 0; // Reset sayacı
            }
            
            // DEBUG: Network kategorizasyonu (çok konservatif)
            System.out.printf("� KONSERVATIF KATEGORI - Loss:%.4f%% Jitter:%.1fms RTT:%.1fms Stable:%d → ", 
                packetLoss*100, jitter, rtt, stableNetworkCounter);
            
            if(burstDetected) System.out.print("🔥 CİDDİ BURST");
            else if(networkBad) System.out.print("❌ GERÇEKTEN KÖTÜ");
            else if(networkFair) System.out.print("🟠 NORMAL");
            else if(networkGood) System.out.print("✅ İYİ (Sayaç:" + stableNetworkCounter + ")"); 
            else if(networkExcellent) System.out.print("⭐ MÜKEMMEL (Sayaç:" + stableNetworkCounter + ")");
            else System.out.print("🔶 STABİL");
            System.out.println();
            
            // ÇOK KÜÇÜK değişiklikler - sadece gerçekten gerekli durumlarda
            if (burstDetected) {
                // Sadece ciddi burst'ta buffer arttır - ama az
                currentSendBuffer = Math.min(MAX_BUFFER, (int)(currentSendBuffer * 1.05)); // Sadece %5 arttır
                currentRecvBuffer = Math.min(MAX_BUFFER, (int)(currentRecvBuffer * 1.05));
                currentOverhead = Math.min(25, currentOverhead + 1); // Sadece 1 arttır
                
                videoQueueTime = Math.min(MAX_QUEUE_TIME, (int)(videoQueueTime * 1.1)); // Sadece %10 arttır
                audioQueueTime = Math.min(MAX_QUEUE_TIME, (int)(audioQueueTime * 1.1));
                
                lastBufferChange = currentTime; // Zaman güncelle
                
                System.out.println("🔴 CİDDİ DURUM - MİNİMAL buffer artış: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms)");
                    
            } else if (networkBad) {
                // Gerçekten kötü ağda küçük artış
                currentSendBuffer = Math.min(MAX_BUFFER, (int)(currentSendBuffer * 1.02)); // Sadece %2 arttır
                currentRecvBuffer = Math.min(MAX_BUFFER, (int)(currentRecvBuffer * 1.02));
                
                videoQueueTime = Math.min(MAX_QUEUE_TIME, (int)(videoQueueTime * 1.05)); // Sadece %5 arttır
                audioQueueTime = Math.min(MAX_QUEUE_TIME, (int)(audioQueueTime * 1.05));
                
                lastBufferChange = currentTime;
                
                System.out.println("� KÖTÜ AĞ - Küçük buffer artış: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms)");
                    
            } else if (networkExcellent && stableNetworkCounter >= STABLE_NETWORK_REQUIRED) {
                // Sadece uzun süre mükemmel ağda ve çok az azalt
                currentSendBuffer = Math.max(MIN_BUFFER, (int)(currentSendBuffer * 0.99)); // Sadece %1 azalt
                currentRecvBuffer = Math.max(MIN_BUFFER, (int)(currentRecvBuffer * 0.99));
                
                videoQueueTime = Math.max(MIN_QUEUE_TIME, (int)(videoQueueTime * 0.98)); // Sadece %2 azalt
                audioQueueTime = Math.max(MIN_QUEUE_TIME, (int)(audioQueueTime * 0.98));
                
                lastBufferChange = currentTime;
                stableNetworkCounter = 0; // Reset sayacı
                
                System.out.println("� UZUN SÜRE MÜKEMMEL - MİNİMAL azalma: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms)");
            } else {
                // Hiçbir değişiklik yapma - mevcut değerleri koru
                System.out.println("� DEĞİŞİKLİK YOK - Mevcut değerler korunuyor: " + 
                    (currentSendBuffer/1000) + "KB (Video: " + (videoQueueTime/1000000) + "ms)");
            }
            
            // Queue elementlerini güncelle
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
        
        // ÇOK UZUN interval - en az 5 saniye bekle
        if (currentTime - lastBitrateChange < BITRATE_CHANGE_INTERVAL_MS) {
            return;
        }
        
        int oldBitrate = current_bitrate_kbps;
        int oldAudioBitrate = current_audio_bitrate_bps;
        
        // ULTRA KATI bitrate yönetimi
        
        // RTT gerçekten kötüyse azalt - ama çok az
        if (currentEwmaRtt > RTT_THRESHOLD_MS * 1.5) { // 90ms'den kötüyse
            // Çok minimal azalma - sadece %1-2 arası
            double reduction_factor = Math.max(0.98, 1.0 - (currentEwmaRtt - RTT_THRESHOLD_MS * 1.5) / 2000.0);
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, 
                                          (int)(current_bitrate_kbps * reduction_factor));
            
            // Audio için sadece çok ciddi durumda azalt (120ms üstü)
            if (currentEwmaRtt > RTT_THRESHOLD_MS * 2.0) {
                current_audio_bitrate_bps = Math.max(MIN_AUDIO_BITRATE_BPS,
                                                    current_audio_bitrate_bps - (AUDIO_BITRATE_STEP_BPS / 4));
            }
            
            System.out.printf("⬇ CİDDİ RTT DURUMU (%.2f ms) - Minimal azalma (%.2f%%):%n", 
                            currentEwmaRtt, (1.0 - reduction_factor) * 100);
            System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                            oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
                            
        } 
        // Bitrate artışı için ULTRA KATI koşullar
        else if (currentEwmaRtt < RTT_GOOD_MS * 0.6) { // 15ms'den iyi olmalı
            excellentNetworkCounter++;
            
            // 25 art arda mükemmel ölçüm gerekli
            if (excellentNetworkCounter >= EXCELLENT_NETWORK_REQUIRED) {
                // Çok minimal artış - sadece %0.5-1 arası
                double increase_factor = Math.min(1.005, 1.0 + (RTT_GOOD_MS * 0.6 - currentEwmaRtt) / 5000.0);
                current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, 
                                              (int)(current_bitrate_kbps * increase_factor));
                
                // Audio için daha da zorlaştır - 30 mükemmel ölçüm gerekli
                if (excellentNetworkCounter >= (EXCELLENT_NETWORK_REQUIRED + 5) && 
                    currentEwmaRtt < RTT_GOOD_MS * 0.4) { // 10ms'den iyi
                    current_audio_bitrate_bps = Math.min(MAX_AUDIO_BITRATE_BPS,
                                                        current_audio_bitrate_bps + (AUDIO_BITRATE_STEP_BPS / 8));
                }
                
                excellentNetworkCounter = 0; // Reset sayacı
                
                System.out.printf("⬆ 25 MÜKEMMEL ÖLÇÜM SONRASI (RTT: %.2f ms) - Micro artış (%.3f%%):%n", 
                                currentEwmaRtt, (increase_factor - 1.0) * 100);
                System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                                oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
            } else {
                System.out.printf("⭐ MÜKEMMEL RTT (%.2f ms) - Sayaç: %d/%d (Artış için bekleniyor)%n", 
                                currentEwmaRtt, excellentNetworkCounter, EXCELLENT_NETWORK_REQUIRED);
            }
            
        } else {
            // RTT normal/kötü - sayaçları sıfırla
            excellentNetworkCounter = 0;
            
            if (currentEwmaRtt < RTT_GOOD_MS) {
                System.out.printf("✅ İYİ RTT (%.2f ms) - Bitrate sabit: %d kbps (Artış için %.2f ms gerekli)%n", 
                                currentEwmaRtt, current_bitrate_kbps, RTT_GOOD_MS * 0.6);
            } else {
                System.out.printf("🔒 NORMAL RTT (%.2f ms) - Bitrate sabit: %d kbps | Audio: %d bps%n", 
                                currentEwmaRtt, current_bitrate_kbps, current_audio_bitrate_bps);
            }
            return;
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
