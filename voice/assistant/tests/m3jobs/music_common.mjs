// music_common.mjs — shared builder for the Xingyi (Xiaomi TV assistant) "port one Java file" jobs on m3-dispatch.
// Each attempt is checked twice: (1) the file alone must compile against the Android SDK jar (Java 8 language,
// Android's checked JSONException), (2) the file + the lead's generated test must compile on the JDK and pass.
import { pathToFileURL } from 'node:url';
import { homedir } from 'node:os';
import { join, dirname } from 'node:path';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const M3 = pathToFileURL(join(homedir(), '.claude', 'scripts', 'm3-dispatch', 'lib.mjs')).href;
export const { card } = await import(M3);

export const ROOT = 'H:/tools/mitv/voice/assistant';
const JDK = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin';
const ANDROID = 'H:/tools/android-sdk/android-35/android.jar';
const LAMBDA = 'H:/tools/android-sdk/android-15/core-lambda-stubs.jar';
const JSON_JAR = `${ROOT}/tests/lib/json-20240303.jar`;
const WORK = `${ROOT}/tests/m3work`;

export const SYSTEM = [
  '你是一名严谨的 Java 工程师。任务是把一份 Python 参考实现逐条移植成一个 Java 8 源文件,行为必须和参考实现完全一致。',
  '你的输出只能是【一个】```java 代码块,里面是完整的、可直接编译的源文件;代码块之外不要写任何文字。',
  '参考实现是唯一依据:它没写的行为不要自作主张,它写了的细节一条都不能漏(顺序、长度条件、列表顺序都算)。',
].join('\n');

export const ENV_CONTEXT = [
  '运行环境:小米电视 Android 9(API 28)。语言级别 Java 8:不能用 var、switch 表达式、文本块、record。',
  '只能用 java.lang / java.util / java.util.regex / java.text' + '(MusicResolver 另外可以用 org.json 和 java.io.IOException)。',
  '不能用 API 28 以后才有的方法:List.of / Set.of / Map.of、String.repeat / isBlank / strip / stripLeading / stripTrailing / lines、',
  'Stream.toList()、Collectors.toUnmodifiable*、Predicate.not、Optional、InputStream.readAllBytes。',
  '不要写 main 方法,不要读写文件、不要访问网络、不要起线程、不要用反射、不要 System.exit。',
  'Android 的 org.json 里 JSONException 是【受检异常】(extends Exception):调用 getXxx / new JSONObject(String) / put 时必须捕获或声明。',
  '包名必须是 package xyz.erapi0neer.xingyi; 公开类型和成员的名字、签名必须与下面的 API 一字不差。',
].join('\n');

export const PORTING_NOTES = [
  'Python → Java 对照(照做,测试会逐条比对):',
  '- unicodedata.normalize("NFKC", s) → java.text.Normalizer.normalize(s, Normalizer.Form.NFKC)。',
  '- s.lower() → s.toLowerCase(Locale.ROOT)。',
  '- Python 的 str.strip() 去掉两端所有空白;Java 写一个 strip 辅助函数,去掉两端 Character.isWhitespace 或 Character.isSpaceChar 为真的字符(不要用 String.strip,API 28 没有)。',
  '- ch.isspace() → Character.isWhitespace(cp) || Character.isSpaceChar(cp)。',
  '- unicodedata.category(ch).startswith(("P","S")) → Character.getType(cp) 属于 CONNECTOR_PUNCTUATION, DASH_PUNCTUATION, START_PUNCTUATION, END_PUNCTUATION, INITIAL_QUOTE_PUNCTUATION, FINAL_QUOTE_PUNCTUATION, OTHER_PUNCTUATION, MATH_SYMBOL, CURRENCY_SYMBOL, MODIFIER_SYMBOL, OTHER_SYMBOL。',
  '- 按"字符"计数/遍历一律用码点:s.codePoints().toArray() / new String(cps, 0, n);长度条件(len(a))也按码点算。',
  '- re.search(p, s) → Pattern.compile(p).matcher(s).find();re.match(r"^...$", s) → matcher(s).matches();re.sub → replaceAll / replaceFirst。',
  '- re.I → Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE。正则里的 \\d、\\s 在 Java 字符串里写成 "\\\\d"、"\\\\s"。',
  '- Python 的 set / list 字面量 → Java 的 HashSet / ArrayList(保持列表顺序:FILLERS、VERBS、QUANT、PARTICLES 的顺序决定匹配结果)。',
  '- VERBS 在参考实现里按长度从长到短排序(sorted(key=len, reverse=True),长度相同保持原列表顺序)— Java 里用稳定排序得到同样的顺序。',
].join('\n');

