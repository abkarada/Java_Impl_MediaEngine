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
    private int Client_ECHO_PORT;   // Echo server port (hedef client'ın echo portu)
    private String LOCAL_HOST; 
    private int LOCAL_RTT_PORT;     // RTT Client için ayrı port    

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double EWMA_ALPHA = 0.15;  // Daha stabil (0.4 -> 0.15)
    
    public double ewmaRtt = 0.0;
    public int sequence = 0;
    
    // Packet loss ve jitter tracking
    private int totalPacketsSent = 0;
    private int packetsLost = 0;
    private double[] recentRTTs = new double[10];  // Son 10 RTT değeri
    private int rttIndex = 0;
    private boolean rttArrayFull = false;
    
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
    
    private double calculateJitter() {
        if (!rttArrayFull && rttIndex < 2) return 0.0;
        
        double sum = 0.0;
        double mean = 0.0;
        int count = rttArrayFull ? recentRTTs.length : rttIndex;
        
        for (int i = 0; i < count; i++) {
            mean += recentRTTs[i];
        }
        mean /= count;
        
        for (int i = 0; i < count; i++) {
            sum += Math.pow(recentRTTs[i] - mean, 2);
        }
        return Math.sqrt(sum / count);
    }
    
    private double calculatePacketLoss() {
        if (totalPacketsSent == 0) return 0.0;
        return (double) packetsLost / totalPacketsSent;
    }
    
    public RTT_Client(String Client_IP, int Client_ECHO_PORT, String LOCAL_HOST, int LOCAL_RTT_PORT, Pipe pipe){
        this.Client_IP = Client_IP;
        this.Client_ECHO_PORT = Client_ECHO_PORT;
        this.LOCAL_HOST = LOCAL_HOST;
        this.LOCAL_RTT_PORT = LOCAL_RTT_PORT;
        this.pipe = pipe;
        this.buffer = ByteBuffer.allocateDirect(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN);

        this.sink = pipe.sink();
        
        try {
            this.ch = DatagramChannel.open(); 
            ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            ch.bind(new InetSocketAddress(LOCAL_HOST, LOCAL_RTT_PORT));
            
            ch.connect(new InetSocketAddress(Client_IP, Client_ECHO_PORT));//ping from rtt port ->client echo port ->echo packet to rtt port
            ch.configureBlocking(false);
            
            this.selector = Selector.open();
            ch.register(selector, SelectionKey.OP_READ);//I am going to receive echo packet from client so I must listen
            
            System.out.println("RTT_Client: " + LOCAL_HOST + ":" + LOCAL_RTT_PORT + " -> Echo server: " + Client_IP + ":" + Client_ECHO_PORT);
            
        } catch (IOException e) {
            System.err.println("RTT_Client connection error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()){
            try {
                // Send PING packet
                ByteBuffer sink_pad = ByteBuffer.allocate(32); // 4 doubles = 32 bytes (RTT, EWMA, PacketLoss, Jitter)

                sequence++;
                totalPacketsSent++;
                long sendTime = System.nanoTime();
                ByteBuffer pingPacket = createPingPacket(LOCAL_RTT_PORT, sequence, sendTime);
                ch.write(pingPacket);
                
                long deadline = System.nanoTime() + 100L * NANOS_PER_MS;  // 100ms timeout (200ms -> 100ms)
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
                                            
                                            // RTT değerini array'e ekle (jitter hesabı için)
                                            recentRTTs[rttIndex] = rttMs;
                                            rttIndex = (rttIndex + 1) % recentRTTs.length;
                                            if (rttIndex == 0) rttArrayFull = true;
                                            
                                            double jitter = calculateJitter();
                                            double packetLossRate = calculatePacketLoss();
                                            
                                            System.out.printf("RTT: %.2f ms (EWMA: %.2f ms) | Loss: %.1f%% | Jitter: %.2f ms%n", 
                                                rttMs, ewmaRtt, packetLossRate*100, jitter);
                                            echoReceived = true;

                                            sink_pad.clear();
                                            sink_pad.putDouble(rttMs);
                                            sink_pad.putDouble(ewmaRtt);
                                            sink_pad.putDouble(packetLossRate);  // Packet loss oranı
                                            sink_pad.putDouble(jitter);          // Jitter değeri
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
                    packetsLost++;
                    System.out.println("PACKET LOSS DETECTED - No ECHO received (Loss: " + 
                        String.format("%.1f%%", calculatePacketLoss()*100) + ")");
                    // Paket kaybında çok agresif müdahale
                    if(ewmaRtt > 0) {
                        ewmaRtt = ewmaRtt * 2.0;  // 1.5 -> 2.0 daha agresif
                    } else {
                        ewmaRtt = 150.0;  // İlk paket kaybında yüksek penalty
                    }
                    
                    // Paket kaybı bilgisini pipe'a gönder
                    try {
                        ByteBuffer lossBuffer = ByteBuffer.allocate(32);  // 4 doubles için
                        lossBuffer.putDouble(0.0);  // RTT - packet loss durumunda 0
                        lossBuffer.putDouble(ewmaRtt);
                        lossBuffer.putDouble(calculatePacketLoss());  // Güncel packet loss oranı
                        lossBuffer.putDouble(calculateJitter());      // Güncel jitter
                        lossBuffer.flip();
                        while(lossBuffer.hasRemaining()) {
                            sink.write(lossBuffer);
                        }
                    } catch (IOException e) {
                        System.err.println("Pipe write error on packet loss: " + e);
                    }
                }
                
                Thread.sleep(200);  // Çok daha sık RTT ölçümü (500ms -> 200ms) 
                
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}