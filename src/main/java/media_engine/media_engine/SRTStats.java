package media_engine.media_engine;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Bu sınıf, ayrı bir thread üzerinde periyodik olarak SRT istatistiklerini
 * (özellikle RTT) almak için kullanılır. Bu sürüm, hem Linux hem de Windows ile uyumludur.
 */
public class SRTStats extends Thread {

    private final String targetIp;
    private final int targetPort;
    private int srtSocket = -1;

    private volatile double currentRttMs = 0.0;
    private volatile double pktSndLossTotal = 0.0;

    /**
     * C dilindeki 'sockaddr_in' ve 'in_addr' yapılarının JNA karşılığı.
     * Bu, platformdan bağımsız bir şekilde soket adresi oluşturmamızı sağlar.
     */
    public static class Sockaddr_in extends Structure {
        public short sin_family;
        public short sin_port;
        public int s_addr; // sin_addr.s_addr
        public byte[] sin_zero = new byte[8];

        public Sockaddr_in() {}

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("sin_family", "sin_port", "s_addr", "sin_zero");
        }
    }


    public SRTStats(String targetIp, int targetPort) {
        this.targetIp = targetIp;
        this.targetPort = targetPort;
    }

    private void initializeSRT() {
        if (SRTLibrary.INSTANCE.srt_startup() != 0) {
            System.err.println("SRTStats: srt_startup failed.");
            return;
        }

        srtSocket = SRTLibrary.INSTANCE.srt_create_socket();
        if (srtSocket < 0) {
            System.err.println("SRTStats: srt_create_socket failed.");
            SRTLibrary.INSTANCE.srt_cleanup();
            return;
        }

        try {
            // Platformdan bağımsız soket adresi yapısını oluşturalım.
            Sockaddr_in sockaddr = new Sockaddr_in();
            InetAddress address = InetAddress.getByName(targetIp);

            sockaddr.sin_family = 2; // AF_INET
            // Port numarasını Big-Endian formatına çeviriyoruz (network byte order)
            sockaddr.sin_port = Short.reverseBytes((short) targetPort);
            // IP adresini Big-Endian formatına çeviriyoruz
            ByteBuffer ipBuffer = ByteBuffer.wrap(address.getAddress());
            sockaddr.s_addr = ipBuffer.getInt();

            if (SRTLibrary.INSTANCE.srt_connect(srtSocket, sockaddr.getPointer(), sockaddr.size()) != 0) {
                System.err.println("SRTStats: srt_connect failed.");
                SRTLibrary.INSTANCE.srt_close(srtSocket);
                srtSocket = -1;
            } else {
                System.out.println("SRTStats: İstatistik takibi için bağlantı başarılı.");
            }

        } catch (Exception e) {
            System.err.println("SRTStats: Adres çözme veya bağlantı hatası: " + e.getMessage());
            if (srtSocket >= 0) SRTLibrary.INSTANCE.srt_close(srtSocket);
            srtSocket = -1;
        }
    }

    @Override
    public void run() {
        initializeSRT();

        while (!Thread.currentThread().isInterrupted() && srtSocket >= 0) {
            try {
                byte[] jsonBuffer = new byte[8192];
                int clearStats = 1;

                if (SRTLibrary.INSTANCE.srt_bistats_json(srtSocket, jsonBuffer, jsonBuffer.length, clearStats) > 0) {
                    int length = 0;
                    while (length < jsonBuffer.length && jsonBuffer[length] != 0) {
                        length++;
                    }
                    String jsonString = new String(jsonBuffer, 0, length);

                    if (!jsonString.isEmpty()) {
                        JSONObject stats = new JSONObject(jsonString);

                        if (stats.has("link") && stats.has("send")) {
                            currentRttMs = stats.getJSONObject("link").getJSONObject("rtt").getDouble("mean");
                            pktSndLossTotal = stats.getJSONObject("send").getDouble("pktSndLossTotal");
                            // System.out.printf("SRT Stats -> RTT: %.2f ms | Packet Loss: %.0f%n", currentRttMs, pktSndLossTotal);
                        }
                    }
                }

                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("SRTStats: İstatistik alınırken hata: " + e.getMessage());
            }
        }

        if (srtSocket >= 0) SRTLibrary.INSTANCE.srt_close(srtSocket);
        SRTLibrary.INSTANCE.srt_cleanup();
        System.out.println("SRTStats thread sonlandı ve kaynaklar temizlendi.");
    }

    public double getCurrentRttMs() {
        return currentRttMs;
    }
}