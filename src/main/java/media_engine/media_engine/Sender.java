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
    
    // SRT Buffer ve Network Parametreleri (SABİT DEĞERLER - DEĞİŞMEYECEK)
    private static final int FIXED_SEND_BUFFER = 512000;       // SABİT: 512KB - asla değişmez
    private static final int FIXED_RECV_BUFFER = 512000;       // SABİT: 512KB - asla değişmez
    private static final int SRT_MSS = 1500;                   // Standard MTU
    private static final int FIXED_OVERHEAD = 15;              // SABİT: %15 - asla değişmez
    
    // Queue Buffer Parametreleri (SABİT DEĞERLER - DEĞİŞMEYECEK)
    private static final int FIXED_VIDEO_QUEUE_TIME = 100000000;  // SABİT: 100ms - asla değişmez
    private static final int FIXED_AUDIO_QUEUE_TIME = 100000000;  // SABİT: 100ms - asla değişmez
    
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
        
        // ULTRA GÜVENLİ ve SABİT başlangıç - buffer değişimi YOK
        System.out.println("🔒 SABİT BUFFER MOD: 100ms queue ve 512KB buffer - DEĞİŞMEZ");
        System.out.println("� SADECE BİTRATE ADAPTIVE: Diğer tüm parametreler sabit kalacak");
        System.out.println("RTT Client kullanacağı ayrı port: " + LOCAL_RTT_PORT);
    }

    // Buffer adaptasyonu KALDIRILDI - Artık sadece sabit değerler kullanılacak
    // Sistem ilk açıldığında ayarlanan değerler hiç değişmeyecek
    // Bu sayede donma problemi ortadan kalkacak

    private void adjustBitrate(double currentEwmaRtt) {
        long currentTime = System.currentTimeMillis();
        
        // Daha uzun interval - en az 3 saniye bekle (5s->3s daha responsive ama stabil)
        if (currentTime - lastBitrateChange < 3000) {
            return;
        }
        
        int oldBitrate = current_bitrate_kbps;
        int oldAudioBitrate = current_audio_bitrate_bps;
        
        // ÇOK BASİT bitrate yönetimi - sadece çok kötü veya çok iyi durumlarda müdahale
        
        // RTT gerçekten kötüyse azalt (80ms üstü)
        if (currentEwmaRtt > 80.0) {
            // Minimal azalma - sadece %5
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, 
                                          (int)(current_bitrate_kbps * 0.95));
            
            // Audio için daha zorlaştır (100ms üstü)
            if (currentEwmaRtt > 100.0) {
                current_audio_bitrate_bps = Math.max(MIN_AUDIO_BITRATE_BPS,
                                                    current_audio_bitrate_bps - 8000);
            }
            
            System.out.printf("⬇ YÜKSEK RTT (%.2f ms) - Bitrate azaltıldı:%n", currentEwmaRtt);
            System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                            oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
                            
        } 
        // Bitrate artışı için daha basit koşul (20ms altı)
        else if (currentEwmaRtt < 20.0) {
            excellentNetworkCounter++;
            
            // 10 iyi ölçüm yeterli (25->10 daha responsive)
            if (excellentNetworkCounter >= 10) {
                // Minimal artış - sadece %2
                current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, 
                                              (int)(current_bitrate_kbps * 1.02));
                
                // Audio için daha zorlaştır (15ms altı ve 15 iyi ölçüm)
                if (excellentNetworkCounter >= 15 && currentEwmaRtt < 15.0) {
                    current_audio_bitrate_bps = Math.min(MAX_AUDIO_BITRATE_BPS,
                                                        current_audio_bitrate_bps + 4000);
                }
                
                excellentNetworkCounter = 0; // Reset sayacı
                
                System.out.printf("⬆ DÜŞÜK RTT (%.2f ms) - Bitrate artırıldı:%n", currentEwmaRtt);
                System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                                oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
            } else {
                System.out.printf("⭐ İYİ RTT (%.2f ms) - Sayaç: %d/10 (Artış için bekleniyor)%n", 
                                currentEwmaRtt, excellentNetworkCounter);
            }
            
        } else {
            // RTT normal - sayaçları sıfırla
            excellentNetworkCounter = 0;
            System.out.printf("🔒 NORMAL RTT (%.2f ms) - Bitrate sabit: %d kbps | Audio: %d bps%n", 
                            currentEwmaRtt, current_bitrate_kbps, current_audio_bitrate_bps);
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
            "h264parse config-interval=1 ! queue max-size-time=" + FIXED_VIDEO_QUEUE_TIME + " ! " +     
            "mpegtsmux name=mux alignment=7 ! queue ! " +
            "srtsink uri=\"srt://" + targetIP + ":" + targetPort +
                "?mode=caller&localport=" + LOCAL_PORT +
                "&latency=" + latency + "&rcvlatency=" + latency +
                "&peerlatency=" + latency + "&tlpktdrop=1&oheadbw=" + FIXED_OVERHEAD +
                "&sndbuf=" + FIXED_SEND_BUFFER + "&rcvbuf=" + FIXED_RECV_BUFFER + 
                "&maxbw=0&inputbw=0&mss=" + SRT_MSS + "\" " +

            "pulsesrc do-timestamp=true ! audioconvert ! audioresample ! " +
            "volume volume=0.8 ! " + 
            "audioconvert ! audioresample ! " +
            "queue max-size-time=" + FIXED_AUDIO_QUEUE_TIME + " ! " +
            "avenc_aac name=aacencoder compliance=-2 bitrate=" + current_audio_bitrate_bps + " ! aacparse ! queue max-size-time=" + FIXED_AUDIO_QUEUE_TIME + " ! mux.";
                       
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
                
                // SADECE BITRATE ADAPTİF - buffer'lar sabit kalacak
                adjustBitrate(ewmaRtt);
                
                // Buffer adaptasyonu KALDIRILDI - donma problemini önlemek için
                
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
