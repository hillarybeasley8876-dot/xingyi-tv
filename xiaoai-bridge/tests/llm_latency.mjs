// Latency of short spoken answers through the gateway's OpenAI-compatible endpoint (streaming).
// node llm_latency.mjs   (reads GATEWAY_API_KEY from the environment; never prints it)
const KEY = process.env.GATEWAY_API_KEY;
if (!KEY) throw new Error('GATEWAY_API_KEY not set');
const SYSTEM = '你是电视上的语音助手。用口语化的简体中文回答，适合直接朗读：不超过80个字，不用列表、markdown、表情。不确定的事实要直说。';
const QUESTIONS = ['先有鸡还是先有蛋？', '今天黄金价格大概是多少？'];
const MODELS = (process.argv[2] ?? 'MiniMax-M3.1-Flash,gemini-3.8-flash').split(',');

async function ask(model, q) {
  const t0 = Date.now();
  const res = await fetch('https://gateway.example.com/v1/chat/completions', {
    method: 'POST',
    headers: { authorization: `Bearer ${KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model, stream: true, max_tokens: 2000, messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: q }] }),
  });
  if (!res.ok) return { model, q, status: res.status, err: (await res.text()).slice(0, 200) };
  let first = 0, text = '', reasoning = 0, buf = '';
  const dec = new TextDecoder();
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') continue;
      try {
        const d = JSON.parse(data).choices?.[0]?.delta ?? {};
        if (d.reasoning_content) reasoning += d.reasoning_content.length;
        if (d.content) { if (!first) first = Date.now() - t0; text += d.content; }
      } catch { /* keep-alive */ }
    }
  }
  text = text.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  return { model, q, firstMs: first, totalMs: Date.now() - t0, reasoningChars: reasoning, text };
}

for (const m of MODELS) for (const q of QUESTIONS) console.log(JSON.stringify(await ask(m, q)));
