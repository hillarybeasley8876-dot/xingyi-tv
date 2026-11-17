# Brief: heard-homophones — an everyday word is not an earlier name: 「未来几天天气」 gets no 「蔚来」 hint, 「王玥」 is not 「王珺」

## Why (review of 2026-11-09, both findings confirmed)
`Heard` builds `heard_like` (`tv/AgentEngine.java:1617`). It goes to the model with every request that reaches the model, and it lists
the words of the request that may be a name an earlier answer spelled right (「质朴」→ 智谱, 「MAX」→ MINIMAX). The contract
tells the model to use the earlier spelling when that makes sense (「说得通就按以前的写法去查」). A wrong hint can therefore
send the model after the wrong name. There are two kinds of wrong hints:

1. **Every 2-character slice is compared.** `words()` (Heard.java:128–144) takes every 2–4-character slice of each Chinese
   run, including slices that cross two words. `alike()` (Heard.java:279) compares a 2-character slice with every 2-character
   name of the last 200 turns, using first readings without tones. 2-character names are common in answers: song titles
   (「网易云正在播放周杰伦《稻香》」 is written to history) and stock names in Quotes.speak's form (「蔚来 5.12 美元」). So:
   - after a 蔚来 quote, 「未来几天天气」 gets 「「未来」可能是刚才说过的「蔚来」」;
   - after 《稻香》, 「明天到香港的天气怎么样」 gets 到香 → 稻香;
   - 《夜曲》 gives 「你也去吗」 → 也去;
   - 《花海》 gives 「华海药业的股价」 → 华海.

   The TV's own 30-day log already has 《她说》 (11月6日), so 「马斯克他说了什么」 gets 他说 → 她说.
2. **A character missing from the pinyin table disappears.** `Pinyin.kept()` (Pinyin.java:123–131, line 127) keeps only the
   6763 GB2312 characters of PinyinData, plus ASCII. It drops 玥 珺 堃 昇 镕 喆 (common in given names), but
   `Heard.keptLength` (Heard.java:346) still counts them. So 「王玥」 is compared as 「王」 with 「王珺」, which is also
   compared as 「王」: same sound. 「王玥是谁」 after an answer that quoted 「王珺」 gets 「「王玥」可能是…说过的「王珺」」.
   「朱镕基」 becomes 「朱基」 and is found inside 「主机游戏」 (zhu ji). Heard.java:280–281 is meant to rule out exactly
   this: a 2-character stretch is too short to look for inside a longer name.

What must stay:
- the real hints: 「查询质朴和MINIMAX股价并且进行比较」 → 质朴→智谱, 「毛台」 → 茅台, 「查询MAX最新的股价」 → MAX→MINIMAX;
- every existing HeardChecks and PinyinChecks case;
- the replay of the TV's 30-day log (`HeardChecks.replay`). It was tuned to give exactly its 3 right hints
  (OPENA→OpenAI, MAX→MiniMax, TIBA→Tibo) and nothing else.

## What to build
1. Add `static boolean standsAlone(String s, int start, int end)` to Heard. Make it package-private and pure. It never throws:
   for null or out-of-range input it returns false. It says whether the Chinese word s[start, end) stands on its own in the
   request s, and it is true only when both sides are open. A "Chinese character" is Heard's `han(c)` (U+4E00–U+9FFF).
   - The left side is open when any of these holds:
     - `start == 0`;
     - the character before the word is not a Chinese character (a letter, digit, space or punctuation);
     - that character is a joining character;
     - `s.substring(0, start)` ends with a lead word.
   - The right side is open when any of these holds:
     - `end == s.length()`;
     - the character after the word is not a Chinese character;
     - that character is a joining character;
     - `s.startsWith(w, end)` for some follow word w.
   - Joining characters: 的 和 跟 与 及 或 是 吗 呢 吧 啊 呀 嘛 (了 is not one of them).
   - Lead words: 一下 查询 查 搜索 搜 放 打开 听 看 问 关于 还有 对比 比较 (「查一下」 and 「搜一下」 end with 一下, 「播放」 ends with 放).
   - Follow words: 股 怎么 怎样 今天 最近 最新 现在 多少 为什么 涨 跌.

   Keep the three lists as constants and use exactly these entries, because the checks below depend on them.
2. In `Heard.hints`, compare a 2-character Chinese word of the request with earlier names only when
   `standsAlone(utterance, start, end)` is true. Compare 3–4-character Chinese words and Latin words as now. Keep building the
   `said` set from every word, including 2-character words that do not stand alone. That set is what stops a hint when the
   name is in the request as it stands. One way to do this: give `Word` a flag `alone`, set it in `words()`, and skip words
   that are not alone only in the matching loop. Update the javadoc of `words()`.
