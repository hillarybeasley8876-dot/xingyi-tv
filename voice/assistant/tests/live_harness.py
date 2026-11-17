"""Acceptance test for the owner's specified MiTV. Uses only the DUMP-protected debug channel.
Run with the assistant in front. Restores the observed speaker level on exit; no camera captures.
"""
from pathlib import Path
import json,re,shlex,subprocess,sys,time
adb=[r'H:\tools\platform-tools\adb.exe','-s','192.168.31.162:5555']
out=Path(r'H:\tools\mitv\voice\evidence-0.3'); out.mkdir(exist_ok=True)
folder='/sdcard/Android/data/xyz.erapi0neer.xingyi/files/'
def cmd(*args,required=True):
 r=subprocess.run(adb+list(args),capture_output=True,timeout=20)
 if required and r.returncode:raise RuntimeError('ADB command failed: '+str(r.returncode))
 return r.stdout.decode('utf-8',errors='replace').replace('\r','').strip()
def debug(op,prompt=None):
 command='am broadcast -a xyz.erapi0neer.xingyi.DEBUG --es op '+op
 if prompt is not None:command+=' --es text '+shlex.quote(prompt)
 cmd('shell',command)
def file(name):return cmd('shell','cat',folder+name,required=False)
def wait_answer(test,seconds=65):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  answer=file('last-result.txt')
  if test(answer):return answer
  time.sleep(.4)
 raise RuntimeError('No expected result; final surface: '+file('last-result.txt')[:300])
def trace(name,ending):
 debug('trace');end=time.monotonic()+12
 while time.monotonic()<end:
  trail=file('harness-trace.txt')
  if ending in trail:
   (out/(name+'-trace.txt')).write_text(trail,encoding='utf-8'); return trail
  time.sleep(.25)
 raise RuntimeError('Missing event: '+ending)
def volume():return int(re.search(r'volume is (\d+)',cmd('shell','media','volume','--stream','3','--get')).group(1))
def ask(s):
 cmd('shell',': > '+folder+'last-result.txt');cmd('shell',': > '+folder+'harness-trace.txt');debug('ask',s)
def save(name,s): (out/name).write_text(s,encoding='utf-8')
base=volume();target=base-1 if base>0 else 1
report={'initial_volume':base}
try:
 debug('harness_tests');report['contract']=wait_answer(lambda s:'AgentHarness' in s);assert report['contract'].startswith('PASS'),report['contract']
 debug('tests');report['device_checks']=wait_answer(lambda s:'checks;' in s or 'FAIL after' in s);assert report['device_checks'].startswith('PASS'),report['device_checks']
 ask('请调用工具查询电视当前的媒体音量和静音状态，只报告真实读数。')
 answer=wait_answer(lambda s:bool(s) and '正在处理' not in s)
 trail=trace('read','turn_end');assert 'tool_start · get_status' in trail and 'after_tool · get_status' in trail and str(base) in answer,(answer,trail)
 report['read_answer']=answer
 if 'xyz.erapi0neer.xingyi' in cmd('shell','dumpsys activity activities | grep mResumedActivity'):
  (out/'assistant-0.3.png').write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
 prompt=f'请把电视的媒体音量设为百分之{target}。'
 ask(prompt);preview=wait_answer(lambda s:'按「确认执行」' in s);assert str(target) in preview and volume()==base
 debug('cancel');wait_answer(lambda s:'已取消' in s);debug('execute');assert volume()==base
 trail=trace('cancel','turn_cancel');assert 'tool_start · set_volume' not in trail
 report['cancel_preserved_volume']=base
 ask(prompt);preview=wait_answer(lambda s:'按「确认执行」' in s);assert volume()==base
 # The pending action requests remote focus. Test the actual remote OK event, not a synthetic tool result.
 cmd('shell','input','keyevent','KEYCODE_DPAD_CENTER');time.sleep(.8)
 trail=trace('write','turn_end');assert 'tool_start · set_volume' in trail and 'after_tool · set_volume · observed' in trail,trail
 assert volume()==target
 report['confirmed_volume']=target
 # UI interactions intentionally stop writing debug answer files. Save the native trace only.
finally:
 if volume()!=base:cmd('shell','media','volume','--stream','3','--set',str(base))
 report['restored_volume']=volume();(out/'live-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
