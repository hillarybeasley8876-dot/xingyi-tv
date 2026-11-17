package xyz.erapi0neer.orrery;

/** PC check for HomeLayout: sh tools/home_check.sh. The strings and the numbers of the mock-up ui_home_v4.py (B). */
final class HomeLayoutCheck {
    static int ok, failed;

    static void check(boolean pass, String what) {
        if (pass) ok++; else failed++;
        System.out.println((pass ? "ok   " : "FAIL ") + what);
    }

    static void eq(String got, String want, String what) {
        check(want.equals(got), what + ": \"" + got + "\"");
    }

    static void near(float got, float want, String what) {
        check(Math.abs(got - want) < 0.01f, what + ": " + got);
    }

    public static void main(String[] a) {
        // the third line under the date: 农历 and the weather
        eq(HomeLayout.infoLine("农历九月三十", "多云 13° – 19°"), "农历九月三十　·　多云 13° – 19°", "infoLine joins both");
        eq(HomeLayout.infoLine("农历九月三十", ""), "农历九月三十", "infoLine, 农历 alone");
        eq(HomeLayout.infoLine("", "多云 13° – 19°"), "多云 13° – 19°", "infoLine, weather alone");
        eq(HomeLayout.infoLine("", ""), "", "infoLine, neither");
        eq(HomeLayout.infoLine(null, null), "", "infoLine, nothing at all");
        eq(HomeLayout.infoLine(" 农历九月三十 ", "  多云 "), "农历九月三十　·　多云", "infoLine trims what the bridge sends");

        // what is playing, top right
        eq(HomeLayout.nowLine("江南", "林俊杰"), "江南　林俊杰", "nowLine, title and artist");
        eq(HomeLayout.nowLine("江南", ""), "江南", "nowLine, no artist");
        eq(HomeLayout.nowLine("", "林俊杰"), "林俊杰", "nowLine, no title");
        eq(HomeLayout.nowLine("", ""), "", "nowLine, nothing playing");
        eq(HomeLayout.nowLine("abcdefghijklmnopqr", ""), "abcdefghijklmnopqr", "nowLine, exactly 18 stays whole");
        eq(HomeLayout.nowLine("abcdefghijklmnopqrs", ""), "abcdefghijklmnopq…", "nowLine, 19 is cut to 18");
        String longSong = "这是一个非常非常非常长的歌名";
        String cut = HomeLayout.nowLine(longSong, "某位歌手");
        check(cut.length() == 18 && cut.endsWith("…"), "nowLine never grows past 18: " + cut.length());
        eq(HomeLayout.nowLine(null, "林俊杰"), "林俊杰", "nowLine, no title at all");

        // the line under the focused entry's name
        eq(HomeLayout.about("音乐", "上次在听《江南》林俊杰"), "音乐　·　上次在听《江南》林俊杰", "about joins kind and note");
        eq(HomeLayout.about("视频　·　新安装", "星辰亻旅行者 2 小时前更新"), "视频　·　新安装　·　星辰亻旅行者 2 小时前更新", "about keeps the kind's own separator");
        eq(HomeLayout.about("照片", ""), "照片", "about, no note");
        eq(HomeLayout.about("照片", null), "照片", "about, no note at all");
        eq(HomeLayout.about("", "今天新安装"), "今天新安装", "about, note alone");
        eq(HomeLayout.about("", ""), "", "about, nothing");

        // the grid the type layer draws on
        near(HomeLayout.SHELF_R, 1824f, "the shelf ends on the right margin");
        near(HomeLayout.COL * HomeLayout.PER_PAGE + HomeLayout.SHELF_X, HomeLayout.SHELF_R, "seven columns fill the shelf");
        near(HomeLayout.COL, 202.85714f, "a column is 1920's seventh");

        // the axis: only as long as the shelf
        near(HomeLayout.axisEnd(1, 0f), HomeLayout.SHELF_X + HomeLayout.COL, "one entry: one column");
        near(HomeLayout.axisEnd(4, 0f), HomeLayout.SHELF_X + 4 * HomeLayout.COL, "four entries (常用): four columns");
        near(HomeLayout.axisEnd(7, 0f), HomeLayout.SHELF_R, "a full page reaches the right margin");
        near(HomeLayout.axisEnd(0, 0f), HomeLayout.SHELF_X + HomeLayout.COL, "an empty shelf still has a column");
        check(HomeLayout.axisEnd(15, 0f) > HomeLayout.SHELF_R, "a long shelf overshoots (HomeUi keeps the full width)");

        // the fade after the axis's end
        near(HomeLayout.fadeLength(HomeLayout.axisEnd(4, 0f)), HomeLayout.FADE_MAX, "a short shelf fades the full 260");
        near(HomeLayout.fadeLength(HomeLayout.SHELF_R - 40f), 40f, "near the margin the fade is what is left");
        near(HomeLayout.fadeLength(HomeLayout.SHELF_R), 0f, "at the margin there is nothing to fade");
        near(HomeLayout.fadeLength(HomeLayout.SHELF_R + 200f), 0f, "past the margin the fade is never negative");

        // the ruler's hairline
        near(HomeLayout.rulerTop(5), 776f, "five shelves: the hairline starts at 776");
        near(HomeLayout.rulerTop(1), 960f, "one shelf: 30 above its row");
        near(HomeLayout.rulerTop(0), 960f, "no shelf at all");
        near(HomeLayout.rulerBottom(), 1002f, "the hairline ends 12 under the last row");
        check(HomeLayout.rulerTop(5) < HomeLayout.INDEX_BOTTOM - 4 * HomeLayout.INDEX_STEP, "the hairline starts above the first row");

        band();
        pagesAndSlots();
        keys();
        words();
        tilt();

        System.out.println("home checks: " + ok + " ok, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final String[] DIR = {"LEFT", "RIGHT", "UP", "DOWN"};

    /** The band: a 6 x 2 grid of cells at the bottom centre (ui_home_ark.py, layout bottom). */
    static void band() {
        near(HomeLayout.BAND_X, 372f, "the band starts at 372");
        near(HomeLayout.BAND_Y, 686f, "the band's top is 686");
        near(HomeLayout.CELL_W, 186f, "a cell is 186 wide");
        near(HomeLayout.CELL_H, 138f, "a cell is 138 tall");
        near(HomeLayout.CELL_GAP, 12f, "the gap between cells is 12");
        near(HomeLayout.BAND_W, 1176f, "six columns of cells: 1176");
        near(HomeLayout.BAND_H, 288f, "two rows of cells: 288");
        near(HomeLayout.BAND_X * 2 + HomeLayout.BAND_W, 1920f, "the band is centred on the screen");
        check(HomeLayout.BAND_PER_PAGE == 8 && HomeLayout.SLOTS.length == 8, "eight slots to a page");

        float[][] want = {{372, 686, 756, 974}, {768, 686, 1152, 824}, {1164, 686, 1350, 824}, {1362, 686, 1548, 824},
                {768, 836, 954, 974}, {966, 836, 1152, 974}, {1164, 836, 1350, 974}, {1362, 836, 1548, 974}};
        float[][] mid = {{564, 830}, {960, 755}, {1257, 755}, {1455, 755}, {861, 905}, {1059, 905}, {1257, 905}, {1455, 905}};
        for (int i = 0; i < 8; i++) {
            float[] w = want[i];
            boolean same = near(HomeLayout.slotX(i), w[0]) && near(HomeLayout.slotY(i), w[1])
                    && near(HomeLayout.slotR(i), w[2]) && near(HomeLayout.slotB(i), w[3]);
            check(same, "slot " + i + " is " + (int) w[0] + "," + (int) w[1] + " to " + (int) w[2] + "," + (int) w[3]);
            check(near(HomeLayout.slotCx(i), mid[i][0]) && near(HomeLayout.slotCy(i), mid[i][1]),
                    "slot " + i + "'s centre is " + (int) mid[i][0] + "," + (int) mid[i][1]);
            check(HomeLayout.slotR(i) <= HomeLayout.BAND_X + HomeLayout.BAND_W
                    && HomeLayout.slotB(i) <= HomeLayout.BAND_Y + HomeLayout.BAND_H, "slot " + i + " is inside the band");
        }
        check(HomeLayout.slotBig(0) && !HomeLayout.slotBig(1) && !HomeLayout.slotBig(7), "only slot 0 is the 2 x 2 cell");
        check(HomeLayout.slotWide(1) && !HomeLayout.slotWide(0) && !HomeLayout.slotWide(2), "only slot 1 is the 2 x 1 cell");
        near(HomeLayout.slotW(0), 384f, "the 2 x 2 cell is 384 wide");
        near(HomeLayout.slotH(0), 288f, "the 2 x 2 cell is as tall as the band");
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    /** Pages, and which entry sits on which slot. */
    static void pagesAndSlots() {
        check(HomeLayout.pages(0) == 1, "an empty shelf still has one page");
        check(HomeLayout.pages(1) == 1 && HomeLayout.pages(4) == 1, "up to eight entries: one page");
        check(HomeLayout.pages(8) == 1, "exactly eight entries: one page");
        check(HomeLayout.pages(9) == 2 && HomeLayout.pages(15) == 2 && HomeLayout.pages(16) == 2, "nine to sixteen: two pages");
        check(HomeLayout.pages(17) == 3, "seventeen entries: three pages");
        check(HomeLayout.pageOf(0) == 0 && HomeLayout.pageOf(7) == 0 && HomeLayout.pageOf(8) == 1 && HomeLayout.pageOf(16) == 2,
                "pageOf walks every eight");
        check(HomeLayout.slotOf(0) == 0 && HomeLayout.slotOf(7) == 7 && HomeLayout.slotOf(8) == 0 && HomeLayout.slotOf(14) == 6,
                "slotOf is the rest of the eight");

        int[][] sizes = {{4, 1}, {8, 1}, {9, 2}, {15, 2}};
        for (int[] sz : sizes) {
            int n = sz[0], pages = sz[1];
            for (int p = 0; p < pages; p++) for (int s = 0; s < 8; s++) {
                int want = p * 8 + s < n ? p * 8 + s : HomeLayout.NONE;
                int got = HomeLayout.entryAt(p, s, n);
                check(got == want, n + " entries, page " + p + " slot " + s + ": entry " + got);
            }
        }
        check(HomeLayout.entryAt(0, 0, 0) == HomeLayout.NONE, "an empty shelf has no entry anywhere");
    }

    /** Where the four keys lead from every slot of a full page, and across the pages. */
    static void keys() {
        int[][] want = {
                {HomeLayout.RULER, 4, 1, 4},
                {4, 5, HomeLayout.NONE, 4},
                {5, 3, HomeLayout.NONE, 6},
                {2, HomeLayout.NONE, HomeLayout.NONE, 7},
                {0, 1, 1, HomeLayout.NONE},
                {1, 6, 1, HomeLayout.NONE},
                {5, 7, 2, HomeLayout.NONE},
                {6, HomeLayout.NONE, 3, HomeLayout.NONE}};
        for (int i = 0; i < 8; i++) for (int d = 0; d < 4; d++) {
            int got = HomeLayout.neighbor(8, i, d);
            check(got == want[i][d], "8 entries, " + DIR[d] + " from slot " + i + ": " + got);
        }
        // the 2 x 2 cell's own edges
        check(HomeLayout.neighbor(8, 0, HomeLayout.LEFT) == HomeLayout.RULER, "LEFT from the 2 x 2 cell leaves for the ruler");
        check(HomeLayout.neighbor(8, 0, HomeLayout.UP) == 1 && HomeLayout.neighbor(8, 0, HomeLayout.DOWN) == 4,
                "UP and DOWN from the 2 x 2 cell reach both rows");
        // the page turns
        check(HomeLayout.neighbor(9, 3, HomeLayout.RIGHT) == 8, "9 entries: RIGHT at the right edge turns the page");
        check(HomeLayout.neighbor(9, 7, HomeLayout.RIGHT) == 8, "9 entries: RIGHT from the last slot turns the page");
        check(HomeLayout.neighbor(9, 8, HomeLayout.LEFT) == 7, "9 entries: LEFT goes back to the last of the first page");
        check(HomeLayout.neighbor(9, 8, HomeLayout.RIGHT) == HomeLayout.NONE, "9 entries: the last page has nothing right");
        check(HomeLayout.neighbor(9, 8, HomeLayout.UP) == HomeLayout.NONE, "9 entries: a lone slot has nothing above");
        check(HomeLayout.neighbor(9, 8, HomeLayout.DOWN) == HomeLayout.NONE, "9 entries: a lone slot has nothing below");
        check(HomeLayout.neighbor(15, 7, HomeLayout.RIGHT) == 8, "15 entries: RIGHT turns to the second page");
        check(HomeLayout.neighbor(15, 8, HomeLayout.LEFT) == 7, "15 entries: LEFT goes back to the first page");
        check(HomeLayout.neighbor(15, 14, HomeLayout.RIGHT) == 11, "15 entries: RIGHT from the bottom row reaches the top one");
        check(HomeLayout.neighbor(15, 11, HomeLayout.RIGHT) == HomeLayout.NONE, "15 entries: the last page's right edge has nothing right");
        check(HomeLayout.neighbor(15, 14, HomeLayout.LEFT) == 13, "15 entries: LEFT walks the second page");
        check(HomeLayout.neighbor(15, 14, HomeLayout.UP) == 10, "15 entries: UP from slot 6 lands on slot 2 of that page");
        // a short shelf: the empty slots are not there to walk into
        check(HomeLayout.neighbor(4, 0, HomeLayout.LEFT) == HomeLayout.RULER, "4 entries: LEFT from the first leaves for the ruler");
        check(HomeLayout.neighbor(4, 0, HomeLayout.DOWN) == HomeLayout.NONE, "4 entries: the second row is empty");
        check(HomeLayout.neighbor(4, 0, HomeLayout.UP) == 1, "4 entries: UP still reaches the wide cell");
        check(HomeLayout.neighbor(4, 2, HomeLayout.RIGHT) == 3, "4 entries: RIGHT along the first row");
        check(HomeLayout.neighbor(4, 3, HomeLayout.RIGHT) == HomeLayout.NONE, "4 entries: nothing right of the last");
        check(HomeLayout.neighbor(4, 3, HomeLayout.LEFT) == 2, "4 entries: LEFT along the first row");
        check(HomeLayout.neighbor(0, 0, HomeLayout.LEFT) == HomeLayout.NONE, "an empty shelf has nowhere to go");
        check(HomeLayout.neighbor(8, 8, HomeLayout.LEFT) == HomeLayout.NONE, "an index off the shelf goes nowhere");
    }

    /** What a cell says: the English line, the name, the state line. */
    static void words() {
        String[][] en = {{"星仪电视助手", "XINGYI · ASSISTANT"}, {"网易云音乐", "NETEASE · MUSIC"},
                {"云视听极光", "TENCENT · VIDEO"}, {"哔哩哔哩", "BILIBILI · VIDEO"}, {"HDMI 1", "SOURCE · HDMI 1"},
                {"HDMI 3", "SOURCE · HDMI 3"}, {"无线投屏", "CAST"}, {"应用商店", "APP STORE"}, {"设置", "SETTINGS"},
                {"电视管家", "MANAGER"}, {"相册", "GALLERY"}, {"TV Bro", "TV BRO · BROWSER"}, {"U盘 / 媒体", "MEDIA"},
                {"夸克网盘", "QUARK · CLOUD"}, {"全部应用", "ALL APPS"}, {"信号源", "SOURCE · HDMI"}};
        for (String[] e : en) eq(HomeLayout.english(e[0], null), e[1], "english(" + e[0] + ")");
        eq(HomeLayout.english("某音乐", null), "MUSIC", "english, a music app by its name");
        eq(HomeLayout.english("欢乐斗地主", null), "GAME", "english, a game by its name");
        eq(HomeLayout.english("南瓜视频", null), "VIDEO", "english, a video app by its name");
        eq(HomeLayout.english("天气", null), "APP", "english, an app nothing is known about");
        eq(HomeLayout.english("", null), "APP", "english, no label at all");
        eq(HomeLayout.english(null, null), "APP", "english, nothing at all");
        eq(HomeLayout.english("随便什么", "tv.danmaku.bili"), "BILIBILI · VIDEO", "english, by package");
        eq(HomeLayout.english("随便什么", "com.ktcp.video"), "TENCENT · VIDEO", "english, by the store's package");
        eq(HomeLayout.english("云视听极光", "com.ktcp.video"), "TENCENT · VIDEO", "english, the label wins over the package");

        eq(HomeLayout.name("星仪电视助手"), "星仪", "the assistant is 星仪 in a cell");
        eq(HomeLayout.name("网易云音乐"), "网易云音乐", "every other name is the label");
        eq(HomeLayout.name("  相册 "), "相册", "the name is trimmed");
        check(HomeLayout.xingyi("星仪电视助手") && !HomeLayout.xingyi("星仪"), "only the assistant's own label is 星仪's");

        eq(HomeLayout.stateLine("音乐", "上次在听《江南》林俊杰"), "上次在听《江南》林俊杰", "the state line is the note when there is one");
        eq(HomeLayout.stateLine("音乐", ""), "音乐", "the state line falls back to the kind");
        eq(HomeLayout.stateLine("音乐", null), "音乐", "the state line, no note at all");
        eq(HomeLayout.stateLine("音乐", "   "), "音乐", "the state line, a blank note");
        eq(HomeLayout.stateLine("", "新安装"), "新安装", "the state line, a note alone");
        eq(HomeLayout.stateLine(null, null), "", "the state line, nothing");
        eq(HomeLayout.agentState("待命"), "待命　·　叫一声「小爱同学」", "星仪's line, waiting");
        eq(HomeLayout.agentState("执行中"), "执行中　·　叫一声「小爱同学」", "星仪's line, working");
        eq(HomeLayout.agentState(""), "待命　·　叫一声「小爱同学」", "星仪's line, nothing said yet");
        eq(HomeLayout.agentState(null), "待命　·　叫一声「小爱同学」", "星仪's line, no bridge");

        eq(HomeLayout.weatherOf("农历九月三十　·　多云 13° – 19°"), "多云 13° – 19°", "the weather out of the third line");
        eq(HomeLayout.weatherOf("多云 13° – 19°"), "多云 13° – 19°", "the weather alone");
        eq(HomeLayout.weatherOf(""), "", "no third line, no weather");
        eq(HomeLayout.weatherOf(null), "", "no third line at all");
        // the 农历 is never the weather: only a part that carries a temperature is one
        eq(HomeLayout.weatherOf("农历九月三十"), "", "the 农历 alone is not the weather");
        eq(HomeLayout.weatherOf(" 农历九月三十 "), "", "the 农历 alone, trimmed, is not the weather either");
        eq(HomeLayout.weatherOf("农历九月三十　·　"), "", "the 农历 and nothing after it");
        eq(HomeLayout.weatherOf("农历九月三十　·　多云"), "", "weather without a temperature is not weather");
        eq(HomeLayout.weatherOf("农历九月三十　·　晴 -3° – 2°"), "晴 -3° – 2°", "a temperature below zero, with a minus");
        eq(HomeLayout.weatherOf("晴 -3° – 2°"), "晴 -3° – 2°", "the same, on its own");
        eq(HomeLayout.weatherOf("小雪 -1°"), "小雪 -1°", "one temperature below zero");
        eq(HomeLayout.weatherOf("多云 13°"), "多云 13°", "one temperature above zero");

        eq(HomeLayout.shelfCount("常用", 8), "常用 · 08", "the band label's shelf and count");
        eq(HomeLayout.shelfCount("工具", 15), "工具 · 15", "a longer shelf counts in two digits");
        eq(HomeLayout.pageAt(0, 2), "01 / 02", "the first of two pages");
        eq(HomeLayout.pageAt(1, 2), "02 / 02", "the last of two pages");
        eq(HomeLayout.pageAt(0, 3), "01 / 03", "the first of three pages");
    }

    /** The tilt: the same 3-D projection android.graphics.Camera does, measured on the band's own corners. */
    static void tilt() {
        float bx = HomeLayout.BAND_X, by = HomeLayout.BAND_Y;
        float bw = HomeLayout.BAND_W, bh = HomeLayout.BAND_H;
        float px = bx + bw / 2f, py = by + bh;               // the band leans back about its bottom centre
        float tilt = HomeLayout.BAND_TILT, cam = HomeLayout.CAM_PX;
        float[] bl = HomeLayout.project(bx, by + bh, tilt, cam, px, py);
        float[] br = HomeLayout.project(bx + bw, by + bh, tilt, cam, px, py);
        float[] tl = HomeLayout.project(bx, by, tilt, cam, px, py);
        float[] tr = HomeLayout.project(bx + bw, by, tilt, cam, px, py);
        near(bl[0], bx, "the bottom left corner does not move");
        near(bl[1], by + bh, "the bottom edge stays where it is");
        near(br[0], bx + bw, "the bottom right corner does not move");
        near(tl[0] + tr[0], 2 * px, "the top edge stays centred on the pivot");
        check(tl[1] > by && tl[1] < by + bh, "the top edge leans back down the screen: " + tl[1]);
        check(by + bh - tl[1] > bh * 0.9f, "the band keeps most of its height: " + (by + bh - tl[1]));

        float bottom = br[0] - bl[0], top = tr[0] - tl[0];
        float narrow = (1f - top / bottom) * 100f;
        check(narrow > 3f && narrow < 4f, "the top edge is 3-4 % narrower than the bottom one: " + narrow + " %");
        check(Math.abs(narrow - HomeLayout.narrowing()) < 0.01f, "narrowing() is what project() measures: " + HomeLayout.narrowing());
        near(HomeLayout.projectScale(by + bh, tilt, cam, py), 1f, "nothing narrows at the pivot");
        check(HomeLayout.projectScale(by, tilt, cam, py) < 1f, "what is above the pivot narrows");
        check(HomeLayout.projectScale(by + bh + 40f, tilt, cam, py) > 1f, "what is below the pivot widens");
        near(HomeLayout.projectScale(by, 0f, cam, py), 1f, "no tilt, no narrowing");
        check(HomeLayout.projectScale(by, tilt, cam * 2f, py) > HomeLayout.projectScale(by, tilt, cam, py),
                "a camera farther away narrows less");
        check(HomeLayout.projectScale(by, tilt * 2f, cam, py) < HomeLayout.projectScale(by, tilt, cam, py),
                "a harder tilt narrows more");
        near(HomeLayout.CAM_PX / HomeLayout.CAM_UNITS, 2200f / 72f, "Camera.setLocation takes the distance in 1/72 px");
        // the band's own numbers, so a change of the grid cannot silently change the tilt
        near(HomeLayout.project(bx, by, tilt, cam, px, py)[0] - bx, 20.48f, "the top left corner moves in by 20.5 px");
    }
}
