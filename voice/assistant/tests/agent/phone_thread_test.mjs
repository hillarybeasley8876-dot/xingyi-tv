/* phone-thread (2026-11-09): the finished thread the phone page shows — the newest exchange once, an older planned
 * task never as the newest. Reads the page's own PhoneThread block and runs it alone; node built-ins only.
 * Run: H:/tools/node/node-v24.22.2-win-x64/node.exe tests/agent/phone_thread_test.mjs */
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../../assets/agent/phone.html', import.meta.url), 'utf8');
const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const block = blocks.find(b => b.indexOf('var PhoneThread') >= 0);
let failed = 0;

function check(ok, label) {
  if (!ok) { failed++; console.log('FAIL: ' + label); }
}
function same(got, want, label) {
  check(JSON.stringify(got) === JSON.stringify(want), label + ' — got ' + JSON.stringify(got)
          + ', wanted ' + JSON.stringify(want));
}

/** Every time is built with the local-time constructor, so nothing here depends on the time zone. */
const D = (y, mo, d, h, mi, s) => new Date(y, mo - 1, d, h, mi, s || 0).getTime();
const NOW = D(2026, 11, 9, 14, 10);
const saids = log => log.map(e => e.said);

const H = [
  { when: '11月9日 14:05', said: '放首歌', replied: '好的' },
  { when: '11月9日 14:01', said: '暂停', replied: '好的，已暂停' },
  { when: '11月8日 22:58', said: '打开B站搜索林俊杰', replied: '已在B站搜索林俊杰' }
];
const A1 = { id: 'a1', utterance: '放首歌', state: 'done', last_reply: '好的', updated_at: D(2026, 11, 9, 14, 5, 20) };

if (!block) {
  console.log('FAIL: phone.html has no script block holding var PhoneThread');
  process.exit(1);
}
const PhoneThread = new Function(block + '\nreturn PhoneThread;')();
const merge = (h, t, now) => PhoneThread.merge(h, t, now === undefined ? NOW : now);

// ---- 1. the history alone, and the newest exchange once ------------------------------------------

