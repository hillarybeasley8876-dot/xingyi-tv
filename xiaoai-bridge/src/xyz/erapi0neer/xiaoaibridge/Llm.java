package xyz.erapi0neer.xiaoaibridge;

import android.content.Context;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Answers a question XiaoAi gave up on, with an OpenAI-compatible chat model, in a form meant to be read aloud. */
final class Llm {
    private static final String PRIVATE = "llm.json";
    private static final String PROMPT = "你是小米电视上的语音助手。小爱同学刚才没能处理用户的这句话，现在由你直接回答。"
            + "回答会被原样朗读出来：用口语化的简体中文，不超过80个字，不用列表、markdown、表情和括号注释，数字用阿拉伯数字。"
            + "涉及实时信息（价格、天气、新闻、比分、日程）时你无法联网查询，要直说，可以给出大致范围或建议去哪里查。"
            + "需要操作电视或家电的要求，你没有这个能力，就简短说明。现在是%s。";

    final String base, model;
    private final String key;

    private Llm(String base, String model, String key) { this.base = base; this.model = model; this.key = key; }

    /** The private config, or null when the PC has not provisioned one yet. */
    static Llm load(Context c) {
        try {
            JSONObject j = new JSONObject(readAll(new File(c.getFilesDir(), PRIVATE)));
            return valid(j) ? new Llm(j.getString("base"), j.getString("model"), j.getString("key")) : null;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Moves files/llm.json from shared storage (pushed by adb) into private storage and deletes the shared copy. */
    static String provision(Context c) {
        File dir = c.getExternalFilesDir(null);
        File shared = dir == null ? null : new File(dir, PRIVATE);
        if (shared == null || !shared.isFile()) return "没有找到待导入的 llm.json";
        try {
            JSONObject j = new JSONObject(readAll(shared));
            if (!valid(j)) return "llm.json 无效(base 必须是 https,model/key 不能为空)";
            File tmp = new File(c.getFilesDir(), PRIVATE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(j.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(c.getFilesDir(), PRIVATE))) return "写入私有目录失败";
            return "已导入模型配置:" + j.getString("model");
        } catch (IOException | JSONException e) {
            return "导入失败:" + e.getClass().getSimpleName();
        } finally {
            shared.delete();
        }
    }

    /** The file's text; also used by BridgeService for the route override. */
    static String readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) {
                if (out.size() + n > 64 * 1024) throw new IOException("too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean valid(JSONObject j) {
        String base = j.optString("base"), model = j.optString("model"), key = j.optString("key");
        return base.startsWith("https://") && !model.isEmpty() && !key.isEmpty() && key.length() < 400;
    }

    /** Blocking; returns the answer text (already trimmed for speech) or throws. */
    String ask(String question, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        HttpsURLConnection c = (HttpsURLConnection) new URL(base + "/v1/chat/completions").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(Math.min(timeoutMs, 20000));
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        try {
            String now = new SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(new Date());
            JSONObject body = new JSONObject().put("model", model).put("stream", true).put("max_tokens", 1500)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", String.format(PROMPT, now)))
                            .put(new JSONObject().put("role", "user").put("content", question)));
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            StringBuilder text = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (System.currentTimeMillis() > deadline) throw new IOException("timeout");
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    try {
                        JSONArray choices = new JSONObject(data).optJSONArray("choices");
                        JSONObject delta = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("delta");
                        if (delta != null && !delta.isNull("content")) text.append(delta.optString("content"));
                    } catch (JSONException ignored) {
                        // keep-alive or a provider-specific event
                    }
                }
            }
            return forSpeech(text.toString());
        } catch (JSONException e) {
            throw new IOException("bad request", e);
        } finally {
            c.disconnect();
        }
    }

    /** Drops hidden reasoning and markup, keeps at most ~120 characters ending on a sentence. */
    static String forSpeech(String raw) {
        String s = raw.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*#`>_~]", "").replaceAll("\\s+", " ").trim();
        if (s.length() <= 120) return s;
        int cut = -1;
        for (int i = 119; i >= 40; i--) if ("。！？!?；;".indexOf(s.charAt(i)) >= 0) { cut = i + 1; break; }
        return cut > 0 ? s.substring(0, cut) : s.substring(0, 120);
    }
}
