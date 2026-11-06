package io.github.syldegil.autotunnel;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Private, atomic, bounded change journal. Never reads a VPN profile or key. */
final class NetworkLog {
    static final int LIMIT=500;
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getNoBackupFilesDir(),"network-changes.json"));}
    static synchronized JSONArray read(Context c){try{return new JSONArray(new String(file(c).readFully(),StandardCharsets.UTF_8));}catch(Exception e){return new JSONArray();}}
    static synchronized boolean append(Context c,String route,String dns,String ip,String lan,String event){
        AtomicFile file=file(c);FileOutputStream out=null;
        try{JSONArray old=read(c),next=new JSONArray();for(int i=Math.max(0,old.length()-LIMIT+1);i<old.length();i++)next.put(old.get(i));
            next.put(new JSONObject().put("at",System.currentTimeMillis()).put("event",event).put("route",route).put("dns",dns).put("ip",ip).put("addresses",lan));
            out=file.startWrite();out.write(next.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);return true;
        }catch(Exception e){if(out!=null)file.failWrite(out);return false;}
    }
    static synchronized void clear(Context c){file(c).delete();}
    static String display(Context c){JSONArray a=read(c);StringBuilder b=new StringBuilder();for(int i=a.length()-1;i>=0;i--){JSONObject o=a.optJSONObject(i);if(o==null)continue;b.append(new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss Z",java.util.Locale.FRANCE).format(new java.util.Date(o.optLong("at")))).append(" · ").append(o.optString("event")).append("\n").append(o.optString("route")).append("\nDNS : ").append(o.optString("dns")).append("\nIP publique : ").append(o.optString("ip")).append("\nAdresses : ").append(o.optString("addresses")).append("\n\n");}return b.length()==0?"Aucun changement enregistré.":b.toString();}
}
