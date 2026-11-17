package xyz.erapi0neer.xingyi;

import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import org.json.JSONObject;

/** Private bootstrap for the :autorelay process. Caller identity is never inferred from intent extras. */
public final class AutoMcpBroker extends ContentProvider {
    public static final String ACTION = "xyz.erapi0neer.xingyi.AUTO_HANDOFF", EXTRA_TICKET = "ticket";
    private static final long TICKET_MS = 12000;
    private static final ArrayDeque<String> seen = new ArrayDeque<>();
    private static volatile boolean busy;
    private static final PreviewContinuation continuation=new PreviewContinuation();
    private static WeakReference<MainActivity> contextOwner=new WeakReference<>(null);
    private static String ticket;
    private static Handoff handoff;
    private static long expires;
    private TvMcpHttp server;
    private HandoffBinding active;

    @Override public boolean onCreate() { return true; }
    public static void setBusy(boolean value) { busy = value; continuation.reset(value); }
    static void setVisible(boolean visible) { continuation.visible(visible,SystemClock.elapsedRealtime()); }
    static void registerContext(MainActivity activity) { contextOwner=new WeakReference<>(activity); }
    static void unregisterContext(MainActivity activity) {
        if(contextOwner.get()==activity) contextOwner.clear();
    }
    static void offerPreview(Runnable confirm,Runnable describe) {
        continuation.offer(confirm,describe,SystemClock.elapsedRealtime(),AgentHarness.CONFIRM_MS);
    }

    private Bundle continuePreview(Bundle extras) {
        if(extras==null || extras.size()!=4)throw new IllegalArgumentException("缺少本次续办的会话");
        String control=extras.getString("control");
        if(!XiaomiDialogReader.continuation(control))return Bundle.EMPTY;
        boolean approval=XiaomiDialogReader.explicitConfirmation(control);
        String id=extras.getString("request_id");
        long when=extras.getLong("spoken_at",-1),now=SystemClock.elapsedRealtime();
        if(!HandoffBinding.hex64(id) || when<=0 || when>now || now-when>12000 || seen.contains(id))
            return Bundle.EMPTY;
        PreviewContinuation.Bound expected=continuation.bind(when,now);
        boolean addressed=extras.getBoolean("addressed");
        if(expected==null && !addressed && !continuation.visibleWhen(when,now)){
            Bundle pass=new Bundle();pass.putString("status","pass");return pass;
        }
        if(expected!=null) {
            LocalAdb state=new LocalAdb(getContext());
            try {
                if(!state.isPreviewOrXiaomiForeground()){
                    Bundle pass=new Bundle();pass.putString("status","other_foreground");return pass;
                }
            } catch(IOException unavailable){return Bundle.EMPTY;} finally{state.cancel();}
        }
        java.util.concurrent.FutureTask<String> check=new java.util.concurrent.FutureTask<>(()->{
            if(AutoRelaySettings.owner(getContext()).isEmpty())return "";
            PreviewContinuation.Status status=continuation.decide(expected,when,SystemClock.elapsedRealtime(),approval);
            if(status==PreviewContinuation.Status.CONFIRMED) {
                expected.confirm.run();
                return "confirmed";
            }
            if(status==PreviewContinuation.Status.WAITING) {
                expected.describe.run();
                return "waiting_confirmation";
            }
            if(status==PreviewContinuation.Status.RUNNING)return "running";
            if(status==PreviewContinuation.Status.STALE)return addressed ? "no_preview" : "pass";
            MainActivity owner=contextOwner.get();
            if(!approval && owner!=null && owner.hasRecentTask())return "understand";
            return addressed ? (approval ? "no_preview" : "no_task") : "pass";
        });
        new Handler(Looper.getMainLooper()).post(check);
        try {
            String status=check.get(2,java.util.concurrent.TimeUnit.SECONDS);
            // An ordinary semantic continuation is submitted through the same one-shot MCP
            // binding as a new instruction, so that submission owns its replay fence.
            if(!"understand".equals(status) && !"pass".equals(status) && !status.isEmpty()){
                if(seen.size()==256)seen.removeFirst();
                seen.addLast(id);
            }
            Bundle out=new Bundle();out.putString("status",status);return out;
        } catch(Exception unavailable) { check.cancel(false);return Bundle.EMPTY; }
    }

