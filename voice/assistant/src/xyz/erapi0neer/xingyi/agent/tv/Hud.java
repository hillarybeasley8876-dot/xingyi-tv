package xyz.erapi0neer.xingyi.agent.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import xyz.erapi0neer.xingyi.agent.Dispatch;
import xyz.erapi0neer.xingyi.agent.PanelRules;

/** The floating progress panel over whatever app is in front: what was understood, the plan,
 * the step being worked on and the outcome. Monochrome glass; never takes focus or input. */
public final class Hud {
    /** Immutable snapshot of what to draw. */
    public static final class State {
        public final String mode;    // running | confirm | ask | reading | done | stopped | failed | hint
        public final String goal, action, reply;
        public final List<String[]> steps;   // {text, status}
        /** The exchanges just before this one, oldest first: {what the user said, what 星仪 answered}. */
        public final List<String[]> before;
        /** The question waiting behind this task (Dispatch.Route.QUEUE), or "". */
        public final String next;
        /** 「2/4」 while a long answer is read page by page, else "". */
        public final String page;
        /** The data answer drawn as a card (weather, stocks, a list), or null for none. */
        public final JSONObject card;
        /** What 星仪 has just done, seen or is trying, oldest first, at most 4: {what, status}, where status is
         * {@code live} for the one running now and {@code ok} / {@code failed} / {@code denied} for the rest. */
        public final List<String[]> trace;
        public State(String mode, String goal, List<String[]> steps, String action, String reply) {
            this(mode, goal, steps, action, reply, null, "", "");
        }
        public State(String mode, String goal, List<String[]> steps, String action, String reply, List<String[]> before,
                     String next) {
            this(mode, goal, steps, action, reply, before, next, "");
        }
        public State(String mode, String goal, List<String[]> steps, String action, String reply, List<String[]> before,
                     String next, String page) {
            this(mode, goal, steps, action, reply, before, next, page, null);
        }
        public State(String mode, String goal, List<String[]> steps, String action, String reply, List<String[]> before,
                     String next, String page, JSONObject card) {
            this(mode, goal, steps, action, reply, before, next, page, card, null);
        }
        public State(String mode, String goal, List<String[]> steps, String action, String reply, List<String[]> before,
                     String next, String page, JSONObject card, List<String[]> trace) {
            this.page = page == null ? "" : page;
            this.mode = mode; this.goal = goal == null ? "" : goal; this.action = action == null ? "" : action;
            this.reply = reply == null ? "" : reply; this.steps = steps == null ? new ArrayList<String[]>() : steps;
            this.before = before == null ? new ArrayList<String[]>() : before;
            this.next = next == null ? "" : next;
            this.card = card;
            this.trace = trace == null ? new ArrayList<String[]>() : trace;
        }
        boolean finished() { return "done".equals(mode) || "stopped".equals(mode) || "failed".equals(mode); }
        /** A hint: the quiet card nobody asked for, its lines in {@code reply}, nothing else. */
        boolean hint() { return "hint".equals(mode); }
    }

    private final AccessibilityService service;
    private final WindowManager wm;
    private Panel panel;
    /** What is on screen (or on its way there), so a hint knows whether it may take the panel. */
    private volatile State current;
    private final Runnable hide = this::remove;

