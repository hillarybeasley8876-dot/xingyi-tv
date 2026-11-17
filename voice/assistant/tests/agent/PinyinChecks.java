package xyz.erapi0neer.xingyi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** names-by-sound (2026-11-07): Pinyin, and stock names that speech recognition wrote as homophones (「质朴」 for 智谱).
 * The worker fills this in; see briefs/names-by-sound.md. */
final class PinyinChecks {
    private PinyinChecks() { }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/agent/fixtures/" + name)), StandardCharsets.UTF_8);
    }

    static void run() {
        try {
            checks();
        } catch (Exception e) {
            check(false, "pinyin checks threw " + e);
        }
    }

    static void checks() throws Exception {
        readings();
        spelling();
        sounding();
        unlisted();
        parts();
        spoken();
        search();
    }

    /** unlisted (2026-11-09): a well-known company that is not listed is answered as such, never with a stock that
     * merely sounds like it (华微电子 starts with huawei). */
    private static void unlisted() {
        check("华为没有上市，没有股价".equals(Quotes.unlisted("华为")), "unlisted: 华为 is not listed, said plainly");
        check("华为没有上市，没有股价".equals(Quotes.unlisted("华为技术")), "unlisted: 华为技术 is 华为");
        check("华为没有上市，没有股价".equals(Quotes.unlisted("华为公司")), "unlisted: 华为公司 is 华为");
        check("华为没有上市，没有股价".equals(Quotes.unlisted("华维")), "unlisted: 「华维」 is 华为 heard wrong");
        check("华为没有上市，没有股价".equals(Quotes.unlisted("花为")), "unlisted: 「花为」 is 华为 heard wrong");
        check("字节跳动没有上市，没有股价".equals(Quotes.unlisted("字节跳动")), "unlisted: 字节跳动 is not listed");
        check("字节跳动没有上市，没有股价".equals(Quotes.unlisted("抖音")), "unlisted: 抖音 is 字节跳动, which is not listed");
        check("字节跳动没有上市，没有股价".equals(Quotes.unlisted("今日头条")), "unlisted: 今日头条 is 字节跳动");
        check("大疆没有上市，没有股价".equals(Quotes.unlisted("大疆")), "unlisted: 大疆 is not listed");
        check("海思没有上市，没有股价".equals(Quotes.unlisted("海思")), "unlisted: 海思 is not listed");
        check("蚂蚁集团没有上市，没有股价".equals(Quotes.unlisted("支付宝")), "unlisted: 支付宝 is 蚂蚁集团, which is not listed");
        check(Quotes.unlisted("oppo") != null && Quotes.unlisted("VIVO") != null, "unlisted: a name in letters, in either case");
        check(Quotes.unlisted("小米") == null, "unlisted: 小米 is listed, so it is not answered here");
        check(Quotes.unlisted("腾讯") == null && Quotes.unlisted("贵州茅台") == null, "unlisted: 腾讯 and 贵州茅台 are listed");
        check(Quotes.unlisted("智谱") == null && Quotes.unlisted("毛台") == null, "unlisted: 智谱 and 「毛台」 (贵州茅台) are listed");
        check(Quotes.unlisted("") == null && Quotes.unlisted(null) == null && Quotes.unlisted("  ") == null, "unlisted: nothing, null, spaces");
    }

    /** parts: the names in one request, with 和 only where it joins two of them (华为和小米, not 天和防务). */
    private static void parts() {
        check(Quotes.parts("华为和小米").equals(Arrays.asList("华为", "小米")), "parts: 「华为和小米」 is two names, 华为 is known without a search");
        check(Quotes.parts("华为 小米、腾讯").equals(Arrays.asList("华为", "小米", "腾讯")), "parts: a space and 、 still separate names");
        check(Quotes.parts("天和防务").equals(Arrays.asList("天和防务")), "parts: 和 inside a company name is not a separator");
        check(Quotes.parts("腾讯和英伟达").equals(Arrays.asList("腾讯和英伟达")), "parts: two listed names are left whole, as before");
        check(Quotes.parts("").isEmpty() && Quotes.parts(null).isEmpty(), "parts: nothing to look up");
    }

    /** The request itself: 「华为股价多少」 is a quick stock_quote for 华为, and the unlisted sentence is what is said. */
    private static void spoken() throws Exception {
        QuickCommand q = QuickCommand.match("华为股价多少", Arrays.asList("哔哩哔哩", "电视设置"));
        check(q != null && "stock_quote".equals(q.tool) && "华为".equals(q.args.optString("query")),
                "「华为股价多少」 is stock_quote for 华为" + (q == null ? "（没有匹配）" : "（得到 " + q.tool + " " + q.args + "）"));
        check(q != null && q.args.optBoolean("final"), "「华为股价多少」 is answered without the model");
        String said = Quotes.unlisted("华为");
        JSONObject result = new JSONObject().put("ok", true).put("message", said).put("answer", said).put("final", true);
        check(q != null && said.equals(q.reply(result)), "what is read out is 「华为没有上市，没有股价」");
    }

    /** readings and hasChinese: the table is pypinyin's, all of GB2312, and nothing else is in it. */
    private static void readings() {
        check("[pu, piao, po]".equals(String.valueOf(Pinyin.readings('朴'))), "朴 reads pu, piao, po, most common first");
        List<String> hang = Pinyin.readings('行');
        check(hang.contains("hang") && hang.contains("xing"), "行 reads both hang and xing");
        List<String> chong = Pinyin.readings('重');
        check(chong.contains("zhong") && chong.contains("chong"), "重 reads both zhong and chong");
        check("zhi".equals(Pinyin.readings('智').get(0)) && "zhi".equals(Pinyin.readings('质').get(0)), "智 and 质 both read zhi");
        List<String> lv = Pinyin.readings('绿');
        check(lv.contains("lv") && lv.contains("lu"), "绿 reads lv (ü written v) and lu");
        check(Pinyin.readings('A').isEmpty(), "a letter has no readings");
        check(Pinyin.readings('，').isEmpty(), "a Chinese comma has no readings");
        check(Pinyin.readings(' ').isEmpty(), "a space has no readings");
        check(Pinyin.readings('㐀').isEmpty(), "a character outside GB2312 has no readings");
        int chars = 0, chunks = PinyinData.CHUNKS.length;
        for (String chunk : PinyinData.CHUNKS) for (int i = 0; i < chunk.length(); i++) if (chunk.charAt(i) == ';') chars++;
        check(chars == 6763, "the table has all 6763 GB2312 characters (" + chars + ")");
        check(chunks == 5, "the readings are packed into " + chunks + " string constants");
        boolean toneless = true;
        for (char c = '啊'; c <= '鿿'; c++) for (String r : Pinyin.readings(c)) if (!r.matches("[a-z]+")) toneless = false;
        check(toneless, "every reading is toneless lower-case letters");
        check(Pinyin.hasChinese("质朴") && Pinyin.hasChinese("B站"), "hasChinese: a name with Chinese characters");
        check(!Pinyin.hasChinese("MINIMAX") && !Pinyin.hasChinese("600519"), "hasChinese: letters and digits are not Chinese");
        check(!Pinyin.hasChinese(null) && !Pinyin.hasChinese("") && !Pinyin.hasChinese("，。"), "hasChinese: nothing, null, punctuation");
    }

    /** spell and initials: what the pinyin search is given. */
    private static void spelling() {
        check("zhipu".equals(Pinyin.spell("质朴")) && "zhipu".equals(Pinyin.spell("智谱")), "spell: 「质朴」 and 「智谱」 are both zhipu");
        check("minimax".equals(Pinyin.spell("MINI MAX")), "spell: 「MINI MAX」 is minimax, spaces left out, letters in lower case");
        check("bzhan".equals(Pinyin.spell("B站")), "spell: 「B站」 is bzhan");
        check("tengxunkonggu".equals(Pinyin.spell("腾讯控股")), "spell: 「腾讯控股」");
        check("guizhoumaotai".equals(Pinyin.spell("贵州茅台")), "spell: 「贵州茅台」");
        check("".equals(Pinyin.spell(null)) && "".equals(Pinyin.spell("")) && "".equals(Pinyin.spell("，。！")), "spell: null, empty and punctuation only give nothing");
        check("600519".equals(Pinyin.spell("600519")), "spell: digits are kept");
        check("zp".equals(Pinyin.initials("质朴")), "initials: 「质朴」 is zp");
        check("gzmt".equals(Pinyin.initials("贵州茅台")), "initials: 「贵州茅台」 is gzmt");
        check("bz".equals(Pinyin.initials("B站")), "initials: 「B站」 is bz");
        check("txkg".equals(Pinyin.initials("腾讯控股")), "initials: 「腾讯控股」 is txkg");
        check("minimax".equals(Pinyin.initials("MINI MAX")), "initials: letters are their own initials");
        check("".equals(Pinyin.initials(null)) && "".equals(Pinyin.initials("")) && "".equals(Pinyin.initials("　")), "initials: null, empty and a full-width space give nothing");
    }

    /** sameSound and soundIndex: which name speech recognition may have meant. */
    private static void sounding() {
        check(Pinyin.sameSound("质朴", "智谱") && Pinyin.sameSound("智谱", "质朴"), "sameSound: 「质朴」 is 「智谱」 heard wrong, either way round");
        check(!Pinyin.sameSound("支付", "智谱"), "sameSound: 「支付」 is not 「智谱」 (fu is not pu)");
        check(Pinyin.sameSound("银行", "银杭"), "sameSound: 「银行」/「银杭」 (行 has hang)");
        check(Pinyin.sameSound("毛台", "茅台"), "sameSound: 「毛台」 is 「茅台」 heard wrong");
        check(!Pinyin.sameSound("质朴", "智谱AI"), "sameSound: lengths differ, so no");
        check(!Pinyin.sameSound(null, "智谱") && !Pinyin.sameSound("智谱", null), "sameSound: null is never the same sound");
        check(!Pinyin.sameSound("", ""), "sameSound: nothing is not the same sound as nothing");
        check(Pinyin.sameSound("A股", "a股"), "sameSound: letters are compared ignoring case");
        check(Pinyin.sameSound("MINIMAX", "minimax"), "sameSound: a name in letters, in either case");
        check(!Pinyin.sameSound("质朴", "智富"), "sameSound: 朴 and 富 share no reading");
        check(Pinyin.soundIndex("质朴", "智谱") == 0, "soundIndex: 「质朴」 is 「智谱」 from the start");
        check(Pinyin.soundIndex("贵州", "贵州茅台") == 0, "soundIndex: a stretch at the start");
        check(Pinyin.soundIndex("毛台", "贵州茅台") == 2, "soundIndex: 「毛台」 sits inside 「贵州茅台」 at 2");
        check(Pinyin.soundIndex("台", "贵州茅台") == 3, "soundIndex: a stretch at the end");
        check(Pinyin.soundIndex("质朴", "智富") == -1, "soundIndex: nothing that sounds like it");
        check(Pinyin.soundIndex("质朴", "东方支付集团控股") == -1, "soundIndex: 支付 inside a longer name still does not sound like 质朴");
        check(Pinyin.soundIndex("贵州茅台", "毛台") == -1, "soundIndex: a longer than b");
        check(Pinyin.soundIndex(null, "智谱") == -1 && Pinyin.soundIndex("质朴", null) == -1, "soundIndex: null");
    }

    /** bySound against the search's real answers (saved 2026-11-07), and pick still what it was. */
    private static void search() throws Exception {
        Quotes.Hit zhipu = Quotes.bySound(fixture("smartbox-zhipu.txt"), "质朴");
        check(zhipu != null && "hk02513".equals(zhipu.code) && "智谱".equals(zhipu.name), "bySound: 「质朴」 found by zhipu is 智谱 hk02513");
        Quotes.Hit zp = Quotes.bySound(fixture("smartbox-zp.txt"), "质朴");
        check(zp != null && "hk02513".equals(zp.code), "bySound: the initials zp find 智谱 too, not 中盘高贝 or 展鹏科技");
        Quotes.Hit maotai = Quotes.bySound(fixture("smartbox-maotai.txt"), "毛台");
        check(maotai != null && "sh600519".equals(maotai.code) && "贵州茅台".equals(maotai.name), "bySound: 「毛台」 is 贵州茅台, whose name has that sound inside");
        check(Quotes.bySound(fixture("smartbox-zhifu.txt"), "质朴") == null, "bySound: zhifu answers 智富 and 置富产业信托, neither sounds like 质朴");
        check(Quotes.bySound(fixture("smartbox-zhipu-misheard.txt"), "质朴") == null, "bySound: the search found nothing at all (v_hint=\"N\";)");
        check(Quotes.bySound(fixture("smartbox-tengxun.txt"), "腾迅") != null, "bySound: 「腾迅」 still finds 腾讯控股 in the answer for tengxun");
        check(Quotes.bySound(fixture("smartbox-zhifu-hanzi.txt"), "支付") != null, "bySound: the answer for 支付 has 东方支付集团控股");
        check(Quotes.bySound(null, "质朴") == null && Quotes.bySound("", "质朴") == null, "bySound: no answer, no hit");
        String mixed = "v_hint=\"sh~000001~上证指数~szzs~ZS^sh~600519~上证银行~szyh~GP-A\";";
        Quotes.Hit stock = Quotes.bySound(mixed, "上证");
        check(stock != null && "sh600519".equals(stock.code), "bySound: without 指数 in the query a stock wins over the index of the same sound");
        Quotes.Hit index = Quotes.bySound(mixed, "上证指数");
        check(index != null && "sh000001".equals(index.code) && "上证指数".equals(index.name), "bySound: 指数 asked for, the index");
        check(Quotes.bySound("v_hint=\"hk~09999~智谱两倍做多~zplbzd~GP\";", "质朴") == null, "bySound: a leveraged product is never taken, even when it sounds right");
        check(Quotes.bySound("v_hint=\"us~zhipu.ps~智谱~zp~GP\";", "质朴") == null, "bySound: a US OTC listing (.ps) is never taken");
        check(Quotes.bySound("v_hint=\"hk~00700~腾讯控股r~txkgr~GP\";", "腾迅") != null, "bySound: Hong Kong's RMB counter is still a hit, ranked last as in pick");
        check("hk02513".equals(Quotes.pick(fixture("smartbox-zhipu-hanzi.txt"), "智谱")), "pick unchanged: 智谱 in the answer for 智谱");
        check("hk00700".equals(Quotes.pick(fixture("smartbox-tengxun.txt"), "腾讯")), "pick unchanged: 腾讯 is the stock hk00700, not 腾讯济安 the index");
        check("sh600519".equals(Quotes.pick(fixture("smartbox-gzmt.txt"), "贵州茅台")), "pick unchanged: 贵州茅台 by its full name");
        check("sh600519".equals(Quotes.pick(fixture("smartbox-gzmt.txt"), "茅台")), "pick unchanged: 茅台 by its short name");
        check(Quotes.pick(fixture("smartbox-zhipu-misheard.txt"), "质朴") == null, "pick unchanged: it does not search by sound itself");
    }
}
