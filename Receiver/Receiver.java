import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;

public class Receiver {

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

        int expectedSeq = 1;
        int lastInOrder = 0;

        int ackCount = 0; // 1-indexed in ChaosEngine.shouldDrop()

        boolean running = true;

        // Teardown state (for reliable EOT under ACK-loss chaos)
        boolean seenEOT = false;
        int eotSeq = -1;

        // Once we see EOT, we’ll keep listening for retransmitted EOTs until we
        // successfully SEND an EOT ACK (i.e., ChaosEngine does not drop it).
        // To avoid hanging forever if sender gives up, we’ll also allow a few timeouts.
        int teardownTimeoutsRemaining = 0;

        while (running) {
            try {
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

                    // Reset in case of rerun
                    expectedSeq = 1;
                    lastInOrder = 0;

                    // Reset teardown state
                    seenEOT = false;
                    eotSeq = -1;
                    teardownTimeoutsRemaining = 0;
                    socket.setSoTimeout(0); // blocking again
                }

                else if (type == TYPE_DATA) {
                    System.out.println("[Receiver] Received DATA seq=" + seq + " len=" + len);

                    if (seq == expectedSeq) {
                        byte[] payload = pkt.getPayload();
                        fos.write(payload, 0, len);

                        lastInOrder = seq;
                        expectedSeq = (expectedSeq + 1) % MOD;

                        DSPacket ack = new DSPacket(TYPE_ACK, seq, new byte[0]);
                        sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);

                    } else {
                        DSPacket ack = new DSPacket(TYPE_ACK, lastInOrder, new byte[0]);
                        System.out.println("[Receiver] Out-of-order/duplicate. Resend ACK " + lastInOrder);
                        sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);
                    }
                }

                else if (type == TYPE_EOT) {
                    System.out.println("[Receiver] Received EOT seq=" + seq);

                    seenEOT = true;
                    eotSeq = seq;

                    DSPacket ack = new DSPacket(TYPE_ACK, eotSeq, new byte[0]);

                    boolean sent = sendAckWithChaos(socket, ack, senderAddr, senderAckPort, rn, ++ackCount);

                    if (sent) {
                        // We successfully sent an EOT ACK (not dropped by ChaosEngine),
                        // so it’s now safe to close.
                        running = false;
                    } else {
                        // ACK was dropped by ChaosEngine; sender will retransmit EOT.
                        // Stay alive and listen for EOT retransmissions.
                        // Use a timeout so we don’t block forever if sender gives up.
                        socket.setSoTimeout(1000);     // 1 second
                        teardownTimeoutsRemaining = 10; // wait up to ~10 seconds total
                        System.out.println("[Receiver] EOT ACK dropped. Staying alive for retransmitted EOT...");
                    }
                }

                else if (type == TYPE_ACK) {
                    System.out.println("[Receiver] Received unexpected ACK seq=" + seq + " (ignored)");
                }

                else {
                    System.out.println("[Receiver] Unknown packet type=" + type + " (ignored)");
                }

            } catch (SocketTimeoutException te) {
                // Only relevant during teardown when we’ve seen EOT but haven’t successfully sent EOT ACK yet.
                if (seenEOT) {
                    teardownTimeoutsRemaining--;
                    if (teardownTimeoutsRemaining <= 0) {
                        // Sender likely gave up after 3 timeouts; we exit to avoid hanging.
                        System.out.println("[Receiver] Teardown wait expired. Exiting.");
                        running = false;
                    }
                    // else keep waiting for retransmitted EOT
                }
            }
        }

        fos.close();
        socket.close();
        System.out.println("[Receiver] Transfer complete. Exiting.");
    }

    /**
     * Sends ACK unless ChaosEngine decides to drop it.
     * @return true if ACK was actually sent, false if dropped.
     */
    private static boolean sendAckWithChaos(DatagramSocket socket, DSPacket ackPkt,
                                           InetAddress senderAddr, int senderAckPort,
                                           int rn, int ackCount) throws IOException {

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] CHAOS: Dropped ACK (count=" + ackCount + ", rn=" + rn + ")");
            return false;
        }

        byte[] bytes = ackPkt.toBytes();
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, senderAddr, senderAckPort);
        socket.send(udp);

        System.out.println("[Receiver] Sent ACK seq=" + ackPkt.getSeqNum() + " (count=" + ackCount + ")");
        return true;
    }
}