// Replays a recorded 星仪 transcript up to a decision point and asks several models for the next step.
// node replay_choice.mjs <transcript.json> <cut-index> [model:effort ...]   (key from env GATEWAY_API_KEY)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const contract = JSON.parse(readFileSync(join(here, '..', '..', 'assets', 'agent', 'contract.json'), 'utf8'));
const [file, cut, ...variants] = process.argv.slice(2);
const all = JSON.parse(readFileSync(file, 'utf8'));
const key = process.env.GATEWAY_API_KEY;
const runs = variants.length ? variants : ['MiniMax-M3.1-Flash:low', 'MiniMax-M3.1-Flash:medium', 'MiniMax-M78:low'];

function clean(messages) {
  // Other models do not need MiniMax's private reasoning; keep only what the protocol defines.
  return messages.map(m => {
    const o = { role: m.role, content: m.content ?? '' };
    if (m.tool_calls) o.tool_calls = m.tool_calls;
    if (m.tool_call_id) o.tool_call_id = m.tool_call_id;
    return o;
  });
}

for (const v of runs) {
  const [model, effort] = v.split(':');
  for (let i = 0; i < Number(process.env.N || 2); i++) {
    const t0 = Date.now();
    const r = await fetch('https://gateway.example.com/v1/chat/completions', {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
      body: JSON.stringify({ model, messages: clean(all.slice(0, Number(cut) + 1)), tools: contract.tools, tool_choice: 'auto',
        reasoning_effort: effort || 'low', max_completion_tokens: 4096, stream: false, ...(process.env.TEMP ? { temperature: Number(process.env.TEMP) } : {}) }),
    });
    const text = await r.text();
    if (r.status !== 200) { console.log(`${v} #${i}: HTTP ${r.status} ${text.slice(0, 160)}`); continue; }
    const msg = JSON.parse(text).choices[0].message;
    const calls = (msg.tool_calls || []).map(c => `${c.function.name} ${c.function.arguments}`).join('; ');
    const said = String(msg.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim().slice(0, 120);
    console.log(`${v} #${i} ${Date.now() - t0}ms → ${calls || 'TEXT: ' + said}`);
  }
}
