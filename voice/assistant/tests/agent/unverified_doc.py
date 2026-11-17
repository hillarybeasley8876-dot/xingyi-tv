"""Writes NEXT.md's 「## 还没验的」 section from tests/agent/unverified.json, so that section cannot drift from the
record: the text comes from Unverified.markdown in the Java core (run through build/agent-core-test, the way
reported.sh runs ReportedMain) — the formatting is NOT reimplemented here, so the checks, this script and
bridge.sh status cannot disagree.

python tests/agent/unverified_doc.py          rewrite NEXT.md's section
python tests/agent/unverified_doc.py --check  exit 1 and show what is out of date; prints UP TO DATE when it is not
"""
import difflib, glob, os, subprocess, sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
JSON = os.path.join(ROOT, 'tests', 'agent', 'unverified.json')
NEXT = os.path.join(ROOT, 'NEXT.md')
OUT = os.path.join(ROOT, 'build', 'agent-core-test')
LIB = os.path.join(ROOT, 'tests', 'lib', 'json-20240303.jar')
JDK = os.path.join('C:\\', 'Program Files', 'Eclipse Adoptium', 'jdk-17.0.20.101-hotspot', 'bin')
CLASS = 'xyz.erapi0neer.xingyi.agent.Unverified'
HEADING = '## 还没验的'


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def classes():
    """build/agent-core-test, compiled if it is not there yet — a --check nobody can run is no check."""
    if os.path.exists(os.path.join(OUT, *CLASS.split('.')) + '.class'):
        return
    os.makedirs(OUT, exist_ok=True)
    src = glob.glob(os.path.join(ROOT, 'src', 'xyz', 'erapi0neer', 'xingyi', 'agent', '*.java'))
    subprocess.check_call([os.path.join(JDK, 'javac.exe'), '-J-XX:ActiveProcessorCount=2', '--release', '8',
                           '-encoding', 'UTF-8', '-nowarn', '-Xlint:-options', '-cp', LIB, '-d', OUT] + src)


def java(*args):
    classes()
    p = subprocess.run([os.path.join(JDK, 'java.exe'), '-XX:ActiveProcessorCount=2', '-Dfile.encoding=UTF-8',
                        '-cp', OUT + os.pathsep + LIB, CLASS] + list(args),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode('utf-8', 'replace'))
        raise SystemExit('Unverified ' + ' '.join(args) + ' 跑不起来')
    return p.stdout.decode('utf-8').replace('\r\n', '\n')


def replaced(text, section):
    """text with its 还没验的 section (that heading up to the next `## `) swapped for `section`."""
    lines = text.split('\n')
    at = [i for i, l in enumerate(lines) if l.strip() == HEADING]
    if not at:
        raise SystemExit('NEXT.md 里没有「%s」这一节，没法生成它' % HEADING)
    at = at[0]
    end = len(lines)
    for j in range(at + 1, len(lines)):
        if lines[j].startswith('## '):
            end = j
            break
    head, tail = '\n'.join(lines[:at]), '\n'.join(lines[end:])
    out = (head + '\n' if head else '') + section.rstrip('\n') + '\n'
    return out + ('\n' + tail if tail else '')


def main(argv):
    want = java('markdown', JSON)
    text = read(NEXT)
    new = replaced(text, want)
    if '--check' in argv:
        if new == text:
            print('UP TO DATE')
            return 0
        sys.stdout.writelines(difflib.unified_diff(text.splitlines(True), new.splitlines(True),
                                                   'NEXT.md（仓库里）', 'NEXT.md（unverified.json 该有的样子）'))
        print('OUT OF DATE：NEXT.md 的「还没验的」一节和 tests/agent/unverified.json 对不上，'
              '跑 python tests/agent/unverified_doc.py')
        return 1
    if new != text:
        with open(NEXT, 'w', encoding='utf-8', newline='') as f:
            f.write(new)
        print('wrote NEXT.md')
    print('UP TO DATE')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
