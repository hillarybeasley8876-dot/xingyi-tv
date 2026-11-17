package xyz.erapi0neer.xingyi.agent;

import java.util.*;
import org.json.*;

/** Checks for Memory and MemoryTools (job memory): remember, forget and how names are written. */
final class MemoryChecks {
    private MemoryChecks() { }

    static void run() throws Exception {
        addFacts();
        forgetting();
        aliases();
        tools();
    }

    private static void check(boolean ok, String label) { AgentCoreTest.check(ok, label); }

    private static void eq(String got, String want, String label) {
        check(want.equals(got), label + ": got [" + got + "] want [" + want + "]");
    }

    private static JSONObject facts(String... items) throws JSONException {
        return new JSONObject().put("facts", new JSONArray(Arrays.asList(items)));
    }

    /** 40 random letters: two of these are far enough apart that the near-duplicate rule never fires. */
    private static String noise(long seed) {
        StringBuilder b = new StringBuilder();
        long x = seed * 2654435761L + 12345;
        for (int i = 0; i < 40; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            b.append((char) ('a' + (int) Math.abs((x >>> 33) % 26)));
        }
        return b.toString();
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('字');
        return b.toString();
    }

    // ---- addFact ---------------------------------------------------------------------------------

    static void addFacts() throws Exception {
        JSONObject p = new JSONObject();
        eq(Memory.addFact(p, "我喜欢林俊杰"), "added", "first fact added");
        check(p.getJSONArray("facts").length() == 1, "missing facts array created");
        eq(Memory.addFact(p, "我喜欢林俊杰。"), "duplicate", "the recognizer's 。 does not make a second fact");
        check(p.getJSONArray("facts").length() == 1, "duplicate stored nothing");
        eq(Memory.addFact(p, "  我喜欢   林俊杰  "), "duplicate", "spaces collapsed before comparing");
        eq(p.getJSONArray("facts").getString(0), "我喜欢林俊杰", "fact kept as it was written");

        JSONObject q = facts("我喜欢林俊杰", "我住在北京");
        eq(Memory.addFact(q, "我最喜欢林俊杰"), "updated", "one word apart is the same fact");
        check(q.getJSONArray("facts").length() == 2 && q.getJSONArray("facts").getString(0).equals("我最喜欢林俊杰")
                && q.getJSONArray("facts").getString(1).equals("我住在北京"), "near-duplicate replaced in place");
        JSONObject c = facts("我喜欢林俊杰的歌");
        eq(Memory.addFact(c, "我喜欢林俊杰"), "updated", "a fact contained in another one replaces it");
        eq(c.getJSONArray("facts").getString(0), "我喜欢林俊杰", "shorter wording wins");

        JSONObject e = new JSONObject();
        eq(Memory.addFact(e, ""), "rejected:empty", "empty fact rejected");
        eq(Memory.addFact(e, "   "), "rejected:empty", "blank fact rejected");
        eq(Memory.addFact(e, null), "rejected:empty", "null fact rejected");
        check(!e.has("facts"), "nothing written for an empty fact");
        eq(Memory.addFact(e, repeat(81)), "rejected:too_long", "81 characters rejected");
        eq(Memory.addFact(e, repeat(80)), "added", "80 characters still fit");

        JSONObject s = new JSONObject();
        eq(Memory.addFact(s, "我的银行卡号是6222020200112233"), "rejected:secret", "card number rejected");
        eq(Memory.addFact(s, "我家门牌号是1234567"), "rejected:secret", "six digits in a row rejected");
        eq(Memory.addFact(s, "wifi密码是abcd1234"), "rejected:secret", "password rejected");
        eq(Memory.addFact(s, "验证码是8899"), "rejected:secret", "verification code rejected");
        check(!s.has("facts"), "no secret ever reaches the file");
        eq(Memory.addFact(s, "我的房间号是1203"), "added", "four digits are just a number");

        JSONObject full = new JSONObject();
        boolean allAdded = true;
        for (int i = 0; i < Memory.MAX_FACTS; i++) allAdded &= "added".equals(Memory.addFact(full, noise(i + 1)));
        check(allAdded, "60 different facts all added");
        eq(Memory.addFact(full, "新的一条记忆"), "added:dropped_oldest", "the 61st fact drops the oldest");
        JSONArray kept = full.getJSONArray("facts");
        check(kept.length() == Memory.MAX_FACTS && kept.getString(kept.length() - 1).equals("新的一条记忆")
                && !kept.getString(0).equals(noise(1)), "list stays at 60, oldest gone, newest last");
        eq(Memory.addFact(full, "新的一条记忆"), "duplicate", "the newest fact is found again");
    }

