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
    private static final double RTT_THRESHOLD_MS = 50.0;
    private static final double RTT_GOOD_MS = 20.0;         
    private double lastEwmaRtt = 0.0;
    private long lastBitrateChange = 0;
    private static final long BITRATE_CHANGE_INTERVAL_MS = 3000; 
    
    private String targetIP;
    private int targetPort;
    private String LOCAL_HOST;
    private int LOCAL_PORT;
    private int latency;

    Pipe pipe;
    Pipe.SourceChannel src;
    private Pipeline pipeline;
    private Element x264encoder;

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
        
        if (currentEwmaRtt > RTT_THRESHOLD_MS) {
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, 
                                          (int)(current_bitrate_kbps * 0.8)); 
            System.out.printf("⬇ NETWORK CONGESTION (RTT: %.2f ms) - Reducing bitrate: %d → %d kbps%n", 
                            currentEwmaRtt, oldBitrate, current_bitrate_kbps);
        } 
        else if (currentEwmaRtt < RTT_GOOD_MS && lastEwmaRtt > 0 && currentEwmaRtt <= lastEwmaRtt) {
            current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, 
                                          current_bitrate_kbps + BITRATE_STEP_KBPS);
            System.out.printf("⬆ NETWORK STABLE (RTT: %.2f ms) - Increasing bitrate: %d → %d kbps%n", 
                            currentEwmaRtt, oldBitrate, current_bitrate_kbps);
        }
        
        if (oldBitrate != current_bitrate_kbps) {
            lastBitrateChange = currentTime;
            
            // Runtime bitrate değişikliği - pipeline restart gereksiz
            if (x264encoder != null) {
                try {
                    x264encoder.set("bitrate", current_bitrate_kbps);
                    System.out.printf(" Bitrate updated dynamically to %d kbps%n", current_bitrate_kbps);
                } catch (Exception e) {
                    System.out.printf("Failed to update bitrate dynamically: %s%n", e.getMessage());
                    System.out.printf("Bitrate will change on next pipeline cycle%n");
                }
            }
        }
        
        lastEwmaRtt = currentEwmaRtt;
    }

	public  void run() {
        System.out.println("Media Engine Sender Started");
        System.out.println("Target: " + targetIP + ":" + targetPort);
        Gst.init("MediaEngine", new String[]{});
    
        String pipelineStr = "v4l2src device=" + device + " io-mode=2 ! " +
            "image/jpeg,width=" + WIDTH + ",height=" + HEIGHT + ",framerate=" + fps + "/1 ! " +
            "jpegdec ! " +
            "videoconvert ! " +
            "x264enc name=encoder tune=zerolatency bitrate=" + current_bitrate_kbps + " key-int-max=" + key_int_max + " ! " +
            "h264parse ! mpegtsmux alignment=7 ! " +
            "srtsink uri=\"srt://" + targetIP + ":" + targetPort + "?mode=caller&localport=" + LOCAL_PORT + "&latency=" + latency + "&rcvlatency=" + latency + "&peerlatency=" + latency + "&tlpktdrop=1&oheadbw=25\"";
      
        new Thread(()->{
            ByteBuffer buf = ByteBuffer.allocate(16); // 2 doubles = 16 bytes
            try{
            while(src.read(buf) > 0){
                buf.flip();
                double rttMs = buf.getDouble();
                double ewmaRtt = buf.getDouble(); 
                System.out.printf("RTT: %.2f ms | EWMA: %.2f ms | Bitrate: %d kbps%n", 
                                rttMs, ewmaRtt, current_bitrate_kbps);
                
                adjustBitrate(ewmaRtt);
                buf.clear();
            }
        }catch(Exception e ){
            System.err.println("NIO pipe Error: " + e);
        }
        }).start(); // Thread'i başlat!
        
        RTT_Client rtt_analysis = new RTT_Client(targetIP, targetPort, LOCAL_HOST, LOCAL_PORT, pipe);
        rtt_analysis.start();
        
        System.out.println("Pipeline: " + pipelineStr);
        pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        
        // x264enc elementini ismi ile bul - runtime bitrate değişikliği için
        x264encoder = pipeline.getElementByName("encoder");
        if (x264encoder != null) {
            System.out.println(" x264encoder found - dynamic bitrate enabled");
        } else {
            System.out.println(" x264encoder element not found - dynamic bitrate disabled");
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
