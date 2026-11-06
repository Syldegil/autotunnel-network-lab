package io.github.syldegil.autotunnel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.github.syldegil.autotunnel.GraphModel.*;
import io.github.syldegil.autotunnel.GraphModel.Properties;
import io.github.syldegil.autotunnel.GraphModel.Policy;

public class MultibranchTest {
    private GraphCompiler.Result compiled(int template){GraphCompiler.Result r=GraphCompiler.compile(GraphTemplates.create(template));assertTrue(r.report(),r.valid());return r;}
    private GraphModel props(GraphModel g,String id,Properties p){Node n=g.node(id);return g.replace(new Node(n.id,n.kind,n.label,n.x,n.y,n.enabled,p));}
    @Test public void allSevenTemplatesCompileAndProduceRealTypedOperations()throws Exception{
        for(int i=0;i<7;i++){GraphCompiler.Result r=compiled(i);SimulatedNetworkBackend b=new SimulatedNetworkBackend(1000);NetworkPlan p=new NetworkEngine(b,new NetworkEngine.MemoryJournal()).preview(r.topology);assertFalse(p.guards.isEmpty());assertFalse(p.policy.isEmpty());assertEquals(0,b.mutationCount);assertFalse(p.preview().contains("wg set"));}
    }
    @Test public void threeSourcesConvergeWithSingleTunnelAndMultipleDestinations(){
        RoutingTopology t=compiled(4).topology;assertEquals(3,t.branches.size());Set<String> tunnels=new HashSet<>();for(RoutingTopology.Branch b:t.branches){tunnels.add(b.tunnel);assertEquals(2,b.destinations.size());assertTrue(b.nodes.contains("firewall"));}assertEquals(Collections.singleton("wg"),tunnels);
    }
    @Test public void mixedPoliciesKeepExplicitDirectSeparateOnTunnelFailure()throws Exception{
        SimulatedNetworkBackend b=new SimulatedNetworkBackend(1000);NetworkEngine e=new NetworkEngine(b,new NetworkEngine.MemoryJournal());NetworkPlan p=e.preview(compiled(6).topology);
        assertEquals(NetworkEngine.State.VPN_PROTECTED,e.apply(p,p.digest,1000));b.proof=false;e.event(NetworkEngine.Event.TUNNEL_LOST,1001);
        assertEquals(NetworkEngine.State.DIRECT,e.branchStates().get(RoutingProfile.Scope.PHONE));assertEquals(NetworkEngine.State.VPN_BLOCKED,e.branchStates().get(RoutingProfile.Scope.USB));assertEquals(NetworkEngine.State.VPN_BLOCKED,e.branchStates().get(RoutingProfile.Scope.WIFI));
        assertEquals(NetworkEngine.State.DIRECT,e.restore(true));assertEquals(0,b.ownedCount());assertEquals(3,b.foreignObjects.size());
    }
    @Test public void policyIntentsAndCapabilitiesArePerBranch(){
        RoutingTopology t=compiled(6).topology;Map<RoutingProfile.Scope,RoutePolicy.Intent> intentions=RoutePolicy.intents(t,RoutingProfile.Family.IPV4,Collections.emptyMap(),1000);
        assertEquals(RoutePolicy.Intent.DIRECT_REQUEST,intentions.get(RoutingProfile.Scope.PHONE));assertEquals(RoutePolicy.Intent.BLOCK_REQUIRED,intentions.get(RoutingProfile.Scope.USB));
        CapabilityMatrix m=new CapabilityMatrix();assertFalse(m.requirements(t).get(CapabilityMatrix.Path.PHONE_WIFI).contains(CapabilityMatrix.Capability.PHONE_IPV4_VPN));assertTrue(m.requirements(t).get(CapabilityMatrix.Path.USB).contains(CapabilityMatrix.Capability.TETHER_OFFLOAD_SAFE));assertFalse(m.permits(t,NetworkPlan.hash("x"),1000,true));
    }
    @Test public void allRequiredBranchesFailClosedAndRollbackIsOwned()throws Exception{
        SimulatedNetworkBackend b=new SimulatedNetworkBackend(1000);NetworkEngine e=new NetworkEngine(b,new NetworkEngine.MemoryJournal());NetworkPlan p=e.preview(compiled(5).topology);
        e.apply(p,p.digest,1000);e.event(NetworkEngine.Event.TUNNEL_LOST,1001);assertTrue(b.closed());for(NetworkEngine.State state:e.branchStates().values())assertEquals(NetworkEngine.State.VPN_BLOCKED,state);
        e.restore(true);assertEquals(0,b.ownedCount());assertEquals(3,b.foreignObjects.size());
    }
    @Test public void injectedFailureNeverOpensPartialTopology()throws Exception{
        SimulatedNetworkBackend b=new SimulatedNetworkBackend(1000);NetworkEngine e=new NetworkEngine(b,new NetworkEngine.MemoryJournal());NetworkPlan p=e.preview(compiled(4).topology);b.failAtMutation=p.guards.size()+4;
        assertEquals(NetworkEngine.State.VPN_BLOCKED,e.apply(p,p.digest,1000));assertTrue(b.closed());assertFalse(b.trace.stream().anyMatch(id->p.open.stream().anyMatch(op->op.id.equals(id))));e.restore(true);assertEquals(0,b.ownedCount());
    }
    @Test public void namespacesTablesPrioritiesAndOperationIdsAreDisjoint()throws Exception{
        NetworkPlan p=new NetworkEngine(new SimulatedNetworkBackend(1000),new NetworkEngine.MemoryJournal()).preview(compiled(4).topology);Set<String> ids=new HashSet<>(),tables=new HashSet<>(),priorities=new HashSet<>(),chains=new HashSet<>();
        for(List<NetworkPlan.Operation> list:Arrays.asList(p.guards,p.policy,p.open,p.close))for(NetworkPlan.Operation op:list){assertTrue("duplicate "+op.id,ids.add(op.id));if(op.apply.contains("-N"))assertTrue(chains.add(op.tool+":"+op.apply.get(op.apply.indexOf("-N")+1)));if(op.apply.contains("priority"))assertTrue(priorities.add(op.apply.get(0)+":"+op.apply.get(op.apply.indexOf("priority")+1)));if(op.apply.contains("table"))tables.add(op.apply.get(op.apply.indexOf("table")+1));}
        assertEquals(new HashSet<>(Arrays.asList("5014","5015")),tables);
        assertTrue(p.guards.stream().anyMatch(op->op.apply.contains("-g")&&op.apply.contains("AT16_U_G4I")));
        assertFalse(p.guards.stream().anyMatch(op->op.apply.contains("-I")&&op.apply.contains("AT16_P_G4I")));
    }
    @Test public void secondTunnelAndProtectedDirectBypassAreRejected(){
        GraphModel g=GraphTemplates.create(4);Node n=g.node("wg");g=g.replace(new Node("wg2",Kind.WIREGUARD,"second",20,20,true,n.properties));assertFalse(GraphCompiler.compile(g).valid());
        g=GraphTemplates.create(5).replace(new Link("bypass","required",Port.OUT_IP,"remote",Port.IN_IP,true,Properties.defaults()));assertFalse(GraphCompiler.compile(g).valid());
    }
    @Test public void conflictingRulesNeedDistinctPriorities(){
        RoutingProfile.Rule a=new RoutingProfile.Rule(RoutingProfile.Direction.OUT,RoutingProfile.Protocol.TCP,RoutingProfile.Family.IPV4,RoutingProfile.Path.PHONE,RoutingProfile.Action.ALLOW,443,443,"","",1,true);
        RoutingProfile.Rule b=new RoutingProfile.Rule(a.direction,a.protocol,a.family,a.path,RoutingProfile.Action.BLOCK,443,443,"","",1,true);
        try{new RoutingProfile(RoutingProfile.Scope.ALL,RoutingProfile.Mode.REQUIRE_TUNNEL,RoutingProfile.V6.BLOCK,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Arrays.asList(a,b));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void sharedFirewallRulesRemainBoundToTheirSource(){
        GraphModel g=GraphTemplates.create(4);RoutingProfile.Rule rule=new RoutingProfile.Rule(RoutingProfile.Direction.FORWARD,RoutingProfile.Protocol.TCP,RoutingProfile.Family.IPV4,RoutingProfile.Path.USB,RoutingProfile.Action.BLOCK,22,22,"","",10,true);
        g=g.profile(new RoutingProfile(RoutingProfile.Scope.ALL,g.profile.mode,g.profile.ipv6,g.profile.routes4,g.profile.routes6,g.profile.dns,Collections.singletonList(rule)));GraphCompiler.Result r=GraphCompiler.compile(g);assertTrue(r.report(),r.valid());
        for(RoutingTopology.Branch b:r.topology.branches)assertEquals(b.profile.scope==RoutingProfile.Scope.USB?1:0,b.profile.rules.size());
    }
    @Test public void ipv6PerBranchDoesNotInheritProtection(){
        GraphModel g=props(GraphTemplates.create(4),"usb",new Properties(RoutingProfile.Family.BOTH,Policy.REQUIRE_TUNNEL,true,"","","","",Collections.emptyList(),Collections.emptyList(),Collections.emptyList()));GraphCompiler.Result r=GraphCompiler.compile(g);assertTrue(r.report(),r.valid());
        for(RoutingTopology.Branch b:r.topology.branches){assertEquals(b.profile.scope==RoutingProfile.Scope.USB?RoutingProfile.V6.REQUIRE_TUNNEL:RoutingProfile.V6.BLOCK,b.profile.ipv6);assertEquals(RoutePolicy.Intent.BLOCK_REQUIRED,RoutePolicy.intent(b.profile,RoutingProfile.Family.IPV6,null,1000));}
    }
    @Test public void topologySurvivesCodecHistoryAndLayoutWithoutPolicyChanges()throws Exception{
        GraphModel g=GraphTemplates.create(6);GraphHistory h=new GraphHistory(g).change(g.layout().camera(100,40,.6f,ViewMode.PHYSICAL));GraphHistory restored=GraphHistory.decode(h.encode());assertEquals(compiled(6).report(),GraphCompiler.compile(restored.current).report());assertArrayEquals(GraphCodec.encode(g),GraphCodec.encode(restored.undo().current));assertArrayEquals(GraphCodec.encode(h.current),GraphCodec.encode(restored.undo().redo().current));
    }
    @Test public void differentFamilyOnSharedPathAndDanglingActiveBranchAreErrors(){
        GraphModel g=props(GraphTemplates.create(4),"usb",new Properties(RoutingProfile.Family.BOTH,Policy.REQUIRE_TUNNEL,true,"","","","",Collections.emptyList(),Collections.emptyList(),Collections.emptyList()));g=props(g,"firewall",new Properties(RoutingProfile.Family.IPV4,Policy.INHERIT,true,"","","","",Collections.emptyList(),Collections.emptyList(),Collections.emptyList()));assertFalse(GraphCompiler.compile(g).valid());assertFalse(GraphCompiler.compile(GraphTemplates.create(4).remove("usb_firewall")).valid());
    }
    @Test public void hardwareBackendCannotApplyCompositeEvenIfItClaimsMutationsEnabled()throws Exception{
        SimulatedNetworkBackend b=new SimulatedNetworkBackend(1000);NetworkPlan p=new NetworkEngine(b,new NetworkEngine.MemoryJournal()).preview(compiled(4).topology);
        NetworkBackend real=new NetworkBackend(){public NetworkSnapshot observe(){return b.snapshot;}public boolean simulated(){return false;}public boolean mutationsEnabled(){return true;}public void preflight(NetworkPlan p){fail();}public void execute(NetworkPlan.Operation o){fail();}public void undo(NetworkPlan.Operation o){fail();}public boolean present(NetworkPlan.Operation o){return false;}public boolean pathVerified(NetworkPlan p,long t){return true;}public void drainOwnedFlows(NetworkPlan p){fail();}public void adoptOwned(NetworkPlan p,Set<String> ids){fail();}};
        assertEquals(NetworkEngine.State.UNSUPPORTED,new NetworkEngine(real,new NetworkEngine.MemoryJournal()).apply(p,p.digest,1000));
    }
}