    // ---- forget ----------------------------------------------------------------------------------

    static void forgetting() throws Exception {
        JSONObject p = facts("我喜欢林俊杰", "我住在北京", "我最喜欢林俊杰的歌");
        p.put("aliases", new JSONObject().put("林俊杰", "林俊杰").put("星辰人旅行者", "星辰亻旅行者"));
        List<String> gone = Memory.forget(p, "林俊杰");
        check(gone.size() == 3, "two facts and one alias forgotten: " + gone);
        check(gone.contains("我喜欢林俊杰") && gone.contains("我最喜欢林俊杰的歌") && gone.contains("林俊杰→林俊杰"),
                "forgotten things are reported as they were: " + gone);
        check(p.getJSONArray("facts").length() == 1 && p.getJSONArray("facts").getString(0).equals("我住在北京"),
                "the rest is kept");
        check(p.getJSONObject("aliases").length() == 1 && p.getJSONObject("aliases").has("星辰人旅行者"),
                "an unrelated alias is kept");

        JSONObject q = facts("我喜欢林俊杰", "我住在北京");
        q.put("aliases", new JSONObject().put("星辰人旅行者", "星辰亻旅行者"));
        List<String> alias = Memory.forget(q, "星辰");
        check(alias.size() == 1 && alias.get(0).equals("星辰人旅行者→星辰亻旅行者"), "an alias goes by its heard form");
        check(q.getJSONObject("aliases").length() == 0 && q.getJSONArray("facts").length() == 2, "no fact touched");

        JSONObject n = facts("我最喜欢林俊杰", "我住在北京");
        check(Memory.forget(n, "我喜欢林俊杰").size() == 1, "a near-duplicate fact is forgotten too");
        JSONObject blank = facts("我喜欢林俊杰");
        blank.put("aliases", new JSONObject().put("星辰人旅行者", "星辰亻旅行者"));
        check(Memory.forget(blank, "").isEmpty() && Memory.forget(blank, "   ").isEmpty() && Memory.forget(blank, null).isEmpty(),
                "an empty what removes nothing");
        check(blank.getJSONArray("facts").length() == 1 && blank.getJSONObject("aliases").length() == 1,
                "nothing was removed by the empty what");
        check(Memory.forget(blank, "薛之谦").isEmpty(), "a name nobody stored removes nothing");
        JSONObject bare = new JSONObject();
        check(Memory.forget(bare, "林俊杰").isEmpty() && !bare.has("facts"), "forgetting from an empty profile is safe");
    }

    // ---- addAlias --------------------------------------------------------------------------------

