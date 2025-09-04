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
    
    public void run() {
        String[] videoSinks = {"xvimagesink", "ximagesink", "autovideosink"};
        String videoSink = videoSinks[0];

        String pipeline =
            "srtsrc uri=\"srt://:" + LOCAL_PORT +
            "?mode=listener&latency=" + LATENCY +
            "&rcvlatency=" + LATENCY +
            "&peerlatency=" + LATENCY +
            "&tlpktdrop=1&oheadbw=5" +
            "&sndbuf=1024000&rcvbuf=1024000&maxbw=0&inputbw=0&mss=1500\" ! " +
            "tsdemux name=dmx " +

            "dmx. ! queue max-size-buffers=15 max-size-time=50000000 ! h264parse ! avdec_h264 ! videoconvert ! " + videoSink + " sync=false " +

            "dmx. ! queue max-size-buffers=10 max-size-time=30000000 ! aacparse ! avdec_aac ! audioconvert ! audioresample ! " +
            "volume volume=0.6 ! " +  // Ses çıkış seviyesini düşür (yankı azaltır)
            "autoaudiosink sync=false buffer-time=2000";
                         
        System.out.println("Media Engine Receiver Started");
        System.out.println("Listening on SRT port: " + LOCAL_PORT);
        System.out.println("Using video sink: " + videoSink);
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