export function extractCode(raw) {
  const blocks = [...raw.matchAll(/```[A-Za-z]*[ \t]*\r?\n([\s\S]*?)\r?\n?```/g)];
  if (blocks.length === 0) throw new Error('没有找到 ``` 代码块。只输出一个 ```java 代码块。');
  if (blocks.length > 1) throw new Error(`找到 ${blocks.length} 个代码块,只能有一个(完整文件放在同一个块里)。`);
  return blocks[0][1];
}

const FORBIDDEN = [
  [/\bstatic\s+void\s+main\s*\(/, '不要写 main 方法'],
  [/java\.net|URLConnection|HttpURLConnection|\bSocket\b/, '不要访问网络'],
  [/java\.io\.File\b|FileInputStream|FileOutputStream|\bFiles\.|java\.nio\.file/, '不要读写文件'],
  [/\bnew\s+Thread\b|Executors|Runtime\.getRuntime|ProcessBuilder/, '不要起线程/进程'],
  [/System\.exit/, '不要调用 System.exit'],
  [/java\.lang\.reflect|\.getDeclared|Class\.forName/, '不要用反射'],
  [/^\s*import\s+android\./m, '这是纯 Java 模块,不要 import android.*'],
  [/\b(List|Set|Map)\.of\s*\(|Map\.entry\s*\(/, 'API 28 没有 List.of / Set.of / Map.of'],
  [/\.repeat\s*\(|\.isBlank\s*\(|\.strip\s*\(|\.stripLeading\s*\(|\.stripTrailing\s*\(|\.lines\s*\(\s*\)/, 'API 28 没有 String.repeat / isBlank / strip / lines'],
  [/\.toList\s*\(\s*\)|toUnmodifiable|Predicate\.not|\bOptional\b|readAllBytes/, '不要用 Stream.toList / toUnmodifiable* / Predicate.not / Optional / readAllBytes'],
  [/^\s*var\s+\w+\s*=|[({;,]\s*var\s+\w+\s*[=:]/m, 'Java 8 没有 var'],
  // passes on the desktop JDK, throws "Unsupported flags: 256" on Android (2026-11-04, crashed the bridge on the TV)
  [/UNICODE_CHARACTER_CLASS|\(\?U\)/, 'Android 不支持 Pattern.UNICODE_CHARACTER_CLASS / (?U):要匹配 Unicode 空白就把字符逐个写进字符类'],
];

export function staticErrors(code, cls) {
  const errs = [];
  if (code.length > 60000) errs.push('[static] 文件太长(>60000 字符)。');
  if (!/^\s*package\s+xyz\.erapi0neer\.xingyi\s*;/m.test(code)) errs.push('[static] 第一行必须是 package xyz.erapi0neer.xingyi;');
  if (!new RegExp(`public\\s+final\\s+class\\s+${cls}\\b`).test(code)) errs.push(`[static] 必须声明 public final class ${cls}。`);
  for (const [re, msg] of FORBIDDEN) if (re.test(code)) errs.push(`[static] ${msg}。`);
  return errs;
}

function run(exe, args, timeoutMs = 120000) {
  const r = spawnSync(exe, args, { encoding: 'utf8', timeout: timeoutMs, windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
  return { code: r.status, out: `${r.stdout ?? ''}${r.stderr ?? ''}`, timedOut: r.error?.code === 'ETIMEDOUT' };
}

function compileErrors(out, cls, code) {
  const lines = code.split('\n');
  const errs = [];
  for (const m of out.matchAll(new RegExp(`${cls}\\.java:(\\d+): error: ([^\\r\\n]+)`, 'g'))) {
    const src = (lines[+m[1] - 1] ?? '').trim().slice(0, 160);
    errs.push(`第 ${m[1]} 行: ${m[2]}\n      出错的那一行: ${src}`);
    if (errs.length >= 8) break;
  }
  return errs.length ? errs : [out.trim().split('\n').slice(0, 12).join('\n')];
}

/** Compile on the Android jar, then compile + run the generated test on the JDK. Returns error strings. */
export function validateJava(cfg, code) {
  const errs = staticErrors(code, cfg.cls);
  if (errs.length) return errs;
  const dir = `${WORK}/${cfg.cls}-${randomUUID().slice(0, 8)}`;
  const src = `${dir}/src/xyz/erapi0neer/xingyi/${cfg.cls}.java`;
  mkdirSync(dirname(src), { recursive: true });
  try {
    writeFileSync(src, code);
    mkdirSync(`${dir}/android`, { recursive: true });
    const a = run(`${JDK}/javac.exe`, ['-J-XX:ActiveProcessorCount=2', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-nowarn', '-Xlint:-options',
      '-bootclasspath', `${ANDROID};${LAMBDA}`, '-d', `${dir}/android`, src]);
    if (a.code !== 0) return compileErrors(a.out, cfg.cls, code).map((e) => `[compile-android] ${e}`);
    mkdirSync(`${dir}/jvm`, { recursive: true });
    const b = run(`${JDK}/javac.exe`, ['-J-XX:ActiveProcessorCount=2', '--release', '8', '-encoding', 'UTF-8', '-nowarn', '-cp', JSON_JAR,
      '-d', `${dir}/jvm`, src, `${ROOT}/tests/${cfg.test}.java`]);
    if (b.code !== 0) return compileErrors(b.out, cfg.cls, code).map((e) => `[compile] ${e}`);
    const t = run(`${JDK}/java.exe`, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', `${dir}/jvm;${JSON_JAR}`,
      `xyz.erapi0neer.xingyi.${cfg.test}`, ...(cfg.testArgs ?? [])], 30000);
    if (t.timedOut) return ['[run] 测试 30 秒没有结束(死循环?)。'];
    if (t.code === 0 && /^PASS /m.test(t.out)) return [];
    const fails = t.out.split(/\r?\n/).filter((l) => l.startsWith('FAIL '));
    const crash = t.out.match(/Exception in thread[^\n]*\n(\s+at [^\n]+\n){0,6}/);
    const outErrs = fails.slice(0, 12).map((l) => `[test] ${l.slice(5)}`);
    if (crash) outErrs.push(`[run] 测试运行时抛异常:\n${crash[0].trim()}`);
    const summary = fails.find((l) => /passed, \d+ failed/.test(l)) ?? t.out.trim().split('\n').slice(-1)[0];
    if (fails.length > 12) outErrs.push(`[test] ...另有 ${fails.length - 12} 条失败`);
    if (summary) outErrs.push(`[test] ${summary.replace(/^FAIL /, '')}`);
    return outErrs.length ? outErrs : [`[run] 测试没有通过,输出:${t.out.trim().slice(0, 800)}`];
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** The part of the Python reference a module ports (split at the resolver marker). */
export function referencePart(which) {
  const ref = readFileSync(`${ROOT}/tests/ref/music_ref.py`, 'utf8');
  const [head, tail] = ref.split('# ---------------- resolver ----------------');
  const imports = 'import json, re, sys, unicodedata\n';
  if (which === 'intent') return head.replace(/^"""[\s\S]*?"""\n/, '').trim();
  return imports + tail.split("if __name__ == '__main__':")[0].trim();
}

export function makeJob(cfg) {
  const N = +(process.env.MUSIC_N || cfg.n || 3);
  const stub = readFileSync(`${ROOT}/tests/stub/${cfg.cls}.java`, 'utf8');
  return {
    name: `xingyi-${cfg.cls}`,
    model: 'MiniMax-M78',
    temperature: cfg.temperature ?? 0.5,
    maxTokens: cfg.maxTokens ?? 32000,
    system: SYSTEM,
    kind: () => cfg.cls,
    items: () => Array.from({ length: N }, (_, k) => ({ id: `${cfg.cls}#${k + 1}` })),
    prompt: () => card({
      title: `把参考实现移植成 ${cfg.cls}.java`,
      goal: cfg.goal,
      context: [ENV_CONTEXT, '', PORTING_NOTES, '', cfg.extra ?? '', '', '### API(公开部分必须一字不差;方法体由你实现)', '```java', stub.trim(), '```',
        '', '### Python 参考实现(唯一依据)', '```python', referencePart(cfg.part), '```'].join('\n'),
      steps: cfg.steps,
      format: `只输出一个 \`\`\`java 代码块,内容是完整的 ${cfg.cls}.java(含 package 和 import)。代码块之外不要有任何文字。`,
      examples: cfg.examples ?? [],
      pitfalls: cfg.pitfalls ?? [],
      criteria: [
        '只有一个 ```java 代码块,第一行是 package xyz.erapi0neer.xingyi;',
        `用 Android SDK 的 android.jar(Java 8)单独编译 ${cfg.cls}.java 必须通过(org.json 的 JSONException 是受检异常)。`,
        `和测试 ${cfg.test}.java 一起在 JDK 上编译并运行,所有检查必须通过(测试由参考实现生成,逐条比对行为)。`,
        '不使用上面列出的禁用 API;不写 main;不读写文件、不联网、不起线程。',
        ...(cfg.criteria ?? []),
      ],
      input: `请输出完整的 ${cfg.cls}.java。`,
    }),
    extract: (raw) => extractCode(raw),
    validate: (code) => validateJava(cfg, code),
    selfTest: async () => {
      const fence = (c) => '```java\n' + c + '\n```';
      return [{ id: `${cfg.cls}#1`, good: [], bad: [fence(stub), '没有代码块', fence(stub) + '\n' + fence('// 2'),
        fence(stub.replace('private MusicIntent(', 'static java.util.List<String> X = java.util.List.of("a");\n    private MusicIntent(').replace('private MusicResolver()', 'static java.util.List<String> X = java.util.List.of("a");\n    private MusicResolver()'))] }];
    },
  };
}