3. In `Pinyin.kept()`, keep a Chinese character (U+4E00–U+9FFF) that has no reading in the table as itself, instead of
   dropping it:
   `if (table().containsKey(c) || (c >= 0x4E00 && c <= 0x9FFF)) b.append(c); else if (ascii(c)) b.append(Character.toLowerCase(c));`.
   - `alike` and `mainAlike` already compare characters without readings literally, so such a character equals only itself
     (玥 = 玥, 玥 ≠ 珺, 镕 ≠ 机).
   - The change reaches `sameSound`, `soundIndex`, `sameMainSound` and `mainSoundIndex` alike.
   - `spell()`, `initials()`, `hasChinese()` and `readings()` stay as they are.
   - `Quotes.bySound` and `Quotes.unlisted` use sameSound and soundIndex, and their PinyinChecks must still pass. None of the
     saved search answers has a character outside the table.
   - Fix the javadocs that say "the characters spell() keeps".
4. Add `static int keptLength(String s)` to Pinyin (package-private). It returns how many characters `kept(s)` keeps; null
   gives 0. `Heard.Name.kept` uses it, and Heard's own `keptLength` is removed, so the two counts can never differ again.
5. Add a new method `homophones()` to `tests/agent/HeardChecks.java` and call it from `checks()`. Use the file's `line` and
   `hint` helpers, with 「查一下」 or "" as the earlier turn's `said` and 「刚才」 as its `when`. It needs at least these checks:
   - **No hint (""):**
     - 「未来几天天气」 after 「蔚来 5.12 美元。」;
     - 「未来三天会下雨吗」 after 「蔚来 5.12 美元。」;
     - 「明天到香港的天气怎么样」 after 「网易云正在播放周杰伦《稻香》。」;
     - 「马斯克他说了什么」 after 「电视已经开着了，屏幕亮着，网易云音乐的《她说》还是暂停状态。」 (the 30-day log's own answer);
     - 「你也去吗」 after 「网易云正在播放周杰伦《夜曲》。」;
     - 「华海药业的股价」 after 「网易云正在播放《花海》。」.
   - **`standsAlone` is false** for ("未来几天天气", 0, 2), ("明天到香港的天气怎么样", 2, 4) (到香),
     ("马斯克他说了什么", 3, 5) (他说), (null, 0, 2) and ("质朴", 1, 3).
   - **`standsAlone` is true** for ("查询质朴和", 2, 4), ("TIBA和质朴", 5, 7), ("质朴", 0, 2), ("质朴股价", 0, 2) and
     ("我说的是质朴吗", 4, 6).
   - **Still hinted:**
     - 「质朴股价」 after 「智谱 426.20 港元。」 → hint("质朴", "智谱", "刚才");
     - 「质朴今天为什么跌」 after 「智谱 426.20 港元。」 → the same hint;
     - 「查一下毛台」 after 「茅台 1453.08 元。」 → hint("毛台", "茅台", "刚才");
     - 「查询质朴和MINIMAX股价并且进行比较」 after 「智谱 426.20 港元。」 → hint("质朴", "智谱", "刚才");
     - 「查询MAX最新的股价」 after 「MINIMAX 155.80 港元。」 → hint("MAX", "MINIMAX", "刚才").
   - **Characters outside the table:**
     - `!Pinyin.sameMainSound("王玥", "王珺")` and `!Pinyin.sameSound("王玥", "王珺")`;
     - `!Pinyin.sameMainSound("王堃", "汪昇")`;
     - `Pinyin.sameMainSound("王玥", "汪玥")` (a rare character equals itself);
     - `Pinyin.mainSoundIndex("朱镕基", "主机游戏") < 0` and `Pinyin.soundIndex("朱镕基", "主机游戏") < 0`;
     - `Pinyin.keptLength` gives 2 for "王玥", 3 for "朱镕基", 7 for "MINI MAX", 0 for "，。" and 0 for null;
     - no hint for 「王玥是谁」 after 「你说的应该是「王珺」。」;
     - no hint for 「朱镕基是谁」 after 「最近的「主机游戏」卖得最好。」.

   Leave every existing check exactly as it is.

## Rules
- Only the files you own. Do not open or grep `tests/agent/AgentCoreTest.java` (parts of it make the API refuse requests).
- No TV, no adb, no network.
- Java 8 (run_core compiles with `--release 8`). Android: no `Pattern.UNICODE_CHARACTER_CLASS` or `(?U)`; read optional JSON
  strings with `isNull` first.

## Acceptance (run all of them yourself before you finish)
- `sh tests/agent/run_core.sh` → `agent core: N passed, 0 failed` with N ≥ 3529 (it is 3523 now; at least 6 new checks)
- `sh tests/agent/android_check.sh` → `ANDROID CHECK OK`
