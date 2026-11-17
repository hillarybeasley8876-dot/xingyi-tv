import xyz.erapi0neer.xingyi.LocalAdb;
import java.lang.reflect.*;
public class LocalAdbPowerStateTest {
    static Method parser;
    static void check(String dump, boolean sleep, boolean awake, boolean changing) throws Exception {
        Object state=parser.invoke(null,dump);
        String[] fields={"sleeping","awake","changing"};boolean[] expected={sleep,awake,changing};
        for(int i=0;i<fields.length;i++){Field f=state.getClass().getDeclaredField(fields[i]);f.setAccessible(true);if(f.getBoolean(state)!=expected[i])throw new AssertionError(fields[i]+" mismatch");}
    }
    public static void main(String[] args) throws Exception {
        parser=LocalAdb.class.getDeclaredMethod("parsePowerState",String.class);parser.setAccessible(true);
        check("  mWakefulness=Awake\n  mWakefulnessChanging=false\nDisplay Power: state=ON\n",false,true,false);
        check("  mWakefulness=Asleep\nDisplay Power: state=OFF\n",true,false,false);
        check("  mWakefulness=Dozing\nDisplay Power: state=DOZE\n",true,false,false);
        check("  mWakefulness=Awake\nDisplay Power: state=OFF\n",true,true,false);
        check("  mWakefulness=Awake\n  mWakefulnessChanging=true\nDisplay Power: state=ON\n",false,true,true);
        check("  mWakefulness=Dreaming\nDisplay Power: state=ON\n",false,false,false);
        check("Permission denied\n",false,false,false);
        check("last event mWakefulness=Awake\nDisplay Power: state=UNKNOWN\n",false,false,false);
        System.out.println("PASS: awake/asleep/dozing/display off/transition/dreaming/missing/reject loose match");
    }
}
