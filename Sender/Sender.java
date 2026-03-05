import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class Sender {

    // Must match DSPacket constants (byte)
    private static final byte TYPE_SOT  = DSPacket.TYPE_SOT;
    private static final byte TYPE_DATA = DSPacket.TYPE_DATA;
    private static final byte TYPE_ACK  = DSPacket.TYPE_ACK;
    private static final byte TYPE_EOT  = DSPacket.TYPE_EOT;

    private static final int MAX_PAYLOAD = DSPacket.MAX_PAYLOAD_SIZE; // 124
    private static final int MOD = 128;

    public static void main(String[] args) throws Exception {

        if (args.length != 5 && args.length != 6) {
            System.out.println("Usage:");
            System.out.println("java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            return;
        }

        String rcvIp = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);

        boolean useGBN = (args.length == 6);
        int windowSize = 0;
        if (useGBN) {
            windowSize = Integer.parseInt(args[5]);
            if (windowSize <= 0 || windowSize > 128 || (windowSize % 4) != 0) {
                System.out.println("Error: window_size must be a positive multiple of 4 and <= 128.");
                return;
            }
        }

        InetAddress rcvAddr = InetAddress.getByName(rcvIp);

        // Bind to sender_ack_port so Receiver can send ACKs back here
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeoutMs);

        List<byte[]> chunks = readFileChunks(inputFile);

        System.out.println("[Sender] Starting handshake (SOT -> ACK)");

        long startTimeNs = System.nanoTime();

        // SOT: type=0, seq=0, length=0 (payload empty)
        DSPacket sot = new DSPacket(TYPE_SOT, 0, new byte[0]);

        if (!sendAndWaitForExactAck(socket, sot, 0, rcvAddr, rcvDataPort)) {
            socket.close();
            return;
        }

        System.out.println("[Sender] Handshake established.");

        // Empty file case: send EOT seq=1 immediately after handshake
        if (chunks.isEmpty()) {
            int eotSeq = 1;
            System.out.println("[Sender] Empty file. Sending EOT seq=" + eotSeq);

            DSPacket eot = new DSPacket(TYPE_EOT, eotSeq, new byte[0]);

            if (!sendAndWaitForExactAck(socket, eot, eotSeq, rcvAddr, rcvDataPort)) {
                socket.close();
                return;
            }

            printTotalTime(startTimeNs);
            socket.close();
            return;
        }

        // Phase 2: Data Transfer
        if (!useGBN) {
            stopAndWait(socket, chunks, rcvAddr, rcvDataPort);
        } else {
            goBackN(socket, chunks, windowSize, rcvAddr, rcvDataPort);
        }

        // Phase 3: EOT
        int eotSeq = (chunks.size() + 1) % MOD;  // EOT = last data seq + 1 (mod 128)
        System.out.println("[Sender] Sending EOT seq=" + eotSeq);

        DSPacket eot = new DSPacket(TYPE_EOT, eotSeq, new byte[0]);

        if (!sendAndWaitForExactAck(socket, eot, eotSeq, rcvAddr, rcvDataPort)) {
            socket.close();
            return;
        }

        printTotalTime(startTimeNs);
        socket.close();
    }

    // -------------------------
    // Stop-and-Wait (RDT 3.0)
    // -------------------------
    private static void stopAndWait(DatagramSocket socket, List<byte[]> chunks,
                                    InetAddress rcvAddr, int rcvDataPort) throws IOException {

        System.out.println("[Sender] Using Stop-and-Wait");

        int seq = 1; // first DATA seq = 1
        for (byte[] payload : chunks) {

            DSPacket dataPkt = new DSPacket(TYPE_DATA, seq, payload);
            System.out.println("[Sender] Send DATA seq=" + seq + " len=" + payload.length);

            if (!sendAndWaitForExactAck(socket, dataPkt, seq, rcvAddr, rcvDataPort)) {
                System.out.println("[Sender] Unable to transfer file.");
                System.exit(0);
            }

            seq = (seq + 1) % MOD;
        }
    }

    // -------------------------
    // Go-Back-N (GBN)
    // -------------------------
    private static void goBackN(DatagramSocket socket, List<byte[]> chunks, int windowSize,
                                InetAddress rcvAddr, int rcvDataPort) throws IOException {

        System.out.println("[Sender] Using Go-Back-N, window=" + windowSize);

        int base = 0;       // oldest unacked index
        int nextToSend = 0; // next index to send

        int consecutiveTimeoutsSameBase = 0;
        int lastBaseAtTimeout = -1;

        while (base < chunks.size()) {

            // Fill the window
            while (nextToSend < chunks.size() && nextToSend < base + windowSize) {

                // Reordering rule for every group of 4 consecutive packets:
                // i+2 → i → i+3 → i+1
                if ((nextToSend % 4 == 0) &&
                    (nextToSend + 3 < chunks.size()) &&
                    (nextToSend + 3 < base + windowSize)) {

                    sendPermutedGroupOf4(socket, chunks, nextToSend, rcvAddr, rcvDataPort);
                    nextToSend += 4;
                } else {
                    int seq = (nextToSend + 1) % MOD;
                    DSPacket pkt = new DSPacket(TYPE_DATA, seq, chunks.get(nextToSend));
                    sendPacket(socket, pkt, rcvAddr, rcvDataPort);
                    System.out.println("[Sender] Sent DATA seq=" + seq + " (idx=" + nextToSend + ")");
                    nextToSend++;
                }
            }

            // Wait for cumulative ACK
            try {
                int ackSeq = receiveAckSeq(socket);
                if (ackSeq < 0) continue; // ignored non-ACK

                int newBase = advanceBaseFromCumulativeAck(base, chunks.size(), ackSeq);

                if (newBase > base) {
                    System.out.println("[Sender] Cumulative ACK " + ackSeq + " => base " + base + " -> " + newBase);
                    base = newBase;

                    // Progress happened => reset critical failure counter
                    consecutiveTimeoutsSameBase = 0;
                    lastBaseAtTimeout = -1;
                } else {
                    System.out.println("[Sender] ACK " + ackSeq + " (no base advance)");
                }

            } catch (SocketTimeoutException te) {

                if (lastBaseAtTimeout == base) consecutiveTimeoutsSameBase++;
                else {
                    lastBaseAtTimeout = base;
                    consecutiveTimeoutsSameBase = 1;
                }

                System.out.println("[Sender] TIMEOUT at base=" + base + " (count=" + consecutiveTimeoutsSameBase + ") => retransmit window");

                if (consecutiveTimeoutsSameBase >= 3) {
                    System.out.println("[Sender] Unable to transfer file.");
                    System.exit(0);
                }

                // Retransmit window from base
                for (int i = base; i < chunks.size() && i < base + windowSize; i++) {
                    int seq = (i + 1) % MOD;
                    DSPacket pkt = new DSPacket(TYPE_DATA, seq, chunks.get(i));
                    sendPacket(socket, pkt, rcvAddr, rcvDataPort);
                    System.out.println("[Sender] Retransmit DATA seq=" + seq + " (idx=" + i + ")");
                }
            }
        }
    }

    // Send a group of 4 in required order: i+2, i, i+3, i+1
    private static void sendPermutedGroupOf4(DatagramSocket socket, List<byte[]> chunks, int i,
                                             InetAddress rcvAddr, int rcvDataPort) throws IOException {

        int[] order = new int[] { i + 2, i, i + 3, i + 1 };

        for (int idx : order) {
            int seq = (idx + 1) % MOD;
            DSPacket pkt = new DSPacket(TYPE_DATA, seq, chunks.get(idx));
            sendPacket(socket, pkt, rcvAddr, rcvDataPort);
            System.out.println("[Sender] Sent (perm) DATA seq=" + seq + " (idx=" + idx + ")");
        }
    }

    // -------------------------
    // Networking Helpers
    // -------------------------
    private static void sendPacket(DatagramSocket socket, DSPacket packet,
                                   InetAddress addr, int port) throws IOException {

        byte[] bytes = packet.toBytes(); // ✅ correct for your DSPacket
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, addr, port);
        socket.send(udp);
    }

    // Wait for a specific ACK seq, retransmitting on timeout.
    // Critical failure: 3 consecutive timeouts with no progress => terminate.
    private static boolean sendAndWaitForExactAck(DatagramSocket socket, DSPacket pkt, int expectedAckSeq,
                                                  InetAddress rcvAddr, int rcvDataPort) throws IOException {

        int consecutiveTimeouts = 0;

        while (true) {
            sendPacket(socket, pkt, rcvAddr, rcvDataPort);

            try {
                int ackSeq = receiveAckSeq(socket);
                if (ackSeq < 0) continue; // ignored non-ACK

                if (ackSeq == expectedAckSeq) {
                    System.out.println("[Sender] ACK " + ackSeq + " received (OK)");
                    return true;
                } else {
                    System.out.println("[Sender] ACK " + ackSeq + " received (unexpected, want " + expectedAckSeq + ")");
                }

            } catch (SocketTimeoutException te) {
                consecutiveTimeouts++;
                System.out.println("[Sender] TIMEOUT waiting for ACK " + expectedAckSeq + " (count=" + consecutiveTimeouts + ") => retransmit");

                if (consecutiveTimeouts >= 3) {
                    System.out.println("[Sender] Unable to transfer file.");
                    return false;
                }
            }
        }
    }

    // Receive an ACK and return its seq number; return -1 if non-ACK received.
    private static int receiveAckSeq(DatagramSocket socket) throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buf, buf.length);
        socket.receive(udp);

        DSPacket p = new DSPacket(buf);

        int type = p.getType();      // returns byte but promoted to int
        int seq = p.getSeqNum();

        if (type != TYPE_ACK) {
            System.out.println("[Sender] Non-ACK received (type=" + type + "), ignoring");
            return -1;
        }
        return seq;
    }

    // Convert cumulative ACK seq to new base index safely within window (<=128)
    private static int advanceBaseFromCumulativeAck(int base, int chunksSize, int ackSeq) {

        int baseSeq = (base + 1) % MOD;
        int prevSeq = (baseSeq - 1 + MOD) % MOD;

        // If receiver is just re-ACKing last in-order (no progress)
        if (ackSeq == prevSeq) return base;

        // Distance forward (0..127) from baseSeq to ackSeq
        int dist = (ackSeq - baseSeq + MOD) % MOD;

        // ackedIndex is base + dist (0-based)
        int ackedIndex = base + dist;

        // base should move to first unacked: ackedIndex + 1
        int newBase = ackedIndex + 1;

        // Clamp to file length
        if (newBase > chunksSize) newBase = chunksSize;

        return newBase;
    }

    // -------------------------
    // File Helpers
    // -------------------------
    private static List<byte[]> readFileChunks(String inputFile) throws IOException {
        File f = new File(inputFile);
        if (!f.exists()) throw new IOException("Input file not found: " + inputFile);

        List<byte[]> chunks = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[MAX_PAYLOAD];
            int n;
            while ((n = fis.read(buf)) != -1) {
                if (n == 0) continue;
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    private static void printTotalTime(long startTimeNs) {
        long endTimeNs = System.nanoTime();
        double seconds = (endTimeNs - startTimeNs) / 1_000_000_000.0;
        System.out.printf("[Sender] Total Transmission Time: %.2f seconds%n", seconds);
    }
}