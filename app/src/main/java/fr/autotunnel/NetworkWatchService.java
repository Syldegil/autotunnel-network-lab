package io.github.syldegil.autotunnel;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
/** Independent observer: cannot start, stop, bind or replace a VPN. */
public final class NetworkWatchService extends Service {
    static volatile boolean running;
    static volatile ExitSnapshot latest;
    static volatile String publicIp="Non mesurée",publicIp6="Non mesurée",logStatus="";
    static volatile long observedAt, ipAt;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService probe=Executors.newSingleThreadExecutor();
    private volatile boolean closed,probing;
    private volatile HttpURLConnection connection;
    private volatile long generation;
    private String previousIdentity="",previousEvent="",previousLan="";
    private long nextProbe;
    static void autoStart(Context c){if(c.getSharedPreferences("network-watch",0).getBoolean("enabled",true))start(c);}
    static void start(Context c){c.getSharedPreferences("network-watch",0).edit().putBoolean("enabled",true).apply();c.startForegroundService(new Intent(c,NetworkWatchService.class));}
    @Override public void onCreate(){super.onCreate();running=true;
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("network-watch","Suivi de la sortie réseau",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,21,new Intent(this,InspectorActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,22,new Intent(this,NetworkWatchService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(21,new Notification.Builder(this,"network-watch").setSmallIcon(R.drawable.ic_shield).setContentTitle("Suivi DNS et IP publique").setContentText("Historique actif · indépendant de WireGuard").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Arrêter le suivi",stop).build()).build());
        log("Début du suivi · intervalle précédent non observé");
        worker.scheduleWithFixedDelay(this::tick,0,2,TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&"STOP".equals(i.getAction())){getSharedPreferences("network-watch",0).edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}return START_STICKY;}
    private void tick(){if(closed)return;try{
        ExitSnapshot s=ExitSnapshot.read(this);latest=s;long now=System.currentTimeMillis();if(observedAt>0 && now-observedAt>10000){generation++;publicIp="Mesure en cours…";publicIp6="Mesure en cours…";ipAt=0;nextProbe=0;log("Reprise après interruption des relevés");}observedAt=now;
        String state=s.identity+"|"+AutoService.running+"|"+wireguardState();
        if(!state.equals(previousIdentity)){generation++;previousIdentity=state;publicIp=s.connected?"Mesure en cours…":"Hors connexion";publicIp6=publicIp;ipAt=0;nextProbe=0;log("Changement de réseau ou de VPN");}
        if(!s.lan.equals(previousLan)){previousLan=s.lan;log("Adresses locales actualisées");}
        if(s.connected&&!probing&&SystemClock.elapsedRealtime()>=nextProbe){probing=true;nextProbe=SystemClock.elapsedRealtime()+30000;long token=generation;probe.execute(()->measure(s,token));}
    }catch(RuntimeException ignored){logStatus="Observation indisponible";}}
    private void measure(ExitSnapshot before,long token){
        String v4=query("https://api.ipify.org",false);
        String v6=closed?"Suivi arrêté":query("https://api6.ipify.org",true);
        if(!closed)try{worker.execute(()->{try{if(closed||token!=generation||!before.identity.equals(ExitSnapshot.read(this).identity))return;publicIp=v4;publicIp6=v6;ipAt=System.currentTimeMillis();log("Sortie Internet mesurée");}finally{probing=false;}});}catch(RejectedExecutionException ignored){}
    }
    private String query(String endpoint,boolean ipv6){HttpURLConnection request=null;
        try{request=(HttpURLConnection)new URL(endpoint).openConnection();connection=request;request.setConnectTimeout(4000);request.setReadTimeout(4000);request.setUseCaches(false);request.setInstanceFollowRedirects(false);
            if(request.getResponseCode()!=200)throw new IOException();try(InputStream in=request.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();int next;while(bytes.size()<129&&(next=in.read())!=-1)bytes.write(next);byte[] b=bytes.toByteArray();String value=new String(b,StandardCharsets.US_ASCII).trim();if(b.length>128||!validIp(value)||value.contains(":")!=ipv6)throw new IOException();return value;}
        }catch(Exception ignored){return "Indisponible (sonde HTTPS)";}finally{if(request!=null)request.disconnect();connection=null;}
    }
    static boolean validIp(String s){if(s==null||s.isEmpty()||s.length()>64||!s.matches("[0-9a-fA-F:.]+"))return false;try{if(!s.contains(":")){String[] p=s.split("\\.",-1);if(p.length!=4)return false;for(String n:p)if(n.isEmpty()||Integer.parseInt(n)>255)return false;}return InetAddress.getByName(s)!=null;}catch(Exception e){return false;}}
    static String wireguardState(){TunnelObservation t=TunnelObservation.latest;return t.fresh(System.currentTimeMillis())?InspectorMeaning.tunnel(true,t.state):AutoService.running?"État du tunnel inconnu":"Tunnel AutoTunnel non observé actif";}
    private void log(String event){ExitSnapshot s=latest;String route=(s==null?"Inconnu":s.route)+" · "+wireguardState()+" · automatisation "+(AutoService.running?"active":"arrêtée");String dns=s==null?"Inconnu":s.dns,lan=s==null?"":s.lan;String ips="IPv4 : "+publicIp+" · IPv6 : "+publicIp6;String eventIdentity=route+dns+ips+lan;
        if(eventIdentity.equals(previousEvent)&&!event.startsWith("Début")&&!event.startsWith("Fin"))return;
        if(NetworkLog.append(this,route,dns,ips,lan,event)){previousEvent=eventIdentity;logStatus="Historique enregistré sur ce téléphone";}else logStatus="Échec d’enregistrement du journal";
    }
    @Override public void onDestroy(){closed=true;running=false;generation++;worker.shutdownNow();probe.shutdownNow();HttpURLConnection c=connection;if(c!=null)c.disconnect();log("Fin du suivi · observations suspendues");latest=null;observedAt=0;publicIp="Suivi arrêté";publicIp6="Suivi arrêté";ipAt=0;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
