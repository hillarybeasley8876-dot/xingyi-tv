package xyz.erapi0neer.xiaoaibridge;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Speakable text from a Responses API reply, with the real web-search reply captured on 2026-11-04 as the main case. */
public final class ResponsesTextTest {
    private static int pass, fail;
    private static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL " + what); } }
    private static void eq(String got, String want, String what) { check(want.equals(got), what + ": got [" + got + "] want [" + want + "]"); }

    private static String read(String path) throws IOException {
        try (FileInputStream in = new FileInputStream(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String message(String text) {
        return "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":" + org.json.JSONObject.quote(text) + "}]}]}";
    }

    public static void main(String[] args) throws IOException {
        String real = read("tests/fixtures/responses-websearch.json");
        eq(ResponsesText.extract(real), "今天上海金早盘价847.21元/克，午盘价841.08元/克。", "fixture");
        check(ResponsesText.usedSearch(real), "fixture used search");
        eq(ResponsesText.extract("{\"output_text\":\"直接给的答案\"}"), "直接给的答案", "top-level output_text");
        eq(ResponsesText.extract(message("金价约800元。（[金价网](https://x.cn/a?b=1)）")), "金价约800元。", "full-width citation");
        eq(ResponsesText.extract(message("见[上海黄金交易所](https://www.sge.com.cn)的报价")), "见上海黄金交易所的报价", "plain link keeps its label");
        eq(ResponsesText.extract(message("详情 https://example.com/a 查看")), "详情 查看", "bare url");
        eq(ResponsesText.extract(message("**今天**  晴，# 18度")), "今天 晴， 18度", "markup and spaces");
        eq(ResponsesText.extract("{\"output\":[{\"type\":\"web_search_call\"}]}"), "", "no message");
        eq(ResponsesText.extract("not json"), "", "bad json");
        eq(ResponsesText.extract("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"一。\"},{\"type\":\"refusal\",\"text\":\"x\"},{\"type\":\"output_text\",\"text\":\"二。\"}]}]}"),
                "一。二。", "only output_text parts, in order");
        check(!ResponsesText.usedSearch(message("x")), "no search call");
        check(!ResponsesText.usedSearch("[]"), "array is not a reply");
        System.out.println((fail == 0 ? "PASS " : "FAIL ") + pass + " passed, " + fail + " failed (responses text)");
        if (fail > 0) System.exit(1);
    }
}
