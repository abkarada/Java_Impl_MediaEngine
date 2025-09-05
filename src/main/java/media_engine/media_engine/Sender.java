package media_engine.media_engine;

import media_engine.Receiver;
import org.freedesktop.gstreamer.*;

import java.io.File;

public class Sender extends Thread {
    // ... (Tüm değişkenleriniz aynı kalıyor, burayı değiştirmeyin) ...
    private static String device = detectCameraDevice();
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int MAX_FPS = 30;
    private static final int key_int_max = 30;
    private volatile int current_bitrate_kbps = 2000;
    private static final int MIN_BITRATE_KBPS = 1000;
    private static final int MAX_BITRATE_KBPS = 6000;
    private volatile int current_audio_bitrate_bps = 128000;
    private static final int MIN_AUDIO_BITRATE_BPS = 64000;
    private static final int MAX_AUDIO_BITRATE_BPS = 192000;
    private long lastBitrateChange = 0;
    private int excellentNetworkCounter = 0;

    private String targetIP;
    private int targetPort;
    private int LOCAL_PORT;
    private int latency;

    private Pipeline pipeline;
    private Element x264encoder;
    private Element aacEncoder;
    private Element srtSink;
    private Receiver receiver;

    private static String detectCameraDevice() {
        for (int i = 0; i <= 5; i++) {
            File deviceFile = new File("/dev/video" + i);
            if (deviceFile.exists()) {
                System.out.println("🎥 Kamera bulundu: /dev/video" + i);
                return "/dev/video" + i;
            }
        }
        System.out.println("⚠️ Kamera bulunamadı, varsayılan olarak /dev/video0 kullanılacak.");
        return "/dev/video0";
    }

    public Sender(String LOCAL_HOST, int LOCAL_PORT, String targetIP, int targetPort, int latency, String srtpKey, Receiver receiver) {
        this.LOCAL_PORT = LOCAL_PORT;
        this.targetIP = targetIP;
        this.targetPort = targetPort;
        this.latency = latency;
        this.receiver = receiver;
        System.out.println("Sender oluşturuldu. İstatistikler GStreamer üzerinden alınacak.");
    }

    private void adjustBitrate(double currentRtt) {
        // ... (Bu metodun içeriği doğru ve aynı kalabilir) ...
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBitrateChange < 3000) { return; }
        int oldBitrate = current_bitrate_kbps;
        int oldAudioBitrate = current_audio_bitrate_bps;
        if (currentRtt > 80.0) {
            current_bitrate_kbps = Math.max(MIN_BITRATE_KBPS, (int) (current_bitrate_kbps * 0.95));
            if (currentRtt > 100.0) {
                current_audio_bitrate_bps = Math.max(MIN_AUDIO_BITRATE_BPS, current_audio_bitrate_bps - 8000);
            }
            excellentNetworkCounter = 0;
            System.out.printf("⬇ YÜKSEK RTT (%.2f ms) - Bitrate azaltıldı: [Video: %d kbps | Audio: %d bps]%n", currentRtt, current_bitrate_kbps, current_audio_bitrate_bps);
        } else if (currentRtt < 20.0 && currentRtt > 0) {
            excellentNetworkCounter++;
            if (excellentNetworkCounter >= 10) {
                current_bitrate_kbps = Math.min(MAX_BITRATE_KBPS, (int) (current_bitrate_kbps * 1.02));
                if (excellentNetworkCounter >= 15 && currentRtt < 15.0) {
                    current_audio_bitrate_bps = Math.min(MAX_AUDIO_BITRATE_BPS, current_audio_bitrate_bps + 4000);
                }
                excellentNetworkCounter = 0;
                System.out.printf("⬆ DÜŞÜK RTT (%.2f ms) - Bitrate artırıldı: [Video: %d kbps | Audio: %d bps]%n", currentRtt, current_bitrate_kbps, current_audio_bitrate_bps);
            } else {
                System.out.printf("⭐ İYİ RTT (%.2f ms) - Artış için sayaç: %d/10%n", currentRtt, excellentNetworkCounter);
            }
        } else {
            excellentNetworkCounter = 0;
            return;
        }
        if (oldBitrate != current_bitrate_kbps || oldAudioBitrate != current_audio_bitrate_bps) {
            lastBitrateChange = currentTime;
            if (x264encoder != null) x264encoder.set("bitrate", current_bitrate_kbps);
            if (aacEncoder != null) aacEncoder.set("bitrate", current_audio_bitrate_bps);
        }
    }

    @Override
    public void run() {
        System.out.println("Media Engine Sender Başlatıldı");

        String pipelineStr = String.format(
                "v4l2src device=%s ! image/jpeg,width=%d,height=%d,framerate=%d/1 ! jpegdec ! videoconvert ! videoflip method=horizontal-flip ! " +
                        "x264enc name=encoder tune=zerolatency bitrate=%d key-int-max=%d ! h264parse config-interval=1 ! queue ! " +
                        "mpegtsmux name=mux alignment=7 ! queue ! " +
                        "srtsink name=srt_sink uri=\"srt://%s:%d?mode=caller&localport=%d&latency=%d\" " +
                        "pulsesrc ! audioconvert ! audioresample ! volume volume=0.8 ! " +
                        "queue ! avenc_aac name=aacencoder bitrate=%d ! aacparse ! queue ! mux.",
                device, WIDTH, HEIGHT, MAX_FPS, current_bitrate_kbps, key_int_max,
                targetIP, targetPort, LOCAL_PORT, latency,
                current_audio_bitrate_bps
        );

        pipeline = (Pipeline) Gst.parseLaunch(pipelineStr);
        Bus bus = pipeline.getBus();

        // **DÜZELTME:** Hata (ERROR) ve Akış Sonu (EOS) mesajlarını dinlemek için listener ekliyoruz.
        bus.connect((Bus.EOS) source -> {
            System.out.println("Sender: Akış sonu (EOS) sinyali alındı.");
            Gst.quit();
        });
        bus.connect((Bus.ERROR) (source, code, message) -> {
            System.err.println("Sender Hata: " + message + " (Kod: " + code + ")");
            Gst.quit();
        });

        x264encoder = pipeline.getElementByName("encoder");
        aacEncoder = pipeline.getElementByName("aacencoder");
        srtSink = pipeline.getElementByName("srt_sink");

        if (srtSink == null) {
            System.err.println("HATA: srtsink elementi bulunamadı. İstatistikler alınamayacak.");
            return;
        }

        Thread bitrateAdjuster = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!pipeline.isPlaying()) break;
                    Structure stats = (Structure) srtSink.get("stats");
                    if (stats != null) {
                        Structure linkStats = (Structure) stats.getValue("link");
                        double rttMicroseconds = linkStats.getDouble("rtt");
                        double rttMilliseconds = rttMicroseconds / 1000.0;
                        adjustBitrate(rttMilliseconds);
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) { /* Boş bırakılabilir */ }
            }
        });

        pipeline.play();
        bitrateAdjuster.start();

        System.out.println("Sender GStreamer ana döngüsü başlatılıyor...");
        Gst.main(); // Programın burada beklemesini ve Gst.quit() çağrılana kadar çalışmasını sağlar.

        // Gst.main() sonlandığında kaynakları temizle
        bitrateAdjuster.interrupt();
        pipeline.stop();
        System.out.println("Sender durduruldu ve kaynaklar temizlendi.");
    }
}