same(saids(merge(H, [], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌'],
        'the history alone is the thread, oldest first');
same(merge(H, [], NOW).map(e => e.reply), ['已在B站搜索林俊杰', '好的，已暂停', '好的'],
        'and every entry keeps the answer it was given');
check(merge(H, [], NOW).every(e => Object.keys(e).length === 2),
        'an entry of the thread is only {said, reply}');

same(saids(merge(H, [A1], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌'],
        'the finished task the history already holds is not shown a second time');
const early = Object.assign({}, A1, { updated_at: D(2026, 11, 9, 14, 4, 59) });
same(saids(merge(H, [early], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌'],
        'nor when its own minute fell just before the history entry (14:04:59 against 14:05)');
same(saids(merge(null, [A1], NOW)), ['放首歌'],
        'before the history came back the finished task is the whole thread');

// ---- 2. an answer longer than the 120 characters the history keeps ---------------------------------

const LONG = '今天多云转晴，'.repeat(19).slice(0, 130);
check(LONG.length === 130, 'the long answer is 130 characters');
const longHistory = [{ when: '11月9日 14:06', said: '今天天气怎么样', replied: LONG.slice(0, 120) }].concat(H);
const weather = { id: 'w1', utterance: '今天天气怎么样', state: 'done', last_reply: LONG,
  updated_at: D(2026, 11, 9, 14, 6, 10) };
same(saids(merge(longHistory, [weather], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '今天天气怎么样'],
        'an answer cut to 120 characters still matches the task that said it: shown once');

// what was said is cut the same way, and Java's trim() goes first: the ledger may carry the spaces the history lost
const longSaid = '林俊杰的歌真好听'.repeat(12);
const paddedHistory = [{ when: '11月9日 14:05', said: longSaid.slice(0, 80), replied: '好的' }].concat(H);
const padded = { id: 'u1', utterance: '  ' + longSaid + ' ', state: 'done', last_reply: ' 好的 ',
  updated_at: D(2026, 11, 9, 14, 5, 20) };
same(saids(merge(paddedHistory, [padded], NOW)),
        ['打开B站搜索林俊杰', '暂停', '放首歌', longSaid.slice(0, 80)],
        'what was said is trimmed and cut to 80 characters before it is compared: shown once');
same(merge(null, [{ id: 'x1', utterance: '你好', state: 'done' }], NOW), [{ said: '你好', reply: '' }],
        'a task with no answer at all joins with an empty reply');

// ---- 3. an older planned task is never the newest ---------------------------------------------------

const H2 = [
  { when: '11月9日 14:07', said: '暂停', replied: '好的' },
  { when: '11月9日 14:03', said: '打开B站搜索林俊杰', replied: '已在B站搜索林俊杰' }
];
const planned = {
  id: 'p1', utterance: '打开B站搜索林俊杰', state: 'done', last_reply: '已在B站搜索林俊杰',
  goal: '在B站搜林俊杰',
  steps: [{ text: '打开B站', status: 'done' }, { text: '搜索林俊杰', status: 'done' }],
  updated_at: D(2026, 11, 9, 14, 3, 30)
};
same(saids(merge(H2, [planned], NOW)), ['打开B站搜索林俊杰', '暂停'],
        'the restarted bridge old planned task does not land under the newer 暂停');
const older = Object.assign({}, planned, { updated_at: D(2026, 11, 7, 20, 0) });
same(saids(merge(H2, [older], NOW)), ['打开B站搜索林俊杰', '暂停'],
        'nor a planned task the history does not hold at all, when it is older than all of it');
const stranger = { id: 'p2', utterance: '打开B站搜索周杰伦', state: 'done', last_reply: '已在B站搜索周杰伦',
  updated_at: D(2026, 11, 7, 20, 0) };
same(saids(merge(H2, [stranger], NOW)), ['打开B站搜索林俊杰', '暂停'],
        'an old task whose words the history never held is dropped for being older than all of it');
same(saids(merge(H2, [Object.assign({}, stranger, { updated_at: D(2026, 11, 9, 14, 8, 0) })], NOW)),
        ['打开B站搜索林俊杰', '暂停', '打开B站搜索周杰伦'],
        'but a task newer than the whole history joins, even when the history holds nothing like it');

// ---- 4. new exchanges join, oldest to newest --------------------------------------------------------

const n1 = { id: 'n1', utterance: '下一首', state: 'done', last_reply: '好的',
  updated_at: D(2026, 11, 9, 14, 6, 30) };
const n2 = { id: 'n2', utterance: '暂停', state: 'done', last_reply: '好的，已暂停',
  updated_at: D(2026, 11, 9, 14, 8, 0) };
same(saids(merge(H, [n1, n2], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '下一首', '暂停'],
        'two exchanges the history has not caught up with join after it, oldest first');
const again = { id: 'n3', utterance: '放首歌', state: 'done', last_reply: '好的',
  updated_at: D(2026, 11, 9, 14, 9, 0) };
same(saids(merge(H, [again], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '放首歌'],
        'the same words said again in a later minute are a new exchange and join again');

// ---- 5. a running task is never in the log -----------------------------------------------------------

const running = {
  id: 'r1', utterance: '打开B站搜索林俊杰', state: 'running',
  steps: [{ text: '打开B站', status: 'in_progress' }], updated_at: D(2026, 11, 9, 14, 9, 0)
};
same(saids(merge(H, [running], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌'],
        'a running task stays out of the log: the page shows it once, as the live block');
same(merge(null, [running], NOW), [], 'and with no history at all the log is empty');
['running', 'awaiting_user', 'awaiting_confirmation'].forEach(s =>
  check(PhoneThread.finished({ state: s }) === false, 'finished is false for ' + s));
['done', 'cancelled', 'failed', 'interrupted', 'answered'].forEach(s =>
  check(PhoneThread.finished({ state: s }) === true, 'finished is true for ' + s));

// ---- 6. one id once ------------------------------------------------------------------------------------

same(saids(merge(H, [n1, n1], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '下一首'],
        'the same id reported twice joins once');

// ---- 7. the year turns ------------------------------------------------------------------------------------

const NEW_YEAR = D(2027, 1, 1, 0, 10);
const HY = [
  { when: '1月1日 0:02', said: '暂停', replied: '好的' },
  { when: '12月31日 23:58', said: '放首歌', replied: '好的' }
];
const song = { id: 'y1', utterance: '放首歌', state: 'done', last_reply: '好的',
  updated_at: D(2026, 12, 31, 23, 58, 30) };
const next = { id: 'y2', utterance: '下一首', state: 'done', last_reply: '好的',
  updated_at: D(2027, 1, 1, 0, 5, 0) };
same(saids(merge(HY, [song, next], NEW_YEAR)), ['放首歌', '暂停', '下一首'],
        'across midnight the thread is still oldest first and nothing is doubled');
check(PhoneThread.at('12月31日 23:58', NEW_YEAR) === D(2026, 12, 31, 23, 58),
        '12月31日 read on 1 January is last year');
check(Number.isNaN(PhoneThread.at('昨天 22:58', NEW_YEAR)), '「昨天 22:58」 is not a time this reads');
check(Number.isNaN(PhoneThread.at('', NEW_YEAR)), 'an empty when is not a time');
check(Number.isNaN(PhoneThread.at(null, NEW_YEAR)), 'a missing when is not a time');
check(PhoneThread.at('11月9日 14:05', NOW) === D(2026, 11, 9, 14, 5), 'a when of this year is that minute itself');

// ---- 8. at most 20 --------------------------------------------------------------------------------------

const many = [];
for (let i = 0; i < 25; i++) {
  many.push({ when: '11月9日 13:' + String(50 - i).padStart(2, '0'), said: '第' + (25 - i) + '句', replied: '好' });
}
const long25 = [{ when: '11月9日 13:51', said: '', replied: '空' }].concat(many);
const cut20 = merge(long25, [], NOW);
const wanted = [];
for (let i = 6; i <= 25; i++) wanted.push('第' + i + '句');
check(cut20.length === 20, 'a longer history is cut to 20 entries: ' + cut20.length);
same(saids(cut20), wanted, 'and it is the newest 20, oldest first, the entry that said nothing dropped');

// ---- 9. merge does not change its arguments --------------------------------------------------------------

const pureHistory = JSON.parse(JSON.stringify(longHistory));
const pureTasks = [JSON.parse(JSON.stringify(A1)), JSON.parse(JSON.stringify(weather))];
const beforeH = JSON.stringify(pureHistory), beforeT = JSON.stringify(pureTasks);
merge(pureHistory, pureTasks, NOW);
check(JSON.stringify(pureHistory) === beforeH, 'the history is the same after merge as before');
check(JSON.stringify(pureTasks) === beforeT, 'the tasks are the same after merge as before');
check(merge(pureHistory, pureTasks, NOW).length === merge(pureHistory, pureTasks, NOW).length,
        'the same arguments always give the same thread');

// ---- 10. the page is wired to it ---------------------------------------------------------------------------

check(html.indexOf('function seed(') < 0, 'the page no longer seeds the thread itself');
check(html.indexOf('lastId') < 0, 'and no longer remembers one last id');
check(html.split('PhoneThread.merge(').length - 1 >= 2,
        'both the state answer and the history answer rebuild the thread: '
        + (html.split('PhoneThread.merge(').length - 1));
check(html.indexOf('PhoneThread.finished(') >= 0, 'the page asks the module what finished means');
check(html.indexOf('/api/history') >= 0 && html.indexOf('/api/state') >= 0,
        'the two requests are still the ones the page always made');

// ---- 11. an older task never lands under a newer one, however late it is reported -------------------------

// the page never saw the planned task finish (the phone was locked), then saw a newer quick task finish, and the
// bridge restarted: state.task falls back to last_task.json and reports the older planned task after the newer one
const quick = { id: 'q1', utterance: '暂停', state: 'done', last_reply: '好的，已暂停',
  updated_at: D(2026, 11, 9, 14, 8, 0) };
const stale = { id: 'q2', utterance: '打开B站搜索周杰伦', state: 'done', last_reply: '已在B站搜索周杰伦',
  goal: '在B站搜周杰伦',
  steps: [{ text: '打开B站', status: 'done' }, { text: '搜索周杰伦', status: 'done' }],
  updated_at: D(2026, 11, 9, 14, 6, 30) };
same(saids(merge(H, [quick, stale], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '暂停'],
        'an older planned task reported after a newer one is not drawn below it as the newest exchange');
same(saids(merge(H, [stale, quick], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '打开B站搜索周杰伦', '暂停'],
        'and reported in the order they happened, both join, oldest first');
const newestFirst = Object.assign({}, stale, { id: 'q3', utterance: '打开B站搜索五月天',
  last_reply: '已在B站搜索五月天', updated_at: D(2026, 11, 9, 14, 9, 0) });
same(saids(merge(H, [quick, newestFirst], NOW)),
        ['打开B站搜索林俊杰', '暂停', '放首歌', '暂停', '打开B站搜索五月天'],
        'a task newer than the one already joined still joins after it, in the order the state reported them');
const undated = { id: 'q4', utterance: '音量加一点', state: 'done', last_reply: '好的' };
same(saids(merge(H, [quick, undated], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '暂停', '音量加一点'],
        'a task the ledger gives no time at all is never dropped for being older');

// ---- 12. an emoji the cut goes through is one exchange, not two -----------------------------------------

// the 120-character cut lands between the two units of 😀; the TV writes the history with getBytes(UTF_8), so it
// holds '?' where the page's own cut would keep the lone high surrogate
const SMILE = '\u{1F600}';
const emojiReply = '好'.repeat(119) + SMILE + '，还要再说两句';
check(emojiReply.length === 128, 'the emoji answer is 128 units long, the smile at 119 and 120');
const emojiHistory = [{ when: '11月9日 14:06', said: '讲个笑话', replied: '好'.repeat(119) + '?' }].concat(H);
const joke = { id: 'e1', utterance: '讲个笑话', state: 'done', last_reply: emojiReply,
  updated_at: D(2026, 11, 9, 14, 6, 10) };
same(saids(merge(emojiHistory, [joke], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '讲个笑话'],
        'an answer whose 120-character cut splits an emoji still matches the history: shown once');
same(saids(merge(null, [joke], NOW)), ['讲个笑话'],
        'and before the history came back the whole answer is the thread, once');
const emojiSaid = '啊'.repeat(79) + SMILE + '哈哈';
const emojiSaidHistory = [{ when: '11月9日 14:05', said: '啊'.repeat(79) + '?', replied: '好的' }].concat(H);
const shout = { id: 'e2', utterance: emojiSaid, state: 'done', last_reply: '好的',
  updated_at: D(2026, 11, 9, 14, 5, 20) };
same(saids(merge(emojiSaidHistory, [shout], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', '啊'.repeat(79) + '?'],
        'the same goes for what was said, cut to 80 characters through an emoji: shown once');
const whole = { id: 'e3', utterance: SMILE + '好听', state: 'done', last_reply: '好的' + SMILE,
  updated_at: D(2026, 11, 9, 14, 6, 10) };
const wholeHistory = [{ when: '11月9日 14:06', said: SMILE + '好听', replied: '好的' + SMILE }].concat(H);
same(saids(merge(wholeHistory, [whole], NOW)), ['打开B站搜索林俊杰', '暂停', '放首歌', SMILE + '好听'],
        'an emoji the cut never touches is left alone and still matches');

if (failed) {
  console.log(failed + ' check(s) failed');
  process.exit(1);
}
console.log('PHONE THREAD OK');
