package media_engine.media_engine;

import media_engine.Receiver;
import java.nio.channels.Pipe;

public class MediaEngine {
    public static String my_IP = "192.168.1.172";
    public static int my_STREAM_SENDER_PORT = 7000;
    public static int my_STREAM_RECEIVER_PORT = 7001;
    public static int my_RTT_SENDER_PORT = 7002;
    public static int my_ECHO_SERVER_PORT = 7003;

    public static String target_IP = "192.168.1.170";
    public static int target_RECEIVER_PORT = 4001;
    public static int target_ECHO_SERVER = 4003;

    public static void main(String[] args) {
        try{
            Pipe pipe = Pipe.open();

            Receiver receiver = new Receiver(my_STREAM_RECEIVER_PORT, my_ECHO_SERVER_PORT, 50);

            Sender sender = new Sender(my_IP, my_STREAM_SENDER_PORT,
                                          my_RTT_SENDER_PORT, target_ECHO_SERVER,
                                          target_IP, target_RECEIVER_PORT,
                                      50, "default0123456789", pipe, receiver);  // Receiver referansı eklendi

            synchronized(receiver) {
                receiver.start();
                Thread.sleep(250);
            }
            synchronized(sender){
                sender.start();
            }

        }catch(Exception e){
            System.err.println("STREAM ERROR: " + e);
        }


    }
}