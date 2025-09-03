package media_engine.media_engine;

import java.net.*;
import java.util.Iterator;
import java.io.IOException;


import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.Pipe;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;




public class RTT_Client extends Thread {
    
    private static final int MAGIC = 0xABCD1357;
    private static final byte VERSION = 1;
    private static final byte MSG_TYPE_PING = 1;
    private static final byte MSG_TYPE_ECHO = 2;
    private static final int PACKET_SIZE = 64;
    
    private String Client_IP;  
    private int Client_PORT;   
    private String LOCAL_HOST; 
    private int LOCAL_PORT;    

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double EWMA_ALPHA = 0.4;  // Daha responsive (0.2 -> 0.4)
    
    public double ewmaRtt = 0.0;
    public int sequence = 0;
    
    DatagramChannel ch;
    Selector selector;
    ByteBuffer buffer; 
    Pipe pipe;
    Pipe.SinkChannel sink;
    
    
    public ByteBuffer createPingPacket(int senderId, int seq, long timestamp) {
        buffer.clear();
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(MSG_TYPE_PING);
        buffer.putShort((short)0);
        buffer.putInt(senderId); 
        buffer.putInt(seq);
        buffer.putLong(timestamp);
        buffer.position(PACKET_SIZE);
        buffer.flip();
        return buffer;
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
    
    public RTT_Client(String Client_IP, int Client_PORT, String LOCAL_HOST, int LOCAL_PORT, Pipe pipe){
        this.Client_IP = Client_IP;
        this.Client_PORT = Client_PORT;
        this.LOCAL_HOST = LOCAL_HOST;
        this.LOCAL_PORT = LOCAL_PORT;
        this.pipe = pipe;
        this.buffer = ByteBuffer.allocateDirect(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN);

        this.sink = pipe.sink();
        
        try {
            this.ch = DatagramChannel.open(); 
            ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            ch.bind(new InetSocketAddress(LOCAL_HOST, LOCAL_PORT));
            
            // Echo server'a bağlanmak için port 7002'yi kullan (sabit Echo portu)
            int echoServerPort = 7002;
            ch.connect(new InetSocketAddress(Client_IP, echoServerPort));
            ch.configureBlocking(false);
            
            this.selector = Selector.open();
            ch.register(selector, SelectionKey.OP_READ);
            
            System.out.println("RTT_Client: " + LOCAL_HOST + ":" + LOCAL_PORT + " -> Echo server: " + Client_IP + ":" + echoServerPort);
            
        } catch (IOException e) {
            System.err.println("RTT_Client connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while(true){
            try {
                // Send PING
                ByteBuffer sink_pad = ByteBuffer.allocate(16); // 2 doubles = 16 bytes (FIXED)

                sequence++;
                long sendTime = System.nanoTime();
                ByteBuffer pingPacket = createPingPacket(LOCAL_PORT, sequence, sendTime);
                ch.write(pingPacket);
                
                long deadline = System.nanoTime() + 200L * NANOS_PER_MS;
                boolean echoReceived = false;

                while (System.nanoTime() < deadline && !echoReceived) {
                    if(selector.select(10) > 0) {
                        for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext();) {
                            SelectionKey k = it.next(); 
                            it.remove();
                            
                            if (k.isReadable()) {
                                ByteBuffer responseBuffer = ByteBuffer.allocate(PACKET_SIZE);
                                
                                int bytesRead;
                                while((bytesRead = ch.read(responseBuffer)) > 0 && bytesRead >= PACKET_SIZE) {
                                    responseBuffer.flip();
                                    
                                    PacketInfo info = parsePacket(responseBuffer);
                                    if(info != null) {
                                        if(info.msgType == MSG_TYPE_ECHO && info.seq == sequence) {
                                            long receiveTime = System.nanoTime();
                                            long rttNanos = receiveTime - info.timestamp;
                                            double rttMs = rttNanos / (double)NANOS_PER_MS;
                                            
                                            if(ewmaRtt == 0.0) {
                                                ewmaRtt = rttMs;
                                            } else {
                                                ewmaRtt = EWMA_ALPHA * rttMs + (1 - EWMA_ALPHA) * ewmaRtt;
                                            }
                                            
                                            System.out.printf("RTT: %.2f ms (EWMA: %.2f ms)%n", rttMs, ewmaRtt);
                                            echoReceived = true;

                                            sink_pad.clear();
                                            sink_pad.putDouble(rttMs);
                                            sink_pad.putDouble(ewmaRtt);  // FIXED: Long → Double
                                            sink_pad.flip();
                                            while(sink_pad.hasRemaining()){
                                                sink.write(sink_pad);
                                            }


                                        }
                                    }
                                    
                                    responseBuffer.clear();
                                }
                            }
                        }
                    }
                }
                
                if(!echoReceived) {
                    System.out.println("TIMEOUT - No ECHO received");
                    if(ewmaRtt > 0) {
                        ewmaRtt = ewmaRtt * 1.5; 
                    }
                }
                
                Thread.sleep(500);  // Daha sık RTT ölçümü (1000ms -> 500ms) 
                
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}