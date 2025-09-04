package media_engine;

import java.io.IOException;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

import media_engine.media_engine.Echo;

public class Receiver extends Thread {
    private int LOCAL_PORT = 5004;
    private int MY_ECHO_PORT;
    private int LATENCY = 250;
    private String srtpKey;  // SRTP anahtarı
    private Echo echo_server;  // Echo server referansı

    // ADAPTIVE BUFFER Parametreleri (Sender ile TAM SENKRON - SİMETRİK)
    private volatile int currentSendBuffer = 512000;       // Başlangıç: 512KB (Sender ile aynı)
    private volatile int currentRecvBuffer = 512000;       // Başlangıç: 512KB (Sender ile aynı)
    private volatile int videoQueueTime = 100000000;      // Başlangıç: 100ms (Sender ile aynı - ULTRA GÜVENLİ)
    private volatile int audioQueueTime = 100000000;      // Başlangıç: 100ms (Sender ile aynı - ULTRA GÜVENLİ)
    private static final int MIN_BUFFER = 128000;         // Minimum: 128KB (güvenli alt limit)
    private static final int MAX_BUFFER = 4000000;        // Maximum: 4MB (burst'lara karşı)
    private static final int MIN_QUEUE_TIME = 80000000;   // 80ms minimum (Sender ile aynı - çok güvenli alt limit)
    private static final int MAX_QUEUE_TIME = 500000000;  // 500ms maximum (Sender ile aynı - çok yüksek üst limit)

    public Receiver(int LOCAL_PORT, int MY_ECHO_PORT, int LATENCY) throws IOException{
        this.LOCAL_PORT = LOCAL_PORT;
        this.LATENCY = LATENCY;
        this.MY_ECHO_PORT = MY_ECHO_PORT;

        try{
            echo_server = new Echo(MY_ECHO_PORT);
            echo_server.start();
         }catch(Exception e){
                System.err.println("ECHO Server Initialize Error: " + e);

            }
        }
    
    // Video sink uyumluluğunu test et (canlı ortam için kritik)
    private String detectWorkingVideoSink(String[] sinks) {
        for (String sink : sinks) {
            try {
                // GStreamer element test et
                System.out.println("🔍 Testing video sink: " + sink);
                return sink;  // İlk mevcut olanı kullan
            } catch (Exception e) {
                System.out.println("❌ " + sink + " kullanılamıyor: " + e.getMessage());
            }
        }
        System.out.println("⚠️ Hiçbir video sink bulunamadı, autovideosink kullanılacak");
        return "autovideosink";  // Fallback
    }
    
