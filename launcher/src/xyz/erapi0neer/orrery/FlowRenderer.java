package xyz.erapi0neer.orrery;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.opengl.GLES31;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES30.*;

/**
 * The home screen's background: half a million particles on the Chen attractor, simulated and
 * drawn on the GPU every frame. Three passes: compute (advance + splat into integer images),
 * soft layer (quarter resolution, also carries the grade gain), final (tone table) to the screen.
 * The Mali-G31 has about 40 operations per pixel per frame at 1080p60, so the full-screen passes
 * only fetch: noise and the tone curve are tables drawn once at build time.
 * The host logic mirrors lookdev/flowpc.py line for line, so a still rendered on the PC is what the
 * TV shows. look.json and shaders pushed to files/flow override the packaged assets (RELOAD).
 */
final class FlowRenderer implements GLSurfaceView.Renderer {
    static final String TAG = "OrreryFlow";
    static final String HEADER = "#version 320 es\nprecision highp float;\nprecision highp int;\n"
            + "precision highp sampler2D;\nprecision highp usampler2D;\nprecision highp uimage2D;\n#line 1\n";
    static final int READ_WRITE = 0x88BA, WRITE_ONLY = 0x88B9;   // image access (GLES 3.1)
    // defaults of the depth / detail keys (flowpc.py has the same): each reproduces the look without it
    static final float[] TWINKLE = {1f, 1.7f, 0f}, TONE = {0.018f, 0f, 0f, 0.35f};
    static final double[] ZERO3 = {0, 0, 0}, ECHO_AT = {0.66, 0.62, 4.5}, VEIL_AT = {0.18, 0.28, 0.75}, WIND = {-0.4, 1.0};

    interface SnapshotSink { void onFrame(Bitmap frame, String name); }

    final Context ctx;
    final File overrides, statsFile;
    volatile SnapshotSink sink;

    // written by the UI thread, read once per frame
    volatile float focusTarget, fadeTarget = 1f, boostTarget = 1f;
    volatile boolean reloadLook;
    volatile String snapName;
    volatile long vsyncNs;                    // vsync time of the requested frame (Choreographer)
    volatile int divider = 2;                 // render every n-th vsync: look.json "fps" 30 -> 2

    JSONObject look;
    boolean ready;
    String failure;
    int width, height, sw, sh, count, fileCount, frame;
    final Prog flow = new Prog(), soft = new Prog(), fin = new Prog(), stars = new Prog();
    final int[] sharp = new int[3], softAcc = new int[3];
    final int[] ssbo = new int[1], vao = new int[1], softTex = new int[1], softFbo = new int[1];
    final int[] noise = new int[1], lut = new int[1];
    double t;
    long lastNs;
    float focus, focusVel, fade, boost = 1f;
    final float[] rot = new float[9];

    // stats, written to files/flow-stats.txt every 5 s
    long statStart;
    int statFrames, slowFrames, glErrors, lastError;
    double maxGapMs, cpuMs;

    FlowRenderer(Context ctx) {
        this.ctx = ctx;
        File ext = ctx.getExternalFilesDir(null);
        overrides = new File(ext, "flow");
        statsFile = new File(ext, "flow-stats.txt");
    }

    // ------------------------------------------------------------------ GLSurfaceView.Renderer
    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        forget();                                   // a new context: every old object name is meaningless
        Log.i(TAG, "GL " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) {
        if (w != width || h != height) { width = w; height = h; ready = false; }
    }