    Hud(AccessibilityService service) {
        this.service = service;
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Thread-safe. A finished state stays up long enough to read, then fades. Always wins over a hint. */
    public void show(State state) {
        current = state;
        Eyes eyes = Eyes.get();
        Runnable r = () -> {
            if (panel == null) {
                panel = new Panel(service);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                // Software rendering on purpose: with FLAG_HARDWARE_ACCELERATED the RenderThread aborted with
                // "Failed to set damage region ... EGL_BAD_ACCESS" while another app's window was torn down
                // (2026-11-04, MiTV settings crashing), taking the whole agent process with it.
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = dp(36); lp.y = dp(36);
                try { wm.addView(panel, lp); } catch (RuntimeException e) {
                    panel = null;
                    if (current == state) current = null; // nothing is up, so the next hint may try again
                    return;
                }
            }
            panel.removeCallbacks(hide);
            panel.set(state);
            long stay = stay(state);
            if (stay > 0) panel.postDelayed(hide, stay);
        };
        if (eyes != null) eyes.onMain(r); else service.getMainExecutor().execute(r);
    }

    /** The quiet card: shown only when nothing of the user's own is on screen. A task's panel (running, waiting for
     * a confirmation or an answer) is never covered by a hint. @return whether it went up. */
    public boolean showHint(State hint) {
        State up = current;
        if (up != null && !up.finished() && !up.hint()) return false;
        show(hint);
        return true;
    }

    /** How long a state stays up on its own: a finished one long enough to read it, a hint a glance, a question until
     * its answer window closes, a consent a little past the 60 s the engine waits for it. 0 = until the next state
     * replaces it. */
    private static long stay(State s) {
        if (s.hint()) return 8000;
        // long enough for XiaoAi to read it out (about 5 characters a second), now that answers are read whole;
        // a card on top of the words is looked at a little longer
        if (s.finished()) return Math.min(75_000, 6000 + s.reply.length() * 200L + (s.card == null ? 0 : 4000));
        if ("ask".equals(s.mode)) return Dispatch.ANSWER_WINDOW_MS;
        if ("confirm".equals(s.mode)) return 65_000;
        return 0;
    }

    /** Whether the panel's window is on the screen right now (added and not yet taken down). */
    public boolean up() { return panel != null && current != null; }

    /**
     * A test card for self_check: it goes up like any other, and the answer is whether its window was really added
     * (2026-11-12 01:19 on the TV: 「自检全是问题啊」 — self_check said 「读屏、按键、模型线路都没问题」 while the panel was
     * not coming up at all, because it never looked at the panel). Returns within a second either way.
     */
    public boolean probe() {
        show(new State("done", "", null, "", "自检：这块面板能显示出来"));
        long end = System.currentTimeMillis() + 1200;
        while (!up() && System.currentTimeMillis() < end) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return up();
    }

    public void hideSoon() {
        Eyes eyes = Eyes.get();
        if (eyes != null) eyes.onMain(() -> { if (panel != null) { panel.removeCallbacks(hide); panel.postDelayed(hide, 1200); } });
    }

    /** Off the screen at once: what it showed was dropped (「停」 while 星仪 was waiting for an answer). */
    public void hide() {
        Eyes eyes = Eyes.get();
        Runnable r = () -> { if (panel != null) { panel.removeCallbacks(hide); remove(); } };
        if (eyes != null) eyes.onMain(r); else service.getMainExecutor().execute(r);
    }

    void dispose() {
        Eyes eyes = Eyes.get();
        Runnable r = this::remove;
        if (eyes != null) eyes.onMain(r); else r.run();
    }

    private void remove() {
        current = null;
        if (panel == null) return;
        Panel p = panel;
        panel = null;
        p.freeze(); // no shimmer, spinner or pulsing dot while it fades out
        p.animate().alpha(0f).setDuration(420).withEndAction(() -> {
            try { wm.removeView(p); } catch (RuntimeException ignored) { }
        }).start();
    }

    private int dp(float v) { return Math.round(v * unit(service)); }

    /** A state drawn off-screen, with where its blocks ended up, so a design check can read the layout without
     * looking at the pixels: {@link #maxY} is the bottom of the last block and {@link #height} what the panel
     * measured for it, so a {@code maxY} at or past the height means the blocks ran into each other. */
    static final class Render {
        public final android.graphics.Bitmap bitmap;
        public final float maxY;
        public final int height;
        Render(android.graphics.Bitmap bitmap, float maxY, int height) {
            this.bitmap = bitmap; this.maxY = maxY; this.height = height;
        }
    }

    /** Draws a state off-screen at its real size, for design checks without lighting up the TV. */
    static Render render(Context c, State s) {
        Panel p = new Panel(c);
        p.set(s);
        p.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        p.layout(0, 0, p.getMeasuredWidth(), p.getMeasuredHeight());
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(p.getMeasuredWidth(), p.getMeasuredHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        p.draw(new Canvas(b));
        return new Render(b, p.maxY(), p.getMeasuredHeight());
    }

    /** Layout unit: 1.5 px per unit on a 1920-wide screen, whatever density the TV reports. */
    static float unit(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.max(m.widthPixels, m.heightPixels) / 1920f * 1.5f;
    }

    /** Lays a line out so a lonely last line is avoided: 「…并播放他最新的视／频」 wrapped one character onto its own
     * line (2026-11-08 render). Narrows the column in 8-unit steps, down to 70 % of it, and keeps the first width at
     * which the line count is unchanged and the last line carries at least 4 characters; otherwise the first layout
     * stands. Ellipsizing happens only at {@code maxLines}. */
    static StaticLayout balanced(CharSequence text, TextPaint paint, int width, int maxLines, float spacingMult) {
        StaticLayout first = lines(text, paint, width, maxLines, spacingMult);
        int lines = first.getLineCount();
        if (lines < 2 || tail(text, first, lines) >= 4) return first;
        int step = Math.max(1, Math.round(8 * unitPx));
        for (int w = width - step; w >= width * 0.7f; w -= step) {
            StaticLayout l = lines(text, paint, w, maxLines, spacingMult);
            // a narrower column that adds a line is worse than the lonely character it was meant to fix
            if (l.getLineCount() == lines && tail(text, l, lines) >= 4) return l;
        }
        return first;
    }

    /** How many characters sit on the last line, trailing spaces not counted. */
    private static int tail(CharSequence text, StaticLayout l, int lines) {
        int start = l.getLineStart(lines - 1), end = l.getLineEnd(lines - 1);
        while (end > start && text.charAt(end - 1) == ' ') end--;
        return end - start;
    }

    /** One layout at one width: high-quality breaking, ellipsized only when it runs past {@code lines}. */
    private static StaticLayout lines(CharSequence text, TextPaint paint, int width, int lines, float spacingMult) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(10, width))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, spacingMult)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    /** The layout unit, kept for {@link #balanced}: it is static and needs the 8-unit step it narrows by. One TV,
     * one density, and only the panel that lays text out ever writes it. */
    private static volatile float unitPx = 1.5f;

