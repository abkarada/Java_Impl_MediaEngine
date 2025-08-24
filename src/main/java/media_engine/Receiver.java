package media_engine;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

public class Receiver extends Thread {
    private int PORT = 5004;
    private int LATENCY = 250;
    
    public Receiver(int PORT, int LATENCY){
        this.PORT = PORT;
        this.LATENCY = LATENCY;
    }
    
    public void run() {
        String pipeline = "srtsrc uri=\"srt://:" + PORT + "?mode=listener&latency=" + LATENCY + "&rcvlatency=" + LATENCY + "&peerlatency=" + LATENCY + "&tlpktdrop=1&oheadbw=25\" ! " +
                         "tsdemux ! h264parse ! avdec_h264 ! videoconvert ! autovideosink sync=true";
                         
        System.out.println("Media Engine Receiver Started");
        System.out.println("Listening on port: " + PORT);
        Gst.init("MediaEngineReceiver", new String[]{});
        System.out.println("Pipeline: " + pipeline);
        Pipeline p = (Pipeline) Gst.parseLaunch(pipeline);
        p.play();
        Gst.main();
        p.stop();
    }
}