package media_engine.media_engine;

import media_engine.Receiver;
import org.freedesktop.gstreamer.*;

import java.io.File;

public class Sender extends Thread {
    // --- MAKSİMUM 250ms GECİKME İÇİN OPTİMİZASYON ---
    // Kalan 50ms'yi jitter tamponu için ayırıyoruz.
    private static final long MAX_QUEUE_TIME_NS = 50_000_000;  // 50ms
    private static final long MIN_QUEUE_TIME_NS = 20_000_000;  // 20ms
    // Başlangıç kuyruk boyutu da düşük olmalı.
    private volatile long video_queue_time_ns = 30_000_000; // 30ms

    // ... Diğer değişkenler ...
    private volatile int current_bitrate_kbps = 2000;
    private static final int MIN_BITRATE_KBPS = 800;
    private static final int MAX_BITRATE_KBPS = 8000;
    private static String device = detectCameraDevice();
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int MAX_FPS = 30;
    private static final int key_int_max = 60;
    private volatile int current_audio_bitrate_bps = 96000;

    private String targetIP;
    private int targetPort;
    private int LOCAL_PORT;
    private int latency;

    private Pipeline pipeline;
    private Element x264encoder, aacEncoder, srtSink, videoQueue, audioQueue;

    public Sender(String LOCAL_HOST, int LOCAL_PORT, String targetIP, int targetPort, int latency, String srtpKey, Receiver receiver) {
        this.LOCAL_PORT = LOCAL_PORT;
        this.targetIP = targetIP;
        this.targetPort = targetPort;
        this.latency = latency;
    }

    private static String detectCameraDevice() { /* ... aynı ... */ return "/dev/video0"; }

    private void adjustRuntimeParameters(double rttMs, double jitterMs) {
        // ... Bitrate Ayarlama Mantığı (aynı kalabilir) ...
        if (rttMs > 100) {
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, (int) (current_bitrate_kbps * 0.90));
        } else if (rttMs < 30) {
            current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, (int) (current_bitrate_kbps * 1.05));
        }
        x264encoder.set("bitrate", current_bitrate_kbps);

        // --- Jitter'a Göre Kuyruk (Buffer) Ayarlama Mantığı (250ms hedefine uygun) ---
        long target_queue_time_ns = (long) (jitterMs * 1.5 * 1_000_000) + 20_000_000; // Jitter'ın 1.5 katı + 20ms pay
        video_queue_time_ns = Math.max(MIN_QUEUE_TIME_NS, Math.min(MAX_QUEUE_TIME_NS, target_queue_time_ns));

        videoQueue.set("max-size-time", video_queue_time_ns);
        audioQueue.set("max-size-time", video_queue_time_ns);

        System.out.printf("RTT: %.1fms, Jitter: %.1fms -> Bitrate: %dKbps, Queue: %dms (Max: %dms)%n",
                rttMs, jitterMs, current_bitrate_kbps, video_queue_time_ns / 1_000_000, MAX_QUEUE_TIME_NS / 1_000_000);
    }

    @Override
    public void run() {
        System.out.println("Sender Başlatıldı (Maksimum 250ms Gecikme Optimizasyonu)");

        // --- MAKSİMUM 250ms GECİKME İÇİN OPTİMİZASYON ---
        // 'leaky=downstream' parametresi eklendi.
        String pipelineStr = String.format(
                "v4l2src device=%s ! image/jpeg,width=%d,height=%d,framerate=%d/1 ! jpegdec ! videoconvert ! videoflip method=horizontal-flip ! " +
                        "x264enc name=encoder tune=zerolatency speed-preset=ultrafast bitrate=%d key-int-max=%d ! h264parse config-interval=-1 ! " +
                        "queue name=video_queue leaky=downstream max-size-time=%d ! mpegtsmux name=mux alignment=7 ! " +
                        "srtsink name=srt_sink uri=\"srt://%s:%d?mode=caller&localport=%d&latency=%d&sndbuf=2097152&rcvbuf=2097152\" " +
                        "pulsesrc ! audioconvert ! audioresample ! " +
                        "queue name=audio_queue leaky=downstream max-size-time=%d ! avenc_aac name=aacencoder bitrate=%d ! aacparse ! mux.",
                device, WIDTH, HEIGHT, MAX_FPS, current_bitrate_kbps, key_int_max, video_queue_time_ns,
                targetIP, targetPort, LOCAL_PORT, latency,
                video_queue_time_ns, current_audio_bitrate_bps
        );

        // ... kodun geri kalanı (main loop, adjusterThread vb.) aynı kalabilir ...
        pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        Bus bus = pipeline.getBus();
        bus.connect((Bus.EOS) source -> Gst.quit());
        bus.connect((Bus.ERROR) (source, code, message) -> { System.err.println("Sender Hata: " + message); Gst.quit(); });
        x264encoder = pipeline.getElementByName("encoder");
        aacEncoder = pipeline.getElementByName("aacencoder");
        srtSink = pipeline.getElementByName("srt_sink");
        videoQueue = pipeline.getElementByName("video_queue");
        audioQueue = pipeline.getElementByName("audio_queue");
        Thread adjusterThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!pipeline.isPlaying()) break;
                    Structure stats = (Structure) srtSink.get("stats");
                    if (stats != null) {
                        Structure linkStats = (Structure) stats.getValue("link");
                        double rttMs = linkStats.getDouble("rtt") / 1000.0;
                        Structure rttStats = (Structure) linkStats.getValue("rtt");
                        double jitterMs = rttStats.getDouble("variance") / 1000.0;
                        adjustRuntimeParameters(rttMs, jitterMs);
                    }
                    Thread.sleep(1000);
                } catch (Exception e) { Thread.currentThread().interrupt(); }
            }
        });
        pipeline.play();
        adjusterThread.start();
        Gst.main();
        adjusterThread.interrupt();
        pipeline.stop();
        System.out.println("Sender durduruldu.");
    }
}