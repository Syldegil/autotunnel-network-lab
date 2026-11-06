package io.github.syldegil.autotunnel;

import android.content.Context;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Captures the current app network. Never binds the process or falls back to another network. */
final class AndroidDiagnosticTransport implements DiagnosticSequence.Transport {
    private final ConnectivityManager manager; private final Network network; private final String origin;
    AndroidDiagnosticTransport(Context context) {
        manager=context.getSystemService(ConnectivityManager.class);network=manager.getActiveNetwork();
        LinkProperties lp=network==null?null:manager.getLinkProperties(network);
        origin="API Android · UID "+android.os.Process.myUid()+" · réseau "+network+" · interface "+(lp==null?"indisponible":lp.getInterfaceName());
    }
    public String origin(){return origin;}
    public void check()throws IOException{if(network==null||!network.equals(manager.getActiveNetwork())||manager.getNetworkCapabilities(network)==null)throw new DiagnosticSequence.Unavailable();}
    public List<InetAddress> resolve(String host,int timeout,DiagnosticSequence.Call call)throws Exception{
        check();call.check();
        if(host.contains(":")||host.matches("[0-9.]+"))return Collections.singletonList(InetAddress.getByName(host)); // strictly validated numeric input, no lookup
        if(Build.VERSION.SDK_INT<29)throw new DiagnosticSequence.Unavailable();
        CompletableFuture<List<InetAddress>> result=new CompletableFuture<>();CancellationSignal signal=new CancellationSignal();
        call.own(()->{signal.cancel();result.completeExceptionally(new InterruptedIOException("Cancelled"));});
        DnsResolver.getInstance().query(network,host,DnsResolver.FLAG_EMPTY,Runnable::run,signal,new DnsResolver.Callback<List<InetAddress>>() {
            public void onAnswer(List<InetAddress> answer,int rcode){if(rcode!=0||answer.isEmpty())result.completeExceptionally(new UnknownHostException());else result.complete(new ArrayList<>(answer));}
            public void onError(DnsResolver.DnsException error){result.completeExceptionally(new UnknownHostException());}
        });
        try{return result.get(timeout,TimeUnit.MILLISECONDS);}catch(ExecutionException e){throw (Exception)e.getCause();}finally{signal.cancel();}
    }
    public Socket connect(InetAddress address,int port,int timeout,DiagnosticSequence.Call call)throws Exception{
        check();Socket socket=call.own(new Socket());
        try{network.bindSocket(socket);socket.connect(new InetSocketAddress(address,port),timeout);return socket;}catch(Exception e){socket.close();throw e;}
    }
    public String tls(Socket socket,String host,int port,int timeout,DiagnosticSequence.Call call)throws Exception{
        check();SSLSocket tls=call.own((SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,port,true));
        tls.setSoTimeout(timeout);SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();
        return tls.getSession().getProtocol()+" · chaîne de confiance et identité "+host+" validées ; HTTP non testé";
    }
}
