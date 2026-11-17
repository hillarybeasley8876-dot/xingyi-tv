package xyz.erapi0neer.xiaoaibridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Streams the main log buffer straight from logd's reader socket. This firmware makes /system/bin/logcat
 * root/shell-only, but the socket is world-connectable and logd serves every uid to a client in the log group
 * (what READ_LOGS grants). The writer's uid comes from logd itself, so it cannot be forged by another app.
 */
final class LogdReader implements AutoCloseable {
    interface Sink { void entry(long epochMillis, int uid, int priority, String tag, String message); }

    private static final int MAX_PACKET = 5 * 1024 + 128;
    private final LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);

    /** lids=0 is the main buffer; tail=1 replays only the newest old entry. */
    void stream(Sink sink) throws IOException {
        socket.connect(new LocalSocketAddress("logdr", LocalSocketAddress.Namespace.RESERVED));
        OutputStream out = socket.getOutputStream();
        out.write("stream lids=0 tail=1".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        byte[] packet = new byte[MAX_PACKET];
        int n;
        while ((n = in.read(packet)) > 0) parse(packet, n, sink);
    }

    /** struct logger_entry (v1..v4): len, hdr_size, pid, tid, sec, nsec, [lid], [uid]; payload = prio, tag\0, msg\0. */
    static void parse(byte[] packet, int n, Sink sink) {
        if (n < 20) return;
        ByteBuffer b = ByteBuffer.wrap(packet, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        int len = b.getShort(0) & 0xffff;
        int hdr = b.getShort(2) & 0xffff;
        if (hdr == 0) hdr = 20;
        if (hdr < 20 || hdr + len > n || len < 3) return;
        long sec = b.getInt(12) & 0xffffffffL, nsec = b.getInt(16) & 0xffffffffL;
        int uid = hdr >= 28 ? b.getInt(24) : -1;
        int priority = packet[hdr];
        int tagEnd = indexOfZero(packet, hdr + 1, hdr + len);
        if (tagEnd < 0) return;
        int msgEnd = indexOfZero(packet, tagEnd + 1, hdr + len);
        if (msgEnd < 0) msgEnd = hdr + len;
        String tag = new String(packet, hdr + 1, tagEnd - hdr - 1, StandardCharsets.UTF_8);
        String message = new String(packet, tagEnd + 1, msgEnd - tagEnd - 1, StandardCharsets.UTF_8);
        sink.entry(sec * 1000L + nsec / 1_000_000L, uid, priority, tag, message);
    }

    private static int indexOfZero(byte[] a, int from, int to) {
        for (int i = from; i < to; i++) if (a[i] == 0) return i;
        return -1;
    }

    @Override public void close() {
        try { socket.shutdownInput(); } catch (IOException | UnsupportedOperationException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
