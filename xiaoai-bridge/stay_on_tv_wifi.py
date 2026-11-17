"""Move the PC back onto the TV's Wi-Fi (YOUR_WIFI_SSID) as soon as it is visible, without interrupting a Codex TV run.

python stay_on_tv_wifi.py [minutes]   keeps rejoining YOUR_WIFI_SSID (when Codex is not mid-run) until the limit.
"""
import re, subprocess, sys, time
from pathlib import Path

PROFILE = 'YOUR_WIFI_SSID'
CODEX_LOG = Path(r'H:\tools\mitv\voice\evidence-0.6\wifi-check.log')
NO_WINDOW = 0x08000000


def run(args):
    r = subprocess.run(args, capture_output=True, creationflags=NO_WINDOW)
    return (r.stdout + r.stderr).decode('gbk', 'replace')


def tv_ip():
    m = re.search(r'192\.168\.31\.\d+', run(['ipconfig']))
    return m.group(0) if m else None


def codex_idle():
    """Codex's run script logs 'Switching…' / 'TV subnet obtained' while it holds the network; leave it alone then."""
    if not CODEX_LOG.exists():
        return True
    lines = CODEX_LOG.read_text(encoding='utf-8', errors='replace').strip().splitlines()
    last = lines[-1] if lines else ''
    age = time.time() - CODEX_LOG.stat().st_mtime
    return ('Original WLAN confirmed' in last and age > 10) or age > 300


def main():
    """Keeps the PC on the TV's network until the time limit; Windows falls back to CMCC by itself when it drops."""
    limit = time.time() + 60 * float(sys.argv[1] if len(sys.argv) > 1 else 60)
    was = None
    while time.time() < limit:
        ip = tv_ip()
        if ip != was:
            print(time.strftime('%H:%M:%S'), 'on the TV network ' + ip if ip else 'off the TV network', flush=True)
            was = ip
        if not ip and codex_idle():
            out = run(['netsh', 'wlan', 'connect', f'name={PROFILE}', f'ssid={PROFILE}', 'interface=WLAN']).strip()
            if '已成功完成连接请求' in out or 'successfully' in out.lower():
                for _ in range(10):
                    time.sleep(2)
                    if tv_ip():
                        break
                continue
        time.sleep(20)
    return 0


if __name__ == '__main__':
    sys.exit(main())
