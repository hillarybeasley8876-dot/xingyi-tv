package xyz.erapi0neer.xingyi;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.IOException;

/** Called only after the poller's eligibility gate; always on a worker in :autorelay. */
public final class AutoMcpSubmit {
    private static final Uri BROKER=Uri.parse("content://xyz.erapi0neer.xingyi.autorelay");
    static String continuePreview(Context context,XiaomiDialogReader.Event event) throws IOException {
        boolean declined="confirmed_refusal".equals(event.cause);
        if ((!declined && !"local_confirmation".equals(event.cause)) || !XiaomiDialogReader.continuation(event.original))return "";
        Bundle data=new Bundle();
        data.putString("request_id",HandoffBinding.digest(event.dialogId));
        data.putLong("spoken_at",event.queryElapsedMs);
        data.putBoolean("addressed",declined || event.original.contains("星仪"));
        data.putString("control",event.original);
        try {
            Bundle reply=context.getContentResolver().call(BROKER,"continue_visible_preview",null,data);
            return reply==null ? "" : reply.getString("status","");
        } catch(RuntimeException unavailable) { throw new IOException("无法核对等待确认的步骤"); }
    }
    public static boolean submit(Context context,String dialogId,String original) throws IOException {
        return submit(context,dialogId,original,false);
    }
    public static boolean submit(Context context,String dialogId,String original,boolean musicSelection) throws IOException {
        if (dialogId==null || dialogId.length()==0 || dialogId.length()>256 || SharedTextDraft.text(original)==null) return false;
        String id=HandoffBinding.digest(dialogId);
        Bundle binding=new Bundle();
        binding.putString("request_id",id);
        binding.putString("digest",HandoffBinding.digest(original));
        ContentResolver resolver=context.getContentResolver();
        String token=null;
        try {
            Bundle reply=resolver.call(BROKER,musicSelection ? "open_music_selection" : "open",null,binding);
            if (reply==null || reply.isEmpty()) return false;
            token=reply.getString("bearer");
            String nonce=reply.getString("nonce");
            if (!HandoffBinding.hex64(nonce)) return false;
            return new HandoffMcpClient(reply.getString("endpoint"),token).submit(id,original,nonce);
        } catch (RuntimeException e) { throw new IOException("无法建立本机 MCP 接力"); }
        finally {
            if (token!=null) try { resolver.call(BROKER,"close",token,null); } catch(RuntimeException ignored) { }
        }
    }
    private AutoMcpSubmit(){}
}
