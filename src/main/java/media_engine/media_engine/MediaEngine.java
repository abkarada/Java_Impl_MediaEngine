package media_engine.media_engine;

import media_engine.Receiver;
import org.freedesktop.gstreamer.Gst;

public class MediaEngine {
    // --- Yerel Makine Ayarları ---
    public static String my_IP = "127.0.0.1"; // Test için localhost
    public static int my_STREAM_SENDER_PORT = 7000;
    public static int my_STREAM_RECEIVER_PORT = 7001;

    // --- Hedef Makine Ayarları ---
    public static String target_IP = "127.0.0.1"; // Test için localhost
    public static int target_RECEIVER_PORT = 7001; // Hedef port, receiver ile aynı olmalı

    // --- SRT Gecikme Ayarı ---
    private static final int SRT_LATENCY = 120;

    public static void main(String[] args) {
        try {
            // Adım 1: GStreamer'ı SADECE BİR KEZ, en başta başlat.
            // Bu, en yaygın çökme nedenini ortadan kaldırır.
            Gst.init("MediaEngine", new String[]{});

            // Adım 2: Receiver'ı oluştur.
            Receiver receiver = new Receiver(my_STREAM_RECEIVER_PORT, SRT_LATENCY);

            // Adım 3: Sender'ı oluştur. SRTStats referansı kaldırıldı.
            Sender sender = new Sender(my_IP, my_STREAM_SENDER_PORT,
                    target_IP, target_RECEIVER_PORT,
                    SRT_LATENCY,
                    "default0123456789",
                    receiver);

            // Adım 4: Thread'leri başlat.
            receiver.start();
            // Sender'ın başlamadan önce Receiver'ın dinlemeye hazır olması için kısa bir bekleme
            Thread.sleep(500);
            sender.start();

        } catch(Exception e) {
            System.err.println("MediaEngine HATA: Uygulama başlatılamadı.");
            e.printStackTrace();
        }
    }
}