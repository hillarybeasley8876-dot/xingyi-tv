package xyz.erapi0neer.xingyi;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.IOException;
import java.util.Collections;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Processes only new foreground-TV turns on the device; never records audio or persists utterances. */
public final class AutoRelayService extends Service {
    private static final String CHANNEL="xiaoai_auto_relay";
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private volatile int generation;
    private ScheduledFuture<?> watch;
    private XiaomiDialogReader reader;
    private XiaomiDialogReader.SongCandidate trackedSong;
    private SongOutcome.Observation lastPlayback;
    private boolean songSettled;
    private volatile LocalAdb activeSongRead;
    private static volatile String phase="stopped";
    private static volatile String songProbe="idle", lastCause="";
    private static volatile long count, acceptedAt;
    private static volatile long controlSeen, controlMatched;
    private static volatile String controlDecision="none";
    private static volatile JSONObject testObservation;
    private static volatile long verifyUntil;
    private static volatile boolean knownTestAccepted;
    private static volatile QuietRefusals quiet;
    private Thread quietThread;
    private final java.util.concurrent.atomic.AtomicLong replySequence = new java.util.concurrent.atomic.AtomicLong();
    private static final int[] RETRY_MS = {60, 120, 240, 440, 640};

    static void beginVerification() {
        knownTestAccepted = false;
        verifyUntil = SystemClock.elapsedRealtime() + 70000;
    }

