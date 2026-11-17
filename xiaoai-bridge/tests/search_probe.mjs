// Does the gateway offer real web search? Tries the Responses API web_search tool and a few model-native options.
// node search_probe.mjs   (GATEWAY_API_KEY from the environment; never printed)
const KEY = process.env.GATEWAY_API_KEY;
const BASE = 'https://gateway.example.com';
const Q = '今天（2026年11月4日）国内黄金价格每克多少元？只回答一句话，并给出数据来源网站名。';

async function post(path, body) {
  const t0 = Date.now();
  const r = await fetch(BASE + path, { method: 'POST', headers: { authorization: `Bearer ${KEY}`, 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const text = await r.text();
  return { status: r.status, ms: Date.now() - t0, text };
}

const trials = [
  ['responses gpt-5.4-mini web_search', '/v1/responses', { model: 'gpt-5.4-mini', input: Q, tools: [{ type: 'web_search' }] }],
  ['chat gpt-5.4-mini web_search_options', '/v1/chat/completions', { model: 'gpt-5.4-mini', web_search_options: {}, messages: [{ role: 'user', content: Q }] }],
  ['chat gemini-3.8-flash googleSearch tool', '/v1/chat/completions', { model: 'gemini-3.8-flash', tools: [{ googleSearch: {} }], messages: [{ role: 'user', content: Q }] }],
  ['chat grok-4.3 search_parameters', '/v1/chat/completions', { model: 'grok-4.3', search_parameters: { mode: 'on' }, messages: [{ role: 'user', content: Q }] }],
];
for (const [name, path, body] of trials) {
  try {
    const r = await post(path, body);
    let answer = r.text;
    try {
      const j = JSON.parse(r.text);
      answer = j.output_text ?? j.choices?.[0]?.message?.content ??
        (j.output ?? []).flatMap((o) => o.content ?? []).map((c) => c.text ?? '').join(' ') ?? r.text;
      const used = JSON.stringify(j).match(/web_search_call|url_citation|grounding|citations/g);
      answer = `${answer}`.slice(0, 220) + (used ? `  [search markers: ${[...new Set(used)].join(',')}]` : '');
    } catch { answer = r.text.slice(0, 220); }
    console.log(`${name} | HTTP ${r.status} | ${r.ms} ms | ${answer.replace(/\s+/g, ' ')}`);
  } catch (e) {
    console.log(`${name} | error ${e.message}`);
  }
}
