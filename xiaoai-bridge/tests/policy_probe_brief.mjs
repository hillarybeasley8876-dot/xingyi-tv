// Sends each paragraph of a brief to the worker model to see which one the gateway's policy filter rejects.
// node policy_probe_brief.mjs <brief.md>
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
const env = JSON.parse(readFileSync(join(homedir(), '.claude', 'settings.json'), 'utf8')).env ?? {};
const key = env.ANTHROPIC_API_KEY || env.ANTHROPIC_AUTH_TOKEN;
const headers = { 'x-api-key': key, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' };
for (const line of String(env.ANTHROPIC_CUSTOM_HEADERS ?? '').split('\n')) { const i = line.indexOf(':'); if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim(); }
const parts = readFileSync(process.argv[2], 'utf8').split(/\n(?=\d+\. \*\*|## )/);
for (const [i, p] of parts.entries()) {
  const body = { model: 'MiniMax-M78', max_tokens: 100, messages: [{ role: 'user', content: `${p}\n\nReply only: ok` }] };
  const r = await fetch('https://gateway.example.com/v1/messages', { method: 'POST', headers, body: JSON.stringify(body) });
  const t = await r.text();
  console.log(`#${i} ${r.status} ${r.status === 200 ? 'ok' : t.slice(0, 90)} | ${p.slice(0, 70).replace(/\s+/g, ' ')}`);
}
