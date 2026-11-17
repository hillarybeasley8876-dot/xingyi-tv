# Brief: tencent-episode — 「我想看繁花第5集」 opens that episode

## Why (the web API asked on the PC 2026-11-11 01:20; the link tried on the TV 20:01)
`tencent_video_open` opens a show, never an episode. Both halves of what an episode needs are known now:
- Tencent Video's own web page reads a show's episodes from
  `POST https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vplatform=2`
  (same Referer / Origin as the search — `Feeds.httpsPost()` already sets them for this host). One page holds 30
  entries; a longer show comes with tabs, each with the `page_context` for the next request.
- On the TV, `tenvideo2://?action=7&cover_id=<cid>&video_id=<vid>` opened exactly that episode (the pause overlay read
  「别无选择 第3集」 for the third episode's vid).

Fixtures (slim, same shape as the real answers; `tests/agent/fixtures/slim_tencent_episodes.py` shows what is kept):
`tencent-episodes-fanhua.json` (30 episodes + six extras, members only from 3 on, no tabs),
`-biewu.json` (30 of 31, tabs 1–30 / 31–31), `-qingyunian2.json` + `-qingyunian2-p2.json` (36 episodes on two tabs),
`-liulang2.json` (a film: one entry, no number), `-paonan.json` + `-paonan-p2.json` (a variety show: 「第1期：…」,
「第1期加更：…」; its tabs count entries, not periods), `-santi.json` (30 + 「彩蛋」 extras on one page),
`-lingjie.json` + `-lingjie-p2.json` (678 short episodes, titles like 「【…】第31集」).

The shape: `data.module_list_datas[].module_datas[]`, each with `module_params` (`tabs`: a JSON **string** holding
`[{begin, end, selected, page_context}]`, or `""`; `sub_title`) and `item_data_lists.item_datas[]`; an entry is an
episode when `item_type` is `"1"` and its `item_params.vid` is not empty. `item_params`: `title` (「3」 for a numbered
episode, text for anything else), `vid`, `play_title` (「繁花 第03集」), `duration` (seconds, a string), `is_trailer`
(`"1"` for a trailer), `video_subtitle` (one line about the episode), `uni_imgtag` (a JSON string; it contains `VIP`
for a members-only episode).

## What to build

### 1. `TencentVideo.java` (pure)
```java
public static final String EPISODES = "https://pbaccess.video.qq.com/trpc.universal_backend_service.page_server_rpc.PageServer/GetPageData?video_appid=3000010&vplatform=2";
public static String episodesBody(String cid, String pageContext) throws JSONException
//  {"page_params":{"req_from":"web_vsite","page_id":"vsite_episode_list","page_type":"detail_operation","id_type":"1",
//   "page_size":"","cid":<cid>,"vid":"","lid":"","page_num":"","page_context":<pageContext or "">,"detail_page_type":"1"},"has_cache":1}
public static final class Episode { public final String title, vid, playTitle, about; public final int number, seconds; public final boolean vip, trailer; }
public static final class Tab { public final int begin, end; public final boolean selected; public final String context; }
public static final class Episodes {
    public final List<Episode> list; public final List<Tab> tabs; public final String subTitle;
    public Episode find(int n)          // null when this page has no such episode
    public List<String> contextsFor(int n)   // the page_contexts worth asking next, in order, at most 4
    public int last()                   // the largest episode number on this page, 0 when none is numbered
}
public static Episodes episodes(String json) throws JSONException   // a reply without data → an empty Episodes
public static String slimEpisodes(String json)                       // as slim(): only what episodes() reads; anything else unchanged
public static String playLink(String cid, String vid)                // tenvideo2://?action=7&cover_id=<cid>&video_id=<vid>; vid [A-Za-z0-9]{6,20}
```
- `number`: the title as an integer when it is 1 to 4 digits, else 0.
- `find(n)`, in this order, never a trailer: (1) the entry whose `number` is n; (2) the first entry whose title starts
  with 「第n期」 (n as digits or a Chinese numeral, spaces allowed inside) **not** followed by 加更, 特别, 下 or 中 — a
  variety show's main part; (3) the first entry whose title contains 「第n集」, 「第n期」 or 「第n话」 (digits or a Chinese
  numeral) not followed by another digit.
- `contextsFor(n)`: first the tab with `begin <= n <= end` when it is not the selected one; then the other tabs that
  are not selected, in their order; at most 4 in all; a tab without a context is skipped.
- Chinese numerals 1 to 99 are enough (十, 十一, 二十, 二十一 …).

### 2. `tv/TvTools.java`, tool `tencent_video_open` only
- A new argument `episode` (int; the lead adds it to the contract — you do not touch the contract). Absent or < 1: as
  today.
- With an episode, after the show is found on Tencent (FOUND, not `upcoming`; ELSEWHERE / NONE answer as today):
  1. the first page: `EPISODE_FEED.post(TencentVideo.EPISODES, TencentVideo.episodesBody(cid, ""), 6000)`, where
     `EPISODE_FEED` is a new feed beside `TENCENT_FEED`, cached ten minutes and slimmed (`slimEpisodes`) the same way;
  2. `find(n)`; when null, ask the `contextsFor(n)` pages one by one until one has it (each
     `episodesBody(cid, context)`);
  3. none → `fail("no_episode", "《" + title + "》在腾讯视频没有第 " + n + " 集" + (show.episodes > 0 ? "（一共 " + show.episodes + " 集）" : ""))`
     — nothing is opened; a feed that cannot be read → `fail("error", "腾讯视频的选集没连上：可以先打开这部片子，再用遥控器选集")`;
  4. found → open `TencentVideo.playLink(cid, vid)` exactly as the play link is opened today (the same wait for
     `TVPlayerActivity`), and the message is
     `"已在云视听极光播放《" + show.title + "》第 " + n + " 集" + (episode.vip ? "；这一集要会员才能看全" : "；不是会员的话正片前有一两分钟广告")`
     (the "also" part is left out for an episode).
