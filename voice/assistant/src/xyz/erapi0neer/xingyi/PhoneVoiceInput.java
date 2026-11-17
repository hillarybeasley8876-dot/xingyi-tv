package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/** A visible, two-minute local pairing window for a phone's keyboard dictation. */
final class PhoneVoiceInput {
    private final Activity activity;
    private final Consumer<String> onText;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private Session current;
    private AlertDialog dialog;

    PhoneVoiceInput(Activity activity, Consumer<String> onText) {
        this.activity = activity;
        this.onText = onText;
    }

    void start() {
        close();
        final int token = generation;
        Thread thread = new Thread(() -> {
            try (Session session = new Session(lanAddress(), 120_000)) {
                main.post(() -> display(token, session));
                String text = session.receive();
                main.post(() -> {
                    if (token != generation || activity.isDestroyed()) return;
                    close();
                    onText.accept(text);
                });
            } catch (IOException e) {
                main.post(() -> {
                    if (token != generation || activity.isDestroyed()) return;
                    close();
                    String message = e instanceof PairingError ? e.getMessage() : "未能连接手机，请检查电视和手机的局域网连接。";
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        }, "xingyi-phone-input");
        thread.start();
    }

    private void display(int token, Session session) {
        if (token != generation || activity.isDestroyed()) {
            session.close();
            return;
        }
        current = session;
        dialog = new AlertDialog.Builder(activity)
                .setTitle("手机语音")
                .setMessage("手机连接与电视相同的网络，在浏览器地址栏打开\n\n" + session.origin
                        + "\n\n配对码  " + session.pin + "\n\n点输入框，用手机输入法的麦克风说话。核对文字后，点「发送到电视」。配对码在两分钟内可用一次。")
                .setNegativeButton("关闭", (d, which) -> close())
                .setOnCancelListener(d -> close()).show();
    }

    void close() {
        generation++;
        if (current != null) { current.close(); current = null; }
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }

    private static InetAddress lanAddress() throws IOException {
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks != null && networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            String name = network.getName();
            if (!network.isUp() || !(name.startsWith("wlan") || name.startsWith("eth"))) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address;
            }
        }
        throw new PairingError("请先把电视连接到家庭 Wi-Fi 或有线局域网。");
    }

    static final class PairingError extends IOException {
        PairingError(String message) { super(message); }
    }

    /** Single-threaded receiver. The socket is bound to one specific local interface. */
    static final class Session implements Closeable {
        final String pin, origin;
        private final ServerSocket server;
        private final String authority;
        private final long expires;
        private volatile Socket active;
        private int failures;

        Session(InetAddress address, int lifetimeMillis) throws IOException {
            pin = String.format(Locale.US, "%08d", new SecureRandom().nextInt(100_000_000));
            server = new ServerSocket(0, 4, address);
            authority = address.getHostAddress() + ":" + server.getLocalPort();
            origin = "http://" + authority;
            expires = System.nanoTime() + lifetimeMillis * 1_000_000L;
        }

        String receive() throws IOException {
            while (true) {
                if (failures >= 5) throw new PairingError("配对码错误次数已用完，请重新打开手机语音。");
                int remaining = (int)((expires - System.nanoTime()) / 1_000_000L);
                if (remaining <= 0) throw new PairingError("配对码已过期，请重新打开手机语音。");
                server.setSoTimeout(remaining);
                Socket client;
                try { client = server.accept(); }
                catch (SocketTimeoutException expired) { throw new PairingError("配对码已过期，请重新打开手机语音。"); }
                try (Socket socket = client) {
                    active = socket;
                    if (server.isClosed()) throw new SocketException();
                    socket.setSoTimeout(Math.min(2500, remaining));
                    String text = handle(socket);
                    if (text != null) return text;
                } catch (IOException invalidOrDisconnected) {
                    if (server.isClosed()) throw invalidOrDisconnected;
                } finally { active = null; }
            }
        }

        private String handle(Socket socket) throws IOException {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            long readDeadline = Math.min(expires, System.nanoTime() + 4_000_000_000L);
            int tail = 0;
            while (tail != 0x0d0a0d0a) {
                checkTime(readDeadline);
                int next = in.read();
                if (next < 0 || head.size() >= 8192) return null;
                head.write(next);
                tail = (tail << 8) | next;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
            Map<String,String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int split = lines[i].indexOf(':');
                if (split < 1) return null;
                String name = lines[i].substring(0, split).toLowerCase(Locale.US);
                if (headers.put(name, lines[i].substring(split + 1).trim()) != null) return null;
            }
            if (!authority.equals(headers.get("host"))) return reject(socket, "403 Forbidden", "地址不匹配。");
            if (lines[0].equals("GET / HTTP/1.1")) {
                respond(socket, "200 OK", FORM);
                return null;
            }
            if (!lines[0].equals("POST / HTTP/1.1")) return reject(socket, "404 Not Found", "请打开电视显示的首页。");
            // Older mobile browsers may omit Origin but send the exact form page in Referer.
            String from = headers.get("origin");
            if (!(from == null ? (origin + "/").equals(headers.get("referer")) : origin.equals(from)))
                return reject(socket, "403 Forbidden", "请从电视显示的地址提交。");
            String type = headers.get("content-type");
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")
                    || headers.containsKey("transfer-encoding")) return reject(socket, "400 Bad Request", "不支持此提交格式。");
            int size;
            try { size = Integer.parseInt(headers.get("content-length")); }
            catch (NumberFormatException e) { return null; }
            if (size < 1 || size > 8192) return reject(socket, "413 Payload Too Large", "请缩短指令后重试。");
            byte[] body = new byte[size];
            int offset = 0;
            while (offset < size) {
                checkTime(readDeadline);
                int count = in.read(body, offset, size - offset);
                if (count < 0) return null;
                offset += count;
            }
            checkTime(readDeadline);
            Map<String,String> form = new HashMap<>();
            try {
                for (String pair : new String(body, StandardCharsets.US_ASCII).split("&", -1)) {
                    int eq = pair.indexOf('=');
                    if (eq < 1) return null;
                    String key = pair.substring(0, eq);
                    if (!("pin".equals(key) || "text".equals(key))) return null;
                    if (form.put(key, URLDecoder.decode(pair.substring(eq + 1), "UTF-8")) != null) return null;
                }
            } catch (IllegalArgumentException malformedEscape) { return null; }
            if (!pin.equals(form.get("pin"))) {
                failures++;
                return reject(socket, "403 Forbidden", "配对码不正确，请返回核对电视上的八位数字。");
            }
            String text = form.get("text");
            if (text == null || text.trim().isEmpty() || text.length() > 500 || text.matches("(?s).*[\\p{Cc}&&[^\\r\\n\\t]].*"))
                return reject(socket, "400 Bad Request", "请输入 1 至 500 字的指令。");
            // Commit once, even when the phone disconnects before reading the receipt.
            server.close();
            try { respond(socket, "200 OK", "<h1>已发送到电视</h1><p>请查看星仪。需要操作设备时，用电视遥控器确认。</p>"); }
            catch (IOException disconnectedAfterSubmit) { }
            return text.trim();
        }

        private static void checkTime(long deadline) throws SocketTimeoutException {
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException();
        }

        private static String reject(Socket socket, String status, String message) throws IOException {
            respond(socket, status, "<p>" + message + "</p>");
            return null;
        }

        private static void respond(Socket socket, String status, String contents) throws IOException {
            String html = "<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width, initial-scale=1'><title>星仪 · 手机语音</title>"
                    + "<style>body{font:18px/1.55 system-ui,sans-serif;max-width:30rem;margin:2rem auto;padding:0 1.2rem;background:#10151a;color:#eef2f5}"
                    + "input,textarea,button{font:inherit;box-sizing:border-box;width:100%;padding:.65rem;margin:.3rem 0 .9rem;border-radius:.45rem;border:1px solid #67727b}button{background:#cfead8;color:#15271d;font-weight:bold}label{display:block}</style>"
                    + contents + "</html>";
            byte[] data = html.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + data.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\n"
                    + "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
        }

        @Override public void close() {
            try { server.close(); } catch (IOException ignored) { }
            Socket client = active;
            if (client != null) try { client.close(); } catch (IOException ignored) { }
        }
    }

    private static final String FORM = "<h1>给星仪一句指令</h1><p>点指令输入框，再点手机输入法的麦克风。说完核对文字，发送到电视。</p>"
            + "<form action='/' method='post' autocomplete='off'><label>电视上的八位配对码<input name=pin type=text inputmode=numeric pattern='[0-9]{8}' maxlength=8 required></label>"
            + "<label>指令<textarea name=text rows=3 maxlength=500 placeholder='打开网易云音乐，播放林俊杰的《不为谁而作的歌》' required></textarea></label>"
            + "<button type=submit>发送到电视</button></form>";
}
