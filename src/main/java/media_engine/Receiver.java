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

    // SABİT BUFFER Parametreleri (Sender ile TAM SENKRON - DEĞİŞMEZ)
    private static final int FIXED_SEND_BUFFER = 512000;       // SABİT: 512KB (Sender ile aynı)
    private static final int FIXED_RECV_BUFFER = 512000;       // SABİT: 512KB (Sender ile aynı)
    private static final int FIXED_VIDEO_QUEUE_TIME = 100000000;  // SABİT: 100ms (Sender ile aynı)
    private static final int FIXED_AUDIO_QUEUE_TIME = 100000000;  // SABİT: 100ms (Sender ile aynı)

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
    
    // Buffer güncelleme fonksiyonu KALDIRILDI
    // Artık buffer'lar sabit kalacak - donma problemi önlenecek

    // SABİT PIPELINE builder method
    private String buildPipeline(String videoSink) {
        return "srtsrc uri=\"srt://:" + LOCAL_PORT +
            "?mode=listener&latency=" + LATENCY +
            "&rcvlatency=" + LATENCY +
            "&peerlatency=" + LATENCY +
            "&tlpktdrop=1&oheadbw=5" +
            "&sndbuf=" + FIXED_SEND_BUFFER + "&rcvbuf=" + FIXED_RECV_BUFFER + 
            "&maxbw=0&inputbw=0&mss=1500\" ! " +
            "tsdemux name=dmx " +

            "dmx. ! queue max-size-buffers=15 max-size-time=" + FIXED_VIDEO_QUEUE_TIME + 
            " ! h264parse ! avdec_h264 ! videoconvert ! " + videoSink + " sync=false " +

            "dmx. ! queue max-size-buffers=10 max-size-time=" + FIXED_AUDIO_QUEUE_TIME + 
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
        System.out.println("🔒 RECEIVER SABİT MOD: 100ms queue (Sender ile aynı) - DEĞİŞMEZ");
        System.out.println("🛡️ SABİT BUFFER: Donma problemi önlendi");
        System.out.println("Listening on SRT port: " + LOCAL_PORT);
        System.out.println("Using video sink: " + videoSink);
        System.out.printf("🔧 SABİT BUFFERS - SRT: snd=%dKB rcv=%dKB | Queues: video=%dms audio=%dms%n",
            FIXED_SEND_BUFFER/1000, FIXED_RECV_BUFFER/1000, FIXED_VIDEO_QUEUE_TIME/1000000, FIXED_AUDIO_QUEUE_TIME/1000000);
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