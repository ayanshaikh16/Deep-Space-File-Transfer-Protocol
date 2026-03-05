import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Receiver {

    // Types (match DSPacket)
    private static final byte TYPE_SOT  = DSPacket.TYPE_SOT;
    private static final byte TYPE_DATA = DSPacket.TYPE_DATA;
    private static final byte TYPE_ACK  = DSPacket.TYPE_ACK;
    private static final byte TYPE_EOT  = DSPacket.TYPE_EOT;

    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        if (args.length != 5) {
            System.out.println("Usage:");
            System.out.println("java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        String senderIp = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        InetAddress senderAddr = InetAddress.getByName(senderIp);

        DatagramSocket socket = new DatagramSocket(rcvDataPort);
        FileOutputStream fos = new FileOutputStream(outputFile);

        System.out.println("[Receiver] Listening on port " + rcvDataPort);

        int expectedSeq = 1;    // per spec: first DATA seq = 1
        int lastInOrder = 0;    // last correctly received in-order DATA seq (starts at 0)
        int ackCount = 0;       // total ACKs we INTEND to send (1-indexed in ChaosEngine logic)

        boolean running = true;

        while (running) {

            byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
            DatagramPacket udp = new DatagramPacket(buf, buf.length);
            socket.receive(udp);

            DSPacket pkt;
            try {
                pkt = new DSPacket(buf);
            } catch (IllegalArgumentException ex) {
                System.out.println("[Receiver] Dropped invalid packet: " + ex.getMessage());
                continue;
            }

            int type = pkt.getType();
            int seq = pkt.getSeqNum();
            int len = pkt.getLength();

            if (type == TYPE_SOT) {
                System.out.println("[Receiver] Received SOT seq=" + seq);

                // ACK SOT seq=0
                DSPacket ack = new DSPacket(TYPE_ACK, 0, new byte[0]);
                sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);

                // Reset state in case of rerun
                expectedSeq = 1;
                lastInOrder = 0;
            }

            else if (type == TYPE_DATA) {
                System.out.println("[Receiver] Received DATA seq=" + seq + " len=" + len);

                if (seq == expectedSeq) {
                    // in-order: write + advance
                    byte[] payload = pkt.getPayload();
                    fos.write(payload, 0, len);

                    lastInOrder = seq;
                    expectedSeq = (expectedSeq + 1) % MOD;

                    // Send ACK for this seq (Stop-and-Wait ACK behavior)
                    DSPacket ack = new DSPacket(TYPE_ACK, seq, new byte[0]);
                    sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);

                } else {
                    // duplicate/out-of-order: do not write
                    // resend ACK for last in-order packet
                    DSPacket ack = new DSPacket(TYPE_ACK, lastInOrder, new byte[0]);
                    System.out.println("[Receiver] Out-of-order/duplicate. Resend ACK " + lastInOrder);
                    sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);
                }
            }

            else if (type == TYPE_EOT) {
                System.out.println("[Receiver] Received EOT seq=" + seq);

                // ACK EOT with same seq
                DSPacket ack = new DSPacket(TYPE_ACK, seq, new byte[0]);
                sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);

                running = false;
            }

            else if (type == TYPE_ACK) {
                // Receiver should not normally receive ACKs (but ignore if it happens)
                System.out.println("[Receiver] Received unexpected ACK seq=" + seq + " (ignored)");
            }

            else {
                System.out.println("[Receiver] Unknown packet type=" + type + " (ignored)");
            }
        }

        fos.close();
        socket.close();
        System.out.println("[Receiver] Transfer complete. Exiting.");
    }

    private static void sendAckWithChaos(DatagramSocket socket, DSPacket ackPkt,
                                         InetAddress senderAddr, int senderAckPort,
                                         int rn, int ackCount) throws IOException {

        // Chaos rule: drop every rn-th ACK (ackCount is 1-indexed by construction)
        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] CHAOS: Dropped ACK (count=" + ackCount + ", rn=" + rn + ")");
            return;
        }

        byte[] bytes = ackPkt.toBytes();
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, senderAddr, senderAckPort);
        socket.send(udp);

        System.out.println("[Receiver] Sent ACK seq=" + ackPkt.getSeqNum() + " (count=" + ackCount + ")");
    }
}