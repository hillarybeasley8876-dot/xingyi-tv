"""Orrery home, Arknights-inspired but drawn in lines (2026-11-08, the user: 「你看看人家明日方舟那种 ui」「但是还是按照线条和简约
排版为主」). An HTML page over the flow, captured by headless Chrome (CSS 3-D transforms; Android Canvas does the same tilt
with android.graphics.Camera).

python ui_home_ark.py <art.png> <tilt|flat> <focus 0..7> <out.png>

What it takes from Arknights' main screen: entries as a cluster of panels of different sizes on the right (a projected
display, tilted), a bold name with an uppercase English line under it and one line of state, an index code in each
panel's corner and a large faint numeral behind, a status strip at the top right, corner brackets and ticks. What it
does not take: filled white or coloured blocks — everything is a 1 px line, text, and at most a 4 % veil.
"""
import os, sys, subprocess, tempfile, shutil

FONTS = r'C:\Users\<user>\AppData\Local\Microsoft\Windows\Fonts'
JBM = r'H:\tools\mitv\launcher\assets\fonts\JetBrainsMono.ttf'
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
W, H, MX = 1920, 1080, 96
INK, ACCENT = '#EEF1F5', '#A8C8EE'

# name, English line, state line, (col, row, w, h) on a 4 x 3 grid
CELLS = [
    ('星仪', 'XINGYI · ASSISTANT', '待命　·　叫一声「小爱同学」', (0, 0, 2, 2)),
    ('网易云音乐', 'NETEASE · MUSIC', '上次在听《江南》林俊杰', (2, 0, 2, 1)),
    ('云视听极光', 'TENCENT · VIDEO', '影视', (2, 1, 1, 1)),
    ('哔哩哔哩', 'BILIBILI · VIDEO', '星辰亻旅行者 更新了', (3, 1, 1, 1)),
    ('信号源', 'SOURCE · HDMI', 'HDMI 1　·　2　·　3', (0, 2, 1, 1)),
    ('无线投屏', 'CAST', '', (1, 2, 1, 1)),
    ('全部应用', 'ALL APPS', '15 个　·　1 个新装', (2, 2, 1, 1)),
    ('设置', 'SETTINGS', '', (3, 2, 1, 1)),
]
CATS = ['常用', '影音', '信号源', '工具', '全部']

# grid geometry (px)
GX, GY, CW, CH, GAP = 1010, 352, 192, 176, 14
LEFT = dict(gx=330, gy=470, cw=170, ch=150, gap=12)
BOTTOM = dict(gx=372, gy=686, cw=186, ch=138, gap=12)
# where each entry sits on the 6 x 2 bottom band: (col, row, w, h)
BAND = [(0, 0, 2, 2), (2, 0, 2, 1), (4, 0, 1, 1), (5, 0, 1, 1), (2, 1, 1, 1), (3, 1, 1, 1), (4, 1, 1, 1), (5, 1, 1, 1)]


def furl(p):
    return 'file:///' + p.replace('\\', '/')


