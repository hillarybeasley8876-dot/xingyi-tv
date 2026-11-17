package xyz.erapi0neer.xingyi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/** Short-lived Streamable HTTP endpoint for an app-targeted, read-only assistant request. */
public final class TvMcpHttp implements Closeable {
    public interface Backend { JSONObject read(String name) throws Exception; }
    public interface SubmitBackend { JSONObject submit(String requestId, String text, String nonce) throws Exception; }
    public static final String VERSION = "2025-11-25";
    private static final int MAX_HEAD = 8192, MAX_BODY = 8192;
    private static final long LIFE_MS = 45000;
    private final ServerSocket server;
    private final Timer expiry;
    private final String token;
    private final String authority;
    private final Backend backend;
    private final SubmitBackend submitter;
    private final long until;
    private volatile boolean closed;
    private Socket client;

    public TvMcpHttp(Backend backend) throws IOException {
        this(backend, null);
    }

    /** A separate session for exactly one pre-authorized handoff. No mutation is exposed by the reader endpoint. */
    public static TvMcpHttp handoff(SubmitBackend submitter) throws IOException {
        return new TvMcpHttp(null, submitter);
    }

    private TvMcpHttp(Backend backend, SubmitBackend submitter) throws IOException {
        if (backend == null && submitter == null) throw new IllegalArgumentException("Missing tool backend");
        this.backend = backend;
        this.submitter = submitter;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : random) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        token = hex.toString();
        server = new ServerSocket(0, 4, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        authority = "127.0.0.1:" + server.getLocalPort();
        until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIFE_MS);
        expiry = new Timer("xingyi-mcp-expiry", true);
        expiry.schedule(new TimerTask() { public void run() { close(); } }, LIFE_MS);
        Thread listener = new Thread(this::serve, "xingyi-mcp-http");
        listener.setDaemon(true);
        listener.start();
    }

    public int port() { return server.getLocalPort(); }
    public String url() { return "http://" + authority + "/mcp"; }
    public String bearer() { return token; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        expiry.cancel();
        try { server.close(); } catch (IOException ignored) { }
        if (client != null) try { client.close(); } catch (IOException ignored) { }
    }

    private void serve() {
        try {
            while (!closed) {
                try (Socket incoming = server.accept()) {
                    synchronized (this) {
                        if (closed) return;
                        client = incoming;
                    }
                    handle(incoming);
                } catch (IOException ignored) {
                    if (server.isClosed()) return;
                } finally {
                    synchronized (this) { client = null; }
                }
            }
        } finally { close(); }
    }

    private void handle(Socket socket) throws IOException {
        long deadline = Math.min(until, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
        InputStream in = new BufferedInputStream(socket.getInputStream());
        try {
            String[] lines = readHead(socket, in, deadline);
            if (lines.length == 0) throw new Rejection(400);
            String[] request = lines[0].split(" ", -1);
            if (request.length != 3 || !request[2].equals("HTTP/1.1")) throw new Rejection(400);
            Map<String,String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1) throw new Rejection(400);
                String name = lines[i].substring(0,colon).toLowerCase(Locale.ROOT);
                if (!name.matches("[!#$%&'*+.^_\u0060|~a-z0-9-]+") ||
                    headers.put(name,lines[i].substring(colon + 1).trim()) != null) throw new Rejection(400);
            }
            if (!authority.equals(headers.get("host")) || headers.containsKey("origin")) throw new Rejection(403);
            String auth = headers.get("authorization");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                    auth.getBytes(StandardCharsets.US_ASCII))) throw new Rejection(401);
            if (!request[1].equals("/mcp")) throw new Rejection(404);
            if (!request[0].equals("POST")) throw new Rejection(405);
            String version = headers.get("mcp-protocol-version");
            if (version != null && !VERSION.equals(version)) throw new Rejection(400);
            String type = headers.get("content-type");
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))
                throw new Rejection(415);
            if (headers.containsKey("transfer-encoding")) throw new Rejection(400);
            String size = headers.get("content-length");
            if (size == null || !size.matches("[0-9]{1,9}")) throw new Rejection(400);
            int n = Integer.parseInt(size);
            if (n > MAX_BODY) throw new Rejection(413);
            byte[] body = new byte[n];
            for (int offset = 0; offset < n;) {
                setRemaining(socket, deadline);
                int count = in.read(body,offset,n-offset);
                if (count < 0) throw new Rejection(400);
                offset += count;
            }
            JSONObject answer;
            try {
                String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
                if (tooDeep(json)) throw new JSONException("Nesting limit");
                JSONTokener tokener = new JSONTokener(json);
                Object parsed = tokener.nextValue();
                if (tokener.nextClean() != 0) throw new JSONException("Trailing input");
                answer = parsed instanceof JSONObject ? dispatch((JSONObject) parsed)
                        : error(null,-32600,"Invalid Request");
            } catch (JSONException | CharacterCodingException e) {
                try { answer = error(null,-32700,"Parse error"); }
                catch (JSONException impossible) { throw new IOException(impossible); }
            }
            reply(socket, answer == null ? 202 : 200, answer == null ? "" : answer.toString());
        } catch (Rejection e) { reply(socket, e.status, ""); }
        catch (SocketTimeoutException e) { reply(socket, 408, ""); }
    }

    private static String[] readHead(Socket socket, InputStream in, long deadline) throws IOException, Rejection {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int tail = 0;
        while (head.size() < MAX_HEAD) {
            setRemaining(socket,deadline);
            int b = in.read();
            if (b < 0 || b > 126 || (b < 32 && b != '\n' && b != '\r' && b != '\t')) throw new Rejection(400);
            head.write(b);
            tail = (tail << 8) | b;
            if (tail == 0x0d0a0d0a) return head.toString("US-ASCII").split("\r\n");
        }
        throw new Rejection(431);
    }

    private static void setRemaining(Socket socket, long deadline) throws IOException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms < 1) throw new SocketTimeoutException();
        socket.setSoTimeout((int) ms);
    }

    // org.json parses nested values recursively; bound depth independently of body size.
    private static boolean tooDeep(String json) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '{' || c == '[') {
                if (++depth > 32) return true;
            } else if (c == '}' || c == ']') depth--;
        }
        return false;
    }

    private JSONObject dispatch(JSONObject rpc) throws JSONException {
        Object id = rpc.opt("id");
        boolean goodId = id instanceof String || id instanceof Integer || id instanceof Long;
        if (!"2.0".equals(rpc.opt("jsonrpc")) || (rpc.has("id") && !goodId))
            return error(null,-32600,"Invalid Request");
        if (!(rpc.opt("method") instanceof String)) {
            if (!rpc.has("method") && goodId && rpc.has("result") != rpc.has("error")) return null;
            return error(id,-32600,"Invalid Request");
        }
        if (!rpc.has("id")) return null; // notifications have no result and never dispatch a tool
        JSONObject params = rpc.optJSONObject("params");
        if (rpc.has("params") && params == null) return error(id,-32602,"Invalid params");
        JSONObject value;
        switch (rpc.getString("method")) {
            case "initialize":
                if (params == null || !(params.opt("protocolVersion") instanceof String)
                        || params.optJSONObject("clientInfo") == null || params.optJSONObject("capabilities") == null)
                    return error(id,-32602,"Invalid initialize parameters");
                value = new JSONObject().put("protocolVersion",VERSION)
                    .put("serverInfo",new JSONObject().put("name","xingyi-tv-local").put("version","1.0"))
                    .put("capabilities",new JSONObject().put("tools",new JSONObject()));
                break;
            case "ping": value = new JSONObject(); break;
            case "tools/list":
                JSONArray tools = new JSONArray();
                if (submitter != null) tools.put(submitTool());
                else tools.put(tool("tv_status","Read the TV media volume, range and mute state."))
                          .put(tool("sleep_timer","Read the current sleep timer status."));
                value = new JSONObject().put("tools",tools);
                break;
            case "tools/call":
                if (params == null || !(params.opt("name") instanceof String))
                    return error(id,-32602,"Missing tool name");
                String name = params.getString("name");
                if (closed || System.nanoTime() >= until) return error(id,-32603,"Endpoint expired");
                if (submitter != null) {
                    if (!"xingyi_submit".equals(name)) return error(id,-32602,"Unknown tool");
                    JSONObject form = params.optJSONObject("arguments");
                    if (form == null || form.length() != 3 || !(form.opt("request_id") instanceof String)
                            || !(form.opt("text") instanceof String) || !(form.opt("nonce") instanceof String))
                        return error(id,-32602,"Invalid handoff arguments");
                    try {
                        JSONObject response = submitter.submit(form.getString("request_id"),form.getString("text"),form.getString("nonce"));
                        if (response == null) throw new IOException();
                        value = output(response.toString(),!response.optBoolean("accepted")).put("structuredContent",response);
                    } catch (Exception rejected) { value = output("Handoff not accepted.",true); }
                    break;
                }
                if (!"tv_status".equals(name) && !"sleep_timer".equals(name))
                    return error(id,-32602,"Unknown tool");
                JSONObject args = params.optJSONObject("arguments");
                if (params.has("arguments") && (args == null || args.length() != 0))
                    return error(id,-32602,"This tool takes no arguments");
                try {
                    JSONObject reading = backend.read(name);
                    if (reading == null) throw new IOException("Unavailable");
                    value = output(reading.toString(),false).put("structuredContent",reading);
                } catch (Exception e) { value = output("The TV could not provide this reading.",true); }
                break;
            default: return error(id,-32601,"Method not found");
        }
        return envelope(id).put("result",value);
    }

    private static JSONObject tool(String name,String description) throws JSONException {
        return new JSONObject().put("name",name).put("description",description)
            .put("inputSchema",new JSONObject().put("type","object").put("properties",new JSONObject())
                    .put("additionalProperties",false))
            .put("annotations",new JSONObject().put("readOnlyHint",true).put("idempotentHint",true)
                    .put("destructiveHint",false).put("openWorldHint",false));
    }
    private static JSONObject submitTool() throws JSONException {
        JSONObject props = new JSONObject();
        for (String field : new String[]{"request_id","text","nonce"}) {
            props.put(field,new JSONObject().put("type","string").put("minLength",1).put("maxLength","text".equals(field)?320:64));
        }
        return new JSONObject().put("name","xingyi_submit").put("description","Submit the one original question bound to this session.")
                .put("inputSchema",new JSONObject().put("type","object").put("properties",props)
                        .put("required",new JSONArray().put("request_id").put("text").put("nonce")).put("additionalProperties",false))
                .put("annotations",new JSONObject().put("readOnlyHint",false).put("destructiveHint",false).put("openWorldHint",true));
    }
    private static JSONObject output(String text,boolean failed) throws JSONException {
        return new JSONObject().put("content",new JSONArray().put(new JSONObject()
                .put("type","text").put("text",text))).put("isError",failed);
    }
    private static JSONObject envelope(Object id) throws JSONException {
        return new JSONObject().put("jsonrpc","2.0").put("id",id == null ? JSONObject.NULL : id);
    }
    private static JSONObject error(Object id,int code,String message) throws JSONException {
        return envelope(id).put("error",new JSONObject().put("code",code).put("message",message));
    }
    private static class Rejection extends Exception {
        final int status;
        Rejection(int status) { this.status=status; }
    }
    private static void reply(Socket socket,int status,String body) throws IOException {
        String extra = "";
        String reason;
        switch (status) {
            case 200: reason="OK"; break;
            case 202: reason="Accepted"; break;
            case 400: reason="Bad Request"; break;
            case 401: reason="Unauthorized"; extra="WWW-Authenticate: Bearer\r\n"; break;
            case 403: reason="Forbidden"; break;
            case 404: reason="Not Found"; break;
            case 405: reason="Method Not Allowed"; extra="Allow: POST\r\n"; break;
            case 408: reason="Request Timeout"; break;
            case 413: reason="Payload Too Large"; break;
            case 415: reason="Unsupported Media Type"; break;
            case 431: reason="Request Header Fields Too Large"; break;
            default: throw new IOException("Unexpected HTTP status");
        }
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String headers = "HTTP/1.1 "+status+" "+reason+"\r\n"+extra+"Connection: close\r\nCache-Control: no-store\r\n"
                +(data.length==0?"":"Content-Type: application/json\r\n")+"Content-Length: "+data.length+"\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