    /** Custom-drawn panel. Sizes are in px for a 1920×1080 TV surface, scaled by density. */
    private static final class Panel extends View {
        /** A hint is three lines at most, however many the card had. */
        private static final int MAX_HINT_LINES = 3;
        /** The box: 384 units wide, 28 of padding, a 22-unit corner. */
        private static final float BOX_W = 384, PAD = 28, RADIUS = 22;
        private static final float TOP = 22, HEADER = 18, AFTER_HEADER = 18;
        /** The step marker column, and the chain's own narrower one. */
        private static final float STEP_COL = 26, CHAIN_COL = 14;
        /** The mark: the ring's radius, and where its dot rests on it — 45° up-right, so the two never read as
         * the power symbol a dot at twelve o'clock makes (2026-11-08 render). */
        private static final float MARK_R = 5, MARK_REST = (float) Math.PI / 4;
        /** Every gap the one layout pass puts between two blocks, and the height of the fixed-size blocks. Nothing
         * else adds a gap: onDraw draws the y this pass worked out. */
        private static final float GAP_EXCHANGE = 12, GAP_SAID_ANSWER = 4, GAP_THREAD_RULE = 14,
                GAP_AFTER_THREAD_RULE = 16, GAP_STEPS = 14, GAP_STEP = 12, GAP_CHAIN = 10, GAP_CHAIN_ROW = 7,
                GAP_CHAIN_STEP = 14, GAP_CARD = 16, GAP_REPLY = 16, GAP_REPLY_CARD = 10, GAP_AFTER_REPLY_RULE = 14,
                GAP_CHIPS = 14, GAP_HINT = 12, GAP_FOOTER = 16, CHIP_H = 24, MIC_H = 20, FOOT_H = 19, BOTTOM = 26;
        /** What a block is; the index is which item of that kind (which step, which chain row, which exchange). */
        private static final int B_HEADER = 0, B_SAID = 1, B_ANSWER = 2, B_RULE = 3, B_GOAL = 4, B_STEP = 5,
                B_CHAIN = 6, B_CARD = 7, B_REPLY = 8, B_CHIPS = 9, B_HINT = 10, B_MIC = 11, B_NEXT = 12;
        /** The two shapes a confirmation's spoken instruction takes; on screen they become chips instead. */
        private static final String[] YES_NO = {"可以就说“好的”，不做就说“算了”。", "可以就说「好的」，不做就说「算了」。"};

        private final float d;
        private final int width, pad, inner;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                hair = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG),
                topLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint mark = new TextPaint(Paint.ANTI_ALIAS_FLAG), status = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                page = new TextPaint(Paint.ANTI_ALIAS_FLAG), goal = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                step = new TextPaint(Paint.ANTI_ALIAS_FLAG), num = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                chain = new TextPaint(Paint.ANTI_ALIAS_FLAG), body = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                caption = new TextPaint(Paint.ANTI_ALIAS_FLAG), said = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                answer = new TextPaint(Paint.ANTI_ALIAS_FLAG), foot = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                footKey = new TextPaint(Paint.ANTI_ALIAS_FLAG), hint = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private State state = new State("running", "", null, "", "");
        private boolean frozen;
        private StaticLayout goalLayout, replyLayout;
        /** The data answer under the steps, or null when this state carries none. */
        private HudCards cards;
        private final List<StaticLayout> stepLayouts = new ArrayList<>();
        private final List<StaticLayout> hintLayouts = new ArrayList<>();
        /** The thinking chain: what 星仪 did, saw or is trying, one line each. */
        private final List<StaticLayout> chainLayouts = new ArrayList<>();
        private final List<String> chainStatus = new ArrayList<>();
        /** The thread above the task: what was said (one line) and what 星仪 answered (two at most), per exchange. */
        private final List<StaticLayout> saidLayouts = new ArrayList<>(), answerLayouts = new ArrayList<>();
        /** A confirmation whose spoken instruction became two chips. */
        private boolean chips;
        private float chipYesW, chipNoW;
        /** Small lines under everything: how to answer a question, and the question waiting its turn. */
        private boolean mic;
        private String next = "";
        private final long born = SystemClock.uptimeMillis();
        private final RectF box = new RectF();

        // The one layout pass: every block with the y it sits at, top to bottom. onMeasure takes the height from
        // it, onDraw draws at these y, and neither adds a gap of its own (2026-11-08 render: onMeasure and onDraw
        // disagreed about where the chain under a step ended, so the steps below it were drawn over).
        private final List<Block> blocks = new ArrayList<>();
        /** How tall the panel is: the last block's bottom plus the bottom padding. */
        private float total;
        /** What the moving parts are drawn at, from the same pass: the mark, the running step's arc and the live
         * chain row, so their invalidation rectangles never have to work a position out again. */
        private float markCx, markCy, curCx, curCy, liveCx, liveCy;
        /** Which step is the current one; -1 when none is. */
        private int curStep = -1;
        /** The hairlines' own extents: the thread's down the inner edge, the axes' down the marker columns. */
        private float threadTop, threadBottom, axisTop, axisBottom, chainAxisTop, chainAxisBottom;
        /** Where the chain's rows start, and where the step text starts. */
        private float chainX;

        /** One laid-out row: what it is, which item of that kind, where its top sits and how tall it is. */
        private static final class Block {
            final int kind, index;
            final float y, h;
            Block(int kind, int index, float y, float h) {
                this.kind = kind; this.index = index; this.y = y; this.h = h;
            }
            float bottom() { return y + h; }
        }

