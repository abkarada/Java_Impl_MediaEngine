package media_engine.media_engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;


public class Echo extends Thread {

    private int LOCAL_PORT;

    private static final int MAGIC = 0xABCD1357;
    private static final byte VERSION = 1;
    private static final byte MSG_TYPE_PING = 1;
    private static final byte MSG_TYPE_ECHO = 2;
    private static final int PACKET_SIZE = 64;
    private static final long NANOS_PER_MS = 1_000_000L;

    DatagramChannel ch;
    Selector sel;

    public Echo(int LOCAL_PORT) throws IOException{
        this.LOCAL_PORT = LOCAL_PORT;

        this.ch = DatagramChannel.open();
        this.sel = Selector.open();
        ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ch.setOption(StandardSocketOptions.SO_REUSEPORT, true);
        ch.bind(new InetSocketAddress("0.0.0.0", LOCAL_PORT));
        ch.configureBlocking(false);
        ch.register(sel, SelectionKey.OP_READ);
        
        System.out.println("Echo server initialized on port: " + LOCAL_PORT);
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


    @Override
    public void run(){
        System.out.println("Echo server started, listening for PING packets on port " + LOCAL_PORT + "...");
        try{
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if(sel.select(10) > 0) {
                       for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext();) {
                                    SelectionKey k = it.next(); 
                                    it.remove();

                                 if (k.isReadable()) {
                                        ByteBuffer EchoBuffer = ByteBuffer.allocate(PACKET_SIZE);
                                        InetSocketAddress senderAddr = (InetSocketAddress) ch.receive(EchoBuffer);
        
                                        if(senderAddr != null) {
                                            EchoBuffer.flip();
                                            PacketInfo info = parsePacket(EchoBuffer);
                                            if(info != null) {
                                                if(info.msgType == MSG_TYPE_PING) {
                                                    System.out.println("PING received from " + senderAddr + " -> sending ECHO");
                                                    ByteBuffer echoPacket = createEchoPacket(EchoBuffer.duplicate());
                                                    ch.send(echoPacket, senderAddr);
                                                }
                                            }
                                            EchoBuffer.clear();
                                        }
                                    }
                                }
                            }
                } catch(Exception e){
                    System.err.println("Echo server error: " + e.getMessage());
                    e.printStackTrace();
                    // Canlı ortamda crash recovery
                    try {
                        Thread.sleep(1000);  // 1 saniye bekle ve devam et
                        System.out.println("🔄 Echo server recovery attempt...");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }  
            }
        } catch (Exception fatal) {
            System.err.println("💥 FATAL Echo server error: " + fatal.getMessage());
            fatal.printStackTrace();
        }
    }    
}