    @Override public void onDrawFrame(GL10 unused) {
        long start = System.nanoTime(), v = vsyncNs, now = v != 0 ? v : start;
        if (reloadLook) { reloadLook = false; ready = false; failure = null; }
        if (!ready && failure == null) build();
        if (!ready) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            lastNs = now;
            return;
        }
        float dt = lastNs == 0 ? divider / 60f : Math.max(1f / 240f, Math.min(1f / 10f, (now - lastNs) / 1e9f));
        double gapMs = lastNs == 0 ? 0 : (now - lastNs) / 1e6;
        lastNs = now;
        animate(dt);
        draw(dt);
        if (snapName != null) { String n = snapName; snapName = null; snapshot(n); }
        stats(gapMs, (System.nanoTime() - start) / 1e6);
    }

    /** The GL thread pauses with the activity; the first frame after must not see the pause as dt. */
    void paused() { lastNs = 0; }

    // ------------------------------------------------------------------ per frame
    void animate(float dt) {
        // focus: critically damped spring, the camera swings and settles without overshoot
        float w = 4f;
        float a = w * w * (focusTarget - focus) - 2f * w * focusVel;
        focusVel += a * dt;
        focus += focusVel * dt;
        float rate = fadeTarget > fade ? 1.6f : 7f;
        fade += (fadeTarget - fade) * (1f - (float) Math.exp(-dt * rate));
        boost += (boostTarget - boost) * (1f - (float) Math.exp(-dt * 4f));
    }

    void draw(float dt) {
        JSONObject a = look.optJSONObject("attractor"), c = look.optJSONObject("camera"), d = look.optJSONObject("dof");
        JSONObject lt = look.optJSONObject("light");
        int cur = frame % 3, nxt = (frame + 1) % 3;
        double tauRate = look.optDouble("flow") / a.optDouble("speed");
        double tau = tauRate * dt * boost;
        int steps = Math.max(1, Math.min(16, (int) Math.ceil(tau / a.optDouble("hmax"))));
        double[] cam = new double[12];
        camera(c, t, focus, false, cam);
        double fpx = (height / 2.0) / Math.tan(Math.toRadians(c.optDouble("fov")) / 2);
        double d60 = Math.exp(-(1.0 / 60.0) / look.optDouble("trail"));
        float decay = (float) Math.exp(-dt / look.optDouble("trail"));
        double energy = look.optDouble("energy") * (524288.0 / count) * (dt * 60.0);
        float norm = (float) ((1.0 - d60) / 256.0);
        float nox = (float) ((frame * 0.6180339887498949) % 1.0), noy = (float) ((frame * 0.4142135623730950) % 1.0);
        float px = height / 1080f;
        float[] shift = arr(c, "shift"), lpos = arr(lt, "pos"), lts = arr(d, "lt"), bloom = arr(look, "bloom");
        double lr = lt.optDouble("r");
        double ppx = width / 2.0 + shift[0] * height, ppy = height / 2.0 + shift[1] * height;
        prof = look.optBoolean("profile", false);
        String skip = look.optString("skip", "");     // measurement only: "compute,soft,final"
        markNs = System.nanoTime();
        glBindVertexArray(vao[0]);

        // 1. advance + splat into this frame's images (they already hold last frame's decayed light)
        glUseProgram(flow.id);
        common(flow, dt);
        flow.setu("u_count", count);
        flow.set("u_abc", arr(a, "abc"));
        flow.set("u_tau", (float) tau);
        flow.seti("u_steps", steps);
        flow.set("u_center", arr(a, "center"));
        flow.set("u_invr", (float) (1.0 / a.optDouble("radius")));
        flow.setMat3("u_rot", rot);
        flow.set("u_cam", (float) cam[0], (float) cam[1], (float) cam[2]);
        flow.set("u_cr", (float) cam[3], (float) cam[4], (float) cam[5]);
        flow.set("u_cu", (float) cam[6], (float) cam[7], (float) cam[8]);
        flow.set("u_cf", (float) cam[9], (float) cam[10], (float) cam[11]);
        flow.set("u_fpx", (float) fpx);
        flow.set("u_pp", (float) ppx, (float) ppy);
        flow.set("u_res", (float) width, (float) height);
        flow.set("u_sres", (float) sw, (float) sh);
        flow.set("u_focus", (float) d.optDouble("focus"));
        flow.set("u_aper", (float) d.optDouble("aperture"));
        flow.set("u_maxcoc", (float) d.optDouble("maxcoc") * px);
        flow.set("u_near", (float) d.optDouble("near"));
        flow.set("u_lt", lts[0] * px, lts[1] * px);
        flow.set("u_atm", arr(look, "atm"));
        flow.set("u_light", lpos[0], lpos[1], lpos[2], (float) (1.0 / (lr * lr)));
        flow.set("u_amb", (float) lt.optDouble("amb"));
        flow.set("u_keep", arr(look, "keep"));
        flow.set("u_energy", (float) energy);
        flow.set("u_glint", arr(look, "glint"));
        flow.set("u_twinkle", arr(look, "twinkle", TWINKLE));
        flow.set("u_dye", arr(look, "dye"));
        flow.set("u_bloom", bloom[0], bloom[1] * px);
        // echo: a scaled, turned copy of the attractor far behind, centred on a point of the rest frame; veil: one close
        // to the lens (2026-11-06, depth). Each off without its key.
        double[] rest = new double[12];
        camera(c, 0, 0, true, rest);
        int ecount = copyLayer(flow, look.optJSONObject("echo"), ECHO_AT, 3.0, 16.0, rest, cam, count, px, ppx, ppy, fpx,
                "u_erot", "u_erel", "u_echo");
        int vcount = copyLayer(flow, look.optJSONObject("veil"), VEIL_AT, 1.2, 40.0, rest, cam, count, px, ppx, ppy, fpx,
                "u_vrot", "u_vrel", "u_veil");
        flow.setu("u_ecount", ecount);
        flow.setu("u_vcount", vcount);
        // spray: particles peeling off the gesture (off without the key)
        JSONObject sp = look.optJSONObject("spray");
        if (sp == null) sp = new JSONObject();
        int scount = (int) Math.floor(count * sp.optDouble("share", 0.0) + 0.5);
        flow.setu("u_scount", scount);
        flow.set("u_spray", (float) sp.optDouble("reach", 0.35), (float) sp.optDouble("spread", 0.5),
                (float) (1.0 / sp.optDouble("period", 7.0)), (float) (sp.optDouble("energy", 1.0) * count / Math.max(scount, 1)));
        double[] wind = darr(sp, "wind", WIND);
        flow.set("u_wind", (float) wind[0], (float) wind[1]);
        glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo[0]);
        GLES31.glBindImageTexture(0, sharp[cur], 0, false, 0, READ_WRITE, GL_R32UI);
        GLES31.glBindImageTexture(1, softAcc[cur], 0, false, 0, READ_WRITE, GL_R32UI);
        if (!skip.contains("compute")) GLES31.glDispatchCompute((count + 127) / 128, 1, 1);
        // the sky: star trails turning about a pole off the frame, into the same images (off without the key)
        JSONObject sky = look.optJSONObject("sky");
        int nstars = sky == null ? 0 : sky.optInt("stars", 0);
        if (nstars > 0 && !skip.contains("sky")) {
            glUseProgram(stars.id);
            common(stars, dt);
            stars.setu("u_stars", nstars);
            stars.set("u_sky", (float) sky.optDouble("inner", 0.03), (float) sky.optDouble("outer", 1.2),
                    (float) Math.toRadians(sky.optDouble("turn", 2.0)), (float) (sky.optDouble("energy", 1.0) * energy));
            stars.set("u_arc", (float) Math.toRadians(sky.optDouble("arcMin", 5.0)), (float) Math.toRadians(sky.optDouble("arcMax", 16.0)));
            // the disc around the luminous core, laid out in the camera's rest frame: turned in the image by tilt,
            // leaning back from the view by incline (its far side recedes, its near side comes close)
            double tilt = Math.toRadians(sky.optDouble("tilt", 0.0)), inc = Math.toRadians(sky.optDouble("incline", 70.0));
            float[] da = new float[3], db = new float[3];
            for (int i = 0; i < 3; i++) {
                double right = rest[3 + i], up = rest[6 + i], fwd = rest[9 + i];
                da[i] = (float) (Math.cos(tilt) * right + Math.sin(tilt) * up);
                db[i] = (float) (Math.cos(inc) * (-Math.sin(tilt) * right + Math.cos(tilt) * up) + Math.sin(inc) * fwd);
            }
            stars.set("u_dc", lpos[0], lpos[1], lpos[2]);
            stars.set("u_da", da);
            stars.set("u_db", db);
            stars.set("u_cam", (float) cam[0], (float) cam[1], (float) cam[2]);
            stars.set("u_cr", (float) cam[3], (float) cam[4], (float) cam[5]);
            stars.set("u_cu", (float) cam[6], (float) cam[7], (float) cam[8]);
            stars.set("u_cf", (float) cam[9], (float) cam[10], (float) cam[11]);
            stars.set("u_fpx", (float) fpx);
            stars.set("u_pp", (float) ppx, (float) ppy);
            stars.set("u_lens", (float) d.optDouble("focus"), (float) d.optDouble("aperture"), (float) d.optDouble("maxcoc") * px,
                    (float) d.optDouble("near"));
            stars.set("u_atm", arr(look, "atm"));
            stars.set("u_res", (float) width, (float) height);
            stars.set("u_sres", (float) sw, (float) sh);
            GLES31.glDispatchCompute((nstars + 127) / 128, 1, 1);
        }
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        mark(0);

        // 2. soft layer: decay into next, tent filter + grade gain into softTex
        glBindFramebuffer(GL_FRAMEBUFFER, softFbo[0]);
        glViewport(0, 0, sw, sh);
        glUseProgram(soft.id);
        common(soft, dt);
        bind(0, softAcc[cur]);
        bind(2, noise[0]);
        soft.seti("u_acc", 0);
        soft.seti("u_noise", 2);
        GLES31.glBindImageTexture(3, softAcc[nxt], 0, false, 0, WRITE_ONLY, GL_R32UI);
        soft.set("u_noff", nox, noy);
        soft.set("u_decay", decay);
        soft.set("u_scale", (float) (norm * look.optDouble("softgain") / 16.0));
        soft.set("u_gain", (float) look.optDouble("exposure") * fade);
        soft.set("u_vig", (float) look.optDouble("vignette"));
        soft.set("u_ground", arr(look, "ground"));
        soft.set("u_sres", (float) sw, (float) sh);
        soft.set("u_aspect", width / (float) height);
        if (!skip.contains("soft")) glDrawArrays(GL_TRIANGLES, 0, 3);
        mark(1);

        // 3. final: decay sharp into next, grade to the screen
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glUseProgram(fin.id);
        common(fin, dt);
        bind(0, sharp[cur]);
        bind(1, softTex[0]);
        bind(2, noise[0]);
        bind(3, lut[0]);
        fin.seti("u_acc", 0);
        fin.seti("u_soft", 1);
        fin.seti("u_noise", 2);
        fin.seti("u_lut", 3);
        GLES31.glBindImageTexture(2, sharp[nxt], 0, false, 0, WRITE_ONLY, GL_R32UI);
        fin.set("u_noff", nox, noy);
        fin.set("u_decay", decay);
        fin.set("u_norm", norm);
        fin.set("u_tsharp", arr(look, "tsharp"));
        fin.set("u_tsoft", arr(look, "tsoft"));
        fin.set("u_grain", (float) look.optDouble("grain") / 255f);
        if (!skip.contains("final")) glDrawArrays(GL_TRIANGLES, 0, 3);
        else { glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT); }
        mark(2);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(0);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
        if (frame % 60 == 0) {
            int e = glGetError();
            if (e != GL_NO_ERROR) { glErrors++; lastError = e; }
        }
        frame++;
        t += dt;
    }

    static void bind(int unit, int tex) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, tex);
    }

    void common(Prog p, float dt) {
        p.set("u_time", (float) t);
        p.set("u_dt", dt);
        p.seti("u_frame", frame);
    }

    // profiling (look.json "profile": true): glFinish after each pass, so the times are per pass, not pipelined
    boolean prof;
    long markNs;
    final double[] passMs = new double[3];
    int passN;

    void mark(int pass) {
        if (!prof) return;
        glFinish();
        long now = System.nanoTime();
        passMs[pass] += (now - markNs) / 1e6;
        markNs = now;
        if (pass == 2) passN++;
    }

    /** Camera position and basis (right, up, forward) into out[0..11]; same maths as flowpc.camera().
     *  rest: without the orbit (the frame the echo is laid out in). */
    static void camera(JSONObject c, double t, double focus, boolean rest, double[] out) {
        double ph = 2 * Math.PI * (t / c.optDouble("period"));
        JSONArray orbit = c.optJSONArray("orbit");
        double oa = rest ? 0 : orbit.optDouble(0), oe = rest ? 0 : orbit.optDouble(1);
        double az = Math.toRadians(c.optDouble("az") + oa * Math.sin(ph) + c.optDouble("focusYaw") * focus);
        double el = Math.toRadians(c.optDouble("el") + oe * Math.cos(ph));
        JSONArray tg = c.optJSONArray("target");
        double dist = c.optDouble("dist");
        double tx = tg.optDouble(0), ty = tg.optDouble(1), tz = tg.optDouble(2);
        double cx = tx + dist * Math.cos(el) * Math.sin(az), cy = ty + dist * Math.sin(el), cz = tz + dist * Math.cos(el) * Math.cos(az);
        double fx = tx - cx, fy = ty - cy, fz = tz - cz;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        // r = normalize(cross(f, +y)), u = cross(r, f)
        double rx = -fz, ry = 0, rz = fx;
        double rlen = Math.sqrt(rx * rx + rz * rz);
        rx /= rlen; rz /= rlen;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double rl = Math.toRadians(c.optDouble("roll")), cs = Math.cos(rl), sn = Math.sin(rl);
        out[0] = cx; out[1] = cy; out[2] = cz;
        out[3] = rx * cs + ux * sn; out[4] = ry * cs + uy * sn; out[5] = rz * cs + uz * sn;
        out[6] = -rx * sn + ux * cs; out[7] = -ry * sn + uy * cs; out[8] = -rz * sn + uz * cs;
        out[9] = fx; out[10] = fy; out[11] = fz;
    }

    /** R = Rz Ry Rx (degrees), packed column-major for glUniformMatrix3fv; same as flowpc.euler(). */
    static void euler(float[] deg, float[] m) {
        double[][] r = eulerM(new double[]{deg[0], deg[1], deg[2]});
        for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++) m[col * 3 + row] = (float) r[row][col];
    }

    /** A copy of the attractor laid out in the rest frame (the echo far behind, the veil close to the lens): centred on
     * a point of the frame (at: fractions from the bottom-left, distance), turned (pitch, yaw, roll in that frame) and
     * scaled. Sets its matrix, offset and (energy, blur) uniforms; returns how many particles it takes. */
    private int copyLayer(Prog flow, JSONObject l, double[] defAt, double defScale, double defBlur, double[] rest, double[] cam,
                          int count, float px, double ppx, double ppy, double fpx, String rotU, String relU, String eU) {
        if (l == null) l = new JSONObject();
        double[] at = darr(l, "at", defAt);
        double ex = (at[0] * width - ppx) / fpx, ey = (at[1] * height - ppy) / fpx;
        double[][] rc = new double[3][3], rct = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) { rc[i][j] = rest[3 + 3 * j + i]; rct[j][i] = rc[i][j]; }
        double[][] er = mul(mul(mul(rc, eulerM(darr(l, "turn", ZERO3))), rct), eulerM(darr(look, "rot", ZERO3)));
        double scale = l.optDouble("scale", defScale);
        float[] m = new float[9], rel = new float[3];
        for (int col = 0; col < 3; col++)
            for (int row = 0; row < 3; row++) m[col * 3 + row] = (float) (er[row][col] * scale);
        for (int i = 0; i < 3; i++)
            rel[i] = (float) (rest[i] + at[2] * (rest[9 + i] + ex * rest[3 + i] + ey * rest[6 + i]) - cam[i]);
        int n = (int) Math.floor(count * l.optDouble("share", 0.0) + 0.5);
        flow.setMat3(rotU, m);
        flow.set(relU, rel);
        flow.set(eU, (float) (l.optDouble("energy", 1.0) * count / Math.max(n, 1)), (float) l.optDouble("blur", defBlur) * px);
        return n;
    }

    static double[][] eulerM(double[] deg) {
        double ax = Math.toRadians(deg[0]), ay = Math.toRadians(deg[1]), az = Math.toRadians(deg[2]);
        double[][] rx = {{1, 0, 0}, {0, Math.cos(ax), -Math.sin(ax)}, {0, Math.sin(ax), Math.cos(ax)}};
        double[][] ry = {{Math.cos(ay), 0, Math.sin(ay)}, {0, 1, 0}, {-Math.sin(ay), 0, Math.cos(ay)}};
        double[][] rz = {{Math.cos(az), -Math.sin(az), 0}, {Math.sin(az), Math.cos(az), 0}, {0, 0, 1}};
        return mul(rz, mul(ry, rx));
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] o = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) o[i][j] += a[i][k] * b[k][j];
        return o;
    }

    /** 64 sunflower points on the unit disc: the stochastic circle of confusion (flowpc.disc64). */
    static float[] disc64() {
        float[] v = new float[128];
        for (int i = 0; i < 64; i++) {
            double r = Math.sqrt((i + 0.5) / 64.0), th = i * 2.399963229728653;
            v[2 * i] = (float) (r * Math.cos(th));
            v[2 * i + 1] = (float) (r * Math.sin(th));
        }
        return v;
    }

    // ------------------------------------------------------------------ build / release
    void build() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            release();
            look = new JSONObject(text("look.json"));
            flow.compute(this, "flow.comp");
            stars.compute(this, "stars.comp");
            soft.link(this, "fullscreen.vert", "soft.frag");
            fin.link(this, "fullscreen.vert", "final.frag");
            euler(arr(look, "rot"), rot);
            loadState(look.optString("state", "chen.bin"));
            count = Math.max(1024, Math.min(fileCount, look.optInt("count", fileCount)));
            divider = Math.max(1, (int) Math.round(60.0 / look.optDouble("fps", 30)));
            sw = (width + 3) / 4;
            sh = (height + 3) / 4;
            for (int i = 0; i < 3; i++) {
                sharp[i] = accTexture(width, height);
                softAcc[i] = accTexture(sw, sh);
            }
            softTex[0] = texture(sw, sh, GL_RG16F, GL_LINEAR, GL_CLAMP_TO_EDGE);
            softFbo[0] = target(softTex[0]);
            noise[0] = texture(128, 128, GL_R8, GL_NEAREST, GL_REPEAT);
            lut[0] = texture(1024, 1, GL_R16F, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glGenVertexArrays(1, vao, 0);
            tables();
            glUseProgram(flow.id);
            int l = flow.at("u_disc");
            if (l >= 0) glUniform2fv(l, 64, disc64(), 0);
            int e = glGetError();
            if (e != GL_NO_ERROR) throw new IllegalStateException("glError 0x" + Integer.toHexString(e) + " during build");
            ready = true;
            frame = 0;
            lastNs = 0;
            resetStats();
            Log.i(TAG, "built " + count + " particles " + width + "x" + height + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable ex) {
            failure = String.valueOf(ex);
            Log.e(TAG, "build failed", ex);
            writeStats("error: " + failure + "\n");
            release();
        }
    }

    /** Draw the noise and tone tables once (tables.frag), with the same GLSL the PC uses. */
    void tables() throws IOException {
        Prog p = new Prog();
        p.link(this, "fullscreen.vert", "tables.frag");
        glUseProgram(p.id);
        glBindVertexArray(vao[0]);
        p.set("u_tone", arr(look, "tone", TONE));
        int[] tex = {noise[0], lut[0]}, size = {128, 128, 1024, 1};
        for (int mode = 0; mode < 2; mode++) {
            int fb = target(tex[mode]);
            glBindFramebuffer(GL_FRAMEBUFFER, fb);
            glViewport(0, 0, size[mode * 2], size[mode * 2 + 1]);
            p.seti("u_mode", mode);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, new int[]{fb}, 0);
        }
        glBindVertexArray(0);
        p.release();
    }

    static int texture(int w, int h, int format, int filter, int wrap) {
        int[] id = new int[1];
        glGenTextures(1, id, 0);
        glBindTexture(GL_TEXTURE_2D, id[0]);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        return id[0];
    }

    static int target(int tex) {
        int[] fb = new int[1];
        glGenFramebuffers(1, fb, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, fb[0]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        int st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (st != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer incomplete 0x" + Integer.toHexString(st));
        return fb[0];
    }

    /** An R32UI accumulation image, cleared. */
    static int accTexture(int w, int h) {
        int id = texture(w, h, GL_R32UI, GL_NEAREST, GL_CLAMP_TO_EDGE);
        int fb = target(id);
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        glClearBufferuiv(GL_COLOR, 0, new int[4], 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, new int[]{fb}, 0);
        return id;
    }

    /** The particle state: count x vec4 float32 (xyz on the attractor, w = -1). Stored uncompressed, mapped. */
    void loadState(String name) throws IOException {
        ByteBuffer buf;
        try (AssetFileDescriptor fd = ctx.getAssets().openFd("flow/" + name);
             FileInputStream in = fd.createInputStream()) {
            buf = in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getLength());
        }
        buf.order(ByteOrder.LITTLE_ENDIAN);
        fileCount = buf.capacity() / 16;
        glGenBuffers(1, ssbo, 0);
        glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[0]);
        glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, fileCount * 16, buf, GL_DYNAMIC_COPY);
        glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /** Free every GL object (look reload). The next frame rebuilds. */
    void release() {
        flow.release(); soft.release(); fin.release(); stars.release();
        for (int i = 0; i < 3; i++) {
            if (sharp[i] != 0) glDeleteTextures(1, sharp, i);
            if (softAcc[i] != 0) glDeleteTextures(1, softAcc, i);
        }
        for (int[] tex : new int[][]{softTex, noise, lut}) if (tex[0] != 0) glDeleteTextures(1, tex, 0);
        if (softFbo[0] != 0) glDeleteFramebuffers(1, softFbo, 0);
        if (ssbo[0] != 0) glDeleteBuffers(1, ssbo, 0);
        if (vao[0] != 0) glDeleteVertexArrays(1, vao, 0);
        forget();
    }

    void forget() {
        flow.id = soft.id = fin.id = 0;
        Arrays.fill(sharp, 0);
        Arrays.fill(softAcc, 0);
        softTex[0] = softFbo[0] = noise[0] = lut[0] = ssbo[0] = vao[0] = 0;
        ready = false;
    }

    // ------------------------------------------------------------------ io
    String text(String name) throws IOException {
        File f = new File(overrides, name);
        InputStream in = f.exists() ? new FileInputStream(f) : ctx.getAssets().open("flow/" + name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) out.write(b, 0, r);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    String source(String name) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : text(name).split("\n")) {
            String s = line.trim();
            if (s.startsWith("#include")) sb.append(source(s.substring(s.indexOf('"') + 1, s.lastIndexOf('"')))).append('\n');
            else sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static float[] arr(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return new float[]{(float) o.optDouble(key, 0)};
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i);
        return v;
    }

    /** An array key that may be absent (the depth / detail keys): the default reproduces the look without it. */
    static float[] arr(JSONObject o, String key, float[] def) {
        return o.optJSONArray(key) == null ? def : arr(o, key);
    }

    static double[] darr(JSONObject o, String key, double[] def) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return def;
        double[] v = new double[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i);
        return v;
    }

    // ------------------------------------------------------------------ snapshot + stats
    void snapshot(String name) {
        ByteBuffer px = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, px);
        Bitmap up = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        px.rewind();
        up.copyPixelsFromBuffer(px);
        android.graphics.Matrix flip = new android.graphics.Matrix();
        flip.preScale(1f, -1f);
        Bitmap frameBmp = Bitmap.createBitmap(up, 0, 0, width, height, flip, false);
        up.recycle();
        SnapshotSink s = sink;
        if (s != null) s.onFrame(frameBmp, name);
    }

    void resetStats() {
        statStart = SystemClock.elapsedRealtime();
        statFrames = slowFrames = 0;
        maxGapMs = cpuMs = 0;
    }

    void stats(double gapMs, double cpu) {
        statFrames++;
        cpuMs += cpu;
        if (gapMs > divider * 25.0) slowFrames++;      // a frame took over 1.5 intervals
        maxGapMs = Math.max(maxGapMs, gapMs);
        long now = SystemClock.elapsedRealtime();
        if (now - statStart < 5000) return;
        double secs = (now - statStart) / 1000.0;
        String s = String.format(Locale.US, "fps=%.1f slow=%d maxgap=%.0fms cpu=%.2fms glErrors=%d last=0x%x n=%d %dx%d frame=%d",
                statFrames / secs, slowFrames, maxGapMs, cpuMs / statFrames, glErrors, lastError, count, width, height, frame);
        if (passN > 0) {
            s += String.format(Locale.US, " | compute=%.1f soft=%.1f final=%.1f ms", passMs[0] / passN, passMs[1] / passN, passMs[2] / passN);
            Arrays.fill(passMs, 0);
            passN = 0;
        }
        Log.i(TAG, s);
        writeStats(s + "\n");
        resetStats();
    }

    void writeStats(String s) {
        try (FileOutputStream o = new FileOutputStream(statsFile)) {
            o.write(s.getBytes("UTF-8"));
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ program wrapper
    static final class Prog {
        int id;
        final Map<String, Integer> loc = new HashMap<>();

        void compute(FlowRenderer r, String cs) throws IOException {
            int p = glCreateProgram();
            int s = compile(GLES31.GL_COMPUTE_SHADER, r.source(cs), cs);
            glAttachShader(p, s);
            glLinkProgram(p);
            glDeleteShader(s);
            finish(p, cs);
        }

        void link(FlowRenderer r, String vs, String fs) throws IOException {
            int p = glCreateProgram();
            int v = compile(GL_VERTEX_SHADER, r.source(vs), vs);
            int f = compile(GL_FRAGMENT_SHADER, r.source(fs), fs);
            glAttachShader(p, v);
            glAttachShader(p, f);
            glLinkProgram(p);
            glDeleteShader(v);
            glDeleteShader(f);
            finish(p, vs + "+" + fs);
        }

        void finish(int p, String what) {
            int[] ok = new int[1];
            glGetProgramiv(p, GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = glGetProgramInfoLog(p);
                glDeleteProgram(p);
                throw new IllegalStateException("link " + what + ": " + log);
            }
            id = p;
            loc.clear();
            int[] n = new int[1], size = new int[1], type = new int[1];
            glGetProgramiv(p, GL_ACTIVE_UNIFORMS, n, 0);
            for (int i = 0; i < n[0]; i++) {
                String name = glGetActiveUniform(p, i, size, 0, type, 0);
                if (name.endsWith("[0]")) name = name.substring(0, name.length() - 3);
                loc.put(name, glGetUniformLocation(p, name));
            }
        }

        static int compile(int kind, String body, String name) {
            int s = glCreateShader(kind);
            glShaderSource(s, HEADER + body);
            glCompileShader(s);
            int[] ok = new int[1];
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = glGetShaderInfoLog(s);
                glDeleteShader(s);
                throw new IllegalStateException(name + ": " + log);
            }
            return s;
        }

        void release() {
            if (id != 0) glDeleteProgram(id);
            id = 0;
            loc.clear();
        }

        int at(String n) { Integer l = loc.get(n); return l == null ? -1 : l; }
        void set(String n, float... v) {
            int l = at(n);
            if (l < 0) return;
            switch (v.length) {
                case 1: glUniform1f(l, v[0]); break;
                case 2: glUniform2f(l, v[0], v[1]); break;
                case 3: glUniform3f(l, v[0], v[1], v[2]); break;
                default: glUniform4f(l, v[0], v[1], v[2], v[3]); break;
            }
        }
        void seti(String n, int v) { int l = at(n); if (l >= 0) glUniform1i(l, v); }
        void setu(String n, int v) { int l = at(n); if (l >= 0) glUniform1ui(l, v); }
        void setMat3(String n, float[] m) { int l = at(n); if (l >= 0) glUniformMatrix3fv(l, 1, false, m, 0); }
    }
}
