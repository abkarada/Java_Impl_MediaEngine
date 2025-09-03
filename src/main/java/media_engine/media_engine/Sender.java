package media_engine.media_engine;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Element;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;


public class Sender extends Thread {
     
    private static final String device = "/dev/video0";
    private static  int WIDTH = 1280;
    private static  int HEIGHT = 720;
    private static  int fps = 30;
    private static int key_int_max = 30;
    
    private volatile int current_bitrate_kbps = 2000;  
    private static final int MIN_BITRATE_KBPS = 1000;
    private static final int MAX_BITRATE_KBPS = 15000;
    private static final int BITRATE_STEP_KBPS = 500;   
    
    // AAC Audio bitrate settings
    private volatile int current_audio_bitrate_bps = 128000;  // 128 kbps default
    private static final int MIN_AUDIO_BITRATE_BPS = 64000;   // 64 kbps minimum
    private static final int MAX_AUDIO_BITRATE_BPS = 256000;  // 256 kbps maximum
    private static final int AUDIO_BITRATE_STEP_BPS = 32000;  // 32 kbps step
    
    private static final double RTT_THRESHOLD_MS = 40.0;     // Daha düşük threshold (50->40)
    private static final double RTT_GOOD_MS = 15.0;         // Daha agresif artış (20->15)
    private long lastBitrateChange = 0;
    private static final long BITRATE_CHANGE_INTERVAL_MS = 1500;  // Daha hızlı ayarlama (3000->1500) 
    
    private String targetIP;
    private int targetPort;
    private String LOCAL_HOST;
    private int LOCAL_PORT;
    private int latency;
    private String srtpKey;  // SRTP anahtarı

    Pipe pipe;
    Pipe.SourceChannel src;
    private Pipeline pipeline;
    private Element x264encoder;
    private Element aacEncoder;  // AAC encoder referansı

    public Sender(String LOCAL_HOST, int LOCAL_PORT,String targetIP, int targetPort, int latency, Pipe pipe) {
        this.LOCAL_HOST = LOCAL_HOST;
        this.LOCAL_PORT = LOCAL_PORT;
        this.targetIP = targetIP;
        this.targetPort = targetPort;
        this.latency = latency;
        this.pipe = pipe;
        this.src = pipe.source();
    }

    private void adjustBitrate(double currentEwmaRtt) {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastBitrateChange < BITRATE_CHANGE_INTERVAL_MS) {
            return;
        }
        
        int oldBitrate = current_bitrate_kbps;
        int oldAudioBitrate = current_audio_bitrate_bps;
        
        if (currentEwmaRtt > RTT_THRESHOLD_MS) {
            // Daha agresif bitrate düşürme
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, 
                                          (int)(current_bitrate_kbps * 0.7)); // 0.8 -> 0.7 daha agresif
            // Audio bitrate düşür
            current_audio_bitrate_bps = Math.max(MIN_AUDIO_BITRATE_BPS,
                                                current_audio_bitrate_bps - (AUDIO_BITRATE_STEP_BPS * 2)); // 2x daha hızlı
            
            System.out.printf("⬇ NETWORK CONGESTION (EWMA: %.2f ms) - Reducing bitrates:%n", currentEwmaRtt);
            System.out.printf("   Video: %d → %d kbps | Audio: %d → %d bps%n", 
                            oldBitrate, current_bitrate_kbps, oldAudioBitrate, current_audio_bitrate_bps);
        } 
        else if (currentEwmaRtt < RTT_GOOD_MS) {
            // Daha hızlı bitrate artırma
            current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, 
                                          current_bitrate_kbps + (BITRATE_STEP_KBPS * 2)); // 2x daha hızlı artış
            // Audio bitrate artır
            current_audio_bitrate_bps = Math.min(MAX_AUDIO_BITRATE_BPS,
                                                current_audio_bitrate_bps + (AUDIO_BITRATE_STEP_BPS * 2)); // 2x daha hızlı
            
            System.out.printf("⬆ NETWORK STABLE (EWMA: %.2f ms) - Increasing bitrates:%n", currentEwmaRtt);
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
            "jpegdec ! videoconvert ! " +
            "x264enc name=encoder tune=zerolatency bitrate=" + current_bitrate_kbps + " key-int-max=" + key_int_max + " ! " +
            "h264parse config-interval=1 ! queue max-size-time=20000000 ! " +     
            "mpegtsmux name=mux alignment=7 ! queue ! " +
            "srtsink uri=\"srt://" + targetIP + ":" + targetPort +
                "?mode=caller&localport=" + LOCAL_PORT +
                "&latency=" + latency + "&rcvlatency=" + latency +
                "&peerlatency=" + latency + "&tlpktdrop=1&oheadbw=25\" " +

            "pulsesrc do-timestamp=true ! audioconvert ! audioresample ! " +
            "volume volume=0.8 ! " + 
            "audioconvert ! audioresample ! " +
            "queue max-size-time=20000000 ! " +
            "avenc_aac name=aacencoder compliance=-2 bitrate=" + current_audio_bitrate_bps + " ! aacparse ! queue max-size-time=20000000 ! mux.";
                       
        new Thread(()->{
            ByteBuffer buf = ByteBuffer.allocate(16); 
            try{
            while(src.read(buf) > 0){
                buf.flip();
                double rttMs = buf.getDouble();
                double ewmaRtt = buf.getDouble(); 
                System.out.printf("RTT: %.2f ms | EWMA: %.2f ms | Video: %d kbps | Audio: %d bps%n", 
                                rttMs, ewmaRtt, current_bitrate_kbps, current_audio_bitrate_bps);
                
                adjustBitrate(ewmaRtt);
                buf.clear();
            }
        }catch(Exception e ){
            System.err.println("NIO pipe Error: " + e);
        }
        }).start();         
        RTT_Client rtt_analysis = new RTT_Client(targetIP, targetPort, LOCAL_HOST, LOCAL_PORT, pipe);
        rtt_analysis.start();
        
        System.out.println("Pipeline: " + pipelineStr);
        pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        
        x264encoder = pipeline.getElementByName("encoder");
        aacEncoder = pipeline.getElementByName("aacencoder");
        
        if (x264encoder != null) {
            System.out.println(" ✓ x264encoder found - dynamic video bitrate enabled");
        } else {
            System.out.println(" ✗ x264encoder element not found - dynamic video bitrate disabled");
        }
        
        if (aacEncoder != null) {
            System.out.println(" ✓ AAC encoder found - dynamic audio bitrate enabled");
        } else {
            System.out.println(" ✗ AAC encoder element not found - dynamic audio bitrate disabled");
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
