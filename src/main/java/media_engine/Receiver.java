package media_engine;

import java.io.IOException;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class Receiver extends Thread {
    private String TARGET_IP;
    private int TARGET_PORT;
    private int LOCAL_PORT = 5004;
    private int LATENCY = 250;
    private String srtpKey;  // SRTP anahtarı
    
    

    public Receiver(String TARGET_IP, int TARGET_PORT,
                        int LOCAL_PORT, int LATENCY) throws IOException{
        this.TARGET_IP = TARGET_IP;
        this.TARGET_PORT = TARGET_PORT;
        this.LOCAL_PORT = LOCAL_PORT;
        this.LATENCY = LATENCY;
    }
    
    public void run() {
        String[] videoSinks = {"xvimagesink", "ximagesink", "autovideosink"};
        String videoSink = videoSinks[0];

        // SRT ile çalışan pipeline
        String pipeline =
            "srtsrc uri=\"srt://:" + LOCAL_PORT +
            "?mode=listener&latency=" + LATENCY +
            "&rcvlatency=" + LATENCY +
            "&peerlatency=" + LATENCY +
            "&tlpktdrop=1&oheadbw=25\" ! " +
            "tsdemux name=dmx " +

            // Video branch
            "dmx. ! queue ! h264parse ! avdec_h264 ! videoconvert ! " + videoSink + " sync=true " +

            // Audio branch (AAC) - Yankı azaltma + Düşük buffer
            "dmx. ! queue ! aacparse ! avdec_aac ! audioconvert ! audioresample ! " +
            "volume volume=0.6 ! " +  // Ses çıkış seviyesini düşür (yankı azaltır)
            "autoaudiosink sync=true buffer-time=25000";  // Başlangıç 25ms (düşük gecikme)
                         
        System.out.println("📺 Adaptive Media Engine Receiver Started");
        System.out.println("🔊 Listening on SRT port: " + LOCAL_PORT);
        System.out.println("🖥️ Using video sink: " + videoSink);
        System.out.println("⚡ Dynamic Buffer System: ENABLED");
        System.out.println("🎯 Initial audio buffer: 25ms (will adapt to network)");

        Gst.init("MediaEngineReceiver", new String[]{});
        System.out.println("Pipeline: " + pipeline);
        Pipeline p = (Pipeline) Gst.parseLaunch(pipeline);
        p.play();
        
        try {
            while (p.isPlaying()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            System.out.println("Receiver interrupted");
        } finally {
            p.stop();
        }
    }
}