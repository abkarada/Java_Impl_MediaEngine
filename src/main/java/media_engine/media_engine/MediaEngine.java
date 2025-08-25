package media_engine.media_engine;

import media_engine.Receiver;
import java.nio.channels.Pipe;

public class MediaEngine {

    public static void main(String[] args) {
        try {
            // Port konfigürasyonu - çakışmaları önlemek için
            int echoPort = 7002;        // Echo servisi için ayrı port
            int receiverPort = 7001;    // Receiver için port
            int senderTargetPort = 7001; // Sender'ın bağlanacağı port
            int rttSourcePort = 7000;   // RTT Client için kaynak port
            
            System.out.println("=== Media Engine Starting ===");
            System.out.println("Echo Server Port: " + echoPort);
            System.out.println("Receiver Port: " + receiverPort);
            System.out.println("RTT Source Port: " + rttSourcePort);
            System.out.println("================================");
            
            Pipe pipe = Pipe.open();
            
            // Echo server'ı ayrı portta başlat
            Echo echo = new Echo(echoPort);
            
            // Receiver'ı kendi portunda başlat  
            Receiver receiver = new Receiver("127.0.0.1", senderTargetPort, receiverPort, 250);
            
            // Sender'ı RTT ile birlikte başlat
            Sender sender = new Sender("127.0.0.1", rttSourcePort, "127.0.0.1", senderTargetPort, 250, pipe);
            
            // Thread'leri sırayla başlat
            echo.start();
            Thread.sleep(200); // Echo'nun başlamasını bekle
            
            receiver.start();
            Thread.sleep(200); // Receiver'ın başlamasını bekle
            
            sender.start();
            
            System.out.println("All components started successfully!");
            
        } catch (java.io.IOException e) {
            System.err.println("IO Error during startup: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Interrupted during startup: " + e.getMessage());
            e.printStackTrace();
        }
    }

}