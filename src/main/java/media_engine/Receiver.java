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

    private String detectWorkingVideoSink(String[] sinks) {
        for (String sink : sinks) {
            System.out.println("🔍 Video sink olarak '" + sink + "' denenecek.");
            return sink;
        }
        return "autovideosink";
    }

    private String buildPipeline(String videoSink) {
        return String.format(
                "srtsrc uri=\"srt://:%d?mode=listener&latency=%d\" ! " +
                        "tsdemux name=demux " +
                        // **DÜZELTME BURADA:** avdec_h24 -> avdec_h264 olarak değiştirildi.
                        "demux. ! queue ! h264parse ! avdec_h264 ! videoconvert ! %s sync=false " +
                        "demux. ! queue ! aacparse ! avdec_aac ! audioconvert ! audioresample ! " +
                        "volume volume=0.6 ! autoaudiosink sync=false",
                LOCAL_PORT, LATENCY, videoSink
        );
    }

    @Override
    public void run() {
        System.out.println("Media Engine Receiver Başlatıldı");

        String[] videoSinks = {"autovideosink", "xvimagesink"};
        String videoSink = detectWorkingVideoSink(videoSinks);
        String pipelineStr = buildPipeline(videoSink);

        System.out.println("Listening on SRT port: " + LOCAL_PORT);
        System.out.println("Pipeline: " + pipelineStr);

        Pipeline pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        Bus bus = pipeline.getBus();

        bus.connect((Bus.EOS) source -> {
            System.out.println("Receiver: Akış sonu (EOS) sinyali alındı.");
            Gst.quit();
        });
        bus.connect((Bus.ERROR) (source, code, message) -> {
            System.err.println("Receiver Hata: " + message + " (Kod: " + code + ")");
            Gst.quit();
        });

        pipeline.play();

        System.out.println("Receiver GStreamer ana döngüsü başlatılıyor...");
        Gst.main();

        pipeline.stop();
        System.out.println("Receiver durduruldu.");
    }
}