"""Orrery 3 flow look-dev on the PC: the TV's own shaders (assets/flow), run by moderngl.

The host logic here (camera, uniforms, pass order, image rotation) mirrors FlowRenderer.java line for
line, so a still rendered here is what the TV draws with the same look.json and frame count.

python flowpc.py gen                                 bake assets/flow/chen.bin, write attractor stats into look.json
python flowpc.py still OUT.png [--frames N] [--fps 30] [--focus F] [--set key=json ...] [--auto]
python flowpc.py clip OUT.mp4 [--seconds S] [--fps 30] [--set ...]
python flowpc.py time [--frames N]                   GPU time per frame on this PC (sanity only; the TV is what counts)

--set takes dotted keys into look.json, e.g. --set camera.az=270 --set exposure=2.5
"""
import os, sys, json, math, time, subprocess
os.environ.setdefault('OMP_NUM_THREADS', '2')
if sys.platform == 'win32':
    import ctypes
    ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x4000)  # BELOW_NORMAL
import numpy as np
import moderngl

HERE = os.path.dirname(os.path.abspath(__file__))
FLOW = os.path.normpath(os.path.join(HERE, '..', 'assets', 'flow'))
OUT = os.path.join(HERE, 'out')
HEADER = '#version 430\n'
W, H = 1920, 1080
# defaults of the depth / detail keys (FlowRenderer has the same): each reproduces the look without it
TWINKLE = [1.0, 1.7, 0.0]
TONE = [0.018, 0.0, 0.0, 0.35]
ECHO = dict(share=0.0, energy=1.0, blur=16.0, scale=3.0, turn=[0.0, 0.0, 0.0], at=[0.66, 0.62, 4.5])
VEIL = dict(share=0.0, energy=1.0, blur=40.0, scale=1.2, turn=[0.0, 0.0, 0.0], at=[0.18, 0.28, 0.75])
SPRAY = dict(share=0.0, reach=0.35, spread=0.5, period=7.0, energy=1.0, wind=[-0.4, 1.0])


# ---------------------------------------------------------------- look + shaders
def load_look(sets=()):
    with open(os.path.join(FLOW, 'look.json'), encoding='utf-8') as f:
        look = json.load(f)
    for s in sets:
        k, v = s.split('=', 1)
        node = look
        parts = k.split('.')
        for p in parts[:-1]:
            node = node[p]
        node[parts[-1]] = json.loads(v)
    return look


def source(name):
    out = []
    with open(os.path.join(FLOW, name), encoding='utf-8') as f:
        for line in f.read().split('\n'):
            t = line.strip()
            if t.startswith('#include'):
                out.append(source(t[t.index('"') + 1:t.rindex('"')]))
            else:
                out.append(line)
    return '\n'.join(out)


def shader(name):
    return HEADER + '#line 1\n' + source(name)