def page(art, tilt, focus):
    global GX, GY, CW, CH, GAP
    if tilt == 'left':
        GX, GY, CW, CH, GAP = LEFT['gx'], LEFT['gy'], LEFT['cw'], LEFT['ch'], LEFT['gap']
    if tilt == 'bottom':
        GX, GY, CW, CH, GAP = BOTTOM['gx'], BOTTOM['gy'], BOTTOM['cw'], BOTTOM['ch'], BOTTOM['gap']
    f = lambda n: furl(os.path.join(FONTS, n))
    cells = []
    for i, (name, en, state, (c, r, w, h)) in enumerate(CELLS):
        if tilt == 'bottom':
            c, r, w, h = BAND[i]
        x, y = c * (CW + GAP), r * (CH + GAP)
        cw, ch = w * CW + (w - 1) * GAP, h * CH + (h - 1) * GAP
        on = i == focus
        big = w * h >= 4
        cls = 'cell' + (' on' if on else '') + (' big' if big else '') + (' wide' if w == 2 and h == 1 else '')
        corners = ''.join(f'<i class="k {k}"></i>' for k in ('tl', 'tr', 'bl', 'br')) if on else ''
        cells.append(f'''<div class="{cls}" style="left:{x}px;top:{y}px;width:{cw}px;height:{ch}px">
  {corners}<span class="code">{i + 1:02d}</span><span class="ghost">{i + 1:02d}</span>
  <div class="name">{name}</div><div class="en">{en}</div>{f'<div class="state">{state}</div>' if state else ''}
</div>''')
    cols, rows = (6, 2) if tilt == 'bottom' else (4, 3)
    gw, gh = cols * CW + (cols - 1) * GAP, rows * CH + (rows - 1) * GAP
    transform = ('perspective(1800px) rotateY(-11deg) rotateX(2deg)' if tilt == 'tilt'
                 else 'perspective(1800px) rotateY(11deg) rotateX(2deg)' if tilt == 'left'
                 else 'perspective(1600px) rotateX(16deg)' if tilt == 'bottom' else 'none')
    origin = '100% 50%' if tilt == 'left' else '50% 100%' if tilt == 'bottom' else '0% 50%'
    veil = ('background: linear-gradient(90deg, rgba(0,0,0,.55) 0%, rgba(0,0,0,.4) 45%, rgba(0,0,0,0) 70%); left: 0; right: auto; width: 1500px;'
            if tilt == 'left' else
            'background: linear-gradient(180deg, rgba(0,0,0,0) 0%, rgba(0,0,0,.35) 55%, rgba(0,0,0,.7) 100%); left: 0; right: auto; top: auto; bottom: 0; width: 1920px; height: 520px;'
            if tilt == 'bottom' else '')
    cats = ''.join(f'<div class="cat{" on" if k == 0 else ""}"><span class="ci">{k + 1:02d}</span>{n}</div>' for k, n in enumerate(CATS))
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: MiL; src: url("{f('MiSans-Light.otf')}"); }}
@font-face {{ font-family: MiN; src: url("{f('MiSans-Normal.otf')}"); }}
@font-face {{ font-family: MiM; src: url("{f('MiSans-Medium.otf')}"); }}
@font-face {{ font-family: MiS; src: url("{f('MiSans-Semibold.otf')}"); }}
@font-face {{ font-family: Inter; src: url("{f('Inter-Variable.ttf')}"); font-weight: 100 900; }}
@font-face {{ font-family: Osw; src: url("{f('Oswald-Variable.ttf')}"); font-weight: 200 700; }}
@font-face {{ font-family: JBM; src: url("{furl(JBM)}"); }}
html, body {{ margin: 0; width: {W}px; height: {H}px; overflow: hidden; background: #000; color: {INK}; }}
.art {{ position: absolute; inset: 0; background: url("{furl(art)}") center / cover; }}
.veil {{ position: absolute; right: 0; top: 0; width: 1100px; height: {H}px;
         background: linear-gradient(90deg, rgba(0,0,0,0) 0%, rgba(0,0,0,.45) 38%, rgba(0,0,0,.62) 100%); }}
.clock {{ position: absolute; left: {MX - 6}px; top: 46px; font: 200 136px/1 Inter; font-feature-settings: 'tnum'; letter-spacing: -0.01em; opacity: .94; }}
.date {{ position: absolute; left: {MX}px; top: 200px; font: 26px MiN; letter-spacing: .08em; opacity: .62; }}
.lunar {{ position: absolute; left: {MX}px; top: 242px; font: 19px MiN; letter-spacing: .1em; opacity: .38; }}
.bars {{ position: absolute; left: {MX}px; top: 286px; width: 220px; height: 14px; opacity: .22;
         background: repeating-linear-gradient(90deg, {INK} 0 1px, transparent 1px 4px, {INK} 4px 6px, transparent 6px 9px, {INK} 9px 10px, transparent 10px 15px); }}
.serial {{ position: absolute; left: {MX + 232}px; top: 286px; font: 11px JBM; letter-spacing: .2em; opacity: .3; }}
.status {{ position: absolute; right: {MX}px; top: 120px; display: flex; gap: 0; }}
.st {{ padding: 0 28px; border-left: 1px solid rgba(238,241,245,.18); text-align: left; }}
.st:first-child {{ border-left: none; }}
.st b {{ display: block; font: 600 11px Osw; letter-spacing: .28em; opacity: .38; margin-bottom: 8px; }}
.st span {{ font: 20px MiN; letter-spacing: .04em; opacity: .82; }}
.grid {{ position: absolute; left: {GX}px; top: {GY}px; width: {gw}px; height: {gh}px; transform: {transform}; transform-origin: {origin}; }}
.frame {{ position: absolute; inset: -22px; }}
.frame i {{ position: absolute; width: 18px; height: 18px; border: 0 solid rgba(238,241,245,.5); }}
.frame .a {{ left: 0; top: 0; border-left-width: 1px; border-top-width: 1px; }}
.frame .b {{ right: 0; top: 0; border-right-width: 1px; border-top-width: 1px; }}
.frame .c {{ left: 0; bottom: 0; border-left-width: 1px; border-bottom-width: 1px; }}
.frame .d {{ right: 0; bottom: 0; border-right-width: 1px; border-bottom-width: 1px; }}
.label {{ position: absolute; left: 0; top: -54px; font: 600 12px Osw; letter-spacing: .32em; opacity: .5; }}
.label em {{ font: normal 12px JBM; letter-spacing: .12em; opacity: .8; margin-left: 18px; }}
.rule {{ position: absolute; left: {gw + 40}px; top: -46px; width: 400px; height: 1px; background: rgba(238,241,245,.18); }}
.cell {{ position: absolute; box-sizing: border-box; border: 1px solid rgba(238,241,245,.2); background: rgba(255,255,255,.015); overflow: hidden; }}
.cell.on {{ border-color: rgba(238,241,245,.95); background: rgba(255,255,255,.06); }}
.cell .k {{ position: absolute; width: 12px; height: 12px; border: 0 solid {ACCENT}; }}
.cell .k.tl {{ left: -1px; top: -1px; border-left-width: 2px; border-top-width: 2px; }}
.cell .k.tr {{ right: -1px; top: -1px; border-right-width: 2px; border-top-width: 2px; }}
.cell .k.bl {{ left: -1px; bottom: -1px; border-left-width: 2px; border-bottom-width: 2px; }}
.cell .k.br {{ right: -1px; bottom: -1px; border-right-width: 2px; border-bottom-width: 2px; }}
.code {{ position: absolute; left: 14px; top: 12px; font: 11px JBM; letter-spacing: .12em; opacity: .42; }}
.cell.on .code {{ opacity: .9; }}
.ghost {{ position: absolute; right: 10px; bottom: -22px; font: 100 118px/1 Inter; opacity: .05; }}
.cell.big .ghost {{ font-size: 260px; bottom: -52px; opacity: .05; }}
.name {{ position: absolute; left: 16px; bottom: 58px; font: 26px MiM; letter-spacing: .06em; opacity: .62; }}
.cell.on .name {{ opacity: 1; }}
.cell.big .name {{ font: 62px MiS; bottom: 92px; letter-spacing: .1em; }}
.cell.wide .name {{ font-size: 30px; }}
.en {{ position: absolute; left: 17px; bottom: 38px; font: 500 11px Osw; letter-spacing: .26em; opacity: .38; }}
.cell.big .en {{ font-size: 14px; bottom: 64px; letter-spacing: .34em; }}
.cell.on .en {{ opacity: .7; }}
.state {{ position: absolute; left: 16px; bottom: 14px; font: 14px MiN; letter-spacing: .04em; opacity: .42; white-space: nowrap; }}
.cell.big .state {{ font-size: 18px; bottom: 26px; opacity: .6; }}
.cell.on .state {{ opacity: .78; }}
.cats {{ position: absolute; left: {MX}px; bottom: 96px; border-left: 1px solid rgba(238,241,245,.18); padding-left: 0; }}
.cat {{ position: relative; font: 22px MiN; letter-spacing: .14em; opacity: .36; padding: 9px 0 9px 64px; }}
.cat .ci {{ position: absolute; left: 28px; top: 15px; font: 12px JBM; opacity: .7; }}
.cat::before {{ content: ''; position: absolute; left: 0; top: 22px; width: 8px; height: 1px; background: {INK}; opacity: .6; }}
.cat.on {{ opacity: 1; }}
.cat.on::before {{ width: 18px; height: 2px; background: {ACCENT}; opacity: 1; }}
.hint {{ position: absolute; right: {MX}px; bottom: 60px; font: 500 11px Osw; letter-spacing: .3em; opacity: .26; }}
</style></head><body>
<div class="art"></div><div class="veil" style="{veil}"></div>
<div class="clock">21:36</div><div class="date">11月8日 星期日</div><div class="lunar">农历九月三十　·　中雨 15° – 19°</div>
<div class="bars"></div><div class="serial">ORRERY · MITV-ASTP0 · 03</div>
<div class="status">
  <div class="st"><b>WEATHER</b><span>中雨　15° – 19°</span></div>
  <div class="st"><b>NOW PLAYING</b><span>江南　林俊杰</span></div>
  <div class="st"><b>XINGYI</b><span>待命</span></div>
</div>
<div class="grid">
  <div class="frame"><i class="a"></i><i class="b"></i><i class="c"></i><i class="d"></i></div>
  <div class="label">TERMINAL<em>常用 · 08</em></div>
  {''.join(cells)}
</div>
<div class="cats" style="{'bottom: auto; top: 470px;' if tilt == 'left' else ''}">{cats}</div>
<div class="hint">ORRERY 4 · XINGYI TERMINAL</div>
</body></html>'''


def capture(html, out):
    tmp = tempfile.mkdtemp(prefix='orrery_ark_')
    p = os.path.join(tmp, 'ui.html')
    with open(p, 'w', encoding='utf-8') as fh:
        fh.write(html)
    shot = os.path.join(tmp, 'shot.png')
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                    '--allow-file-access-from-files', f'--window-size={W},{H}', '--virtual-time-budget=3000',
                    f'--screenshot={shot}', furl(p)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    shutil.copy(shot, out)
    shutil.rmtree(tmp, ignore_errors=True)
    print(out)


if __name__ == '__main__':
    a = sys.argv[1:]
    capture(page(os.path.abspath(a[0]), a[1], int(a[2])), a[3])
