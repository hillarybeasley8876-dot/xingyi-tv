"""Give the bridge its model config (gateway URL, model, key) without the key touching a command line or the screen.

python provision.py [--model MiniMax-M3.1-Flash]
The key comes from the Windows user environment (GATEWAY_API_KEY). The JSON goes over adb's stdin into the app's
shared files dir; the app moves it into private storage and deletes the shared copy, which this script then verifies.
"""
import argparse, json, subprocess, sys, time, winreg

ADB = r'H:\tools\platform-tools\adb.exe'
TV = '192.168.31.162:5555'
PKG = 'xyz.erapi0neer.xiaoaibridge'
SHARED = f'/sdcard/Android/data/{PKG}/files/llm.json'
NO_WINDOW = 0x08000000


def user_env(name):
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as k:
        return winreg.QueryValueEx(k, name)[0]


def sh(cmd, data=None):
    r = subprocess.run([ADB, '-s', TV, 'shell', cmd], input=data, capture_output=True, timeout=30, creationflags=NO_WINDOW)
    return r.returncode, r.stdout.decode('utf-8', 'replace')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default='MiniMax-M3.1-Flash')
    a = ap.parse_args()
    key = user_env('GATEWAY_API_KEY')
    body = json.dumps({'base': 'https://gateway.example.com', 'model': a.model, 'key': key}).encode()
    rc, _ = sh(f'cat > {SHARED}', body)
    if rc:
        print('push failed'); return 1
    sh(f'am broadcast -a {PKG}.PROVISION')
    time.sleep(2)
    rc, out = sh(f'ls {SHARED} 2>/dev/null || echo gone')
    sh(f'am broadcast -a {PKG}.STATUS')
    time.sleep(1.5)
    _, status = sh(f'cat /sdcard/Android/data/{PKG}/files/status.txt')
    model = next((l for l in status.splitlines() if l.startswith('model:')), 'model: ?')
    print('shared copy:', out.strip(), '|', model)
    return 0 if out.strip() == 'gone' and a.model in model else 1


if __name__ == '__main__':
    sys.exit(main())
