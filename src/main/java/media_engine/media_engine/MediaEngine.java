package media_engine.media_engine;

import media_engine.Receiver;
import java.nio.channels.Pipe;

public class MediaEngine {

    public static void main(String[] args) {
        try {
            int echoPort = 7002;        
            int receiverPort = 7001;    
            int senderTargetPort = 7001; 
            int rttSourcePort = 7000;   
            
            System.out.println("=== Media Engine Starting ===");
            System.out.println("Echo Server Port: " + echoPort);
            System.out.println("Receiver Port: " + receiverPort);
            System.out.println("RTT Source Port: " + rttSourcePort);
            System.out.println("================================");
            
            Pipe pipe = Pipe.open();
            
            Echo echo = new Echo(echoPort);
            
            Receiver receiver = new Receiver("192.168.1.213", senderTargetPort, receiverPort, 50);
            
            Sender sender = new Sender("192.168.1.207", rttSourcePort, "192.168.1.213", senderTargetPort, 50, pipe);
            
            echo.start();
            Thread.sleep(200); 
            
            receiver.start();
            Thread.sleep(200); 
            
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