        Panel(Context c) {
            super(c);
            d = unit(c);
            unitPx = d;
            width = Math.round(BOX_W * d);
            pad = Math.round(PAD * d);
            inner = width - 2 * pad;
            Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, d * 0.75f));
            edge.setColor(Color.argb(22, 255, 255, 255));
            hair.setStyle(Paint.Style.STROKE);
            hair.setStrokeCap(Paint.Cap.ROUND);
            topLight.setStyle(Paint.Style.STROKE);
            topLight.setStrokeWidth(Math.max(1f, d));
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            bg.setColor(Color.argb(244, 12, 13, 16));   // until onSizeChanged gives the panel its gradient
            shimmer = new LinearGradient(-80 * d, 0, 80 * d, 0,
                    new int[]{Color.argb(0, 255, 255, 255), Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    null, Shader.TileMode.CLAMP);
            text(mark, medium, 12.5f, 215).setLetterSpacing(0.32f);
            text(status, regular, 11.5f, 140);
            text(page, regular, 11.5f, 105);
            text(goal, medium, 18, 245);
            text(step, regular, 14, 245);
            // the step numbers line up on the axis: tabular digits, so 01 and 02 are the same width
            text(num, light, 12, 245).setFontFeatureSettings("tnum");
            text(chain, regular, 12.5f, 225);
            text(body, regular, 15.5f, 232);
            // With a card the words repeat what the card already shows: on screen they are a caption under it.
            text(caption, regular, 13, 175);
            text(said, regular, 12.5f, 120);
            text(answer, regular, 12.5f, 168);
            text(foot, regular, 12, 140);
            text(footKey, regular, 12, 95);
            text(hint, regular, 14.5f, 222);
            text(chip, regular, 12.5f, 220);
            setAlpha(0f);
            animate().alpha(1f).setDuration(260).start();
        }

        private TextPaint text(TextPaint p, Typeface tf, float size, int alpha) {
            p.setTypeface(tf);
            p.setTextSize(size * d);
            p.setColor(Color.argb(alpha, 255, 255, 255));
            return p;
        }

        void set(State s) {
            state = s;
            frozen = false;
            chips = false;
            String replyText = "";
            if (!s.hint()) {
                boolean spoken = s.finished() || "ask".equals(s.mode) || "confirm".equals(s.mode)
                        || "reading".equals(s.mode);
                replyText = spoken ? s.reply : "";
                if ("confirm".equals(s.mode)) replyText = withoutYesNo(replyText);
            }
            // The data answer is drawn as a card, but only once the answer is there to be read: a card while the
            // work is still running would sit under steps that are about to change. It is also only drawn while
            // the words under it are its caption (PanelRules): a card an earlier step left on the ledger has
            // nothing to do with this answer, and it is left out altogether rather than cutting the answer to the
            // two caption lines under it (2026-11-09 review).
            cards = s.finished() || "reading".equals(s.mode) ? HudCards.of(s.card, d, inner) : null;
            if (cards != null && !PanelRules.replyIsCaption(replyText, s.card)) cards = null;
            saidLayouts.clear();
            answerLayouts.clear();
            if (!s.hint()) for (String[] e : s.before) {
                saidLayouts.add(lines("“" + e[0] + "”", said, inner - Math.round(12 * d), 1, 1.2f));
                answerLayouts.add(balanced(e[1], answer, inner - Math.round(12 * d), 2, 1.25f));
            }
            mic = "ask".equals(s.mode);   // XiaoAi only listens after its wake word (2026-11-05: 「我没法继续输入了」)
            next = s.next;
            goalLayout = s.goal.isEmpty() ? null : balanced(s.goal, goal, inner, 2, 1.25f);
            stepLayouts.clear();
            int stepW = inner - Math.round(STEP_COL * d);
            for (String[] st : s.steps) stepLayouts.add(balanced(st[0], step, stepW, 2, 1.25f));
            chain(s);
            hintLayouts.clear();
            if (s.hint()) {
                // At most two lines each: the habit line (「这个时候你常听…，说「放首…的歌」就行」) is cut at the panel's
                // width on one line (2026-11-05 render), and the way to ask for it again is the part that matters.
                for (String l : s.reply.split("\n")) {
                    if (hintLayouts.size() >= MAX_HINT_LINES) break;
                    if (!l.trim().isEmpty()) hintLayouts.add(lines(l, hint, inner, 2, 1.3f));
                }
                replyLayout = null;
            } else {
                // with a card the numbers are already on screen: two quiet lines of words under it are enough
                replyLayout = replyText.isEmpty() ? null
                        : balanced(replyText, cards == null ? body : caption, inner, cards == null ? 10 : 2,
                                cards == null ? 1.38f : 1.25f);
            }
            if (chips) {
                chipYesW = chip.measureText("说“好的”") + 24 * d;
                chipNoW = chip.measureText("说“算了”") + 24 * d;
            }
            // the one layout pass runs from here (onMeasure), placing every block and the panel's height; onDraw
            // only reads the y it wrote, so the two can no longer disagree about where a block ends
            requestLayout();
            invalidate();
        }

        /** The thinking chain: what 星仪 did, saw or is trying. It belongs to the step it runs under, so it is
         * indented to that step's text; a card takes the panel's attention instead and hides it, and a finished
         * panel keeps only the last two rows — the answer is what matters then. */
        private void chain(State s) {
            chainLayouts.clear();
            chainStatus.clear();
            boolean mode = "running".equals(s.mode) || "confirm".equals(s.mode) || "ask".equals(s.mode) || s.finished();
            if (!mode || cards != null) return;
            List<String[]> rows = s.trace;
            // an old caller that only set the action line still shows what it is doing
            if (rows.isEmpty() && !s.action.isEmpty() && "running".equals(s.mode))
                rows = java.util.Collections.singletonList(new String[]{s.action, "live"});
            int from = s.finished() ? Math.max(0, rows.size() - 2) : Math.max(0, rows.size() - 4);
            int w = inner - Math.round((s.steps.isEmpty() ? CHAIN_COL : STEP_COL + CHAIN_COL) * d);
            for (int i = from; i < rows.size(); i++) {
                chainLayouts.add(lines(rows.get(i)[0], chain, w, 1, 1.2f));
                chainStatus.add(rows.get(i)[1]);
            }
        }

        /** Drops the spoken instruction a confirmation ends with: on screen two chips say it better, and XiaoAi
         * still reads the whole sentence. @return the words that stay. */
        private String withoutYesNo(String reply) {
            for (String tail : YES_NO) {
                if (!reply.endsWith(tail)) continue;
                chips = true;
                return reply.substring(0, reply.length() - tail.length()).trim();
            }
            return reply;
        }

        /** On its way out: stop everything that moves, so a panel whose window closed costs nothing. */
        void freeze() { frozen = true; invalidate(); }

        /** The one layout pass: every block with the y it sits at, top to bottom, and the panel's height from the
         * last of them. The thinking chain is placed *inside* the step loop, under the step it belongs to, so the
         * steps below that step move down instead of being drawn over (2026-11-08 render). */
        @Override protected void onMeasure(int w, int h) {
            layout();
            setMeasuredDimension(width, Math.round(total));
        }

        private void layout() {
            blocks.clear();
            chainX = pad + (stepLayouts.isEmpty() ? 0 : Math.round(STEP_COL * d));
            markCx = pad + MARK_R * d;
            markCy = (TOP + HEADER / 2) * d;
            curStep = current();
            curCy = -1;
            float y = TOP * d;
            add(B_HEADER, 0, y, HEADER * d);
            y += (HEADER + AFTER_HEADER) * d;
            total = rest(plan(thread(y))) + BOTTOM * d;
        }

        private void add(int kind, int index, float y, float h) { blocks.add(new Block(kind, index, y, h)); }

        /** The bottom of the last block: what a design check compares with the measured height. */
        float maxY() { return blocks.isEmpty() ? 0 : blocks.get(blocks.size() - 1).bottom(); }

        /** The exchanges above this one: what was said and what 星仪 answered, then the rule that closes them. */
        private float thread(float y) {
            if (saidLayouts.isEmpty()) return y;
            threadTop = y;
            for (int i = 0; i < saidLayouts.size(); i++) {
                if (i > 0) y += GAP_EXCHANGE * d;
                add(B_SAID, i, y, saidLayouts.get(i).getHeight());
                y += saidLayouts.get(i).getHeight() + GAP_SAID_ANSWER * d;
                add(B_ANSWER, i, y, answerLayouts.get(i).getHeight());
                y += answerLayouts.get(i).getHeight();
            }
            threadBottom = y;
            y += GAP_THREAD_RULE * d;
            add(B_RULE, 0, y, 0);
            return y + GAP_AFTER_THREAD_RULE * d;
        }

        /** The goal, then the plan: a step per row with the thinking chain set under the one it belongs to. */
        private float plan(float y) {
            if (goalLayout != null) {
                add(B_GOAL, 0, y, goalLayout.getHeight());
                y += goalLayout.getHeight();
            }
            int under = chainLayouts.isEmpty() ? -1 : chainAt();
            boolean placed = false;
            if (!stepLayouts.isEmpty()) {
                y += GAP_STEPS * d;
                for (int i = 0; i < stepLayouts.size(); i++) {
                    StaticLayout l = stepLayouts.get(i);
                    add(B_STEP, i, y, l.getHeight());
                    if (i == 0) axisTop = centre(l, y);
                    axisBottom = centre(l, y);
                    if (i == curStep) { curCx = pad + STEP_COL / 2 * d; curCy = centre(l, y); }
                    y += l.getHeight();
                    if (i == under) {
                        y = chainRows(y + GAP_CHAIN * d);
                        placed = true;
                        if (i < stepLayouts.size() - 1) y += GAP_CHAIN_STEP * d;
                    } else if (i < stepLayouts.size() - 1) {
                        y += GAP_STEP * d;
                    }
                }
            }
            // nothing planned yet: the chain is all there is, and it sits under the goal on its own
            if (!placed && !chainLayouts.isEmpty()) y = chainRows(y + GAP_STEPS * d);
            return y;
        }

        /** The chain rows themselves, indented under the step they belong to. */
        private float chainRows(float y) {
            for (int i = 0; i < chainLayouts.size(); i++) {
                StaticLayout l = chainLayouts.get(i);
                add(B_CHAIN, i, y, l.getHeight());
                if (i == 0) chainAxisTop = centre(l, y);
                chainAxisBottom = centre(l, y);
                if ("live".equals(chainStatus.get(i))) {
                    liveCx = chainX + CHAIN_COL / 2 * d;
                    liveCy = centre(l, y);
                }
                y += l.getHeight();
                if (i < chainLayouts.size() - 1) y += GAP_CHAIN_ROW * d;
            }
            return y;
        }

        /** Everything under the plan: the card, the reply with its rule, the chips, the hint lines, the footer. */
        private float rest(float y) {
            if (cards != null) {
                y += GAP_CARD * d;
                add(B_CARD, 0, y, cards.height());
                y += cards.height();
            }
            if (replyLayout != null) {
                y += (cards == null ? GAP_REPLY : GAP_REPLY_CARD) * d;
                add(B_RULE, 0, y, 0);
                y += GAP_AFTER_REPLY_RULE * d;
                add(B_REPLY, 0, y, replyLayout.getHeight());
                y += replyLayout.getHeight();
            }
            if (chips) {
                y += GAP_CHIPS * d;
                add(B_CHIPS, 0, y, CHIP_H * d);
                y += CHIP_H * d;
            }
            for (int i = 0; i < hintLayouts.size(); i++) {
                // a hint is the header and its lines, nothing else, so the first one starts where the header left off
                if (i > 0) y += GAP_HINT * d;
                add(B_HINT, i, y, hintLayouts.get(i).getHeight());
                y += hintLayouts.get(i).getHeight();
            }
            if (mic) {
                y += GAP_FOOTER * d;
                add(B_MIC, 0, y, MIC_H * d);
                y += MIC_H * d;
            }
            if (!next.isEmpty()) {
                if (!mic) y += GAP_FOOTER * d;
                add(B_NEXT, 0, y, FOOT_H * d);
                y += FOOT_H * d;
            }
            return y;
        }

        /** Which step the chain belongs under: the one running; once the task is over, the last step that was
         * actually attempted (a skipped one never ran, so nothing happened under it). */
        private int chainAt() {
            if (stepLayouts.isEmpty()) return -1;
            int i = curStep;
            if (i < 0) for (int j = state.steps.size() - 1; j >= 0; j--) {
                String st = state.steps.get(j)[1];
                if (!"skipped".equals(st) && !"pending".equals(st)) { i = j; break; }
            }
            return i < 0 ? stepLayouts.size() - 1 : i;
        }

        /** The step drawn as current: the one marked in_progress, else the first pending while work is live. A
         * panel that has stopped, failed or finished has none — the work is over, so nothing is drawn running. */
        private int current() {
            int i = drawn("in_progress");
            return i >= 0 ? i : drawn("pending");
        }

        /** The first step in that state that PanelRules still draws as running; -1 when there is none. */
        private int drawn(String status) {
            for (int i = 0; i < state.steps.size(); i++) {
                if (status.equals(state.steps.get(i)[1]) && PanelRules.stepDrawnRunning(live(), status)) return i;
            }
            return -1;
        }

        /** Whether anything on the panel still moves. */
        private boolean live() { return !state.finished() && !state.hint() && !frozen; }

        @Override protected void onDraw(Canvas c) {
            float r = RADIUS * d;
            box.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(box, r, r, bg);
            box.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
            c.drawRoundRect(box, r, r, edge);
            // light catching the top edge, between the two corners
            c.drawLine(r, edge.getStrokeWidth() / 2, getWidth() - r, edge.getStrokeWidth() / 2, topLight);
            long t = SystemClock.uptimeMillis() - born;
            boolean live = live();
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                switch (b.kind) {
                    case B_HEADER: header(c, t, live); break;
                    case B_SAID: said(c, b); break;
                    case B_ANSWER: draw(c, answerLayouts.get(b.index), pad + 12 * d, b.y); break;
                    case B_RULE: rule(c, b); break;
                    case B_GOAL: draw(c, goalLayout, pad, b.y); break;
                    case B_STEP: step(c, t, live, b); break;
                    case B_CHAIN: chainRow(c, t, live, b); break;
                    case B_CARD: cards.draw(c, pad, b.y); break;
                    case B_REPLY: draw(c, replyLayout, pad, b.y); break;
                    case B_CHIPS: drawChips(c, b); break;
                    case B_HINT: hintLine(c, b); break;
                    case B_MIC: micLine(c, b); break;
                    case B_NEXT: nextLine(c, b); break;
                    default: break;
                }
            }
            if (live) drawShimmer(c, t, r);
        }

        private void draw(Canvas c, StaticLayout l, float x, float y) {
            c.save(); c.translate(x, y); l.draw(c); c.restore();
        }

        /** The mark, the wordmark, and on the right the status word with the page after it. The mark is a fine
         * ring with a dot resting on it at 45° up-right: at twelve o'clock the two read as a power button. */
        private void header(Canvas c, long t, boolean live) {
            boolean off = "failed".equals(state.mode) || "stopped".equals(state.mode);
            glyph.setShader(null);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(Math.max(1f, 0.9f * d));
            glyph.setColor(Color.argb(off ? 110 : 150, 255, 255, 255));
            c.drawCircle(markCx, markCy, MARK_R * d, glyph);
            if (!off) {
                float a = orbit(t, live);
                glyph.setStyle(Paint.Style.FILL);
                glyph.setColor(Color.argb(dotAlpha(t, live), 255, 255, 255));
                c.drawCircle(markCx + (float) Math.sin(a) * MARK_R * d, markCy - (float) Math.cos(a) * MARK_R * d,
                        2.2f * d, glyph);
            }
            c.drawText("星仪", pad + 19 * d, markCy + mark.getTextSize() * 0.36f, mark);
            float right = getWidth() - pad;
            if (!state.page.isEmpty()) {
                float pw = page.measureText(state.page);
                c.drawText(state.page, right - pw, markCy + page.getTextSize() * 0.36f, page);
                right -= pw + 8 * d;
            }
            String word = label(state.mode);
            c.drawText(word, right - status.measureText(word), markCy + status.getTextSize() * 0.36f, status);
            // only the modes whose dot moves are worth waking the panel for
            if (live && markMoves()) postInvalidateDelayed(80, Math.round(markCx - 8 * d), Math.round(markCy - 8 * d),
                    Math.round(markCx + 8 * d), Math.round(markCy + 8 * d));
        }

        /** Whether the mark's dot is moving: round the ring while working, pulsing while it waits for the user. */
        private boolean markMoves() {
            return "running".equals(state.mode) || "confirm".equals(state.mode) || "ask".equals(state.mode);
        }

        /** Where the dot sits on the ring: from its resting place at 45° once round every 2.4 s while work runs. */
        private float orbit(long t, boolean live) {
            return live && "running".equals(state.mode) ? MARK_REST + (t % 2400) / 2400f * 2 * (float) Math.PI
                    : MARK_REST;
        }

        /** The dot pulses between 120 and 255 while 星仪 waits for the user, and is still otherwise. */
        private int dotAlpha(long t, boolean live) {
            boolean waiting = "confirm".equals(state.mode) || "ask".equals(state.mode);
            if (!live || !waiting) return 255;
            return 120 + Math.round(135 * (0.5f - 0.5f * (float) Math.cos(t * 2 * Math.PI / 1400.0)));
        }

        /** What was said in one exchange of the thread; the first one draws the hairline down the inner left edge. */
        private void said(Canvas c, Block b) {
            if (b.index == 0) {
                hair.setShader(null);
                hair.setStrokeWidth(Math.max(1f, d));
                hair.setColor(Color.argb(40, 255, 255, 255));
                c.drawLine(pad, threadTop, pad, threadBottom, hair);
            }
            draw(c, saidLayouts.get(b.index), pad + 12 * d, b.y);
        }

        /** The hairline that closes the thread, and the one that opens the reply: the same fine rule. */
        private void rule(Canvas c, Block b) {
            hair.setShader(null);
            hair.setStrokeWidth(Math.max(1f, d * 0.75f));
            hair.setColor(Color.argb(22, 255, 255, 255));
            c.drawLine(pad, b.y, pad + inner, b.y, hair);
        }

        /** One step of the plan: its marker on the axis and its text beside it. The first one draws the axis. */
        private void step(Canvas c, long t, boolean live, Block b) {
            int i = b.index;
            float gx = pad + STEP_COL / 2 * d, cy = centre(stepLayouts.get(i), b.y);
            if (i == 0) stepAxis(c, gx);
            String st = state.steps.get(i)[1];
            // frozen mid-fade: the layout still says which step was current, so the marker asks again whether
            // anything is running before it draws the arc
            boolean now = i == curStep && PanelRules.stepDrawnRunning(live, st);
            marker(c, st, now, gx, cy, t, i);
            step.setColor(Color.argb(stepAlpha(st, now), 255, 255, 255));
            draw(c, stepLayouts.get(i), pad + STEP_COL * d, b.y);
            if (now && live) postInvalidateDelayed(80, Math.round(curCx - 11 * d), Math.round(curCy - 11 * d),
                    Math.round(curCx + 11 * d), Math.round(curCy + 11 * d));
        }

        /** The fine axis down the marker column, from the first step's centre to the last, broken around the
         * running step so the spinner arc round its number has room and never touches the digits. */
        private void stepAxis(Canvas c, float gx) {
            hair.setShader(null);
            hair.setStrokeWidth(Math.max(1f, d));
            hair.setColor(Color.argb(30, 255, 255, 255));
            float gap = curCy < 0 ? -1 : 11 * d;
            if (gap < 0) {
                c.drawLine(gx, axisTop, gx, axisBottom, hair);
                return;
            }
            if (curCy - gap > axisTop) c.drawLine(gx, axisTop, gx, curCy - gap, hair);
            if (curCy + gap < axisBottom) c.drawLine(gx, curCy + gap, gx, axisBottom, hair);
        }

        /** The step marker on the axis: a check, the step's own number, a cross or a dash. The running step's
         * number is plain — no disc under it — with the arc round it at a radius the digits never reach. */
        private void marker(Canvas c, String st, boolean now, float cx, float cy, long t, int i) {
            glyph.setShader(null);
            glyph.setStyle(Paint.Style.STROKE);
            if ("done".equals(st)) {
                glyph.setStrokeWidth(1.3f * d);
                glyph.setColor(Color.argb(165, 255, 255, 255));
                c.drawLine(cx - 3.5f * d, cy, cx - 1 * d, cy + 2.5f * d, glyph);
                c.drawLine(cx - 1 * d, cy + 2.5f * d, cx + 3.5f * d, cy - 2.5f * d, glyph);
            } else if ("failed".equals(st)) {
                cross(c, cx, cy, 3 * d, 1.2f * d, 200);
            } else if ("skipped".equals(st)) {
                glyph.setStrokeWidth(1.2f * d);
                glyph.setColor(Color.argb(110, 255, 255, 255));
                c.drawLine(cx - 3 * d, cy, cx + 3 * d, cy, glyph);
            } else {
                String n = i < 9 ? "0" + (i + 1) : String.valueOf(i + 1);
                num.setColor(Color.argb(now ? 245 : 80, 255, 255, 255));
                c.drawText(n, cx - num.measureText(n) / 2, cy - (num.ascent() + num.descent()) / 2, num);
                if (!now) return;
                glyph.setStrokeWidth(Math.max(1f, d));
                glyph.setColor(Color.argb(245, 255, 255, 255));
                float rad = 9.5f * d, start = (t % 1100) / 1100f * 360f;
                c.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, start, 110, false, glyph);
            }
        }

        private void cross(Canvas c, float cx, float cy, float half, float w, int alpha) {
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(w);
            glyph.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawLine(cx - half, cy - half, cx + half, cy + half, glyph);
            c.drawLine(cx + half, cy - half, cx - half, cy + half, glyph);
        }

        private static int stepAlpha(String st, boolean now) {
            if (now) return 245;
            if ("done".equals(st)) return 125;
            if ("failed".equals(st)) return 200;
            if ("skipped".equals(st)) return 90;
            return 100;
        }

        /** The optical centre of a layout's first line, in canvas y. */
        private float centre(StaticLayout l, float top) {
            return top + l.getLineBaseline(0) + (l.getLineAscent(0) + l.getLineDescent(0)) / 2f;
        }

        /** One row of the thinking chain under the step it belongs to: what 星仪 did, saw, or is trying this
         * second. The first row draws the chain's own axis, in its own narrower column. */
        private void chainRow(Canvas c, long t, boolean live, Block b) {
            int i = b.index, last = chainLayouts.size() - 1;
            float gx = chainX + CHAIN_COL / 2 * d, cy = centre(chainLayouts.get(i), b.y);
            if (i == 0) {
                hair.setShader(null);
                hair.setStrokeWidth(Math.max(1f, d));
                hair.setColor(Color.argb(30, 255, 255, 255));
                c.drawLine(gx, chainAxisTop, gx, chainAxisBottom, hair);
            }
            String st = chainStatus.get(i);
            glyph.setShader(null);
            if ("live".equals(st)) {
                glyph.setStyle(Paint.Style.STROKE);
                glyph.setStrokeWidth(Math.max(1f, d));
                glyph.setColor(Color.argb(225, 255, 255, 255));
                float rad = 3.5f * d, start = (t % 900) / 900f * 360f;
                c.drawArc(gx - rad, cy - rad, gx + rad, cy + rad, start, 120, false, glyph);
                if (live) postInvalidateDelayed(80, Math.round(liveCx - 6 * d), Math.round(liveCy - 6 * d),
                        Math.round(liveCx + 6 * d), Math.round(liveCy + 6 * d));
            } else if ("ok".equals(st)) {
                glyph.setStyle(Paint.Style.FILL);
                glyph.setColor(Color.argb(120, 255, 255, 255));
                c.drawCircle(gx, cy, 1.5f * d, glyph);
            } else {
                cross(c, gx, cy, 2.5f * d, Math.max(1f, d), 160);
            }
            chain.setColor(Color.argb(chainAlpha(st, i == last), 255, 255, 255));
            draw(c, chainLayouts.get(i), chainX + CHAIN_COL * d, b.y);
        }

        private static int chainAlpha(String st, boolean newest) {
            if ("live".equals(st)) return 225;
            if (!"ok".equals(st)) return 150;
            return newest ? 140 : 110;
        }

        /** The two ways a confirmation is answered, as quiet chips instead of the sentence XiaoAi reads out. */
        private void drawChips(Canvas c, Block b) {
            float h = CHIP_H * d, base = b.y + h / 2 + chip.getTextSize() * 0.36f;
            hair.setShader(null);
            hair.setStrokeWidth(Math.max(1f, d));
            hair.setColor(Color.argb(110, 255, 255, 255));
            box.set(pad, b.y, pad + chipYesW, b.y + h);
            c.drawRoundRect(box, 12 * d, 12 * d, hair);
            chip.setColor(Color.argb(220, 255, 255, 255));
            c.drawText("说“好的”", pad + 12 * d, base, chip);
            float x = pad + chipYesW + 10 * d;
            box.set(x, b.y, x + chipNoW, b.y + h);
            c.drawRoundRect(box, 12 * d, 12 * d, hair);
            chip.setColor(Color.argb(150, 255, 255, 255));
            c.drawText("说“算了”", x + 12 * d, base, chip);
        }

        /** One line of the quiet hint card: the first is the one that matters, the rest are quieter. */
        private void hintLine(Canvas c, Block b) {
            hint.setColor(Color.argb(b.index == 0 ? 240 : 222, 255, 255, 255));
            draw(c, hintLayouts.get(b.index), pad, b.y);
        }

        /** How to answer a question: a fine-line microphone and the words. */
        private void micLine(Canvas c, Block b) {
            microphone(c, pad + 7 * d, b.y + 8 * d);
            foot.setColor(Color.argb(140, 255, 255, 255));
            c.drawText("回答时先叫一声「小爱同学」", pad + 22 * d, b.y + 12 * d, foot);
        }

        /** The question waiting its turn behind this one. */
        private void nextLine(Canvas c, Block b) {
            footKey.setColor(Color.argb(95, 255, 255, 255));
            c.drawText("接下来", pad, b.y + 12 * d, footKey);
            float x = pad + footKey.measureText("接下来") + 8 * d;
            foot.setColor(Color.argb(155, 255, 255, 255));
            c.drawText(TextUtils.ellipsize(next, foot, pad + inner - x, TextUtils.TruncateAt.END).toString(),
                    x, b.y + 12 * d, foot);
        }

        /** A microphone in fine lines: a capsule, the arc that catches it, and a short stem. */
        private void microphone(Canvas c, float cx, float cy) {
            glyph.setShader(null);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(Math.max(1f, d));
            glyph.setColor(Color.argb(140, 255, 255, 255));
            box.set(cx - 2.5f * d, cy - 6 * d, cx + 2.5f * d, cy + 2 * d);
            c.drawRoundRect(box, 2.5f * d, 2.5f * d, glyph);
            box.set(cx - 7 * d, cy - 5 * d, cx + 7 * d, cy + 9 * d);
            c.drawArc(box, 0, 180, false, glyph);
            c.drawLine(cx, cy + 9 * d, cx, cy + 11 * d, glyph);
        }

        /** A thin shimmer along the bottom edge while work is in progress. The gradient is built once and moved
         * with a matrix; only the strip, the mark, the running step and the live chain row are redrawn (software
         * rendering on a 4×A35 TV: whole-panel redraws cost ~20 % of a core). */
        private void drawShimmer(Canvas c, long t, float r) {
            float bw = getWidth() - 2 * r, by = getHeight() - 1.5f * d;
            float head = (t % 1800) / 1800f * (bw + 160 * d) - 80 * d;
            shift.setTranslate(r + head, 0);
            shimmer.setLocalMatrix(shift);
            hair.setStyle(Paint.Style.STROKE);
            hair.setStrokeWidth(Math.max(1f, d));
            hair.setShader(shimmer);
            c.drawLine(r, by, r + bw, by, hair);
            hair.setShader(null);
            postInvalidateDelayed(80, 0, Math.round(getHeight() - 6 * d), getWidth(), getHeight());
        }

        /** The box fill and the light on its top edge, both built once the panel knows how tall it is. */
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            boxFill = new LinearGradient(0, 0, 0, h, Color.argb(242, 17, 18, 22), Color.argb(246, 7, 8, 10),
                    Shader.TileMode.CLAMP);
            bg.setShader(boxFill);
            topLight.setShader(new LinearGradient(RADIUS * d, 0, w - RADIUS * d, 0,
                    new int[]{Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    null, Shader.TileMode.CLAMP));
        }

        private LinearGradient boxFill, shimmer;
        private final Matrix shift = new Matrix();

        private static String label(String mode) {
            switch (mode) {
                case "confirm": return "等你确认";
                case "ask": return "等你回答";
                case "reading": return "朗读";
                case "done": return "完成";
                case "stopped": return "已停下";
                case "failed": return "没做成";
                case "hint": return "提示";
                default: return "执行中";
            }
        }
    }
}