- `strict` (the quick command): with an episode, the episode has to be found as well, else `not_sure`.
- With `play` false and an episode: the episode wins (it is played).
- The show remembered for `media_now_playing` (job ktcp-state: two static fields) gets 「第 n 集」 appended to its title
  when an episode was opened.
- Nothing else in this file; no new `import` lines.

### 3. `QuickCommand.java`, the matcher `watch` only
A title may end with 「第N集」 / 「第N期」 / 「第N话」 (N digits or a Chinese numeral, 1 to 999): the title is what is before
it (the usual title rules apply to that), and the command gets `"episode", N`. (Today such a sentence is left to the
model by `WATCH_EPISODE`; a 「第…集」 anywhere else in the title still is.) 「最新一集」 / 「最后一集」 stay with the model.

## Expected (`TencentVideo.episodes(fixture)`)
| fixture | entries | find | result |
|---|---|---|---|
| fanhua | 36, last() 30, no tabs, subTitle 会员看全集 | 1 | vid a0047l69jnp, not vip, playTitle 繁花 第01集, about 王家卫执导！顶级阵容演绎沪上风云 |
| fanhua | | 3 | w0047o2kopn, **vip** |
| fanhua | | 30 | j0047aj1c1n, vip, seconds 3171 |
| fanhua | | 31 | null; `contextsFor(31)` empty |
| biewu | 30, tabs (1–30 selected, 31–31) | 3 | p0027xysx65, not vip |
| biewu | | 31 | null; `contextsFor(31)` has one context (the 31–31 tab's) |
| qingyunian2 | 30 | 30 | y4100r10687, vip |
| qingyunian2 | | 31 | null; `contextsFor(31)` = the 31–36 tab's context |
| qingyunian2-p2 | 7 (31–36 and one extra 「彩蛋」) | 31 | u4100vc3n8u, vip |
| qingyunian2-p2 | | 36 | v4100ydmxec |
| qingyunian2-p2 | | 37 | null |
| liulang2 | 1, last() 0 | 1 | null (a film has no episodes) |
| paonan | 30, last() 0 | 1 | p41029oi70w (「第1期：跑男团X时代少年团撕名牌大乱斗」, not the 加更) |
| paonan | | 10 | j4102w8onm4 |
| paonan | | 11 | null; `contextsFor(11)` = the other tab's context (the 1–30 tab is the selected one) |
| paonan-p2 | 6 | 11 | n4102q9atln |
| paonan-p2 | | 12 | l4102bn6gub |
| santi | 40, last() 30 | 30 | o00450p4b98, vip |
| santi | | 31 | null |
| lingjie | 30, last() 0 | 30 | c3548lseum8 |
| lingjie | | 31 | null; `contextsFor(31)` starts with the 31–60 tab's context and has 4 entries |
| lingjie-p2 | 30 | 31 | p3548o0iinp |
| lingjie-p2 | | 60 | a3549cabzcz |

## Checks
- `tests/agent/MovieChecks.java`, a new group `tencentEpisodes()` called from `run()`: the table; `episodesBody`
  (parse it back: `cid`, `page_context`, `page_id`); `playLink("3z4n09p1pzelftp", "p0027xysx65")` is
  `tenvideo2://?action=7&cover_id=3z4n09p1pzelftp&video_id=p0027xysx65`, a bad vid throws
  `IllegalArgumentException`; `episodes(slimEpisodes(x))` finds the same as `episodes(x)` for every fixture;
  `episodes("{\"ret\":1}")` is empty. At least 45 checks.
- `tests/agent/QuickChecks.java`, **inside the existing group `watch()`** (no new group, no new line in `run()`):
  我想看别无选择第3集 → `{"title":"别无选择","episode":3,"strict":true}` · 我想看庆余年第二季第五集 → title 庆余年第二季,
  episode 5 · 我想看电视剧繁花第10集 → title 繁花, kind 电视剧, episode 10 · 看别无选择第三十一集 → episode 31 ·
  我想看奔跑吧第2期 → title 奔跑吧, episode 2; still `none`: 我想看繁花第5集 (the title is 2 characters and no kind) ·
  我想看第5集 · 我想看别无选择最新一集 · 我想看别无选择第0集. At least 9 checks; the `none("我想看繁花第5集")` that is
  there stays as it is.

## Rules
- Only the files you own. Never open or grep `tests/agent/AgentCoreTest.java` or `src/xyz/erapi0neer/xingyi/LocalAdb.java`.
  No TV, no adb, no network. Java 8 / Android API 28. Do not edit the fixtures or `assets/agent/contract.json`.
- Another job (media-seek) works in `QuickCommand.java`, `TvTools.java` and `QuickChecks.java` at the same time: in
  `QuickCommand` touch only `watch(...)` and what it uses, in `TvTools` only the `tencent_video_open` tool and the feed
  constants beside `TENCENT_FEED`, in `QuickChecks` only the body of `watch()`.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → last line `agent core: N passed, 0 failed` with N at least 54 more than before you
  started (the job's check requires the number in the job file)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
- `sh tests/agent/accept_tencent_episode.sh` → `TENCENT EPISODE OK`