    static void aliases() throws Exception {
        JSONObject p = new JSONObject();
        eq(Memory.addAlias(p, "星辰人旅行者", "星辰亻旅行者"), "added", "alias added");
        check(p.getJSONObject("aliases").getString("星辰人旅行者").equals("星辰亻旅行者"), "missing aliases object created");
        eq(Memory.addAlias(p, "星辰人旅行者", "星辰亻旅行者"), "duplicate", "the same pair is already there");
        eq(Memory.addAlias(p, "星辰人旅行者", "星辰旅行者"), "updated", "the same heard form, a new written form");
        eq(p.getJSONObject("aliases").getString("星辰人旅行者"), "星辰旅行者", "the new written form wins");
        eq(Memory.addAlias(p, "", "星辰亻旅行者"), "rejected:empty", "empty heard form rejected");
        eq(Memory.addAlias(p, "星辰人旅行者", " "), "rejected:empty", "empty written form rejected");
        eq(Memory.addAlias(p, repeat(21), "星辰亻旅行者"), "rejected:too_long", "21 characters rejected");
        eq(Memory.addAlias(p, "星辰人旅行者", repeat(21)), "rejected:too_long", "a long written form rejected");
        eq(Memory.addAlias(p, "周杰伦", "周杰伦"), "rejected:same", "a name written as it is heard is pointless");
        check(p.getJSONObject("aliases").length() == 1, "no rejected alias was stored");

        JSONObject full = new JSONObject();
        for (int i = 0; i < Memory.MAX_ALIASES; i++) Memory.addAlias(full, "听到" + i, "写作" + i);
        check(full.getJSONObject("aliases").length() == Memory.MAX_ALIASES, "40 aliases fit");
        eq(Memory.addAlias(full, "听到0", "写作零"), "updated", "a known heard form still updates when full");
        eq(Memory.addAlias(full, "听到99", "写作99"), "rejected:full", "a new key does not fit");
        check(full.getJSONObject("aliases").length() == Memory.MAX_ALIASES, "the list stays at 40");
    }

    // ---- the tools -------------------------------------------------------------------------------

    /** The profile file, in memory: load hands out a copy, so a tool that does not save really changes nothing. */
    static final class Mem implements MemoryTools.Store {
        JSONObject profile = new JSONObject();
        int saves;
        Exception failure;
        boolean onSave;
        @Override public JSONObject load() throws Exception {
            if (failure != null && !onSave) throw failure;
            return new JSONObject(profile.toString());
        }
        @Override public void save(JSONObject p) throws Exception {
            if (failure != null && onSave) throw failure;
            saves++;
            profile = new JSONObject(p.toString());
        }
    }

    private static Tool tool(List<Tool> tools, String name) {
        for (Tool t : tools) if (t.name().equals(name)) return t;
        return null;
    }

