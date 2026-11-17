package xyz.erapi0neer.xingyi.agent;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Streamed answers (2026-11-06 overnight pass): the SSE stream put back together (Sse, from the gateway's own streams
 * in tests/agent/fixtures/sse-*.txt), and the loop handing a plain answer to the host's reader as it grows, keeping the
 * page already read when a long answer is sent back to be shortened. */
final class StreamChecks {
    private StreamChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static void run() {
        try {
            text();
            tools();
            pieces();
            early();
            earlyLoop();
            loop();
        } catch (Exception e) {
            check(false, "stream checks threw " + e);
        }
    }

    /** What may be read while the model is still writing it (2026-11-09: first speech median 5.4 s on a whole answer). */
    private static void early() {
        // a two-page answer still gives its first page, as before
        String twoPages = sentences("", 6);
        List<String> pages = Speech.pages(twoPages, Speech.PAGE_MAX);
        check(pages.size() >= 2 && pages.get(0).equals(Speech.early(twoPages, Speech.PAGE_MAX)),
                "a two-page answer gives its first page: " + Speech.early(twoPages, Speech.PAGE_MAX));

        // one page so far, but its first sentence is whole and the model has begun the next: read that sentence now
        String blue = "天空是蓝的，因为阳光里的蓝光被空气散射得最多。傍晚";
        check("天空是蓝的，因为阳光里的蓝光被空气散射得最多。".equals(Speech.early(blue, Speech.PAGE_MAX)),
                "the first sentence goes out at once: " + Speech.early(blue, Speech.PAGE_MAX));
        check(Speech.early("天空是蓝的，因为阳光里的蓝光被空气散射得最多。", Speech.PAGE_MAX) == null,
                "the same without anything after it waits for the end");
        check(Speech.early("好的。然后", Speech.PAGE_MAX) == null, "「好的。」 alone is not read out");
        check(Speech.early("天空是蓝的，因为阳光里的蓝", Speech.PAGE_MAX) == null, "a sentence still being written is not read");
        check(Speech.early(null, Speech.PAGE_MAX) == null && Speech.early("", Speech.PAGE_MAX) == null, "nothing yet, nothing read");

        // markdown and a list are cleaned the way clean() cleans them, and what goes out is the answer's own head
        String stream = "**要点**：\n- 第一条内容在这里，说明它为什么重要。\n- 第二";
        String finished = "**要点**：\n- 第一条内容在这里，说明它为什么重要。\n- 第二条内容也在。";
        String want = "要点：第一条内容在这里，说明它为什么重要。";
        check(want.equals(Speech.early(stream, Speech.PAGE_MAX)), "markdown and a list are cleaned: " + Speech.early(stream, Speech.PAGE_MAX));
        check(Speech.clean(finished).startsWith(want), "the early text is a prefix of clean(finished)");

        // a first sentence of nearly a page still goes out whole, not cut short at the page size
        StringBuilder near = new StringBuilder();
        while (near.length() < Speech.PAGE_MAX - 20) near.append("这是一句很长的话");
        String longFirst = near + "。后面还有一句。";
        check(Speech.pages(longFirst, Speech.PAGE_MAX).size() == 1 && (near + "。").equals(Speech.early(longFirst, Speech.PAGE_MAX)),
                "a first sentence near a page is read whole: " + Speech.early(longFirst, Speech.PAGE_MAX));
    }

    /** The host's reader as AgentEngine has it: one early read per task, whatever Speech.early gives. */
    static final class EarlyReader implements AgentLoop.Drafts {
        String read = "";
        int reads;
        @Override public void text(Ledger task, String soFar) {
            if (!read.isEmpty()) return;
            String take = Speech.early(soFar, Speech.PAGE_MAX);
            if (take != null) { read = take; reads++; }
        }
        @Override public String read(Ledger task) { return read; }
    }

    /** A short answer read from its first sentence on: the loop still ends on the model's own text. */
    private static void earlyLoop() throws Exception {
        Streaming model = new Streaming();
        String answer = "天空是蓝的，因为阳光里的蓝光被空气散射得最多。傍晚的时候会变红。";
        model.replies.add(new JSONObject().put("role", "assistant").put("content", answer));
        EarlyReader reader = new EarlyReader();
        AgentLoop l = new AgentLoop(AgentCoreTest.contract, model, Collections.<Tool>emptyList(), null, (w, t) -> true, () -> 1L, null);
        l.drafts(reader);
        Ledger t = l.run(new AgentLoop.Request("天空为什么是蓝的", "xiaoai"));
        check(t.state == Ledger.State.ANSWERED && answer.equals(t.reply), "a short streamed answer still ends on the model's text");
        check(reader.reads == 1 && "天空是蓝的，因为阳光里的蓝光被空气散射得最多。".equals(reader.read)
                        && Speech.clean(t.reply).startsWith(reader.read),
                "its first sentence was read early and is the answer's head: " + reader.read);
    }

    static final class Heard implements Sse.Sink {
        final List<String> texts = new ArrayList<>();
        int calls;
        @Override public void text(String soFar) { texts.add(soFar); }
        @Override public void calls() { calls++; }
    }