    static JSONObject receipt() throws Exception {
        QuietRefusals listener = quiet;
        return new JSONObject().put("phase",phase).put("accepted_count",count).put("accepted_elapsed_ms",acceptedAt)
            .put("control_seen",controlSeen).put("control_matched",controlMatched).put("control_decision",controlDecision)
            .put("quiet_state",listener==null ? "off" : listener.state)
            .put("quiet_matched",listener==null ? 0 : listener.matched)
            .put("quiet_stop_sent",listener==null ? 0 : listener.sent)
            .put("controlled_sample",testObservation).put("song_probe",songProbe).put("last_cause",lastCause)
            .put("known_test_accepted",SystemClock.elapsedRealtime() < verifyUntil && knownTestAccepted);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder n;
        if (Build.VERSION.SDK_INT>=26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"小爱自动接手",NotificationManager.IMPORTANCE_LOW));
            n=new Notification.Builder(this,CHANNEL);
        } else n=new Notification.Builder(this);
        PendingIntent off=PendingIntent.getService(this,85,new Intent(this,AutoRelayService.class).setAction("disable"),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(904,n.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("星仪正在等待小爱交接")
            .setContentText("核对新会话的明确拒绝或网易云错版，可在星仪中关闭").setOngoing(true).addAction(0,"关闭接手",off).build());
        if(intent!=null && "disable".equals(intent.getAction())) { AutoRelaySettings.disable(this);stopSelf();return START_NOT_STICKY; }
        boolean trial=intent!=null && "trial".equals(intent.getAction()) && AutoRelaySettings.trialActive(this);
        String account=trial?intent.getStringExtra("owner"):AutoRelaySettings.owner(this);
        String expected=trial?intent.getStringExtra("expected"):null;
        if(account==null || !account.matches("[0-9]{1,24}") || (trial && expected==null)) { stopSelf();return START_NOT_STICKY; }
        configureQuiet(!trial);
        if (watch!=null && intent!=null && "refresh_quiet".equals(intent.getAction())) return START_STICKY;
        int run=++generation;
        if(watch!=null) watch.cancel(false);
        cancelSongRead();
        worker.execute(()->{ reader=null; clearSong(); testObservation=null; phase="starting"; });
        watch=worker.scheduleWithFixedDelay(()->{
            if(run!=generation) return;
            if(!AutoRelaySettings.isArmed(this)) { stopSelfResult(id);return; }
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(!power.isInteractive()) { reader=null;clearSong();phase="asleep";return; }
            try {
                if(reader==null) {
                    reader=new XiaomiDialogReader(this,account);
                    reader.arm();
                    phase="armed";
                    return;
                }
                if(trial) testObservation=reader.diagnose(expected);
                XiaomiDialogReader.Event event=reader.poll();
                if(event==null) {
                    XiaomiDialogReader.SongCandidate song=reader.pollSongCandidate();
                    if (trial && song != null && !expected.equals(song.original)) song=null;
                    event=observeSong(song,run);
                } else clearSong();
                if(event==null || (trial && !expected.equals(event.original) && !"一加一等于几".equals(event.original))) return;
                if(run!=generation || !AutoRelaySettings.isArmed(this)) return;
                deliver(event);
            } catch (Exception e) {
                // Re-arm past the current turn. A lost acknowledgement must never replay an operation.
                reader=null;clearSong();phase="source_or_delivery_unavailable";
            }
        },0,1500,TimeUnit.MILLISECONDS);
        return trial?START_NOT_STICKY:START_STICKY;
    }

    private void cancelSongRead() {
        LocalAdb adb=activeSongRead;
        if(adb!=null) adb.cancel();
    }

    private void deliver(XiaomiDialogReader.Event event) throws IOException {
        phase="submitting";
        if(XiaomiDialogReader.continuation(event.original)) {
            String status=AutoMcpSubmit.continuePreview(this,event);
            controlDecision=status.isEmpty()?"unavailable":status;
            if("pass".equals(status)||"other_foreground".equals(status)){phase="armed";return;}
            if(status.isEmpty()){phase="not_accepted";return;}
            reader.noteConfirmationHandled(event.dialogId);
            if("understand".equals(status) && !AutoMcpSubmit.submit(this,event.dialogId,event.original)) {
                phase="not_accepted"; return;
            }
            if("no_task".equals(status))VendorSpeech.speak(this,"你想接着做哪件事？");
            else if("no_preview".equals(status))VendorSpeech.speak(this,"当前没有可确认的操作预览。");
            else if("running".equals(status))VendorSpeech.speak(this,"任务正在执行，无需再次确认。");
            count++;acceptedAt=SystemClock.elapsedRealtime();phase="accepted";lastCause="continue_"+status;
            return;
        }
        if (!AutoMcpSubmit.submit(this,event.dialogId,event.original,event.musicSelection)) {
            phase="not_accepted";
            return;
        }
        count++; acceptedAt=SystemClock.elapsedRealtime(); phase="accepted"; lastCause=event.cause;
        if (acceptedAt < verifyUntil && "你能读取星仪助手内部的本机休眠定时吗".equals(event.original)) knownTestAccepted=true;
    }

    private void onNativeReply(String id) {
        int run = generation;
        long seq = replySequence.incrementAndGet();
        try { worker.execute(() -> checkNativeDecision(run,seq,id,0)); }
        catch (RejectedExecutionException stopped) { /* Service stopped during delivery. */ }
    }

    private void onLocalConfirmation(String id,String phrase,long when) {
        int run=generation;
        controlSeen++;controlDecision="checking";
        try { worker.schedule(()->checkLocalConfirmation(run,id,phrase,when,0),50,TimeUnit.MILLISECONDS); }
        catch(RejectedExecutionException stopped) { /* Service no longer enabled. */ }
    }
    private void checkLocalConfirmation(int run,String id,String phrase,long when,int attempt) {
        if(run!=generation || reader==null || AutoRelaySettings.owner(this).isEmpty()
                || !((PowerManager)getSystemService(POWER_SERVICE)).isInteractive())return;
        try {
            XiaomiDialogReader.Event event=reader.inspectLocalConfirmation(id,phrase,when);
            if(event!=null) { controlMatched++;if(run==generation)deliver(event);return; }
            if(attempt<RETRY_MS.length)worker.schedule(()->checkLocalConfirmation(run,id,phrase,when,attempt+1),
                    RETRY_MS[attempt],TimeUnit.MILLISECONDS);
            else controlDecision="history_mismatch";
        } catch(IOException | RejectedExecutionException unavailable) { /* No unchecked confirm. */ }
    }

    /** The firmware writes its transcript asynchronously after producing the speech event. */
    private void checkNativeDecision(int run,long seq,String id,int attempt) {
        if (run!=generation || seq!=replySequence.get() || reader==null || AutoRelaySettings.owner(this).isEmpty()
                || !((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()) return;
        try {
            XiaomiDialogReader.Event event = reader.claimConfirmedRefusal(id);
            if (event!=null) {
                if (run==generation && seq==replySequence.get() && !AutoRelaySettings.owner(this).isEmpty()) deliver(event);
                return;
            }
            if (attempt<RETRY_MS.length) worker.schedule(() -> checkNativeDecision(run,seq,id,attempt+1),
                    RETRY_MS[attempt],TimeUnit.MILLISECONDS);
        } catch (IOException | RejectedExecutionException unavailable) {
            // The ordinary polling path remains armed and uses the same delivered-dialog ledger.
        }
    }

    private void configureQuiet(boolean enabled) {
        if (enabled && quiet == null) {
            quiet = new QuietRefusals(this,this::onNativeReply,this::onLocalConfirmation);
            quietThread = new Thread(quiet, "xiaomi-refusal-audio");
            quietThread.start();
        } else if (!enabled && quiet != null) {
            quiet.close();
            quietThread.interrupt();
            quietThread = null;
            quiet = null;
        }
    }

    private void clearSong() {
        trackedSong=null; lastPlayback=null; songSettled=false; songProbe="idle";
    }

    /** Observe only explicit NetEase song requests. Never send their titles to a catalog here. */
    private XiaomiDialogReader.Event observeSong(XiaomiDialogReader.SongCandidate song,int run) throws IOException {
        if(song==null) { clearSong(); return null; }
        if(!song.same(trackedSong)) { clearSong(); trackedSong=song; }
        if(songSettled || run!=generation) return null;
        LocalAdb adb=new LocalAdb(this);
        activeSongRead=adb;
        SongOutcome.Observation now;
        try {
            if(run!=generation || !AutoRelaySettings.isArmed(this)) return null;
            now=adb.readNetEasePlayback();
        } catch(IOException unavailable) {
            lastPlayback=null; songProbe="observation_unavailable";
            return null;
        } finally {
            adb.cancel(); activeSongRead=null;
        }
        if(run!=generation || !AutoRelaySettings.isArmed(this)) return null;
        SongOutcome.Verdict verdict=SongOutcome.judge(lastPlayback,now,song.queryElapsedMs,
                song.title,Collections.singletonList(song.title),song.artist,null);
        lastPlayback=now;
        if(SongOutcome.possiblyIntended(now,song.title,song.artist,song.queryElapsedMs)) {
            songSettled=true; songProbe="target_or_unverified_release"; return null;
        }
        if(verdict!=SongOutcome.Verdict.WRONG_VERSION || now.sampledElapsedMs-song.queryElapsedMs<4000) {
            songProbe="awaiting_evidence"; return null;
        }
        XiaomiDialogReader.Event event=reader.claimSongCorrection(song);
        songProbe=event!=null?"fresh_wrong_recording":"dialog_changed";
        return event;
    }

    @Override public void onDestroy() {
        ++generation;
        configureQuiet(false);
        cancelSongRead();
        if(watch!=null) watch.cancel(true);
        worker.shutdownNow();
        AutoRelaySettings.endTrial(this);
        stopForeground(true);
        phase="stopped";
        super.onDestroy();
    }
}