    /** Only one activity launch can redeem the private RAM ticket. Process death discards it. */
    static final class Handoff {
        final String original;
        final GenericRefusalText.Scope scope;
        Handoff(String original,GenericRefusalText.Scope scope) { this.original=original; this.scope=scope; }
    }
    static synchronized Handoff takeRequest(String value) {
        if (!HandoffBinding.equal(ticket,value)) return null;
        Handoff result = !busy && SystemClock.elapsedRealtime() < expires ? handoff : null;
        ticket = null; handoff = null;
        return result;
    }
    public static String take(String value) {
        Handoff result=takeRequest(value);
        return result==null ? null : result.original;
    }
    private static synchronized String issue(String text,boolean musicSelection) {
        if (busy || (ticket != null && SystemClock.elapsedRealtime() < expires)) return null;
        GenericRefusalText.Scope scope = musicSelection ? GenericRefusalText.musicSelection(text) : GenericRefusalText.scope(text);
        if (musicSelection && scope == null) return null;
        ticket = HandoffBinding.random();
        handoff = new Handoff(text,scope);
        expires = SystemClock.elapsedRealtime()+TICKET_MS;
        String key = ticket;
        new Handler(Looper.getMainLooper()).postDelayed(() -> forget(key),TICKET_MS);
        return ticket;
    }
    private static synchronized void forget(String key) {
        if (HandoffBinding.equal(ticket,key)) { ticket = null; handoff = null; }
    }
    private void checkCaller() {
        int pid = Binder.getCallingPid(), uid = Binder.getCallingUid();
        if (uid != android.os.Process.myUid() || pid == android.os.Process.myPid()) throw new SecurityException("需要私有接力进程");
        Context app = getContext();
        long identity = Binder.clearCallingIdentity();
        try {
            ActivityManager manager = (ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs = manager == null ? null : manager.getRunningAppProcesses();
            if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs)
                if (p.pid == pid && p.uid == uid && (app.getPackageName()+":autorelay").equals(p.processName)) return;
        } finally { Binder.restoreCallingIdentity(identity); }
        throw new SecurityException("需要私有接力进程");
    }
    @Override public synchronized Bundle call(String op,String arg,Bundle extras) {
        checkCaller();
        if("continue_visible_preview".equals(op) && arg==null)return continuePreview(extras);
        if ("close".equals(op)) {
            if (server != null && HandoffBinding.equal(server.bearer(),arg)) { server.close(); server=null; active=null; }
            return Bundle.EMPTY;
        }
        boolean musicSelection="open_music_selection".equals(op);
        if ((!musicSelection && !"open".equals(op)) || arg != null || extras == null || extras.size()!=2) throw new IllegalArgumentException("未知接力请求");
        HandoffBinding binding = new HandoffBinding(extras.getString("request_id"),extras.getString("digest"));
        if (busy || seen.contains(binding.requestId)) return Bundle.EMPTY;
        if (seen.size() == 256) seen.removeFirst();
        seen.addLast(binding.requestId);
        if (server != null) server.close();
        active = binding;
        try {
            server = TvMcpHttp.handoff((id,text,nonce) -> {
                String key;
                synchronized (AutoMcpBroker.this) {
                    if (active != binding || !binding.claim(id,text,nonce) || (key=issue(text,musicSelection)) == null)
                        return new JSONObject().put("accepted",false);
                }
                try {
                    Context app=getContext();
                    app.startActivity(new Intent(app,MainActivity.class).setAction(ACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_TICKET,key));
                } catch (RuntimeException e) { forget(key); throw e; }
                return new JSONObject().put("accepted",true).put("request_id",binding.requestId);
            });
        } catch (IOException e) { active=null; server=null; throw new IllegalStateException("本机接力无法启动"); }
        Bundle b = new Bundle();
        b.putString("endpoint",server.url()); b.putString("bearer",server.bearer()); b.putString("nonce",binding.nonce);
        return b;
    }
    @Override public String getType(Uri u){return null;}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
