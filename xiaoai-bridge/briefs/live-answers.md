# Brief: live answers — web search for real-time questions + follow-up memory

## Why
When XiaoAi cannot answer a question, the bridge asks a model (`Llm.ask`, MiniMax-M3.1-Flash through an
OpenAI-compatible gateway) and speaks the answer. Two gaps:
1. Real-time questions ("今天金价多少", "明天北京天气", "刚才的比分") get "我查不了实时数据". The same gateway offers
   OpenAI's **Responses API with the `web_search` tool** (model `gpt-5.4-mini`): measured 5.2 s, real search, answers like
   "今天上海金早盘价847.21元/克…". A captured real response is in `tests/fixtures/responses-websearch.json`.
2. Follow-ups ("那明天呢？", "他多大了？") have no context: every question is asked alone.

## What to build
1. **`src/xyz/erapi0neer/xiaoaibridge/Realtime.java`** (new, pure Java, no android.*):
   `static boolean needsSearch(String question)` — true when the answer depends on live data:
   - any of these words alone: 天气 气温 下雨 下雪 雾霾 空气质量 股价 股票 汇率 金价 油价 热搜 新闻 比分 赛果 赛程 票房 开奖 限行 航班 地震 疫情;
   - or a time word (今天 今日 现在 此刻 目前 当前 最新 实时 刚刚 刚才 昨天 明天 后天 本周 这周 今年 最近) together with a data word
     (价格 多少钱 价钱 报价 行情 排名 结果 成绩 名单 发布 上映 播出 涨 跌 几度 多少度 冠军).
   - false otherwise (stable knowledge: "量子纠缠是什么", "李白是哪个朝代的", "先有鸡还是先有蛋").
   - null / empty -> false.
2. **`src/xyz/erapi0neer/xiaoaibridge/ResponsesText.java`** (new, pure Java, uses org.json):
   - `static String extract(String json)`: speech-ready text of a Responses API reply. Use top-level `output_text` if it is a non-empty string;
     otherwise concatenate `text` of every `output[i].content[j]` where `output[i].type == "message"` and `content[j].type == "output_text"`.
     Then remove citations and links: a parenthesized markdown link like `([sge.com.cn](https://...))` is removed entirely (with its parentheses);
     a plain markdown link `label` becomes `label`; bare `http(s)://...` URLs are removed; markdown emphasis `*` `#` `` ` `` is removed;
     whitespace collapsed; trimmed. Unparseable JSON or nothing found -> "" (never throw).
   - `static boolean usedSearch(String json)`: true if any `output[i].type == "web_search_call"`.
   - Expected for the fixture: `extract` = `今天上海金早盘价847.21元/克，午盘价841.08元/克。`, `usedSearch` = true.
3. **`src/xyz/erapi0neer/xiaoaibridge/Conversation.java`** (new, pure Java): thread-safe memory of the last 3 turns.
   `void add(String question, String answer, long epochMillis)`, `java.util.List<String[]> recent(long nowMillis)` returns {question, answer}
   pairs of turns newer than 180000 ms, oldest first (a copy), `void clear()`. Keep at most 3 turns (drop the oldest).
4. **`Llm.java`** changes (keep everything that exists working exactly as now, including `ask(String, int)` and `forSpeech`):
   - Config: `llm.json` may contain `"search_model"`; default `"gpt-5.4-mini"` when absent. Store it in a final field `searchModel`.
   - A `Conversation` instance owned by the Llm object.
   - New `String answer(String question, int timeoutMs) throws IOException`:
     history = recent turns; if `Realtime.needsSearch(question)` -> `askWithSearch`, else a chat request like `ask` but with the history
     inserted as alternating user/assistant messages between the system message and the new user message.
     On success: remember the turn (question, answer) and return `forSpeech(answer)`.
   - `askWithSearch`: POST `{base}/v1/responses` (non-streaming) with JSON
     `{"model": searchModel, "instructions": <system prompt>, "input": [history turns as {"role":"user"|"assistant","content":...}..., {"role":"user","content":question}], "tools":[{"type":"web_search"}], "max_output_tokens": 800}`.
     The system prompt is the existing PROMPT but its real-time sentence ("涉及实时信息…") replaced by
     "需要实时信息时先联网搜索，用一两句话说清结论，可以带上来源名称，不要念网址。". Timeout = timeoutMs overall.
     Parse with `ResponsesText.extract`. If the HTTP status is not 200, the text is empty, or an IOException happens: fall back to the plain
     chat path (without search) so the user still gets an answer.
   - Same Authorization header and key as `ask`. Read responses with the existing bounded-read style (no java.nio.file, Java 8).
5. **`provision.py`**: add `"search_model": "gpt-5.4-mini"` to the JSON it writes (keep everything else).
6. **Tests** (new files): `tests/RealtimeTest.java` (>= 20 true and >= 20 false cases; summary label `(realtime)`),
   `tests/ResponsesTextTest.java` (the fixture + >= 6 synthetic JSON cases incl. no message, bad JSON, link variants; label `(responses text)`),
   `tests/ConversationTest.java` (expiry, max 3, order, copy semantics; label `(conversation)`).
   Each prints `PASS <n> passed, 0 failed (<label>)` and exits 1 on any failure. Read the fixture from `tests/fixtures/responses-websearch.json`
   (path relative to the repo root; the test runner runs from the repo root).

## Do NOT change
`Takeover.java` (the lead switches the call site to `answer(...)` after merging), `BridgeService.java`, the song modules, existing tests.
