package media_engine;

import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

import java.io.IOException;

public class Receiver extends Thread {
    private int LOCAL_PORT;
    private int LATENCY;

    public Receiver(int LOCAL_PORT, int LATENCY) throws IOException {
        this.LOCAL_PORT = LOCAL_PORT;
        this.LATENCY = LATENCY;
    }

    private String buildPipeline(String videoSink) {
        // --- MAKSİMUM 250ms GECİKME İÇİN OPTİMİZASYON ---
        // 'leaky=downstream' ve düşük 'max-size-time' parametreleri eklendi.
        return String.format(
                "srtsrc uri=\"srt://:%d?mode=listener&latency=%d&sndbuf=2097152&rcvbuf=2097152\" ! " +
                        "tsdemux name=demux " +
                        "demux. ! queue name=video_queue leaky=downstream max-size-time=50000000 ! h264parse ! avdec_h264 ! videoconvert ! %s sync=false " +
                        "demux. ! queue name=audio_queue leaky=downstream max-size-time=50000000 ! aacparse ! avdec_aac ! audioconvert ! audioresample ! " +
                        "volume volume=0.6 ! autoaudiosink sync=false",
                LOCAL_PORT, LATENCY, videoSink
        );
    }

    private String detectWorkingVideoSink(String[] sinks) { /* ... aynı ... */ return "autovideosink"; }

    @Override
    public void run() {
        System.out.println("Receiver Başlatıldı (Maksimum 250ms Gecikme Optimizasyonu)");

        String videoSink = detectWorkingVideoSink(new String[]{"autovideosink", "xvimagesink"});
        String pipelineStr = buildPipeline(videoSink);

        System.out.println("Listening on SRT port: " + LOCAL_PORT);
        System.out.println("Pipeline: " + pipelineStr);

        Pipeline pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        Bus bus = pipeline.getBus();

        bus.connect((Bus.EOS) source -> Gst.quit());
        bus.connect((Bus.ERROR) (source, code, message) -> { System.err.println("Receiver Hata: " + message); Gst.quit(); });

        pipeline.play();
        Gst.main();

        pipeline.stop();
        System.out.println("Receiver durduruldu.");
    }
}