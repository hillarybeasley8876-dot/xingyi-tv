package xyz.erapi0neer.xingyi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.erapi0neer.xingyi.agent.Ledger;
import xyz.erapi0neer.xingyi.agent.tv.AgentClient;

public class MainActivity extends Activity {
    private static final String SCREEN_ASK = "看看现在屏幕上是什么，用一两句话告诉我";
    private static final int MUSIC = AudioManager.STREAM_MUSIC;

    private AssistantLayout ui;
    private PhoneVoiceInput phoneVoice;
    private TvControl tv;
    private AudioManager audio;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedList<String> recent = new LinkedList<>();
    private BroadcastReceiver debug;
    private VoiceInputBridge voiceInput;
    private String lastSharedDraft;
    private AlertDialog replaceDraftDialog;
    private String desktopRequest, desktopPreview;
    private int automaticGeneration = -1;
    private final SpeechOutcome feedback = new SpeechOutcome();

    /** The agent runs in the bridge app (never force-stopped by MiTV's cleaner); this screen polls its state. */
    private final Runnable agentPoll = this::pollAgent;
    /** Following the engine's task; the poll runs while the activity is visible, faster while watching. */
    private boolean agentWatching, agentVisible, agentAsking;
    private String agentSeen = "";
    /** Request ids only grow, so a larger one is always a newer task than the one this screen last showed. */
    private int agentRequest = -1, agentShown = -1;
    private volatile int generation;
    private volatile boolean destroyed;
    private volatile LocalAdb activeAdb;
    private boolean running;
    private boolean awaitingConfirmation;
    private boolean debugResult;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        tv = new TvControl(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        ui = new AssistantLayout(this, this::onAction);
        AutoMcpBroker.registerContext(this);
        setContentView(ui.root);
        ui.progress.restore(state);
        phoneVoice = new PhoneVoiceInput(this, text -> {
            debugResult = false;
            ui.input.setText(text);
            ui.input.setSelection(text.length());
            submitText(text);
        });
        refreshVolume();
        setState(false, false, "准备就绪");
        ui.send.requestFocus();
        tts = new TextToSpeech(this, result -> main.post(() -> {
            if (!destroyed && tts != null && result == TextToSpeech.SUCCESS) {
                ttsReady = tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= 0;
            }
        }));
        debug = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if ("speech_receipt".equals(intent.getStringExtra("op"))) {
                    try { setResult(1,feedback.receipt(intent.getStringExtra("target_hash")).toString(),null); }
                    catch (Exception ignored) { setResult(0,"{}",null); }
                } else onDebug(intent);
            }
        };
        registerReceiver(debug, new IntentFilter("xyz.erapi0neer.xingyi.DEBUG"),
                "android.permission.DUMP", null);
        voiceInput = new VoiceInputBridge((text, id) -> {
            if (destroyed || !hasWindowFocus()) return 3;
            if (id.equals(desktopRequest)) return 2;
            ui.input.setText(text);
            ui.input.setSelection(text.length());
            debugResult = false;
            submitFrom(text, "voice", false);
            desktopRequest = id;
            return 1;
        }, this::reviewFromDesktop);
        IntentFilter voiceFilter = new IntentFilter(VoiceInputBridge.ACTION);
        voiceFilter.addAction(VoiceInputBridge.REVIEW);
        registerReceiver(voiceInput, voiceFilter, "android.permission.DUMP", null);
        receiveEntry(getIntent(), state != null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receiveEntry(intent, false);
    }

    private void receiveEntry(Intent intent, boolean restored) {
        if (intent == null || !AutoMcpBroker.ACTION.equals(intent.getAction())) {
            receiveSharedDraft(intent, restored);
            return;
        }
        // Consume even a busy, restored or obsolete launch. An intent replay must never run it later.
        setIntent(new Intent(Intent.ACTION_MAIN).setComponent(getComponentName()));
        AutoMcpBroker.Handoff request;
        try { request = AutoMcpBroker.takeRequest(intent.getStringExtra(AutoMcpBroker.EXTRA_TICKET)); }
        catch (RuntimeException malformed) { return; }
        if (request == null || restored || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
                || destroyed || replaceDraftDialog != null || !AutoRelaySettings.isArmed(this)) return;
        String original = request.original;
        // Preserve any text the user was editing. Never add automatic speech to recents or diagnostic files.
        debugResult = false;
        // A running task is not a reason to drop the hand-off: the engine supersedes it, exactly as a new utterance does.
        submitFrom(original, "voice", true);
        if (original != null && !original.trim().isEmpty()) feedback.begin(generation, original);
    }

    private void receiveSharedDraft(Intent intent, boolean restored) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        // Never replay shared extras on resume/recreation or let them select diagnostic operations.
        setIntent(new Intent(Intent.ACTION_MAIN).setComponent(getComponentName()));
        boolean history = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (!SharedTextDraft.canReceive(restored, history, running, awaitingConfirmation, destroyed)) return;
        String draft;
        try {
            if (!"text/plain".equalsIgnoreCase(intent.getType()) || intent.getData() != null
                    || intent.hasExtra(Intent.EXTRA_STREAM)) return;
            draft = SharedTextDraft.text(intent.getStringExtra(Intent.EXTRA_TEXT));
        } catch (RuntimeException invalidExtras) { return; }
        String previous = ui.input.getText().toString();
        SharedTextDraft.Choice choice = SharedTextDraft.choice(draft, previous, lastSharedDraft, replaceDraftDialog != null);
        if (choice == SharedTextDraft.Choice.IGNORE) return;
        lastSharedDraft = draft;
        if (choice == SharedTextDraft.Choice.ASK_TO_REPLACE) {
            replaceDraftDialog = new AlertDialog.Builder(this)
                    .setTitle("替换输入框里的文字？")
                    .setMessage(draft)
                    .setNegativeButton("保留原文", null)
                    .setPositiveButton("填入新文字", (dialog, button) -> {
                        if (!destroyed && !running && !awaitingConfirmation && previous.contentEquals(ui.input.getText()))
                            fillSharedDraft(draft);
                    }).create();
            replaceDraftDialog.setOnDismissListener(dialog -> replaceDraftDialog = null);
            replaceDraftDialog.show();
            replaceDraftDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        } else fillSharedDraft(draft);
    }

    private void fillSharedDraft(String draft) {
        ui.input.setText(draft);
        ui.input.setSelection(draft.length());
        ui.taskStatus.setText("已收到分享文字，核对后请选择「发送」。");
        ui.input.requestFocus();
    }

    private void reviewFromDesktop(String id, String op, String preview, VoiceInputBridge reply) {
        if (destroyed || !id.equals(desktopRequest)) { reply.reply(4, null); return; }
        if ("cancel".equals(op) && (running || awaitingConfirmation)) {
            cancelByUser();
            reply.reply(3, VoiceInputBridge.encoded("已取消后续步骤。"));
        } else if ("confirm".equals(op)) {
            if (!awaitingConfirmation || desktopPreview == null || !desktopPreview.equals(preview)) {
                reply.reply(4, null);
                return;
            }
            confirm();
            reply.reply(1, null);
        } else if (!"status".equals(op)) {
            reply.reply(4, null);
        } else if (awaitingConfirmation && desktopPreview != null) {
            reply.reply(2, desktopPreview);
        } else if (running) {
            reply.reply(1, null);
        } else {
            reply.reply(3, VoiceInputBridge.encoded(ui.answer.getText().toString()));
        }
    }

    private void onAction(String id) {
        // Only DUMP-protected test commands can opt in to saving results.
        debugResult = false;
        switch (id) {
            case "history":
                if (!running && !awaitingConfirmation) showHistory();
                break;
            case "memory":
                if (!running && !awaitingConfirmation) showMemory();
                break;
            case "send": submitText(ui.input.getText().toString()); break;
            case "remote_voice": openRemoteVoiceInput(); break;
            case "execute": confirm(); break;
            case "cancel": cancelByUser(); break;
            case "screen": requestScreen(); break;
            case "camera": requestXiaoai("打开摄像头", false); break;
            case "phone_voice":
                resetWork();
                phoneVoice.start();
                break;
            case "xiaoai": requestXiaoai(ui.input.getText().toString(), true); break;
            case "recent": chooseRecent(); break;
            case "apps": chooseApp(); break;
            case "hdmi": chooseInput(); break;
            case "volume_down": local("音量小一点"); break;
            case "volume_up": local("音量大一点"); break;
            case "mute": local(audio.isStreamMute(MUSIC) ? "取消静音" : "静音"); break;
            case "timer": chooseTimer(); break;
            case "settings": local("打开设置"); break;
            case "home": local("返回桌面"); break;
            case "speak": speak(); break;
            case "capabilities": capabilities(); break;
        }
    }

    private void openRemoteVoiceInput() {
        resetWork();
        show("搜狗键盘打开后，请按住遥控器语音键说话。核对输入框中的文字后选择「发送」。");
        ui.input.requestFocus();
        ui.input.post(() -> {
            InputMethodManager ime = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime == null || !ime.showSoftInput(ui.input, InputMethodManager.SHOW_IMPLICIT))
                show("键盘未能打开，请在输入框按确定后重试。也可以选择「手机语音」。");
        });
    }

    private void setState(boolean working, boolean pending, String label) {
        running = working;
        awaitingConfirmation = pending;
        // A task this screen only follows belongs to the engine, which supersedes it on the next request; only work
        // the app does itself (adb, a hand-off to 小爱) may hold off a hand-off.
        AutoMcpBroker.setBusy(!agentWatching && (working || pending));
        ui.taskStatus.setText(label);
        ui.send.setEnabled(!working);
        ui.execute.setEnabled(pending && !working);
        ui.cancel.setEnabled(working || pending);
        ui.speak.setEnabled(!working && ui.answer.length() > 0);
        ui.button("history").setEnabled(!working && !pending);
        ui.button("memory").setEnabled(!working && !pending);
    }

    private void show(String message) {
        ui.answer.setText(message);
        ui.answerScroll.scrollTo(0, 0);
        if (debugResult && generation != automaticGeneration) writeDiagnostic("last-result.txt", message);
    }

    private void refreshVolume() {
        boolean muted = audio.isStreamMute(MUSIC);
        ui.status.setText("媒体音量  " + audio.getStreamVolume(MUSIC) + " / "
                + audio.getStreamMaxVolume(MUSIC) + (muted ? "   ·   已静音" : ""));
        ui.button("mute").setText(muted ? "取消静音" : "静音");
    }

    private boolean current(int token) { return !destroyed && token == generation; }

    private int resetWork() {
        desktopRequest = null;
        desktopPreview = null;
        int token = ++generation;
        agentWatching = false;
        agentRequest = -1;
        agentSeen = "";
        LocalAdb adb = activeAdb;
        activeAdb = null;
        if (adb != null) adb.cancel();
        if (!destroyed) setState(false, false, "准备就绪");
        return token;
    }

    private void cancelByUser() {
        if (agentWatching) {
            agentWatching = false;
            agentRequest = -1;
            worker.execute(() -> AgentClient.cancel(this));
            setState(false, false, "已取消");
            schedulePoll(1500);
            return;
        }
        ui.progress.finish(generation,false,"你已取消任务");
        if (!running && !awaitingConfirmation) return;
        resetWork();
        show("已取消后续步骤。");
        setState(false, false, "已取消");
        ui.send.requestFocus();
    }

    private void remember(String text) {
        recent.remove(text);
        recent.addFirst(text);
        while (recent.size() > 5) recent.removeLast();
    }

    private void submitText(String raw) { submitFrom(raw, "ui", false); }

    /** Every typed or relayed request goes to the agent engine; it plans, acts and reports through onAgent. */
    private void submitFrom(String raw, String source, boolean automatic) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.length() > 2000) {
            show("请输入 1 至 2000 字的问题或指令。");
            ui.input.requestFocus();
            return;
        }
        lastSharedDraft = null;
        if (!debugResult && !automatic) remember(text);
        String request = desktopRequest;
        resetWork();
        desktopRequest = request;
        if (automatic) automaticGeneration = generation;
        setState(true, false, "已交给星仪");
        show("“" + text + "”" + "\n\n正在理解你的意思…");
        final int token = generation;
        worker.execute(() -> {
            org.json.JSONObject submitted = AgentClient.submitTracked(this, text, source);
            main.post(() -> { if (current(token)) applySubmit(submitted); });
        });
    }

    /** How the engine routed the request. "started" begins a task and "queued" one that runs after the task before it,
     * which this screen then follows by its id; every other result is the whole answer and leaves 发送 ready again. */
    private void applySubmit(org.json.JSONObject submitted) {
        if (destroyed) return;
        String result = submitted.optString("result", "unavailable");
        if ("started".equals(result) || "queued".equals(result)) {
            agentRequest = submitted.optInt("request", -1);
            agentShown = agentRequest;
            watch(200);
            return;
        }
        if ("approved".equals(result)) {
            // The waiting step was confirmed (here, by voice or from the PC); its task keeps running.
            show("已确认，继续执行。");
            setState(true, false, "已确认，继续执行");
            watch(200);
            return;
        }
        if ("declined".equals(result)) { settled("这一步不做了。", "这一步不做了"); return; }
        if ("cancelled".equals(result)) { settled("已停止。", "已停止"); return; }
        if ("side".equals(result)) { settled("已调好音量，正在做的事没有打断。", "音量已调好"); return; }
        if ("idle".equals(result)) { settled("现在没有正在执行的任务。", "没有任务"); return; }
        if ("empty".equals(result)) { settled("请输入 1 至 2000 字的问题或指令。", "准备就绪"); return; }
        settled("星仪后台没有响应。请确认「小爱语音接力」已安装并在运行。", "后台没有响应");
    }

    /** A result that ends this request on screen: it is shown, 发送 works again and the input takes the focus. */
    private void settled(String message, String label) {
        if (destroyed) return;
        agentWatching = false;
        agentRequest = -1;
        show(message);
        setState(false, false, label);
        ui.input.requestFocus();
        schedulePoll(1500);
    }

    /** Follow the engine's current task from now on, polling it at the watching rate. */
    private void watch(long delay) {
        agentWatching = true;
        agentSeen = "";
        schedulePoll(delay);
    }

    private void schedulePoll(long delay) {
        main.removeCallbacks(agentPoll);
        if (agentVisible && !destroyed) main.postDelayed(agentPoll, delay);
    }

    /** True when the engine holds a task from the last half hour that 继续 could refer to. */
    boolean hasRecentTask() {
        org.json.JSONObject t = AgentClient.state(this).optJSONObject("task");
        return t != null && System.currentTimeMillis() - t.optLong("updated_at") < 30 * 60_000L;
    }

    private void pollAgent() {
        // One state call at a time: the worker also runs the long ones (adb, a submit), and a backlog would
        // replay stale states.
        if (destroyed || !agentVisible || agentAsking) return;
        agentAsking = true;
        worker.execute(() -> {
            org.json.JSONObject s = AgentClient.state(this);
            main.post(() -> {
                agentAsking = false;
                applyAgentState(s);
            });
        });
    }

    /** Shows what the engine is doing: the task it is running, the question or confirmation it waits for, and how
     * the task ended. While this screen follows nothing it picks up a task it has not shown yet (one started by
     * voice, or one still running when the activity came back). */
    private void applyAgentState(org.json.JSONObject s) {
        if (destroyed || !agentVisible) return;
        boolean busy = s.optBoolean("busy");
        String awaiting = s.optString("awaiting");
        int request = s.optInt("request", -1);
        boolean live = busy || !awaiting.isEmpty();
        if (!agentWatching) {
            if (!live || request <= agentShown) { schedulePoll(1500); return; }
            agentWatching = true;
            agentSeen = "";
        }
        if (request > agentRequest) agentRequest = request;
        if (request > agentShown) agentShown = request;
        org.json.JSONObject raw = s.optJSONObject("task");
        Ledger task = null;
        try { if (raw != null) task = Ledger.fromJson(raw); } catch (Exception ignored) { }
        if (task == null) {
            agentWatching = false;
            schedulePoll(1500);
            return;
        }
        String signature = raw.toString() + busy + awaiting;
        if (!signature.equals(agentSeen)) {
            agentSeen = signature;
            if ("consent".equals(awaiting)) onAgent(task, "confirm", s.optString("prompt"));
            else if (!awaiting.isEmpty()) onAgent(task, "answer", s.optString("prompt"));
            else if (!busy && !task.state.open()) { agentWatching = false; onAgent(task, "finish", task.reply); }
            else onAgent(task, "progress", lastAction(task));
        }
        schedulePoll(agentWatching ? 400 : 1500);
    }

    private static String lastAction(Ledger t) {
        synchronized (t) {
            if (t.actions.isEmpty()) return t.planned() ? "正在执行" : "正在理解";
            return t.actions.get(t.actions.size() - 1).optString("what", "正在执行");
        }
    }

    private void onAgent(Ledger task, String event, String text) {
        if (destroyed) return;
        boolean working = task.state.open();
        ui.progress.agent(task.goal.isEmpty() ? "“" + task.utterance + "”" : task.goal, agentText(task),
                working ? ("confirm".equals(event) ? "等你确认" : "进行中") : finishLabel(task.state),
                working, task.state == Ledger.State.DONE || task.state == Ledger.State.ANSWERED);
        switch (event) {
            case "confirm":
                setState(false, true, "等你确认");
                show("需要你确认：" + text + "\n按「确认执行」继续，或对小爱说“好的”；不做就说“算了”。");
                ui.execute.requestFocus();
                break;
            case "answer":
                setState(false, false, "等你回答");
                show("星仪在问：" + text + "\n在下面输入答案后发送，或直接对小爱说。");
                ui.input.requestFocus();
                break;
            case "finish":
                setState(false, false, finishLabel(task.state));
                show(task.reply);
                refreshVolume();
                break;
            default:
                // A confirmation answered elsewhere (by voice, or from the PC) arrives here: 等你确认 goes away.
                setState(true, false, text);
                show("“" + task.utterance + "”\n\n" + text + "…");
        }
    }

    /** Steps with their state, then what actually ran, for the 执行过程 panel. */
    private static String agentText(Ledger t) {
        StringBuilder s = new StringBuilder();
        synchronized (t) {
            for (int i = 0; i < t.steps.size(); i++) {
                Ledger.Step st = t.steps.get(i);
                String mark = "done".equals(st.status) ? "●" : "in_progress".equals(st.status) ? "◐" : "failed".equals(st.status) ? "✕" : "skipped".equals(st.status) ? "–" : "○";
                if (s.length() > 0) s.append('\n');
                s.append(mark).append("  ").append(st.text);
            }
            if (!t.actions.isEmpty()) {
                s.append(s.length() > 0 ? "\n\n" : "").append("实际操作");
                for (org.json.JSONObject a : t.actions)
                    s.append('\n').append(a.optBoolean("ok") ? "✓ " : "✗ ").append(a.optString("what"));
            }
        }
        return s.length() == 0 ? "正在理解你的意思…" : s.toString();
    }

    private static String finishLabel(Ledger.State state) {
        switch (state) {
            case DONE: return "已完成";
            case ANSWERED: return "已回答";
            case AWAITING_USER: return "等你回答";
            case CANCELLED: return "已停止";
            case INTERRUPTED: return "停在中途，说“星仪继续”可接着做";
            default: return "没做成";
        }
    }

    private void confirm() {
        if (!agentWatching) return;
        worker.execute(() -> {
            org.json.JSONObject s = AgentClient.state(this);
            if (!"consent".equals(s.optString("awaiting"))) {
                main.post(() -> { if (!destroyed) setState(false, false, "现在没有要确认的操作"); });
                return;
            }
            AgentClient.consent(this, true);
            main.post(() -> {
                if (destroyed) return;
                show("已确认，继续执行。");
                setState(true, false, "已确认，继续执行");
                schedulePoll(200);
            });
        });
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "操作未完成，请重试。";
        return message;
    }

    private void failLater(int token, Exception error) {
        String message = describe(error);
        main.post(() -> failed(token, message));
    }

    private void failed(int token, String message) {
        if (!current(token)) return;
        ui.progress.finish(token,false,"任务未完成，请看结果");
        speakResult(token,"failed",0,message.contains("预览已过期") ? "操作预览已过期，这次没有执行这项操作。" : "星仪未能确认本次请求已完成，请查看电视上的提示。");
        String request = desktopRequest;
        resetWork();
        desktopRequest = request;
        show(message);
        setState(false, false, "请求已停止");
        refreshVolume();
    }

    private void speakResult(int token, String stage, int preview, String text) {
        if (!current(token) || debugResult || (token == automaticGeneration && !AutoRelaySettings.isArmed(this))
                || !feedback.claim(token,stage,preview)) return;
        feedback.submitted(VendorSpeech.SUBMITTED.equals(VendorSpeech.speak(this,text)),text);
    }

    private void local(String command) {
        int token = resetWork();
        try {
            show(tv.execute(Commands.parse(command)).trim());
            setState(false, false, "电视操作");
            refreshVolume();
        } catch (Exception error) { failed(token, describe(error)); }
    }

    private void chooseRecent() {
        resetWork();
        if (recent.isEmpty()) { show("最近发送的问题会出现在这里。"); return; }
        String[] items = recent.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("最近指令").setItems(items, (dialog, index) -> {
            ui.input.setText(items[index]);
            ui.input.setSelection(ui.input.length());
            show("已填入所选指令，可编辑后发送。");
            ui.send.requestFocus();
        }).setNegativeButton("返回", null).show();
    }

    private void chooseApp() {
        int token = resetWork();
        try {
            Map<String, Intent> installed = tv.apps();
            String[] labels = installed.keySet().toArray(new String[0]);
            if (labels.length == 0) { show("未找到可打开的应用。"); return; }
            new AlertDialog.Builder(this).setTitle("打开应用")
                    .setItems(labels, (dialog, index) -> local("打开" + labels[index]))
                    .setNegativeButton("返回", null).show();
        } catch (Exception error) { failed(token, describe(error)); }
    }

    private void chooseInput() {
        resetWork();
        String[] labels = {"HDMI 1", "HDMI 2", "HDMI 3"};
        new AlertDialog.Builder(this).setTitle("切换信号源")
                .setItems(labels, (dialog, index) -> local(labels[index]))
                .setNegativeButton("返回", null).show();
    }

    private void requestXiaoai(String raw, boolean userEntered) {
        int token = resetWork();
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) { show("请先输入要交给小爱的问题或米家指令。"); return; }
        new AlertDialog.Builder(this).setTitle("交给电视小爱").setMessage(query)
                .setPositiveButton("转交", (dialog, which) -> {
                    if (!current(token)) return;
                    if (userEntered && query.length() <= 1000) remember(query);
                    VendorQuery.dispatch(this, query, result -> {
                        if (current(token)) {
                            show(result);
                            setState(false, false, "小爱转交结果");
                        }
                    });
                }).setNegativeButton("取消", null).show();
    }

    private void chooseTimer() {
        int token = resetWork();
        String[] times = {"15 分钟", "30 分钟", "60 分钟", "取消定时"};
        new AlertDialog.Builder(this).setTitle("定时休眠 · " + SleepTimer.getStatus(this))
                .setItems(times, (dialog, choice) -> {
                    if (!current(token)) return;
                    if (choice == 3) {
                        try { SleepTimer.cancel(this); show(SleepTimer.getStatus(this)); }
                        catch (RuntimeException error) { failed(token, describe(error)); }
                    } else {
                        checkTimerConnection(token, new int[]{15, 30, 60}[choice]);
                    }
                }).setNegativeButton("返回", null).show();
    }

    private void checkTimerConnection(int token, int minutes) {
        setState(true, false, "正在准备定时休眠");
        show("正在连接电视…");
        worker.execute(() -> {
            LocalAdb adb = new LocalAdb(this);
            try {
                activeAdb = adb;
                if (!current(token)) return;
                adb.connect();
                main.post(() -> {
                    if (!current(token)) return;
                    try {
                        SleepTimer.schedule(this, minutes);
                        show(SleepTimer.getStatus(this));
                        setState(false, false, "定时已设置");
                    } catch (RuntimeException error) { failed(token, describe(error)); }
                });
            } catch (Exception e) { failLater(token, e); }
            finally { adb.cancel(); if (activeAdb == adb) activeAdb = null; }
        });
    }

    private void requestScreen() {
        int token = resetWork();
        new AlertDialog.Builder(this).setTitle("查看当前屏幕")
                .setMessage("助手会暂时退到后台，让星仪自己看一眼电视当前的画面。")
                .setPositiveButton("看一下", (dialog, which) -> { if (current(token)) lookAtScreen(token); })
                .setNegativeButton("取消", null).show();
    }

    /** No screenshot here any more: 星仪 looks at the TV itself (screen_look) and its panel answers over the app
     * that is in front; this screen shows the same answer when the user comes back to it. */
    private void lookAtScreen(int token) {
        setState(true, false, "正在看屏幕");
        show("星仪正在看当前屏幕…");
        if (!moveTaskToBack(true)) { failed(token, "无法隐藏助手界面。"); return; }
        worker.execute(() -> {
            try { Thread.sleep(1200); } catch (InterruptedException stopped) { return; }
            main.post(() -> { if (current(token)) submitFrom(SCREEN_ASK, "ui", false); });
        });
    }

    private void speak() {
        String text = ui.answer.getText().toString().trim();
        if (text.isEmpty()) return;
        if (ttsReady && tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply") == TextToSpeech.SUCCESS) return;
        Toast.makeText(this, VendorSpeech.speak(this, text), Toast.LENGTH_LONG).show();
    }

    private void capabilities() {
        resetWork();
        String voice = ttsReady || VendorSpeech.isAvailable(this) ? "可朗读回答" : "播报暂不可用";
        show("输入问题后，星仪可以查询电视状态、查看应用，并列出待确认的电视操作。\n\n"
                + "屏幕理解每次只分析你确认的一张画面，受保护的视频可能无法读取。\n\n"
                + "点「遥控器语音」可打开电视键盘尝试口述，或点「手机语音」通过家庭 Wi-Fi 发送听写文字。远场呼叫仍由小爱处理。星仪" + voice + "。\n\n"
                + "点歌时说出网易云、歌手和歌名。例如「打开网易云音乐，播放林俊杰的不为谁而作的歌」。星仪会核对播放器里的歌曲。\n\n"
                + "「交给小爱」可转交米家文字指令，已验证打开摄像头。影视搜索取决于目标应用支持。");
        setState(false, false, "功能与状态");
    }

    /** 历史: what 星仪 did in the last 7 days, newest first. Picking one shows it and fills the input, so 发送 repeats it. */
    private void showHistory() {
        resetWork();
        setState(true, false, "正在读取历史");
        worker.execute(() -> {
            final JSONArray items = AgentClient.history(this, null, 7, 50);
            main.post(() -> {
                if (destroyed) return;
                setState(false, false, "历史");
                if (items.length() == 0) { show("最近 7 天还没有记录。"); return; }
                String[] labels = new String[items.length()];
                for (int i = 0; i < labels.length; i++) {
                    org.json.JSONObject e = items.optJSONObject(i);
                    labels[i] = e == null ? "" : e.optString("when") + " · " + cut(e.optString("said"), 30);
                }
                new AlertDialog.Builder(this).setTitle("历史 · 最近 7 天")
                        .setItems(labels, (dialog, index) -> showHistoryItem(items.optJSONObject(index)))
                        .setNegativeButton("返回", null).show();
            });
        });
    }

    private void showHistoryItem(org.json.JSONObject entry) {
        if (destroyed || entry == null) return;
        String said = entry.optString("said"), playing = entry.optString("playing");
        StringBuilder text = new StringBuilder(entry.optString("when"))
                .append("\n你说：").append(said)
                .append("\n星仪：").append(entry.optString("replied"));
        if (!playing.isEmpty()) text.append("\n当时在放：《").append(playing).append("》");
        show(text.toString());
        setState(false, false, "历史记录");
        ui.input.setText(said);
        ui.input.setSelection(said.length());
        ui.send.requestFocus();
    }

    /** 记忆: everything 星仪 keeps about the user, grouped; every entry can be deleted. */
    private void showMemory() {
        resetWork();
        setState(true, false, "正在读取记忆");
        worker.execute(() -> {
            final org.json.JSONObject kept = AgentClient.memory(this);
            main.post(() -> { if (!destroyed) showMemoryList(kept); });
        });
    }

    private void showMemoryList(org.json.JSONObject kept) {
        setState(false, false, "记忆");
        final ArrayList<String> labels = new ArrayList<>(), kinds = new ArrayList<>(), which = new ArrayList<>();
        JSONArray facts = kept.optJSONArray("facts");
        for (int i = 0; facts != null && i < facts.length(); i++) {
            String fact = facts.optString(i);
            if (fact.isEmpty()) continue;
            labels.add("记住的事：" + fact); kinds.add("fact"); which.add(fact);
        }
        JSONArray aliases = kept.optJSONArray("aliases");
        for (int i = 0; aliases != null && i < aliases.length(); i++) {
            org.json.JSONObject a = aliases.optJSONObject(i);
            if (a == null) continue;
            labels.add("写法：" + a.optString("heard") + " → " + a.optString("written"));
            kinds.add("alias"); which.add(a.optString("heard"));
        }
        JSONArray notes = kept.optJSONArray("notes");
        for (int i = 0; notes != null && i < notes.length(); i++) {
            org.json.JSONObject n = notes.optJSONObject(i);
            if (n == null) continue;
            labels.add("随手记：" + n.optString("text") + "（" + n.optString("when") + "）");
            kinds.add("note"); which.add(String.valueOf(n.optInt("id")));
        }
        JSONArray schedules = kept.optJSONArray("schedules");
        for (int i = 0; schedules != null && i < schedules.length(); i++) {
            org.json.JSONObject s = schedules.optJSONObject(i);
            if (s == null) continue;
            labels.add("预约：" + s.optString("text"));
            kinds.add("schedule"); which.add(String.valueOf(s.optInt("id")));
        }
        // The habits are one learned summary, not single items, so they are one entry that clears them all.
        JSONArray habits = kept.optJSONArray("habits");
        StringBuilder learned = new StringBuilder();
        for (int i = 0; habits != null && i < habits.length(); i++) {
            if (learned.length() > 0) learned.append('\n');
            learned.append(habits.optString(i));
        }
        if (learned.length() > 0) {
            labels.add("使用习惯（自动整理）"); kinds.add("habits"); which.add(learned.toString());
        }
        boolean on = kept.optBoolean("hints", true);
        int keptCount = labels.size();
        labels.add("亮屏提示：" + (on ? "开着" : "关着")); kinds.add("hints"); which.add(on ? "on" : "off");
        if (keptCount == 0) show("星仪还没有记住什么。");
        new AlertDialog.Builder(this).setTitle("记忆")
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        memoryItem(kinds.get(index), which.get(index), labels.get(index)))
                .setNegativeButton("返回", null).show();
    }

    private void memoryItem(String kind, String which, String label) {
        if (destroyed) return;
        if ("hints".equals(kind)) { toggleHints("on".equals(which)); return; }
        boolean habits = "habits".equals(kind);
        new AlertDialog.Builder(this).setTitle(habits ? "清空使用习惯？" : "删掉这条？").setMessage(label)
                .setNegativeButton("返回", null)
                .setPositiveButton(habits ? "清空" : "删掉", (dialog, button) -> forgetItem(kind, which))
                .show();
    }

    private void forgetItem(String kind, String which) {
        setState(true, false, "正在删除");
        worker.execute(() -> {
            final String sentence = AgentClient.forget(this, kind, which);
            main.post(() -> {
                if (destroyed) return;
                show(sentence);
                showMemory();
            });
        });
    }

    private void toggleHints(boolean on) {
        setState(true, false, "正在设置提示");
        worker.execute(() -> {
            AgentClient.hints(this, !on);
            main.post(() -> {
                if (destroyed) return;
                show(!on ? "亮屏提示已打开。" : "亮屏提示已关掉。");
                showMemory();
            });
        });
    }

    /** One line of a list: at most {@code max} characters, then an ellipsis. */
    private static String cut(String value, int max) {
        String line = value == null ? "" : value.replace('\n', ' ').trim();
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }

    private void onDebug(Intent intent) {
        String op = intent.getStringExtra("op");
        if (generation == automaticGeneration && (running || awaitingConfirmation)) return;
        debugResult = true;
        if ("ask".equals(op)) submitText(intent.getStringExtra("text"));
        else if ("execute".equals(op)) confirm();
        else if ("cancel".equals(op)) cancelByUser();
        else if ("screen".equals(op)) requestScreen();
        else if ("tests".equals(op)) { resetWork(); selfTest(); }
        else if ("harness_tests".equals(op)) {
            final int token = resetWork();
            setState(true, false, "正在运行流程测试");
            worker.execute(() -> {
                try {
                    String report = HarnessContractTest.run();
                    main.post(() -> {
                        if (!current(token)) return;
                        show(report);
                        setState(false, false, "流程测试结束");
                    });
                } catch (Exception error) { failLater(token, error); }
            });
        }
        else if ("timer".equals(op)) {
            int token = resetWork();
            try { SleepTimer.schedule(this, 1); show(SleepTimer.getStatus(this)); }
            catch (RuntimeException error) { failed(token, describe(error)); }
        }
    }

    private void writeDiagnostic(String name, String value) {
        File directory = getExternalFilesDir(null);
        if (directory == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private void selfTest() {
        int passed = 0;
        try {
            String[] commands = {"音量调到20", "HDMI2", "返回桌面", "静音", "取消静音", "打开设置"};
            for (String command : commands) {
                if (Commands.parse(command).length() != 1) throw new Exception(command);
                passed++;
            }
            if (Commands.parse("你好吗").length() != 0) throw new Exception("chat parser");
            passed++;
            String[] invalid = {
                    "[{\"tool\":\"shell\",\"args\":{}}]",
                    "[{\"tool\":\"set_volume\",\"args\":{\"level\":101}}]",
                    "[{\"tool\":\"set_volume\",\"args\":{\"level\":1.5}}]",
                    "[{\"tool\":\"switch_input\",\"args\":{\"name\":\"HDMI4\"}}]"
            };
            for (String json : invalid) {
                boolean rejected = false;
                try { Commands.validate(new JSONArray(json)); } catch (Exception expected) { rejected = true; }
                if (!rejected) throw new Exception("unsafe action accepted");
                passed++;
            }
            boolean muted = audio.isStreamMute(MUSIC);
            // A muted stream can report zero even when its stored audible level is nonzero.
            if (muted) audio.adjustStreamVolume(MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            int previous = audio.getStreamVolume(MUSIC);
            try {
                JSONArray bad = new JSONArray("[{\"tool\":\"set_volume\",\"args\":{\"level\":1}},"
                        + "{\"tool\":\"shell\",\"args\":{}}]");
                try { tv.execute(bad); throw new Exception("invalid batch accepted"); }
                catch (IllegalArgumentException expected) { }
                if (audio.getStreamVolume(MUSIC) != previous) throw new Exception("partial mutation");
                passed++;
                tv.execute(Commands.parse("音量调到10"));
                if (audio.getStreamVolume(MUSIC) != Math.round(audio.getStreamMaxVolume(MUSIC) * .1f))
                    throw new Exception("volume mismatch");
                passed++;
                tv.execute(Commands.parse("静音"));
                if (!audio.isStreamMute(MUSIC)) throw new Exception("mute mismatch");
                passed++;
            } finally {
                audio.setStreamVolume(MUSIC, previous, 0);
                audio.adjustStreamVolume(MUSIC, muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            }
            show("PASS " + passed + " checks; volume restored. Apps: " + tv.apps().keySet());
        } catch (Exception error) { show("FAIL after " + passed + ": " + describe(error)); }
        refreshVolume();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if(ui!=null)ui.progress.save(out);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ui != null) refreshVolume();
    }

    @Override public void onBackPressed() {
        if (running || awaitingConfirmation) cancelByUser();
        else super.onBackPressed();
    }

    @Override protected void onStart() {
        super.onStart();
        AutoMcpBroker.setVisible(true);
        agentVisible = true;
        // Picks up whatever the engine is doing now, including a task that started while this screen was away.
        schedulePoll(300);
    }

    @Override protected void onStop() {
        agentVisible = false;
        main.removeCallbacks(agentPoll);
        AutoMcpBroker.setVisible(false);
        super.onStop();
        if (phoneVoice != null) phoneVoice.close();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        AutoMcpBroker.unregisterContext(this);
        agentWatching = false;
        agentVisible = false;
        main.removeCallbacks(agentPoll);
        AutoMcpBroker.setBusy(false);
        if (replaceDraftDialog != null) replaceDraftDialog.dismiss();
        if (phoneVoice != null) phoneVoice.close();
        resetWork();
        worker.shutdownNow();
        if (tts != null) tts.shutdown();
        if (debug != null) unregisterReceiver(debug);
        if (voiceInput != null) unregisterReceiver(voiceInput);
        super.onDestroy();
    }
}
