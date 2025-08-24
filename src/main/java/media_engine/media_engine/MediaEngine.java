package media_engine.media_engine;

import media_engine.Receiver;
import media_engine.media_engine.Sender;

public class MediaEngine {

    public static void main(String[] args) {
        // Başlatıcı kodu burada
        Receiver receiver = new Receiver(7001, 250);
        Sender sender = new Sender("127.0.0.1", 7001, 250);
        
        receiver.start();
        sender.start(); 
    }

}