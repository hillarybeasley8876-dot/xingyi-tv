package xyz.erapi0neer.xingyi.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.*;

/** The Xiaomi TV app store's catalog (assets/agent/store_apps.json, StoreCatalog): what install_app may install, by the
 * names people say, and when the user's own words already ask for it. */
final class StoreChecks {
    private StoreChecks() { }

    static void run() throws Exception {
        StoreCatalog c = new StoreCatalog(new String(Files.readAllBytes(Paths.get("assets/agent/store_apps.json")), StandardCharsets.UTF_8));
        check(c.size() >= 100, "the catalog has the store's apps: " + c.size());
        is(c, "爱奇艺", "com.gitvdemo.video");
        is(c, "银河奇异果", "com.gitvdemo.video");
        is(c, "腾讯视频", "com.ktcp.video");
        is(c, "芒果TV", "com.hunantv.license");
        is(c, "芒果", "com.hunantv.license");
        is(c, "QQ音乐", "com.tencent.qqmusictv");
        is(c, "qq音乐", "com.tencent.qqmusictv");
        is(c, "快手", "com.kwai.tv.yst");
        is(c, "央视频", "com.newtv.cboxtv");
        is(c, "CCTV", "com.newtv.cboxtv");
        is(c, "喜马拉雅", "com.fmxos.app.smarttv");
        is(c, "com.hunantv.license", "com.hunantv.license");
        is(c, "云视听 极光", "com.ktcp.video");
        check(c.find("桌面") == null && c.find("com.mitv.tvhome") == null, "the PatchWall home screen is never offered (removed for its ads)");
        check(c.find("小米电视商城") == null, "nor the shopping app");
        check(c.find("优酷") == null, "an app the store does not list is not guessed");
        check(c.find("") == null && c.find(null) == null && c.find("x") == null, "nothing or one letter finds nothing");
        StoreCatalog.Entry iqiyi = c.find("爱奇艺");
        check(StoreCatalog.named("用爱奇艺看狂飙", iqiyi), "「用爱奇艺看狂飙」 names 爱奇艺: installing it needs no extra question");
        check(StoreCatalog.named("装一下银河奇异果", iqiyi), "the store's own name counts too");
        check(!StoreCatalog.named("我想看狂飙", iqiyi), "「我想看狂飙」 names no app");
        check(!StoreCatalog.named(null, iqiyi) && !StoreCatalog.named("爱奇艺", null), "missing words or app: not named");
        check(new StoreCatalog("{}").size() == 0 && new StoreCatalog("{\"apps\":[1,{}]}").size() == 0, "a broken catalog is empty, not an error");
    }

    private static void is(StoreCatalog c, String query, String pkg) {
        StoreCatalog.Entry e = c.find(query);
        check(e != null && pkg.equals(e.pkg), query + " → " + pkg + (e == null ? " (nothing)" : " (" + e.pkg + ")"));
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }
}
