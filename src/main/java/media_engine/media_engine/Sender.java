package media_engine.media_engine;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class Sender extends Thread {
     
    private static final String device = "/dev/video0";
    private static  int WIDTH = 1280;
    private static  int HEIGHT = 720;
    private static  int fps = 30;
    private static  int initial_bitrate_kbps = 8000;
    private static  int max_bitrate_kbps = 12000;
    private static int key_int_max = 30;
    
    private String targetIP;
    private int targetPort;
    private int latency;
    
    public Sender(String targetIP, int targetPort, int latency) {
        this.targetIP = targetIP;
        this.targetPort = targetPort;
        this.latency = latency;
    }

	public  void run() {
        System.out.println("Media Engine Sender Started");
        System.out.println("Target: " + targetIP + ":" + targetPort);
        Gst.init("MediaEngine", new String[]{});
    
        String pipelineStr = "v4l2src device=" + device + " io-mode=2 ! " +
            "image/jpeg,width=" + WIDTH + ",height=" + HEIGHT + ",framerate=" + fps + "/1 ! " +
            "jpegdec ! " +
            "videoconvert ! " +
            "x264enc tune=zerolatency bitrate=" + initial_bitrate_kbps + " key-int-max=" + key_int_max + " ! " +
            "h264parse ! mpegtsmux alignment=7 ! " +
            "srtsink uri=\"srt://" + targetIP + ":" + targetPort + "?mode=caller&latency=" + latency + "&rcvlatency=" + latency + "&peerlatency=" + latency + "&tlpktdrop=1&oheadbw=25\"";
      
        System.out.println("Pipeline: " + pipelineStr);
        Pipeline pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        pipeline.play();
        Gst.main();
        pipeline.stop();
    }
}
