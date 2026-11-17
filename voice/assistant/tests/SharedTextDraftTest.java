package xyz.erapi0neer.xingyi;

public final class SharedTextDraftTest {
    private static int checks;

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("case " + (checks + 1));
        ++checks;
    }

    public static void main(String[] args) {
        String query = "用网易云听林俊杰的《不为谁而作的歌》";
        check(query.equals(SharedTextDraft.text("　" + query + " ")));
        check(SharedTextDraft.text("　 ") == null);
        check(SharedTextDraft.text(null) == null);
        check(SharedTextDraft.text(query + "\n另一行") == null);
        check(SharedTextDraft.text("反向\u202e控制") == null);
        check(SharedTextDraft.text("表达😀").equals("表达😀"));
        check(SharedTextDraft.text("坏代理\ud800") == null);
        check(SharedTextDraft.text(new String(new char[321]).replace('\0', 'A')) == null);
        check(SharedTextDraft.canReceive(false, false, false, false, false));
        check(!SharedTextDraft.canReceive(true, false, false, false, false));
        check(!SharedTextDraft.canReceive(false, true, false, false, false));
        check(!SharedTextDraft.canReceive(false, false, true, false, false));
        check(!SharedTextDraft.canReceive(false, false, false, true, false));
        check(!SharedTextDraft.canReceive(false, false, false, false, true));
        check(SharedTextDraft.choice(query, "", null, false) == SharedTextDraft.Choice.FILL);
        check(SharedTextDraft.choice(query, "原草稿", null, false) == SharedTextDraft.Choice.ASK_TO_REPLACE);
        check(SharedTextDraft.choice(query, "正在编辑", query, false) == SharedTextDraft.Choice.IGNORE);
        check(SharedTextDraft.choice(query, "原草稿", null, true) == SharedTextDraft.Choice.IGNORE);
        check(SharedTextDraft.choice(null, "原草稿", null, false) == SharedTextDraft.Choice.IGNORE);
        System.out.println("PASS " + checks + " incoming draft checks");
    }
}
