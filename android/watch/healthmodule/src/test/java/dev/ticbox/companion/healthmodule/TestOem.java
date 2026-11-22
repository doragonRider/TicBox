package dev.ticbox.companion.healthmodule;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
public final class TestOem {
 public static Application app;
 public static final List<String> calls = new ArrayList<>();
 public static boolean failAlarm, dropDbWrites;
 public static Point sleepPoint, wakePoint;
 public static int dbWrites;
 public static void reset(Application application) { app=application; calls.clear(); failAlarm=false; dropDbWrites=false; sleepPoint=null; wakePoint=null; dbWrites=0; app.getSharedPreferences("healthy",0).edit().clear().commit(); app.getSharedPreferences("sleep_settings",0).edit().clear().commit(); }
 public static boolean bool(Context c,String key,boolean fallback){ return c.getSharedPreferences("healthy",0).getBoolean(key,fallback); }
 public static String text(Context c,String key,String fallback){ return c.getSharedPreferences("healthy",0).getString(key,fallback); }
 public static void healthy(Context c,String key,Object value,String setter){ calls.add(setter); SharedPreferences.Editor e=c.getSharedPreferences("healthy",0).edit(); if(value instanceof Boolean)e.putBoolean(key,(Boolean)value);else e.putString(key,(String)value);e.apply(); }
 public static void alarm(String name){ calls.add(name); if(failAlarm)throw new IllegalStateException("fixture"); }
 public static int minutes(String t){return Integer.parseInt(t.substring(0,2))*60+Integer.parseInt(t.substring(3));}
 public static int duration(String a,String b){int d=minutes(b)-minutes(a);return d<=0?d+1440:d;}
 public static boolean compatible(String a,String b,String c,String d){int s=minutes(a),e=s+duration(a,b),s2=minutes(c),e2=s2+duration(c,d);for(int shift:new int[]{-1440,0,1440})if(Math.max(s,s2+shift)<Math.min(e,e2+shift))return false;return true;}
 public static final class Pref {private final SharedPreferences p;public Pref(Context c){p=c.getSharedPreferences("sleep_settings",0);}public Object b(String k,Object fallback){return p.contains(k)?p.getAll().get(k):fallback;}public void c(String k,Object v){calls.add("pref:"+k);SharedPreferences.Editor e=p.edit();if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else e.putString(k,(String)v);e.apply();}public SharedPreferences d(){return p;}}
 public static final class Point {private final long value;public Point(long value){this.value=value;}public float g(){return value;}}
 public static class Storage {private final Handler worker=new Handler(Looper.getMainLooper());public Handler N(){return worker;}public void R(long now,long sleep,long wake){calls.add("db_enqueue");dbWrites++;worker.post(()->{if(!dropDbWrites)sleepPoint=new Point(sleep);});worker.post(()->{if(!dropDbWrites)wakePoint=new Point(wake);});}public Point A(long from,long to){return sleepPoint;}public Point z(long from,long to){return wakePoint;}}
}