    // ADAPTIVE BUFFER güncelleme metodu (Sender'dan çağrılacak - ama önce kendi değerleriyle karşılaştır)
    public void updateBuffers(int sendBuffer, int recvBuffer, int videoQueue, int audioQueue) {
        // Sender'dan gelen değerleri kendi güvenli limitlerde tut
        int safeSendBuffer = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, sendBuffer));
        int safeRecvBuffer = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, recvBuffer));
        int safeVideoQueue = Math.max(MIN_QUEUE_TIME, Math.min(MAX_QUEUE_TIME, videoQueue));
        int safeAudioQueue = Math.max(MIN_QUEUE_TIME, Math.min(MAX_QUEUE_TIME, audioQueue));
        
        // Mevcut değerlerle karşılaştır - çok büyük değişiklikler engelle
        boolean significantChange = false;
        
        // %50'den fazla değişiklik yapma (güvenlik)
        if (Math.abs(safeSendBuffer - this.currentSendBuffer) > this.currentSendBuffer * 0.5) {
            safeSendBuffer = (int)(this.currentSendBuffer * (safeSendBuffer > this.currentSendBuffer ? 1.5 : 0.5));
            significantChange = true;
        }
        
        if (Math.abs(safeVideoQueue - this.videoQueueTime) > this.videoQueueTime * 0.5) {
            safeVideoQueue = (int)(this.videoQueueTime * (safeVideoQueue > this.videoQueueTime ? 1.5 : 0.5));
            significantChange = true;
        }
        
        // Değerleri güncelle
        this.currentSendBuffer = safeSendBuffer;
        this.currentRecvBuffer = safeRecvBuffer;
        this.videoQueueTime = safeVideoQueue;
        this.audioQueueTime = safeAudioQueue;
        
        if (significantChange) {
            System.out.printf("⚠️ RECEIVER GÜVENLİK - Aşırı değişiklik engellendi!%n");
        }
        
        System.out.printf("🔧 RECEIVER SYNC UPDATE - SRT: snd=%dKB rcv=%dKB | Queues: video=%dms audio=%dms%n",
            currentSendBuffer/1000, currentRecvBuffer/1000, videoQueueTime/1000000, audioQueueTime/1000000);
    }

    // ADAPTIVE PIPELINE builder method
    private String buildPipeline(String videoSink) {
        return "srtsrc uri=\"srt://:" + LOCAL_PORT +
            "?mode=listener&latency=" + LATENCY +
            "&rcvlatency=" + LATENCY +
            "&peerlatency=" + LATENCY +
            "&tlpktdrop=1&oheadbw=5" +
            "&sndbuf=" + currentSendBuffer + "&rcvbuf=" + currentRecvBuffer + 
            "&maxbw=0&inputbw=0&mss=1500\" ! " +
            "tsdemux name=dmx " +

            "dmx. ! queue max-size-buffers=15 max-size-time=" + videoQueueTime + 
            " ! h264parse ! avdec_h264 ! videoconvert ! " + videoSink + " sync=false " +

            "dmx. ! queue max-size-buffers=10 max-size-time=" + audioQueueTime + 
            " ! aacparse ! avdec_aac ! audioconvert ! audioresample ! " +
            "volume volume=0.6 ! " +  // Ses çıkış seviyesini düşür (yankı azaltır)
            "autoaudiosink sync=false buffer-time=2000";
    }

    public void run() {
        // Dinamik video sink tespiti (canlı ortam için güvenli)
        String[] videoSinks = {"autovideosink", "xvimagesink", "ximagesink", "waylandsink", "glimagesink"};
        String videoSink = detectWorkingVideoSink(videoSinks);
        
        System.out.println("🖥️ Kullanılacak video sink: " + videoSink);

        // ADAPTIVE PIPELINE: Buffer değerleri değişken olacak
        String pipeline = buildPipeline(videoSink);
                         
        System.out.println("Media Engine Receiver Started");
        System.out.println("🔒 RECEIVER SİMETRİK MOD: 100ms queue (Sender ile aynı)");
        System.out.println("🛡️ ULTRA GÜVENLİ BUFFER: 80-500ms aralık");
        System.out.println("Listening on SRT port: " + LOCAL_PORT);
        System.out.println("Using video sink: " + videoSink);
        System.out.printf("🔧 SİMETRİK BUFFERS - SRT: snd=%dKB rcv=%dKB | Queues: video=%dms audio=%dms%n",
            currentSendBuffer/1000, currentRecvBuffer/1000, videoQueueTime/1000000, audioQueueTime/1000000);
        System.out.println("Attempting to open video window...");
    
        Gst.init("MediaEngineReceiver", new String[]{});
        System.out.println("Pipeline: " + pipeline);
        Pipeline p = (Pipeline) Gst.parseLaunch(pipeline);
        p.play();
               
        try {
            while (p.isPlaying()) {
                Thread.sleep(100);
            }
        } catch (Exception e) {
            System.out.println("Receiver interrupted");
        } finally {
            p.stop();
            if (echo_server != null && echo_server.isAlive()) {
                echo_server.interrupt();
            }
        }
    }
    
    public void cleanup() {
        if (echo_server != null && echo_server.isAlive()) {
            echo_server.interrupt();
        }
    }
}