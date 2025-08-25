package media_engine;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;


public class Receiver extends Thread {
    private String TARGET_IP;
    private int TARGET_PORT;
    private int LOCAL_PORT = 5004;
    private int LATENCY = 250;
    
    
    private static final int MAGIC = 0xABCD1357;
    private static final byte VERSION = 1;
    private static final byte MSG_TYPE_PING = 1;
    private static final byte MSG_TYPE_ECHO = 2;
    private static final int PACKET_SIZE = 64;
    private static final long NANOS_PER_MS = 1_000_000L;

    DatagramChannel ch;
    Selector sel;

    public Receiver(String TARGET_IP, int TARGET_PORT,
                        int LOCAL_PORT, int LATENCY){
        this.TARGET_IP = TARGET_IP;
        this.TARGET_PORT = TARGET_PORT;
        this.LOCAL_PORT = LOCAL_PORT;
        this.LATENCY = LATENCY;
        try{
            this.ch = DatagramChannel.open();
            this.sel = Selector.open();
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            // RTT ECHO için LOCAL_PORT'a bind et, TARGET_PORT'a connect etme
            ch.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
            ch.configureBlocking(false);
            ch.register(sel, SelectionKey.OP_READ);

        }catch(Exception e){
            System.err.println("Datagram Channel Creation Error:" + e);
        }

    }

        static class PacketInfo {
        byte version, msgType;
        short flags;
        int senderId, seq;
        long timestamp;
        
        PacketInfo(byte version, byte msgType, short flags, int senderId, int seq, long timestamp) {
            this.version = version;
            this.msgType = msgType;
            this.flags = flags;
            this.senderId = senderId;
            this.seq = seq;
            this.timestamp = timestamp;
        }
    }

        public ByteBuffer createEchoPacket(ByteBuffer pingPacket) {
        pingPacket.rewind();
        pingPacket.position(5);
        pingPacket.put(MSG_TYPE_ECHO);
        pingPacket.rewind();
        pingPacket.limit(PACKET_SIZE);
        return pingPacket;
    }

        public PacketInfo parsePacket(ByteBuffer packet) {
        packet.rewind();
        
        int magic = packet.getInt();
        if(magic != MAGIC) return null;
        
        byte version = packet.get();
        byte msgType = packet.get();
        short flags = packet.getShort();
        int senderId = packet.getInt();
        int seq = packet.getInt();
        long timestamp = packet.getLong();
        
        return new PacketInfo(version, msgType, flags, senderId, seq, timestamp);
    }

 
    
    public void run() {
        String pipeline = "srtsrc uri=\"srt://:" + LOCAL_PORT + "?mode=listener&latency=" + LATENCY + "&rcvlatency=" + LATENCY + "&peerlatency=" + LATENCY + "&tlpktdrop=1&oheadbw=25\" ! " +
                         "tsdemux ! h264parse ! avdec_h264 ! videoconvert ! autovideosink sync=true";
                         
        System.out.println("Media Engine Receiver Started");
        System.out.println("Listening on port: " + LOCAL_PORT);
        
        // Start ECHO thread
        Thread echoThread = new Thread(() -> {
                 
                try{
                     while (true) {
                        if(sel.select(10) > 0) {
                            for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext();) {
                                SelectionKey k = it.next(); 
                                it.remove();
                            
                             if (k.isReadable()) {
                                    ByteBuffer EchoBuffer = ByteBuffer.allocate(PACKET_SIZE);
                                
                                    // UDP unconnected channel için receive kullan
                                    InetSocketAddress senderAddr = (InetSocketAddress) ch.receive(EchoBuffer);
                                    if(senderAddr != null) {
                                        EchoBuffer.flip();
                                    
                                         PacketInfo info = parsePacket(EchoBuffer);
                                        if(info != null) {
                                            if(info.msgType == MSG_TYPE_PING) {
                                                ByteBuffer echoPacket = createEchoPacket(EchoBuffer.duplicate());
                                                // ECHO'yu PING gönderen adrese geri gönder
                                                ch.send(echoPacket, senderAddr);
                                            }
                                        }
                                        EchoBuffer.clear();
                                    }
                                }
                         }
                        }
                    }
                }catch(Exception e){
                    System.out.println("Echo Error:" + e);
                       }                    
                });
        echoThread.start();

        Gst.init("MediaEngineReceiver", new String[]{});
        System.out.println("Pipeline: " + pipeline);
        Pipeline p = (Pipeline) Gst.parseLaunch(pipeline);
        p.play();
        Gst.main();
        p.stop();
    }
}