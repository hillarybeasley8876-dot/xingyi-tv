// Saves one real Responses API answer with web_search as a test fixture (no key inside).
// node capture_responses_fixture.mjs <out.json>
import { writeFileSync } from 'node:fs';
const KEY = process.env.GATEWAY_API_KEY;
const body = {
  model: 'gpt-5.4-mini',
  instructions: '你是电视上的语音助手。用口语化的简体中文回答，适合朗读：不超过80个字，不用列表和markdown。',
  input: '今天国内黄金价格每克多少元？',
  tools: [{ type: 'web_search' }],
};
const r = await fetch('https://gateway.example.com/v1/responses', {
  method: 'POST', headers: { authorization: `Bearer ${KEY}`, 'content-type': 'application/json' }, body: JSON.stringify(body),
});
const text = await r.text();
if (text.includes(KEY)) throw new Error('key echoed, not saving');
writeFileSync(process.argv[2], text);
console.log('HTTP', r.status, text.length, 'bytes');
