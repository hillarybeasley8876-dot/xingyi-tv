"""Lead-owned acceptance check for the private-logs job: the user's words must not be written to shared storage."""
import re, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
src = root / 'src' / 'xyz' / 'erapi0neer' / 'xiaoaibridge'
log = (src / 'BridgeLog.java').read_text(encoding='utf-8')
svc = (src / 'BridgeService.java').read_text(encoding='utf-8')
sh = (root / 'bridge.sh').read_text(encoding='utf-8')
problems = []
if not re.search(r'new File\(\s*\w+\.getFilesDir\(\)\s*,\s*"bridge-log\.txt"\s*\)', log):
    problems.append('BridgeLog must write bridge-log.txt into getFilesDir()')
if re.search(r'getExternalFilesDir[\s\S]{0,200}bridge-log\.txt"\s*\)\s*;\s*\n\s*}', log) and 'delete' not in log:
    problems.append('BridgeLog still targets shared storage')
if '"status.txt"' in svc and 'FileOutputStream' in svc:
    problems.append('BridgeService still writes status.txt')
if 'getExternalFilesDir' in svc:
    problems.append('BridgeService still uses getExternalFilesDir')
if svc.count('setResultData') < 2:
    problems.append('STATUS and LOG must both answer with setResultData')
if '".LOG"' not in svc and "'.LOG'" not in svc:
    problems.append('LOG action missing')
if re.search(r'cat\s+\$D/|cat\s+/sdcard', sh):
    problems.append('bridge.sh still cats files on /sdcard')
if 'base64 -d' not in sh:
    problems.append('bridge.sh must decode the broadcast result with base64 -d')
print('\n'.join(problems) if problems else 'PRIVATE LOGS OK')
sys.exit(1 if problems else 0)
