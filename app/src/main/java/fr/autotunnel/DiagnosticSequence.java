package io.github.syldegil.autotunnel;

import java.io.*;
import java.net.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** One bounded diagnostic: DNS -> at most two TCP attempts -> authenticated TLS, never HTTP. */
final class DiagnosticSequence {
    enum Status { NOT_RUN, RUNNING, PASS, FAIL, TIMEOUT, CANCELLED, UNAVAILABLE }
    static final class Step {
        final String name; final Status status; final long millis; final String detail;
        Step(String name,Status status,long millis,String detail){this.name=name;this.status=status;this.millis=millis;this.detail=detail;}
    }
    static final class Result {
        final long at; final String target,origin; final List<Step> steps;
        Result(long at,String target,String origin,List<Step> steps){this.at=at;this.target=target;this.origin=origin;this.steps=Collections.unmodifiableList(new ArrayList<>(steps));}
        String describe(){StringBuilder b=new StringBuilder("Diagnostic ponctuel · ").append(new Date(at)).append("\nDestination : ").append(target).append("\nOrigine : ").append(origin);
            for(Step s:steps)b.append("\n\n").append(s.name).append(" · ").append(s.status).append(" · ").append(s.millis).append(" ms\n").append(s.detail);
            return b.append("\n\nTLS réussi = certificat et identité validés sur cette connexion ; aucune requête HTTP envoyée. Aucun autre hôte, chemin Freebox, profil professionnel ou protection anti-fuite qualifié.").toString();}
    }
    static final class Call implements AutoCloseable {
        private boolean cancelled, timedOut;
        private final List<AutoCloseable> resources=new ArrayList<>();
        synchronized <T extends AutoCloseable> T own(T resource) throws IOException {
            if(cancelled){try{resource.close();}catch(Exception ignored){}throw new InterruptedIOException("Cancelled");} resources.add(resource);return resource;
        }
        synchronized void check() throws InterruptedIOException {if(cancelled || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");}
        synchronized boolean cancelled(){return cancelled;}
        synchronized boolean timedOut(){return timedOut;}
        void timeout(){synchronized(this){timedOut=true;}close();}
        public void close(){List<AutoCloseable> copy; synchronized(this){cancelled=true;copy=new ArrayList<>(resources);resources.clear();} for(AutoCloseable r:copy)try{r.close();}catch(Exception ignored){}}
    }
    static final class Unavailable extends IOException { Unavailable(){super("Network unavailable or changed");} }
    interface Transport {
        String origin(); void check() throws IOException;
        List<InetAddress> resolve(String host,int timeout,Call call) throws Exception;
        Socket connect(InetAddress address,int port,int timeout,Call call) throws Exception;
        String tls(Socket socket,String host,int port,int timeout,Call call) throws Exception;
    }
    interface Observer {void update(Result result);}
    static Result run(DiagnosticTarget target,Transport transport,Call call,Observer observer) {
        long at=System.currentTimeMillis(), deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        List<Step> steps=new ArrayList<>();for(String name:new String[]{"DNS / adresse","TCP","TLS / certificat"})steps.add(new Step(name,Status.NOT_RUN,0,"Étape non exécutée"));
        int stage=0;long started=System.nanoTime();String origin=transport.origin();
        try {
            call.check();transport.check();
            steps.set(stage,new Step(steps.get(stage).name,Status.RUNNING,0,"Résolution sur le réseau sélectionné"));publish(at,target,origin,steps,observer);
            List<InetAddress> addresses=transport.resolve(target.host,budget(deadline,4000),call);call.check();transport.check();
            if(addresses.isEmpty())throw new UnknownHostException();
            steps.set(stage,new Step(steps.get(stage).name,Status.PASS,elapsed(started),(target.host.contains(":")||target.host.matches("[0-9.]+") ? "Adresse numérique ; aucune requête DNS" : addresses.size()+" adresse(s) résolue(s) ; au plus deux essayées")));publish(at,target,origin,steps,observer);
            stage=1;started=System.nanoTime();steps.set(stage,new Step("TCP",Status.RUNNING,0,"Connexion ponctuelle"));publish(at,target,origin,steps,observer);
            Socket socket=null;Exception last=null;int attempts=0;String address="";
            for(InetAddress candidate:addresses.subList(0,Math.min(2,addresses.size()))) {
                call.check();transport.check();attempts++;
                try{socket=transport.connect(candidate,target.port,budget(deadline,3000),call);address=candidate.getHostAddress();break;}
                catch(IOException e){last=e;call.check();transport.check();}
            }
            if(socket==null)throw last==null?new IOException():last;
            call.check();transport.check();
            steps.set(stage,new Step("TCP",Status.PASS,elapsed(started),"Destination "+address+":"+target.port+" · source "+socket.getLocalAddress().getHostAddress()+" · essais "+attempts));publish(at,target,origin,steps,observer);
            stage=2;started=System.nanoTime();steps.set(stage,new Step("TLS / certificat",Status.RUNNING,0,"Validation système du certificat et du nom"));publish(at,target,origin,steps,observer);
            String tls=transport.tls(socket,target.host,target.port,budget(deadline,4000),call);call.check();transport.check();
            steps.set(stage,new Step("TLS / certificat",Status.PASS,elapsed(started),tls));
        }catch(Exception e){Status status=call.timedOut()?Status.TIMEOUT:call.cancelled()||e instanceof InterruptedException||e instanceof InterruptedIOException&&!(e instanceof SocketTimeoutException)?Status.CANCELLED:e instanceof Unavailable?Status.UNAVAILABLE:e instanceof SocketTimeoutException||e instanceof TimeoutException?Status.TIMEOUT:Status.FAIL;
            steps.set(stage,new Step(steps.get(stage).name,status,elapsed(started),reason(e,status)));
        }finally{call.close();}
        return publish(at,target,origin,steps,observer);
    }
    private static Result publish(long at,DiagnosticTarget target,String origin,List<Step> steps,Observer observer){Result r=new Result(at,target.label(),origin,steps);observer.update(r);return r;}
    private static long elapsed(long from){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-from);}
    private static int budget(long deadline,int max)throws SocketTimeoutException{long left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(left<=0)throw new SocketTimeoutException();return (int)Math.min(left,max);}
    private static String reason(Exception e,Status status){
        if(status==Status.CANCELLED)return "Annulé ; aucune conclusion sur la destination";
        if(status==Status.UNAVAILABLE)return "Réseau absent ou changé ; aucun repli vers un autre réseau";
        if(status==Status.TIMEOUT)return "Délai dépassé ; ne prouve ni filtrage ni hôte hors ligne";
        for(Throwable c=e;c!=null;c=c.getCause())if(c instanceof CertificateException||c instanceof SSLPeerUnverifiedException)return "Certificat ou identité TLS refusé ; validation jamais désactivée";
        if(e instanceof UnknownHostException)return "Résolution sans réponse utilisable (nom absent ou erreur DNS)";
        if(e instanceof ConnectException)return "Connexion TCP refusée ou impossible";
        if(e instanceof SSLException)return "Négociation TLS échouée ; service ou protocole à vérifier";
        return "Échec de l’étape : "+e.getClass().getSimpleName();
    }
}
