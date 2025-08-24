package media_engine.media_engine;

import media_engine.Receiver;
import media_engine.media_engine.Sender;
import java.nio.channels.Pipe;

public class MediaEngine {

    public static void main(String[] args) {
        // Başlatıcı kodu burada
        try {
            Pipe pipe = Pipe.open();
            Receiver receiver = new Receiver(7001, 250);
            Sender sender = new Sender("127.0.0.1", 7001, 250, pipe);
            
            receiver.start();
            sender.start(); 

        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

}