    static void tools() throws Exception {
        Mem store = new Mem();
        List<Tool> tools = MemoryTools.create(store);
        check(tools.size() == 3 && tool(tools, "remember") != null && tool(tools, "forget") != null
                && tool(tools, "remember_name") != null, "three tools: remember, forget, remember_name");
        for (Tool t : tools) check(t.gate(new JSONObject(), null) == Tool.Gate.read(), t.name() + " needs no plan");
        Tool remember = tool(tools, "remember"), forget = tool(tools, "forget"), name = tool(tools, "remember_name");
        eq(remember.describe(AgentCoreTest.o("fact", "我喜欢林俊杰"), null), "记住：我喜欢林俊杰", "remember describes itself");
        eq(forget.describe(AgentCoreTest.o("what", "林俊杰"), null), "忘掉：林俊杰", "forget describes itself");
        eq(name.describe(AgentCoreTest.o("heard", "星辰人旅行者", "written", "星辰亻旅行者"), null),
                "记住写法：星辰人旅行者 → 星辰亻旅行者", "remember_name describes itself");

        JSONObject r = remember.run(AgentCoreTest.o("fact", "我喜欢林俊杰"), null);
        check(r.getBoolean("ok") && r.getString("status").equals("recorded"), "remember records a new fact");
        eq(r.getString("message"), "记住了：我喜欢林俊杰", "remember says what it kept");
        check(r.getInt("facts") == 1, "remember reports how many facts there are now");
        check(store.saves == 1 && store.profile.getJSONArray("facts").length() == 1, "the profile was written once");

        JSONObject again = remember.run(AgentCoreTest.o("fact", "我喜欢林俊杰"), null);
        check(again.getBoolean("ok") && again.getString("status").equals("duplicate"), "remember knows it already knows");
        eq(again.getString("message"), "本来就记得：我喜欢林俊杰", "duplicate says so in Chinese");
        check(store.saves == 1, "a duplicate is not written to the file");

        JSONObject secret = remember.run(AgentCoreTest.o("fact", "我的银行卡号是6222020200112233"), null);
        check(!secret.getBoolean("ok") && secret.getString("status").equals("rejected"), "remember refuses a card number");
        check(secret.getString("message").contains("密码") && secret.getString("message").contains("不记"),
                "the refusal says why: " + secret.getString("message"));
        JSONObject empty = remember.run(AgentCoreTest.o("fact", " "), null);
        check(!empty.getBoolean("ok") && empty.getString("status").equals("rejected"), "remember refuses nothing to remember");
        JSONObject tooLong = remember.run(AgentCoreTest.o("fact", repeat(81)), null);
        check(!tooLong.getBoolean("ok") && tooLong.getString("status").equals("rejected"), "remember refuses an endless fact");
        check(store.saves == 1, "nothing rejected was written");

        JSONObject updated = remember.run(AgentCoreTest.o("fact", "我最喜欢林俊杰"), null);
        check(updated.getString("status").equals("recorded") && store.saves == 2, "a better wording is written back");
        JSONObject f = forget.run(AgentCoreTest.o("what", "林俊杰"), null);
        check(f.getBoolean("ok") && f.getString("status").equals("forgotten"), "forget removes what matched");
        check(f.getJSONArray("removed").length() == 1 && f.getJSONArray("removed").getString(0).equals("我最喜欢林俊杰"),
                "forget reports what went");
        eq(f.getString("message"), "已忘掉和「林俊杰」有关的 1 条记忆", "forget counts what went");
        check(store.saves == 3 && store.profile.getJSONArray("facts").length() == 0, "the file lost the fact");

        JSONObject none = forget.run(AgentCoreTest.o("what", "薛之谦"), null);
        check(none.getBoolean("ok") && none.getString("status").equals("nothing"), "forget says when there was nothing");
        eq(none.getString("message"), "没有和「薛之谦」有关的记忆", "and names what it looked for");
        check(store.saves == 3, "nothing removed, nothing written");

        JSONObject a = name.run(AgentCoreTest.o("heard", "星辰人旅行者", "written", "星辰亻旅行者"), null);
        check(a.getBoolean("ok") && a.getString("status").equals("recorded"), "remember_name records the written form");
        check(a.getString("message").contains("星辰亻旅行者") && store.saves == 4, "and writes it to the file");
        JSONObject dup = name.run(AgentCoreTest.o("heard", "星辰人旅行者", "written", "星辰亻旅行者"), null);
        check(dup.getBoolean("ok") && dup.getString("status").equals("duplicate") && store.saves == 4,
                "the same pair is not written twice");
        JSONObject same = name.run(AgentCoreTest.o("heard", "周杰伦", "written", "周杰伦"), null);
        check(!same.getBoolean("ok") && same.getString("status").equals("rejected"), "a pointless alias is refused");
        check(store.saves == 4 && store.profile.getJSONObject("aliases").length() == 1, "and not stored");

        store.failure = new java.io.IOException("磁盘满了");
        JSONObject broken = remember.run(AgentCoreTest.o("fact", "我住在北京"), null);
        check(!broken.getBoolean("ok") && broken.getString("status").equals("error"), "a broken file is reported, not thrown");
        check(broken.getString("message").startsWith("记忆没存上：") && broken.getString("message").contains("磁盘满了"),
                "the error is speakable: " + broken.getString("message"));
        store.onSave = true;
        JSONObject unsaved = remember.run(AgentCoreTest.o("fact", "我住在北京"), null);
        check(!unsaved.getBoolean("ok") && unsaved.getString("status").equals("error"), "a failed write is reported too");
        check(forget.run(AgentCoreTest.o("what", "星辰"), null).getString("status").equals("error"),
                "forget reports a failed write instead of throwing");
        store.failure = null;
        store.onSave = false;
        JSONObject after = remember.run(AgentCoreTest.o("fact", "我住在北京"), null);
        check(after.getBoolean("ok") && store.saves == 5, "and works again once the file is back");
    }
}
