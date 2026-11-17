"""Run actual SleepTimer/TimerReceiver state transitions with an in-memory Android boundary.
No device, network, real clock delay, or ADB operation is involved.
"""
from pathlib import Path
import subprocess, tempfile
BASE=Path(__file__).resolve().parents[1]
JDK=Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin')
STUBS={
'android/content/Context.java':'''package android.content; public abstract class Context {public static final String ALARM_SERVICE="alarm"; public abstract SharedPreferences getSharedPreferences(String n,int m);public abstract Object getSystemService(String n);public ContentResolver getContentResolver(){return new ContentResolver();}public abstract ComponentName startForegroundService(Intent i);}''',
'android/content/ContentResolver.java':'''package android.content; public class ContentResolver {}''',
'android/content/ComponentName.java':'''package android.content; public class ComponentName {}''',
'android/content/BroadcastReceiver.java':'''package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
'android/content/Intent.java':'''package android.content; import android.net.Uri;import java.util.*;public class Intent {public static final String ACTION_BOOT_COMPLETED="boot";public String action;public Uri data;public Class<?> cls;public Map<String,String> extras=new HashMap<>();public Intent(){}public Intent(Context c,Class<?> cl){cls=cl;}public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}public Intent setData(Uri d){data=d;return this;}public Intent putExtra(String k,String v){extras.put(k,v);return this;}public String getStringExtra(String k){return extras.get(k);}public String identity(){return action+"|"+data+"|"+cls;}}''',
'android/net/Uri.java':'''package android.net;public final class Uri {final String s;private Uri(String t){s=t;}public static Uri parse(String s){return new Uri(s);}public String toString(){return s;}}''',
'android/os/SystemClock.java':'''package android.os;public final class SystemClock {public static long now=1000000;public static long elapsedRealtime(){return now;}}''',
'android/provider/Settings.java':'''package android.provider;import android.content.ContentResolver;public class Settings {public static class Global {public static final String BOOT_COUNT="boot_count";public static int count=7;public static int getInt(ContentResolver c,String k,int d){return count;}}}''',
'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {String getString(String k,String d);long getLong(String k,long d);int getInt(String k,int d);boolean contains(String k);Editor edit();interface Editor {Editor putString(String k,String v);Editor putLong(String k,long v);Editor putInt(String k,int v);Editor remove(String k);boolean commit();}}''',
'android/app/PendingIntent.java':'''package android.app;import android.content.*;import java.util.*;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2,FLAG_NO_CREATE=4;static Map<String,PendingIntent> values=new HashMap<>();public final Intent intent;public boolean canceled;PendingIntent(Intent i){intent=i;}public static PendingIntent getBroadcast(Context c,int id,Intent i,int flags){String k=i.identity();PendingIntent p=values.get(k);if(p==null&&(flags&FLAG_NO_CREATE)==0){p=new PendingIntent(i);values.put(k,p);}return p;}public void cancel(){canceled=true;values.remove(intent.identity());}}''',
'android/app/AlarmManager.java':'''package android.app;public class AlarmManager {public static final int ELAPSED_REALTIME_WAKEUP=2;public PendingIntent last;public long due;public boolean fail;public void cancel(PendingIntent p){if(last==p)last=null;}public void setExactAndAllowWhileIdle(int type,long when,PendingIntent p){if(fail)throw new IllegalStateException("fake service failure");last=p;due=when;}}''',
'xyz/erapi0neer/xingyi/LocalAdb.java':'''package xyz.erapi0neer.xingyi;public class LocalAdb {public boolean canceled;public void cancel(){canceled=true;}}''',
'xyz/erapi0neer/xingyi/TimerService.java':'''package xyz.erapi0neer.xingyi;public class TimerService {}''',
'xyz/erapi0neer/xingyi/SleepTimerStateTest.java':r'''package xyz.erapi0neer.xingyi;
import android.content.*;import android.app.*;import android.os.*;import android.provider.Settings;import java.util.*;
public class SleepTimerStateTest {
 static int count;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;}
 static class Memory implements SharedPreferences {
  final Map<String,Object> map=new HashMap<>();
  public String getString(String k,String d){Object x=map.get(k);return x==null?d:(String)x;}
  public long getLong(String k,long d){Object x=map.get(k);return x==null?d:(Long)x;}
  public int getInt(String k,int d){Object x=map.get(k);return x==null?d:(Integer)x;}
  public boolean contains(String k){return map.containsKey(k);}
  public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();public Editor putString(String k,String v){changes.put(k,v);return this;}public Editor putLong(String k,long v){changes.put(k,v);return this;}public Editor putInt(String k,int v){changes.put(k,v);return this;}public Editor remove(String k){changes.put(k,null);return this;}public boolean commit(){for(Map.Entry<String,Object>e:changes.entrySet())if(e.getValue()==null)map.remove(e.getKey());else map.put(e.getKey(),e.getValue());return true;}};}
 }
 static class Fake extends Context {
  final Memory p=new Memory();final AlarmManager a=new AlarmManager();final List<Intent> starts=new ArrayList<>();
  public SharedPreferences getSharedPreferences(String n,int m){return p;}public Object getSystemService(String n){return a;}public ComponentName startForegroundService(Intent i){starts.add(i);return new ComponentName();}
 }
 public static void main(String[] ignored) {
  Fake c=new Fake();TimerReceiver receiver=new TimerReceiver();
  SleepTimer.schedule(c,1);Intent old=c.a.last.intent;String oldToken=old.getStringExtra(SleepTimer.EXTRA_TOKEN);
  check(SleepTimer.remainingMinutes(c)==1,"one minute display");
  receiver.onReceive(c,old);check(c.starts.isEmpty(),"early delivery ignored");
  SystemClock.now+=60000;receiver.onReceive(c,old);check(c.starts.size()==1,"due delivery forwarded");
  SleepTimer.cancel(c);check(!SleepTimer.claim(c,oldToken),"already queued service cannot claim after cancel");
  receiver.onReceive(c,old);check(c.starts.size()==1,"already queued broadcast ignored after cancel");
  SleepTimer.schedule(c,1);Intent first=c.a.last.intent;String firstToken=first.getStringExtra(SleepTimer.EXTRA_TOKEN);
  SystemClock.now+=60000;check(SleepTimer.claim(c,firstToken),"current timer claimed");
  check(!SleepTimer.claim(c,firstToken),"duplicate service cannot claim twice");
  LocalAdb running=new LocalAdb();check(SleepTimer.attach(c,firstToken,running),"attach active connection");
  SleepTimer.schedule(c,30);String second=c.p.getString("token","");long newDeadline=c.p.getLong("deadline",0);
  check(running.canceled,"reschedule cancels in-flight old connection");
  SleepTimer.finish(c,firstToken,"old failure");
  check(c.p.getLong("deadline",0)==newDeadline&&c.p.getString("state","").equals("scheduled"),"old worker cannot clear new timer or overwrite result");
  receiver.onReceive(c,first);check(c.starts.size()==1,"old delivery after replacement ignored");
  LocalAdb late=new LocalAdb();check(!SleepTimer.attach(c,firstToken,late)&&late.canceled,"late attach from replaced worker refused");
  check(!firstToken.equals(second),"replacement has a separate generation");
  try {SleepTimer.schedule(c,0);throw new AssertionError("bad interval accepted");}catch(IllegalArgumentException expected){}
  check(second.equals(c.p.getString("token","")),"invalid scheduling does not destroy active timer");
  SystemClock.now+=30*60000;check(SleepTimer.claim(c,second),"replacement can execute at its own due time");
  SystemClock.now+=120001;check(SleepTimer.getStatus(c).contains("未确认"),"stale running timer cannot show success after process death");
  LocalAdb current=new LocalAdb();check(SleepTimer.attach(c,second,current),"new active connection attached");
  SleepTimer.cancel(c);check(current.canceled,"cancel closes running connection");
  SleepTimer.finish(c,second,"done");check(SleepTimer.getStatus(c).equals("已取消定时休眠"),"late success cannot overwrite cancel result");
  SleepTimer.schedule(c,15);Intent beforeBoot=c.a.last.intent;Settings.Global.count++;
  SystemClock.now=0;receiver.onReceive(c,beforeBoot);check(c.starts.size()==1,"delivery from previous boot rejected");
  check(SleepTimer.remainingMinutes(c)==0,"previous boot timer not shown as active");
  receiver.onReceive(c,new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));check(!c.p.contains("deadline"),"boot clears persisted old deadline");
  c.a.fail=true;try {SleepTimer.schedule(c,1);throw new AssertionError("alarm failure hidden");}catch(IllegalStateException expected){}
  check(!c.p.contains("deadline")&&SleepTimer.getStatus(c).contains("失败"),"scheduler failure persisted without fake pending deadline");
  check(SleepTimer.currentStatus(c).contains("未设置"),"an old alarm error must not look like a failed query for today's schedule");
  receiver.onReceive(c,new Intent().setAction(SleepTimer.ACTION));check(c.starts.size()==1,"tokenless legacy delivery cannot launch service");
  System.out.println("PASS "+count+" timer state/race checks; no device operation.");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='xingyi_timer_state_') as d:
 root=Path(d).resolve();sources=[]
 for name,text in STUBS.items():
  file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text,encoding='utf-8');sources.append(str(file))
 sources += [str(BASE/'src/xyz/erapi0neer/xingyi'/name) for name in ('SleepTimer.java','TimerReceiver.java')]
 out=root/'classes';out.mkdir()
 subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-d',str(out),*sources],check=True)
 subprocess.run([str(JDK/'java.exe'),'-cp',str(out),'xyz.erapi0neer.xingyi.SleepTimerStateTest'],check=True)