    private static Sse feed(String fixture, Heard heard) throws Exception {
        Sse sse = new Sse(heard);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("tests/agent/fixtures/" + fixture), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) sse.line(line);
        }
        return sse;
    }

    static void text() throws Exception {
        Heard heard = new Heard();
        Sse sse = feed("sse-text.txt", heard);
        JSONObject env = sse.envelope();
        JSONObject choice = env.getJSONArray("choices").getJSONObject(0), m = choice.getJSONObject("message");
        String want = "天空看起来是蓝色的，是因为阳光进入大气后，波长较短的蓝光更容易被空气分子散射到各个方向。散射后的蓝光从四面八方进入我们的眼睛，所以天空呈现蓝色。";
        check(sse.done() && "stop".equals(choice.getString("finish_reason")), "the text stream finished");
        check(want.equals(m.getString("content")), "content joined: " + m.optString("content"));
        check("assistant".equals(m.getString("role")) && "MiniMax AI".equals(m.getString("name")) && m.has("audio_content")
                && m.getString("reasoning_content").startsWith("We need answer"), "the message keeps the fields a plain answer has");
        check(!m.has("tool_calls") && env.getJSONObject("base_resp").getInt("status_code") == 0, "no calls, base_resp kept");
        check(heard.calls == 0 && heard.texts.size() >= 3 && want.equals(heard.texts.get(heard.texts.size() - 1))
                && heard.texts.get(0).equals("天空看起来是蓝色的"), "the sink saw it grow: " + heard.texts.size());
        check(sse.spoke(), "text reached the sink");
    }

    static void tools() throws Exception {
        Heard heard = new Heard();
        Sse sse = feed("sse-tools.txt", heard);
        JSONObject choice = sse.envelope().getJSONArray("choices").getJSONObject(0), m = choice.getJSONObject("message");
        JSONArray calls = m.getJSONArray("tool_calls");
        check("tool_calls".equals(choice.getString("finish_reason")) && calls.length() == 2, "two calls");
        JSONObject a = calls.getJSONObject(0), b = calls.getJSONObject(1);
        check("set_volume".equals(a.getJSONObject("function").getString("name"))
                && new JSONObject(a.getJSONObject("function").getString("arguments")).getInt("level") == 20
                && a.getString("id").startsWith("call_function_") && "function".equals(a.getString("type")), "set_volume whole");
        check("web_search".equals(b.getJSONObject("function").getString("name"))
                && new JSONObject(b.getJSONObject("function").getString("arguments")).getString("query").contains("上海"), "web_search whole");
        check(heard.calls == 1 && heard.texts.isEmpty() && !sse.spoke() && !m.has("content"), "a calling round says nothing");
    }

    static void pieces() throws Exception {
        // arguments split over deltas, as OpenAI-style servers send them; a tool call ends any text
        Heard heard = new Heard();
        Sse sse = new Sse(heard);
        sse.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"好的\"}}]}");
        sse.line("");
        sse.line(": keep-alive");
        sse.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"web_\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}");
        sse.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"search\",\"arguments\":\" \\\"天气\\\"}\"}}]}}]}");
        sse.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"不该读\"},\"finish_reason\":\"tool_calls\"}]}");
        boolean more = sse.line("data: [DONE]");
        JSONObject m = sse.envelope().getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONObject fn = m.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function");
        check(!more && "web_search".equals(fn.getString("name")) && "天气".equals(new JSONObject(fn.getString("arguments")).getString("query")),
                "split arguments joined: " + fn);
        check(heard.texts.size() == 1 && heard.calls == 1, "text after a call is not passed on");
        // a stream cut short: no finish_reason, which parseReply refuses as unfinished
        Sse cut = new Sse(null);
        cut.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"说到一半\"}}]}");
        check("".equals(cut.envelope().getJSONArray("choices").getJSONObject(0).getString("finish_reason")) && !cut.done()
                && !cut.finished(), "a cut stream has no finish");
        // an error in place of an answer
        Sse err = new Sse(null);
        err.line("data: {\"type\":\"error\",\"error\":{\"type\":\"insufficient_balance_error\",\"message\":\"insufficient balance (1008)\"}}");
        check(err.error() != null && err.error().getJSONObject("error").getString("type").startsWith("insufficient"), "the error envelope is kept");
        boolean threw = false;
        try { new Sse(null).line("data: {not json"); } catch (JSONException e) { threw = true; }
        check(threw, "a broken line throws");
        // MiniMax's own API ends with the whole message instead of a delta: it replaces, it does not double
        Heard h2 = new Heard();
        Sse whole = new Sse(h2);
        whole.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}");
        whole.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，世界。\"}}]}");
        whole.line("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"你好，世界。\",\"reasoning_content\":\"想一想\"}}]}");
        JSONObject wm = whole.envelope().getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        check("你好，世界。".equals(wm.getString("content")) && "想一想".equals(wm.getString("reasoning_content")) && whole.finished(),
                "a final whole message is not doubled: " + wm);
        check("你好，世界。".equals(h2.texts.get(h2.texts.size() - 1)), "the sink ends on the whole text");
        Sse wholeCalls = new Sse(null);
        wholeCalls.line("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c9\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}]}}]}");
        JSONObject wc = wholeCalls.envelope().getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        check(wc.getJSONArray("tool_calls").length() == 1 && "c9".equals(wc.getJSONArray("tool_calls").getJSONObject(0).getString("id")),
                "a whole message with a call");
    }

    /** A model that streams its plain answers to the sink in pieces, as the gateway does. */
    static final class Streaming implements AgentLoop.Model {
        final List<JSONObject> replies = new ArrayList<>();
        final List<JSONArray> seen = new ArrayList<>();
        int next, streamed;
        @Override public JSONObject next(JSONArray messages, JSONArray tools) throws Exception { return next(messages, tools, false, null); }
        @Override public JSONObject next(JSONArray messages, JSONArray tools, boolean strong, Sse.Sink sink) throws Exception {
            seen.add(new JSONArray(messages.toString()));
            JSONObject r = replies.get(Math.min(next++, replies.size() - 1));
            String content = r.optString("content");
            if (sink != null && !r.has("tool_calls") && !content.isEmpty()) {
                streamed++;
                for (int i = 20; i < content.length(); i += 20) sink.text(content.substring(0, i));
                sink.text(content);
            }
            return new JSONObject(r.toString());
        }
        @Override public void cancel() { }
    }

    static final class Reader implements AgentLoop.Drafts {
        final List<String> texts = new ArrayList<>();
        String read = "";
        @Override public void text(Ledger task, String soFar) {
            texts.add(soFar);
            List<String> pages = Speech.pages(soFar, Speech.PAGE_MAX);
            if (read.isEmpty() && pages.size() >= 2) read = pages.get(0);
        }
        @Override public String read(Ledger task) { return read; }
    }

    private static String sentences(String lead, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) b.append(lead).append("第").append(i).append("点，这里是一句用来凑长度的解释，说明它为什么重要。");
        return b.toString();
    }

    static void loop() throws Exception {
        // a plain answer streams to the reader; the reply is the model's text, unchanged
        Streaming model = new Streaming();
        String answer = sentences("", 6);
        model.replies.add(new JSONObject().put("role", "assistant").put("content", answer));
        Reader reader = new Reader();
        AgentLoop l = new AgentLoop(AgentCoreTest.contract, model, Collections.<Tool>emptyList(), null, (w, t) -> true, () -> 1L, null);
        l.drafts(reader);
        Ledger t = l.run(new AgentLoop.Request("详细讲讲", "xiaoai"));
        check(t.state == Ledger.State.ANSWERED && answer.equals(t.reply), "a streamed answer ends as before");
        check(model.streamed == 1 && reader.texts.size() > 3 && !reader.read.isEmpty(), "the reader saw it grow and took page 1");

        // a long answer whose first page was read: the rewrite follows that page and the reply keeps it at its head
        Streaming longer = new Streaming();
        String tooLong = sentences("很长的", 30);
        longer.replies.add(new JSONObject().put("role", "assistant").put("content", tooLong));
        longer.replies.add(new JSONObject().put("role", "assistant").put("content", "接着说剩下的要点，压缩过了。最后是结论。"));
        Reader r2 = new Reader();
        AgentLoop l2 = new AgentLoop(AgentCoreTest.contract, longer, Collections.<Tool>emptyList(), null, (w, t2) -> true, () -> 1L, null);
        l2.drafts(r2);
        Ledger t2 = l2.run(new AgentLoop.Request("详细讲讲为什么P和NP问题这么难", "xiaoai"));
        JSONArray second = longer.seen.get(1);
        String asked = second.getJSONObject(second.length() - 1).getString("content");
        check(asked.contains("开头这段已经读给用户听了") && asked.contains(r2.read), "the rewrite is told what was read");
        check(t2.reply.equals(r2.read + "接着说剩下的要点，压缩过了。最后是结论。"), "the reply is the page read, then the rewrite");

        // a planned task's report is not streamed
        Streaming planned = new Streaming();
        planned.replies.add(new JSONObject().put("role", "assistant").put("content", "").put("tool_calls", new JSONArray().put(
                new JSONObject().put("id", "p1").put("type", "function").put("function", new JSONObject().put("name", "plan")
                        .put("arguments", new JSONObject().put("goal", "做一件事").put("steps", new JSONArray().put("第一步").put("第二步")).put("done_when", "做完").toString())))));
        planned.replies.add(new JSONObject().put("role", "assistant").put("content", sentences("报告", 6)));
        Reader r3 = new Reader();
        AgentLoop l3 = new AgentLoop(AgentCoreTest.contract, planned, Collections.<Tool>emptyList(), null, (w, t3) -> true, () -> 1L, null);
        l3.drafts(r3);
        l3.run(new AgentLoop.Request("帮我做一件事", "xiaoai"));
        check(r3.texts.isEmpty() && planned.streamed == 0, "a planned task's report comes whole");
    }
}
