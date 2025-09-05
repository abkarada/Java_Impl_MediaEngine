package media_engine.media_engine;

import media_engine.Receiver;
import org.freedesktop.gstreamer.Gst;

public class MediaEngine {
    // ... IP ve Port ayarları ...
    public static String my_IP = "127.0.0.1";
    public static int my_STREAM_SENDER_PORT = 7000;
    public static int my_STREAM_RECEIVER_PORT = 7001;
    public static String target_IP = "127.0.0.1";
    public static int target_RECEIVER_PORT = 7001;

    // --- MAKSİMUM 250ms GECİKME İÇİN OPTİMİZASYON ---
    // Toplam gecikmenin en büyük kısmını SRT'ye veriyoruz.
    private static final int SRT_LATENCY = 200; // 200ms

    public static void main(String[] args) {
        try {
            Gst.init("MediaEngine", new String[]{});

            Receiver receiver = new Receiver(my_STREAM_RECEIVER_PORT, SRT_LATENCY);

            Sender sender = new Sender(my_IP, my_STREAM_SENDER_PORT,
                    target_IP, target_RECEIVER_PORT,
                    SRT_LATENCY,
                    "default0123456789",
                    receiver);

            receiver.start();
            Thread.sleep(500);
            sender.start();

        } catch(Exception e) {
            System.err.println("MediaEngine HATA: Uygulama başlatılamadı.");
            e.printStackTrace();
        }
    }
}