# ---------------------------------------------------------------- shared host math (mirror of FlowRenderer.java)
def euler(deg):
    ax, ay, az = [math.radians(a) for a in deg]
    Rx = np.array([[1, 0, 0], [0, math.cos(ax), -math.sin(ax)], [0, math.sin(ax), math.cos(ax)]])
    Ry = np.array([[math.cos(ay), 0, math.sin(ay)], [0, 1, 0], [-math.sin(ay), 0, math.cos(ay)]])
    Rz = np.array([[math.cos(az), -math.sin(az), 0], [math.sin(az), math.cos(az), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def camera(look, t, focus, rest=False):
    """Position and basis (right, up, forward); rest: without the orbit (the frame the echo is laid out in)."""
    c = look['camera']
    ph = 2 * math.pi * (t / c['period'])
    ob = (0.0, 0.0) if rest else c['orbit']
    az = math.radians(c['az'] + ob[0] * math.sin(ph) + c['focusYaw'] * focus)
    el = math.radians(c['el'] + ob[1] * math.cos(ph))
    tgt = np.array(c['target'], dtype=np.float64)
    cam = tgt + c['dist'] * np.array([math.cos(el) * math.sin(az), math.sin(el), math.cos(el) * math.cos(az)])
    f = tgt - cam
    f /= np.linalg.norm(f)
    r = np.cross(f, np.array([0.0, 1.0, 0.0]))
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    rl = math.radians(c['roll'])
    r, u = r * math.cos(rl) + u * math.sin(rl), -r * math.sin(rl) + u * math.cos(rl)
    return cam, r, u, f


def disc64():
    """64 sunflower points on the unit disc: the stochastic circle of confusion (FlowRenderer.disc64)."""
    out = []
    for i in range(64):
        r = math.sqrt((i + 0.5) / 64.0)
        th = i * 2.399963229728653
        out += [r * math.cos(th), r * math.sin(th)]
    return np.array(out, dtype='f4')


def sky_uniforms(look, t, dt, frame, w, h, sw, sh):
    """The sky pass's uniforms (FlowRenderer.java: the sky block), or None when the look has no sky: a disc of stars
    around the luminous core, through the flow's own camera."""
    sky = look.get('sky')
    if not sky or int(sky.get('stars', 0)) <= 0:
        return None
    c, d = look['camera'], look['dof']
    energy = look['energy'] * (524288.0 / look['count']) * (dt * 60.0)
    cam, cr, cu, cf = camera(look, t, 0.0)
    mo, mr, mu, mf = camera(look, 0.0, 0.0, rest=True)
    fpx = (h / 2) / math.tan(math.radians(c['fov']) / 2)
    ppx, ppy = w / 2 + c['shift'][0] * h, h / 2 + c['shift'][1] * h
    tilt, inc = math.radians(sky.get('tilt', 0.0)), math.radians(sky.get('incline', 70.0))
    da = math.cos(tilt) * mr + math.sin(tilt) * mu
    db = math.cos(inc) * (-math.sin(tilt) * mr + math.cos(tilt) * mu) + math.sin(inc) * mf
    return dict(u_time=t, u_dt=dt, u_frame=frame, u_stars=int(sky['stars']),
                u_sky=(sky.get('inner', 0.03), sky.get('outer', 1.2), math.radians(sky.get('turn', 2.0)),
                       sky.get('energy', 1.0) * energy),
                u_arc=(math.radians(sky.get('arcMin', 5.0)), math.radians(sky.get('arcMax', 16.0))),
                u_dc=tuple(look['light']['pos']), u_da=tuple(da), u_db=tuple(db),
                u_cam=tuple(cam), u_cr=tuple(cr), u_cu=tuple(cu), u_cf=tuple(cf), u_fpx=fpx, u_pp=(ppx, ppy),
                u_lens=(d['focus'], d['aperture'], d['maxcoc'] * (h / 1080.0), d['near']), u_atm=tuple(look['atm']),
                u_res=(float(w), float(h)), u_sres=(float(sw), float(sh)))


def frame_uniforms(look, t, dt, frame, focus, w, h, sw, sh, fade=1.0, boost=1.0):
    """Every uniform of every pass for one frame. FlowRenderer.java computes the same values."""
    a, c, d = look['attractor'], look['camera'], look['dof']
    tau_rate = look['flow'] / a['speed']                   # attractor time per second at the median speed
    tau = tau_rate * dt * boost
    steps = max(1, min(16, int(math.ceil(tau / a['hmax']))))
    cam, cr, cu, cf = camera(look, t, focus)
    fpx = (h / 2) / math.tan(math.radians(c['fov']) / 2)
    d60 = math.exp(-(1.0 / 60.0) / look['trail'])
    decay = math.exp(-dt / look['trail'])
    energy = look['energy'] * (524288.0 / look['count']) * (dt * 60.0)
    norm = (1.0 - d60) / 256.0
    noff = ((frame * 0.6180339887498949) % 1.0, (frame * 0.4142135623730950) % 1.0)
    lt = look['light']
    ppx, ppy = w / 2 + c['shift'][0] * h, h / 2 + c['shift'][1] * h
    common = dict(u_time=t, u_dt=dt, u_frame=frame)
    flow = dict(common,
                u_count=look['count'], u_abc=tuple(a['abc']), u_tau=tau, u_steps=steps,
                u_center=tuple(a['center']), u_invr=1.0 / a['radius'],
                u_cam=tuple(cam), u_cr=tuple(cr), u_cu=tuple(cu), u_cf=tuple(cf),
                u_fpx=fpx, u_pp=(ppx, ppy),
                u_res=(float(w), float(h)), u_sres=(float(sw), float(sh)),
                u_focus=d['focus'], u_aper=d['aperture'], u_maxcoc=d['maxcoc'] * (h / 1080.0), u_near=d['near'],
                u_lt=tuple(x * (h / 1080.0) for x in d['lt']), u_atm=tuple(look['atm']),
                u_light=(*lt['pos'], 1.0 / (lt['r'] * lt['r'])), u_amb=lt['amb'], u_keep=tuple(look['keep']),
                u_energy=energy, u_glint=tuple(look['glint']), u_twinkle=tuple(look.get('twinkle', TWINKLE)),
                u_dye=tuple(look['dye']), u_bloom=(look['bloom'][0], look['bloom'][1] * (h / 1080.0)))
    rot = euler(look['rot'])
    # echo: a scaled, turned copy of the attractor far behind, centred on a point of the rest frame; veil: one close to
    # the lens (FlowRenderer.copyLayer)
    mo, mr, mu, mf = camera(look, 0.0, 0.0, rest=True)
    rc = np.column_stack([mr, mu, mf])

    def layer(e):
        ex, ey, ez = e['at']                                # frame fraction from the bottom-left, distance
        off = mo + ez * (mf + ((ex * w - ppx) / fpx) * mr + ((ey * h - ppy) / fpx) * mu)
        n = int(math.floor(look['count'] * e['share'] + 0.5))
        return n, rc @ euler(e['turn']) @ rc.T @ rot * e['scale'], tuple(off - cam), \
            (e['energy'] * look['count'] / max(n, 1), e['blur'] * (h / 1080.0))   # turn: pitch, yaw, roll

    ecount, erot, erel, echo = layer(dict(ECHO, **look.get('echo', {})))
    vcount, vrot, vrel, veil = layer(dict(VEIL, **look.get('veil', {})))
    flow.update(u_ecount=ecount, u_erot=erot, u_erel=erel, u_echo=echo,
                u_vcount=vcount, u_vrot=vrot, u_vrel=vrel, u_veil=veil)
    sp = dict(SPRAY, **look.get('spray', {}))                # spray: particles peeling off the gesture
    scount = int(math.floor(look['count'] * sp['share'] + 0.5))
    flow.update(u_scount=scount, u_spray=(sp['reach'], sp['spread'], 1.0 / sp['period'],
                                          sp['energy'] * look['count'] / max(scount, 1)), u_wind=tuple(sp['wind']))
    soft = dict(common, u_noff=noff, u_decay=decay, u_scale=norm * look['softgain'] / 16.0,
                u_gain=look['exposure'] * fade, u_vig=look['vignette'], u_ground=tuple(look['ground']),
                u_sres=(float(sw), float(sh)), u_aspect=w / h)
    final = dict(common, u_noff=noff, u_decay=decay, u_norm=norm, u_tsharp=tuple(look['tsharp']),
                 u_tsoft=tuple(look['tsoft']), u_grain=look['grain'] / 255.0)
    return flow, rot, soft, final


# ---------------------------------------------------------------- renderer
class Flow:
    def __init__(self, look, w=W, h=H):
        self.look, self.w, self.h = look, w, h
        self.sw, self.sh = (w + 3) // 4, (h + 3) // 4
        self.ctx = ctx = moderngl.create_standalone_context(require=430)
        self.cs = ctx.compute_shader(shader('flow.comp'))
        self.stars = ctx.compute_shader(shader('stars.comp'))
        vs = shader('fullscreen.vert')
        self.soft = ctx.program(vertex_shader=vs, fragment_shader=shader('soft.frag'))
        self.final = ctx.program(vertex_shader=vs, fragment_shader=shader('final.frag'))
        tables = ctx.program(vertex_shader=vs, fragment_shader=shader('tables.frag'))
        self.soft_vao = ctx.vertex_array(self.soft, [])
        self.final_vao = ctx.vertex_array(self.final, [])
        data = np.fromfile(os.path.join(FLOW, look['state']), dtype='<f4')
        n = look['count']
        assert data.size >= n * 4, f"state has {data.size // 4} particles, look wants {n}"
        self.particles = ctx.buffer(data[:n * 4].tobytes())

        def acc(sz):
            t = ctx.texture(sz, 1, dtype='u4')
            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
            t.write(np.zeros(sz[0] * sz[1], dtype='u4').tobytes())
            return t
        self.sharp = [acc((w, h)) for _ in range(3)]
        self.softacc = [acc((self.sw, self.sh)) for _ in range(3)]
        self.softtex = ctx.texture((self.sw, self.sh), 2, dtype='f2')
        self.softtex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.softtex.repeat_x = self.softtex.repeat_y = False
        self.soft_fb = ctx.framebuffer(color_attachments=[self.softtex])
        # build-time tables: noise (R8, repeat, nearest) and tone curve (R16F, clamp, linear)
        self.noise = ctx.texture((128, 128), 1, dtype='f1')
        self.noise.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.lut = ctx.texture((1024, 1), 1, dtype='f2')
        self.lut.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.lut.repeat_x = self.lut.repeat_y = False
        tvao = ctx.vertex_array(tables, [])
        for mode, tex in ((0, self.noise), (1, self.lut)):
            fb = ctx.framebuffer(color_attachments=[tex])
            fb.use()
            tables['u_mode'] = mode
            tables['u_tone'] = tuple(look.get('tone', TONE))
            tvao.render(moderngl.TRIANGLES, vertices=3)
        self.cs['u_disc'].write(disc64().tobytes())
        self.out = ctx.texture((w, h), 4, dtype='f1')
        self.out_fb = ctx.framebuffer(color_attachments=[self.out])
        self.frame = 0
        self.t = 0.0

    @staticmethod
    def put(prog, vals):
        for k, v in vals.items():
            if k in prog:
                if isinstance(v, np.ndarray):                     # mat3, column-major like glUniformMatrix3fv
                    prog[k].write(v.T.astype('f4').tobytes())
                else:
                    prog[k].value = v

    def step(self, dt=1.0 / 60.0, focus=0.0, fade=1.0, boost=1.0):
        ctx, f = self.ctx, self.frame
        cur, nxt = f % 3, (f + 1) % 3
        flow, rot, soft, final = frame_uniforms(self.look, self.t, dt, f, focus, self.w, self.h, self.sw, self.sh,
                                                fade, boost)
        # 1. advance + splat into this frame's images (they already hold last frame's decayed light)
        self.put(self.cs, flow)
        self.cs['u_rot'].write(rot.T.astype('f4').tobytes())
        self.particles.bind_to_storage_buffer(0)
        self.sharp[cur].bind_to_image(0, read=True, write=True)
        self.softacc[cur].bind_to_image(1, read=True, write=True)
        self.cs.run((self.look['count'] + 127) // 128)
        sky = sky_uniforms(self.look, self.t, dt, f, self.w, self.h, self.sw, self.sh)
        if sky:                                                  # the star trails (FlowRenderer: the same dispatch)
            self.put(self.stars, sky)
            self.stars.run((sky['u_stars'] + 127) // 128)
        ctx.memory_barrier(moderngl.ALL_BARRIER_BITS)
        # 2. soft layer: decay into next, tent filter + grade gain into softtex
        self.soft_fb.use()
        self.softacc[cur].use(0)
        self.noise.use(2)
        self.soft['u_acc'] = 0
        self.soft['u_noise'] = 2
        self.softacc[nxt].bind_to_image(3, read=False, write=True)
        self.put(self.soft, soft)
        self.soft_vao.render(moderngl.TRIANGLES, vertices=3)
        # 3. final: decay sharp into next, grade to the screen
        self.out_fb.use()
        self.sharp[cur].use(0)
        self.softtex.use(1)
        self.noise.use(2)
        self.lut.use(3)
        self.final['u_acc'] = 0
        self.final['u_soft'] = 1
        self.final['u_noise'] = 2
        self.final['u_lut'] = 3
        self.sharp[nxt].bind_to_image(2, read=False, write=True)
        self.put(self.final, final)
        self.final_vao.render(moderngl.TRIANGLES, vertices=3)
        ctx.memory_barrier(moderngl.ALL_BARRIER_BITS)
        self.frame += 1
        self.t += dt

    def image(self):
        a = np.frombuffer(self.out.read(alignment=1), dtype=np.uint8).reshape(self.h, self.w, 4)[::-1, :, :3]
        return np.ascontiguousarray(a)

    def linear_sharp(self):
        """Last frame's sharp accumulation in display-linear units (for auto exposure)."""
        cur = (self.frame - 1) % 3          # the image last frame accumulated into
        n = np.frombuffer(self.sharp[cur].read(), dtype='<u4').astype(np.float64)
        d60 = math.exp(-(1.0 / 60.0) / self.look['trail'])
        return n * (1.0 - d60) / 256.0


# ---------------------------------------------------------------- state generation
GEN_CS = r"""
#version 430
layout(local_size_x = 256) in;
layout(std430, binding = 0) buffer S { vec4 X[]; };
uniform uint uCount;
uniform uint uSrc;
uniform uint uDst;
uniform int uSteps;
uniform float uH;
uniform vec3 u_abc;
vec3 F(vec3 p) {
    return vec3(u_abc.x * (p.y - p.x), (u_abc.z - u_abc.x) * p.x - p.x * p.z + u_abc.z * p.y, p.x * p.y - u_abc.y * p.z);
}
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= uCount) return;
    vec3 p = X[uSrc + i].xyz;
    for (int k = 0; k < uSteps; k++) {
        vec3 k1 = F(p), k2 = F(p + 0.5 * uH * k1), k3 = F(p + 0.5 * uH * k2), k4 = F(p + uH * k3);
        p += uH / 6.0 * (k1 + 2.0 * k2 + 2.0 * k3 + k4);
    }
    X[uDst + i] = vec4(p, -1.0);
}
"""


def gen(look):
    """Lay the particles on the attractor: K trajectories burnt in, then M samples along each, shuffled."""
    a = look['attractor']
    n = look['count']
    K = 8192
    M = (n + K - 1) // K
    h = a['hmax']
    ctx = moderngl.create_standalone_context(require=430)
    cs = ctx.compute_shader(GEN_CS)
    rng = np.random.default_rng(7)
    buf = np.zeros(((M + 1) * K, 4), dtype='f4')
    buf[:K, :3] = np.array([-10.0, 0.0, 37.0]) + rng.normal(0, 0.5, (K, 3))
    sb = ctx.buffer(buf.tobytes())
    sb.bind_to_storage_buffer(0)
    cs['uCount'] = K
    cs['uH'] = h
    cs['u_abc'] = tuple(a['abc'])
    groups = (K + 255) // 256

    def run(src, dst, steps):
        cs['uSrc'] = src
        cs['uDst'] = dst
        cs['uSteps'] = steps
        cs.run(groups)
        ctx.memory_barrier(moderngl.ALL_BARRIER_BITS)
    for _ in range(20):                        # burn-in: 20 x 2000 steps = 60 attractor time units
        run(0, 0, 2000)
    for j in range(M):                         # samples ~0.05 attractor time apart along each trajectory
        run(j * K, (j + 1) * K, 32)
    X = np.frombuffer(sb.read(), dtype='f4').reshape(-1, 4)[K:K + n].copy()
    p = X[:, :3].astype(np.float64)
    assert np.all(np.isfinite(p)), "trajectory blew up"
    c = np.median(p, axis=0)
    R = float(np.percentile(np.linalg.norm(p - c, axis=1), 99.0))
    A, B, C = a['abc']
    x, y, z = p[:4096].T
    v = np.stack([A * (y - x), (C - A) * x - x * z + C * y, x * y - B * z], axis=1)
    speed = float(np.median(np.linalg.norm(v, axis=1)) / R)
    X = X[rng.permutation(n)]
    X[:, 3] = -1.0
    X.astype('<f4').tofile(os.path.join(FLOW, look['state']))
    print(f'{n:,} particles  centre {np.round(c, 3).tolist()}  radius {R:.3f}  median speed {speed:.3f} /tau  '
          f'file {os.path.getsize(os.path.join(FLOW, look["state"])) / 1e6:.1f} MB')
    return [round(float(q), 5) for q in c], round(R, 5), round(speed, 5)


def write_stats(center, radius, speed):
    path = os.path.join(FLOW, 'look.json')
    with open(path, encoding='utf-8') as f:
        txt = f.read()
    look = json.loads(txt)
    look['attractor'].update(center=center, radius=radius, speed=speed)
    # keep the hand-written layout: rewrite only the attractor line
    import re
    line = json.dumps(look['attractor'])
    txt = re.sub(r'"attractor": \{[^\n]*\}', '"attractor": ' + line, txt)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(txt)


# ---------------------------------------------------------------- CLI
def opt(args, name, default=None, cast=str):
    if name in args:
        return cast(args[args.index(name) + 1])
    return default


def sets(args):
    return [args[i + 1] for i, a in enumerate(args) if a == '--set']


def save_png(a, path):
    from PIL import Image
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    Image.fromarray(a).save(path)
    print(path)


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else 'still'
    look = load_look(sets(args))
    if cmd == 'gen':
        write_stats(*gen(look))
        return
    fl = Flow(look)
    focus = opt(args, '--focus', 0.0, float)
    if cmd == 'still':
        frames = opt(args, '--frames', 150, int)
        dt = 1.0 / opt(args, '--fps', 60, float)
        for _ in range(frames):
            fl.step(dt=dt, focus=focus)
        if '--auto' in args:
            d = fl.linear_sharp()
            p = np.percentile(d[d > 0], 99.7) if np.any(d > 0) else 1.0
            print(f'auto: 99.7th pct of lit sharp pixels = {p:.4f} linear; exposure for tone 2.2 = {2.2 / p:.3f}')
        save_png(fl.image(), args[1])
    elif cmd == 'clip':
        secs = opt(args, '--seconds', 6.0, float)
        fps = opt(args, '--fps', 30, int)
        out = args[1]
        for _ in range(120):
            fl.step()
        ff = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                               '-s', f'{W}x{H}', '-r', str(fps), '-i', '-', '-c:v', 'libx264', '-preset', 'medium',
                               '-crf', '16', '-pix_fmt', 'yuv420p', '-threads', '4', out], stdin=subprocess.PIPE)
        per = max(1, round(60 / fps))
        for i in range(int(secs * fps)):
            for _ in range(per):
                fl.step()
            ff.stdin.write(fl.image().tobytes())
        ff.stdin.close()
        ff.wait()
        print(out)
    elif cmd == 'time':
        frames = opt(args, '--frames', 300, int)
        fl.step()
        fl.ctx.finish()
        t0 = time.perf_counter()
        for _ in range(frames):
            fl.step()
        fl.ctx.finish()
        print(f'{(time.perf_counter() - t0) / frames * 1000:.2f} ms/frame on this PC')


if __name__ == '__main__':
    main()
