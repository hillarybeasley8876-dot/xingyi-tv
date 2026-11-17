package xyz.erapi0neer.xingyi;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** In-process extension points. Callbacks are trusted app code; there are no model-supplied scripts. */
public final class HarnessHooks {
    public interface Hook {
        // Return a nonempty reason to reject a before_tool event. Other events are notifications.
        String onEvent(String event, JSONObject data) throws Exception;
    }
    private final List<Hook> hooks = new ArrayList<>();
    private final Context context;
    private final String turnId=UUID.randomUUID().toString();
    private final StringBuilder timeline=new StringBuilder();
    public HarnessHooks(Context c){context=c.getApplicationContext();}
    public void add(Hook hook){hooks.add(hook);}
    public synchronized String emit(String event,String tool,String state,JSONObject args) throws Exception {
        JSONObject data=new JSONObject().put("turn",turnId).put("event",event).put("tool",tool==null?"":tool).put("state",state==null?"":state).put("time",System.currentTimeMillis());
        JSONObject forHook=new JSONObject(data.toString());if(args!=null)forHook.put("arguments",args);String rejection=null;
        for(Hook hook:hooks){String reason=hook.onEvent(event,forHook);if(reason!=null&&!reason.isEmpty())rejection=reason;}
        timeline.append(event).append(tool==null||tool.isEmpty()?"":" · "+tool).append(state==null||state.isEmpty()?"":" · "+state).append('\n');
        // No prompt, screenshot, model reasoning, credentials or tool arguments are logged.
        File file=new File(context.getFilesDir(),"harness-audit.jsonl");
        if(file.length()>128*1024){File old=new File(context.getFilesDir(),"harness-audit.previous.jsonl");if(old.exists())old.delete();file.renameTo(old);}
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write((data.toString()+"\n").getBytes("UTF-8"));}
        return rejection;
    }
    public synchronized String timeline(){return timeline.toString();}
}


