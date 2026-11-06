package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class IsolationRecoveryTest {
    private ConntrackIsolation.Flow flow(String id,String source) { return new ConntrackIsolation.Flow(id,"0","tcp",source,45000,"203.0.113.1",443,"203.0.113.1",443,source,45000); }
    private static final class Kernel implements ConntrackIsolation.Adapter {
        final List<ConntrackIsolation.Flow> flows = new ArrayList<>(); boolean closed = true, complete = true, delete = true;
        public boolean closedAndOffloadDrained(String generation) { return closed && generation.equals("epoch1"); }
        public boolean completeInventory() { return complete; }
        public List<ConntrackIsolation.Flow> read() { return new ArrayList<>(flows); }
        public void deleteExact(ConntrackIsolation.Flow f) { if (delete) flows.remove(f); }
    }
    @Test public void drainsExactClientTuplesPreservesSystemAndIsIdempotent() throws Exception {
        Kernel k = new Kernel(); k.flows.add(flow("1","198.18.2.9")); k.flows.add(flow("2","192.0.2.1"));
        assertEquals(1,ConntrackIsolation.drain(k,"epoch1",Collections.singleton("198.18.2.9"),Collections.emptySet()));
        assertEquals("2",k.flows.get(0).id);
        assertEquals(0,ConntrackIsolation.drain(k,"epoch1",Collections.singleton("198.18.2.9"),Collections.emptySet()));
    }
    @Test public void incompleteOrUnfrozenInventoryCannotDeleteAnything() throws Exception {
        for (int i=0;i<2;i++) { Kernel k=new Kernel();k.flows.add(flow("1","198.18.2.9"));if(i==0)k.closed=false;else k.complete=false;
            try { ConntrackIsolation.drain(k,"epoch1",Collections.singleton("198.18.2.9"),Collections.emptySet());fail(); } catch(IllegalStateException expected) { }
            assertEquals(1,k.flows.size()); }
    }
    @Test public void indispensableAndAmbiguousFlowsRefuseBeforeDeletion() throws Exception {
        Kernel k=new Kernel(); ConntrackIsolation.Flow f=flow("1","198.18.2.9");k.flows.add(f);
        try { ConntrackIsolation.drain(k,"epoch1",Collections.singleton(f.source),Collections.singleton(f.identity()));fail(); }catch(IllegalStateException expected){}
        k.flows.add(flow("1","192.0.2.1"));
        try { ConntrackIsolation.drain(k,"epoch1",Collections.singleton(f.source),Collections.emptySet());fail(); }catch(IllegalStateException expected){}
        assertEquals(2,k.flows.size());assertFalse(f.proposedDeletion().contains("-F"));assertTrue(f.proposedDeletion().contains("--reply-dst"));
    }
    @Test public void failedDeletionCannotCompleteIsolation() throws Exception {
        Kernel k=new Kernel();k.flows.add(flow("1","198.18.2.9"));k.delete=false;
        try { ConntrackIsolation.drain(k,"epoch1",Collections.singleton("198.18.2.9"),Collections.emptySet());fail(); }catch(IllegalStateException expected){}
        assertTrue(k.closed);assertEquals(1,k.flows.size());
    }
    @Test public void incomingNatFlowIsSelectedByReplyClientAddress() throws Exception {
        Kernel k=new Kernel();k.flows.add(new ConntrackIsolation.Flow("1","0","tcp","203.0.113.9",45000,"192.0.2.1",443,"198.18.2.9",443,"203.0.113.9",45000));
        assertEquals(1,ConntrackIsolation.drain(k,"epoch1",Collections.singleton("198.18.2.9"),Collections.emptySet()));
    }
    @Test public void firstPacketRequiresPreNetworkDualStackAndNewInterfaceAcknowledgement() {
        BootAdmission b=new BootAdmission("boot1");b.interfaceCreated("usb:7");
        b.earlyBarrier("boot1",false,true,true,true);b.barrierAcknowledged("usb:7");assertFalse(b.mayOpen("usb:7",true,true));
        b.earlyBarrier("boot1",true,true,true,true);b.interfaceCreated("usb:7");b.barrierAcknowledged("usb:7");assertTrue(b.mayOpen("usb:7",true,true));
        b.interfaceCreated("usb:8");assertFalse(b.mayOpen("usb:8",true,true));b.invalidate();assertFalse(b.mayOpen("usb:7",true,true));
    }
    @Test public void wrongBootIpv6OrBypassCannotQualifyEarlyGuard() {
        for(int i=0;i<3;i++){BootAdmission b=new BootAdmission("boot1");b.earlyBarrier(i==0?"old":"boot1",true,true,i!=1,i!=2);
            b.interfaceCreated("wifi:10");b.barrierAcknowledged("wifi:10");assertFalse(b.mayOpen("wifi:10",true,true));}
    }
    @Test public void offloadRestorePreservesAbsentValueIsIdempotentAndDetectsForeignChange() throws Exception {
        RecoveryLedger.Entry e=new RecoveryLedger.Entry(RecoveryLedger.Kind.OFFLOAD_SETTING,"tether_offload_disabled",null,"1");
        final Map<String,String> values=new HashMap<>();values.put(e.resource,"1"); final int[] writes={0};
        RecoveryLedger.Adapter a=new RecoveryLedger.Adapter(){public String current(RecoveryLedger.Entry x){return values.get(x.resource);}
            public void restoreExact(RecoveryLedger.Entry x){values.put(x.resource,x.before);writes[0]++;}public void checkpoint(RecoveryLedger.Entry x){}};
        RecoveryLedger.restore(Collections.singletonList(e),a,true);RecoveryLedger.restore(Collections.singletonList(e),a,true);assertEquals(1,writes[0]);assertNull(values.get(e.resource));
        values.put(e.resource,"0");try{RecoveryLedger.restore(Collections.singletonList(e),a,true);fail();}catch(IllegalStateException expected){}assertEquals("0",values.get(e.resource));
    }
}
