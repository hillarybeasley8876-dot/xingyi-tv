"""Exercise the production reader against a provider that can advance between its two queries."""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/xyz/erapi0neer/xingyi'
STUBS = {
    'android/content/Context.java': 'package android.content; public abstract class Context {public Context getApplicationContext(){return this;} public abstract ContentResolver getContentResolver();}',
    'android/content/ContentResolver.java': 'package android.content; import android.database.Cursor; import android.net.Uri; public abstract class ContentResolver {public abstract Cursor query(Uri u,String[] p,String s,String[] a,String sort);}',
    'android/database/Cursor.java': 'package android.database; public interface Cursor extends java.io.Closeable {int getCount(); boolean moveToNext();String getString(int i);long getLong(int i); void close();}',
    'android/net/Uri.java': 'package android.net; public class Uri {public static Uri parse(String s){return new Uri();}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}'
}
TEST = r'''package xyz.erapi0neer.xingyi;
import java.util.*;
import org.json.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
public class ReaderBoundary {
 static int n;
 static void check(boolean yes){if(!yes)throw new AssertionError("check "+(n+1)); n++;}
 static class Source extends ContentResolver {
   String id="previous", alternate=null, nextIdAfterDetail=null, text="测试原话", reply="小爱目前不支持", category="UNSUPPORT";
   String precedingId="";
   long precedingTime;
   long time=System.currentTimeMillis()-60000;
   int rawReads, rawProjections, metaQueries, rowCount=2;
   public Cursor query(Uri u,String[] cols,String filter,String[] args,String sort){
     check(args.length==4 && "1".equals(args[2]) && (args[1].isEmpty() || (id.equals(args[1]) && cols.length==2)));
     boolean raw=cols.length==3;
     if(raw)rawProjections++;else metaQueries++;
     final String key=args[1].isEmpty() ? (raw && alternate!=null?alternate:id) : precedingId;
     if(raw && nextIdAfterDetail!=null){id=nextIdAfterDetail;nextIdAfterDetail=null;}
     final long ts=args[1].isEmpty() ? time : precedingTime;
     return new Cursor(){
       int i=-1;
       public int getCount(){return rowCount;}
       public boolean moveToNext(){return ++i<rowCount;}
       public long getLong(int column){return ts+i;}
       public String getString(int col){
         if(col==0)return key;
         if(!raw||col!=2)throw new AssertionError();
         rawReads++;
         try {
           JSONObject header=new JSONObject().put("dialog_id",key).put("namespace",i==0?"Template":"SpeechSynthesizer").put("name",i==0?"Query":"Speak");
           JSONObject payload=new JSONObject().put("text",i==0?text:reply).put("tts_category",category);
           return new JSONObject().put("header",header).put("payload",payload).toString();
         }catch(JSONException e){throw new AssertionError(e);}
       }
       public void close(){}
     };
   }
 }
 public static void main(String[] unused)throws Exception{
   Source src=new Source();
   Context ctx=new Context(){public ContentResolver getContentResolver(){return src;}};
   XiaomiDialogReader r=new XiaomiDialogReader(ctx,"123");
   r.arm();check(src.rawProjections==0);check(r.poll()==null && src.rawProjections==0);
   Thread.sleep(35);src.id="A";src.time=System.currentTimeMillis();
   src.alternate="different";check(r.poll()==null && src.rawReads==0);
   src.alternate=null;check(r.poll()==null);
   JSONObject other=r.diagnose("另一个受控探针");check(!other.getBoolean("matched") && !other.has("instructions") && !other.toString().contains("测试原话"));
   JSONObject ok=r.diagnose("测试原话");check(ok.getBoolean("explicit_unsupported") && !ok.toString().contains("测试原话") && !ok.has("dialog_id"));
   Thread.sleep(2230);XiaomiDialogReader.Event e=r.poll();check(e!=null && "测试原话".equals(e.original));check(r.poll()==null);
   long last=src.time;src.id="stale";src.time=System.currentTimeMillis()-80000;int reads=src.rawReads;check(r.poll()==null && src.rawReads==reads);
   src.id="A";src.time=last;r.arm();check(r.poll()==null && src.rawReads==reads);
   Thread.sleep(20); src.text="打开网易云音乐播放林俊杰的《不为谁而作的歌》";src.category="SUCC";
   src.id="song1";src.time=System.currentTimeMillis();check(r.poll()==null);
   XiaomiDialogReader.SongCandidate song=r.pollSongCandidate();
   check(song!=null && "林俊杰".equals(song.artist) && "不为谁而作的歌".equals(song.title) && src.text.equals(song.original));
   check(r.poll()==null && song.same(r.pollSongCandidate()));
   src.id="song2";src.time=System.currentTimeMillis();check(r.claimSongCorrection(song)==null);
   check(r.poll()==null);song=r.pollSongCandidate();check(song!=null && "song2".equals(song.dialogId));
   src.category="NEED_CONFIRM";check(r.claimSongCorrection(song)==null && r.poll()==null && r.pollSongCandidate()==null);
   src.category="SUCC";check(r.poll()==null && r.pollSongCandidate()!=null);
   e=r.claimSongCorrection(song);check(e!=null && "fresh_wrong_recording".equals(e.cause) && src.text.equals(e.original));
   check(r.claimSongCorrection(song)==null && r.pollSongCandidate()==null);
   src.category="UNSUPPORT";check(r.poll()==null);Thread.sleep(2230);check(r.poll()==null && r.pollSongCandidate()==null);
   src.id="song3";src.time=System.currentTimeMillis();check(r.poll()==null);Thread.sleep(2230);
   e=r.poll();check(e!=null && "explicit_refusal".equals(e.cause));
   src.category="SUCC";check(r.poll()==null && r.pollSongCandidate()==null);
   String confirmed="abcdef0123456789abcdef0123456789", otherId="abcd9999999999999999999999999999";
   src.id=confirmed;src.time=System.currentTimeMillis();
   int details=src.rawProjections;
   check(r.claimConfirmedRefusal(null)==null && r.claimConfirmedRefusal("abcd")==null);
   check(r.claimConfirmedRefusal(otherId)==null && src.rawProjections==details);
   check(r.claimConfirmedRefusal(confirmed)==null); // SUCC is not a failure
   src.category="UNSUPPORT";src.nextIdAfterDetail=otherId;
   check(r.claimConfirmedRefusal(confirmed)==null); // superseded during the detail read
   src.id=confirmed;
   e=r.claimConfirmedRefusal(confirmed);
   check(e!=null && confirmed.equals(e.dialogId) && "confirmed_refusal".equals(e.cause) && src.text.equals(e.original));
   check(r.claimConfirmedRefusal(confirmed)==null && r.poll()==null);
   Thread.sleep(2250);check(r.poll()==null); // polling cannot replay the event-driven claim
   src.id=otherId;src.time=System.currentTimeMillis();check(r.poll()==null);
   Thread.sleep(2250);check(r.poll()!=null && r.claimConfirmedRefusal(otherId)==null);
   r.arm();check(r.claimConfirmedRefusal(otherId)==null);
   Thread.sleep(20);src.id="title-point";src.text="播放空心";src.time=System.currentTimeMillis();src.category="SUCC";check(r.poll()==null);
   src.precedingTime=src.time;Thread.sleep(5);src.id=confirmed;src.text="黄霄云的空心";src.time=System.currentTimeMillis();
   src.precedingId="another-dialog";src.category=null;src.reply="抱歉，目前暂不支持该功能";
   e=r.claimConfirmedRefusal(confirmed);check(e!=null && !e.musicSelection); // regular preview only, predecessor changed
   Thread.sleep(5);src.id="title-again";src.text="网易云音乐播放空心";src.time=System.currentTimeMillis();src.category="SUCC";check(r.poll()==null);
   src.precedingTime=src.time;src.precedingId=src.id;Thread.sleep(5);src.id="cccc0000000000000000000000000000";src.text="刘端端的空心";src.time=System.currentTimeMillis();src.category=null;
   e=r.claimConfirmedRefusal(src.id);check(e!=null && e.musicSelection && src.text.equals(e.original));
   check(r.claimConfirmedRefusal(src.id)==null);
   Thread.sleep(6);src.id="aaaaaaaa000000000000000000000000";src.text="继续执行";src.category=null;
   src.rowCount=1;src.time=System.currentTimeMillis();check(r.poll()==null);
   long recognized=android.os.SystemClock.elapsedRealtime();
   check(r.inspectLocalConfirmation(src.id,"确认执行",recognized)==null);
   check(r.inspectLocalConfirmation("bbbbbbbb000000000000000000000000",src.text,recognized)==null);
   check(r.inspectLocalConfirmation(src.id,src.text,recognized-3000)==null);
   e=r.inspectLocalConfirmation(src.id,src.text,recognized);
   check(e!=null && e.cause.equals("local_confirmation") && e.original.equals(src.text));
   src.rowCount=2;src.reply="好的，已经为你打开视频";src.category="SUCC";
   check(r.inspectLocalConfirmation(src.id,src.text,recognized)!=null);
   r.noteConfirmationHandled(src.id);check(r.inspectLocalConfirmation(src.id,src.text,recognized)==null);
   System.out.println("PASS reader fences, provider race, opt-in, song cancellation, shared same-turn dedup, settle, and diagnostic redaction ("+n+" checks)");
 }
}'''

if __name__ == '__main__':
    jar = Path(os.environ.get('ANDROID_JSON_JAR', str(Path(tempfile.gettempdir()) / 'xingyi-android-json-0.0.20131108.vaadin1.jar')))
    with tempfile.TemporaryDirectory(prefix='xingyi-dialogs-') as folder:
        tmp = Path(folder)
        files = []
        for name, source in {**STUBS, 'ReaderBoundary.java': TEST}.items():
            path = tmp / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_text(source, encoding='utf8')
            files.append(path)
        java = [SRC / (name + '.java') for name in ('SharedTextDraft', 'ReadOnlyRefusalText', 'GenericRefusalText', 'XiaomiRefusalGate','XiaomiDialogReader')]
        subprocess.run(['javac','-encoding','UTF-8','-cp',str(jar),'-d',str(tmp),*map(str,[*files,*java,ROOT/'tests/XiaomiRefusalGateTest.java'])],check=True)
        for cls in ('XiaomiRefusalGateTest','ReaderBoundary'):
            subprocess.run(['java','-cp',str(tmp)+os.pathsep+str(jar),'xyz.erapi0neer.xingyi.'+cls],check=True)
