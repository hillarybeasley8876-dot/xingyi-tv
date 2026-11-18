package xyz.erapi0neer.xingyi.agent.tv;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import xyz.erapi0neer.xingyi.agent.KeyNames;

/**
 * 电视自带的遥控服务：{@code GET http://<电视的局域网 IP>:6095/controller?action=keyevent&keycode=<名字>}，就是小米手机
 * 遥控用的那个服务。2026-11-10 01:44–01:47 实测：31–58 毫秒就回 {@code {"status":0,…}}，键像遥控器按下去一样注入（窗口
 * 管理器记 {@code Virtual KeyEvent} DOWN，约 185 毫秒后 UP）；本机 ADB 的 {@code input keyevent} 每次约 0.85 秒。
 *
 * <p>为什么用 {@link Socket} 自己写请求，不用 {@code java.net} 的 HTTP 客户端：接力的清单写着
 * {@code usesCleartextTraffic="false"}（有意为之，不改），Android 会拒绝明文的 {@code http://} 网址。
 *
 * <p>服务只听局域网地址（{@code ::ffff:192.0.2.10:6095}），不听 127.0.0.1，所以地址从 {@link NetworkInterface} 找。
 * 任何失败都只回 false，并且 60 秒内不再试它（免得每个键白等一次连接超时）。从不抛异常。
 */
final class RemoteKeys {
    /** 电视自己的遥控服务端口。 */
    private static final int PORT = 6095;
    /** 连接 800 毫秒、读 1500 毫秒：实测一次 31–58 毫秒，超了就是服务不在。 */
    private static final int CONNECT_MS = 800, READ_MS = 1500;
    /** 回答最多读这么多字节（实测的回执不到 100 字节）。 */
    private static final int MAX_BYTES = 4096;
    /** 电视的局域网地址和「服务坏了」各记 60 秒。 */
    private static final long IP_MS = 60_000L, DOWN_MS = 60_000L;

    private static volatile String ip;
    private static volatile long ipAt;
    private static volatile long downUntil;

    private RemoteKeys() { }

    /** 按一次这个键（{@link KeyNames#of} 的名字）。成功（服务回了 status 0）才 true。 */
    static boolean press(String name) {
        if (name == null || name.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (now < downUntil) return false;
        String host = lanIp(now);
        if (host == null) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, PORT), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            OutputStream out = s.getOutputStream();
            out.write(("GET /controller?action=keyevent&keycode=" + name + " HTTP/1.0\r\nHost: " + host + ":" + PORT
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            boolean ok = KeyNames.ok(read(s.getInputStream()));
            if (!ok) downUntil = now + DOWN_MS;
            return ok;
        } catch (Exception e) {
            downUntil = now + DOWN_MS;
            return false;
        }
    }

    /** 回答，最多 {@link #MAX_BYTES} 字节；服务用 Connection: close 收尾，读到 -1 为止。 */
    private static String read(InputStream in) throws Exception {
        byte[] buf = new byte[MAX_BYTES];
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        return new String(buf, 0, n, "UTF-8");
    }

    /** 电视的局域网 IPv4（开着、非回环、site-local 的那个），60 秒找一次。 */
    private static String lanIp(long now) {
        String cached = ip;
        if (cached != null && now - ipAt < IP_MS) return cached;
        String found = findIp();
        ip = found;
        ipAt = now;
        return found;
    }

    /** wlan / eth first: the TV also has p2p0 (Wi-Fi Direct, used by casting), whose private address the service does
     * not listen on; the order NetworkInterface lists them in is not fixed. */
    private static String findIp() {
        String other = null;
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                boolean lan = n.getName().startsWith("wlan") || n.getName().startsWith("eth");
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (lan) return a.getHostAddress();
                    if (other == null) other = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return other;
    }
}
