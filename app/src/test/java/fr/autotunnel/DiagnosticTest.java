package io.github.syldegil.autotunnel;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
public class DiagnosticTest {
    @Test public void parsesDomainIpv4Ipv6AndPorts(){
        assertEquals("example.com",DiagnosticTarget.parse("EXAMPLE.com").host);assertEquals(443,DiagnosticTarget.parse("example.com").port);
        assertEquals(8443,DiagnosticTarget.parse("192.0.2.18:8443").port);assertEquals("::1",DiagnosticTarget.parse("[::1]:443").host);
        assertEquals("xn--bcher-kva.example",DiagnosticTarget.parse("bücher.example").host);
    }
    @Test public void rejectsCredentialsUrlsQueriesWhitespaceAndAmbiguousAddresses(){
        for(String s:new String[]{"", " example.com", "example.com\n", "https://example.com", "a@b", "a/b", "a?q", "a#x", "example.com:0", "a:65536", "::1", "[:::]", "[::1%wlan0]", "127.1", "012.0.0.1", "999.0.0.1", "a..b", "a.", "a:", "a\\b"}){
            try{DiagnosticTarget.parse(s);fail("accepted "+s);}catch(IllegalArgumentException expected){}
        }
    }
    static class Transport implements DiagnosticSequence.Transport {
        int dns,tcp,tls; Exception dnsError,tcpError,tlsError; boolean available=true;
        public String origin(){return "test";}
        public void check()throws IOException{if(!available)throw new DiagnosticSequence.Unavailable();}
        public List<InetAddress> resolve(String h,int timeout,DiagnosticSequence.Call c)throws Exception{dns++;if(dnsError!=null)throw dnsError;return Arrays.asList(InetAddress.getByAddress(new byte[]{127,0,0,1}),InetAddress.getByAddress(new byte[]{127,0,0,2}));}
        public Socket connect(InetAddress a,int p,int timeout,DiagnosticSequence.Call c)throws Exception{tcp++;if(tcpError!=null)throw tcpError;return c.own(new Socket(){public InetAddress getLocalAddress(){return InetAddress.getLoopbackAddress();}});}
        public String tls(Socket s,String h,int p,int timeout,DiagnosticSequence.Call c)throws Exception{tls++;if(tlsError!=null)throw tlsError;return "TLS validated (fixture)";}
    }
    private DiagnosticSequence.Result run(Transport t){return DiagnosticSequence.run(DiagnosticTarget.parse("example.com"),t,new DiagnosticSequence.Call(),r->{});}
    @Test public void successIsOrderedAndSnapshotImmutable(){Transport t=new Transport();List<DiagnosticSequence.Result> snapshots=new ArrayList<>();DiagnosticSequence.Result r=DiagnosticSequence.run(DiagnosticTarget.parse("example.com"),t,new DiagnosticSequence.Call(),snapshots::add);assertEquals(1,t.dns);assertEquals(1,t.tcp);assertEquals(1,t.tls);for(DiagnosticSequence.Step step:r.steps)assertEquals(DiagnosticSequence.Status.PASS,step.status);assertEquals(DiagnosticSequence.Status.RUNNING,snapshots.get(0).steps.get(0).status);}
    @Test public void dnsFailureSkipsTcpAndTls(){Transport t=new Transport();t.dnsError=new UnknownHostException();DiagnosticSequence.Result r=run(t);assertEquals(DiagnosticSequence.Status.FAIL,r.steps.get(0).status);assertEquals(0,t.tcp);assertEquals(0,t.tls);assertEquals(DiagnosticSequence.Status.NOT_RUN,r.steps.get(2).status);}
    @Test public void tcpTimeoutTriesAtMostTwoAndSkipsTls(){Transport t=new Transport();t.tcpError=new SocketTimeoutException();DiagnosticSequence.Result r=run(t);assertEquals(2,t.tcp);assertEquals(0,t.tls);assertEquals(DiagnosticSequence.Status.TIMEOUT,r.steps.get(1).status);}
    @Test public void certificateFailureNeverLooksLikeDnsFailure(){Transport t=new Transport();SSLHandshakeException e=new SSLHandshakeException("test");e.initCause(new CertificateException());t.tlsError=e;DiagnosticSequence.Result r=run(t);assertEquals(DiagnosticSequence.Status.PASS,r.steps.get(0).status);assertEquals(DiagnosticSequence.Status.PASS,r.steps.get(1).status);assertEquals(DiagnosticSequence.Status.FAIL,r.steps.get(2).status);assertTrue(r.steps.get(2).detail.contains("Certificat"));}
    @Test public void absentNetworkSendsNothing(){Transport t=new Transport();t.available=false;DiagnosticSequence.Result r=run(t);assertEquals(0,t.dns);assertEquals(DiagnosticSequence.Status.UNAVAILABLE,r.steps.get(0).status);}
    @Test public void changedNetworkStopsBeforeConnect(){Transport t=new Transport(){public List<InetAddress> resolve(String h,int ms,DiagnosticSequence.Call c)throws Exception{List<InetAddress>a=super.resolve(h,ms,c);available=false;return a;}};assertEquals(DiagnosticSequence.Status.UNAVAILABLE,run(t).steps.get(0).status);assertEquals(0,t.tcp);}
    @Test public void cancellationClosesResourcesAndRejectsLateRegistration()throws Exception{DiagnosticSequence.Call c=new DiagnosticSequence.Call();boolean[] closed={false,false};c.own(()->closed[0]=true);c.close();assertTrue(closed[0]);try{c.own(()->closed[1]=true);fail();}catch(InterruptedIOException expected){}assertTrue(closed[1]);Transport t=new Transport();DiagnosticSequence.Result r=DiagnosticSequence.run(DiagnosticTarget.parse("example.com"),t,c,x->{});assertEquals(0,t.dns);assertEquals(DiagnosticSequence.Status.CANCELLED,r.steps.get(0).status);}
    @Test public void deadlineIsTimeoutNotUserCancellation(){DiagnosticSequence.Call c=new DiagnosticSequence.Call();c.timeout();DiagnosticSequence.Result r=DiagnosticSequence.run(DiagnosticTarget.parse("example.com"),new Transport(),c,x->{});assertEquals(DiagnosticSequence.Status.TIMEOUT,r.steps.get(0).status);}
}
