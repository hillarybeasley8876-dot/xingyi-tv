package xyz.erapi0neer.xingyi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Real loopback connections exercise the same pairing server used by the TV. */
public final class PhoneVoiceInputTest {
    static String exchange(PhoneVoiceInput.Session s, String method, String host, String origin, String body) throws Exception {
        URI uri = new URI(s.origin);
        try (Socket client = new Socket(uri.getHost(), uri.getPort())) {
            client.setSoTimeout(2000);
            String request = method + " / HTTP/1.1\r\nHost: " + host + "\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "
                    + body.length() + "\r\nConnection: close\r\n\r\n" + body;
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line).append('\n');
            return response.toString();
        }
    }
    static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
    }
    static Future<String> receive(PhoneVoiceInput.Session s) {
        FutureTask<String> result = new FutureTask<>(s::receive);
        Thread t = new Thread(result);
        t.setDaemon(true); t.start();
        return result;
    }
    static boolean stopped(Future<String> job) throws Exception {
        try { job.get(2, TimeUnit.SECONDS); return false; }
        catch (ExecutionException e) { return e.getCause() instanceof IOException; }
    }
    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (PhoneVoiceInput.Session s = new PhoneVoiceInput.Session(loopback, 10000)) {
            Future<String> accepted = receive(s);
            String host = new URI(s.origin).getAuthority();
            String command = "打开网易云音乐 播放林俊杰的《不为谁而作的歌》";
            String body = "pin=" + s.pin + "&text=" + URLEncoder.encode(command, "UTF-8");
            String form = exchange(s, "GET", host, null, "");
            check(form.contains("200 OK") && form.contains("发送到电视") && !form.contains(s.pin), "public page hides PIN");
            check(exchange(s,"POST","attacker.test",s.origin,body).contains("403"), "reject alternate Host");
            check(exchange(s,"POST",host,"http://attacker.test",body).contains("403"), "reject cross-origin form");
            check(exchange(s,"POST",host,null,body).contains("403"), "reject missing proof of source");
            check(exchange(s,"POST",host,s.origin,"pin=X&text=test").contains("403"), "reject wrong PIN");
            check(exchange(s,"POST",host,s.origin,"pin="+s.pin+"&text=+").contains("400"), "reject empty command");
            check(!accepted.isDone(), "invalid requests do not dispatch text");
            check(exchange(s,"POST",host,s.origin,body).contains("200 OK"), "accept paired submission");
            check(accepted.get(2, TimeUnit.SECONDS).equals(command), "preserve Chinese text");
            try {
                exchange(s,"POST",host,s.origin,body); throw new AssertionError("replay connected");
            } catch (ConnectException expected) { }
        }
        try (PhoneVoiceInput.Session s = new PhoneVoiceInput.Session(loopback, 2000)) {
            Future<String> accepted = receive(s);
            for (int i=0; i<5; i++)
                check(exchange(s,"POST",new URI(s.origin).getAuthority(),s.origin,"pin=x&text=hi").contains("403"), "limit");
            check(stopped(accepted), "session terminates after repeated bad PINs");
        }
        try (PhoneVoiceInput.Session s = new PhoneVoiceInput.Session(loopback, 100)) {
            check(stopped(receive(s)), "expired pairing emits no text");
        }
        try (PhoneVoiceInput.Session s = new PhoneVoiceInput.Session(loopback, 120000)) {
            Future<String> accepted = receive(s);
            s.close();
            check(stopped(accepted), "cancel closes accept");
        }
        System.out.println("PASS phone pairing HTTP: source, PIN, Chinese, one-shot, attempts, expiry, cancel");
    }
}
