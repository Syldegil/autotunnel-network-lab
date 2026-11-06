package io.github.syldegil.autotunnel;

import java.util.*;

/** Exact-flow isolation contract. No CLI availability or safe deletion inferred from a successful listing. */
public final class ConntrackIsolation {
    public static final class Flow {
        public final String id, zone, source, destination, replySource, replyDestination, protocol;
        public final int sourcePort, destinationPort, replySourcePort, replyDestinationPort;
        public Flow(String id, String zone, String protocol, String source, int sourcePort, String destination, int destinationPort,
                    String replySource, int replySourcePort, String replyDestination, int replyDestinationPort) {
            if (!id.matches("[0-9]{1,10}") || !zone.matches("[0-9]{1,5}") || !Arrays.asList("tcp", "udp").contains(protocol)) throw new IllegalArgumentException();
            if (Long.parseLong(id) > 4294967295L || Integer.parseInt(zone) > 65535) throw new IllegalArgumentException();
            RoutingProfile.Family family = RoutingProfile.address(source);
            for (String address : Arrays.asList(destination, replySource, replyDestination))
                if (RoutingProfile.address(address) != family) throw new IllegalArgumentException();
            for (int port : new int[]{sourcePort, destinationPort, replySourcePort, replyDestinationPort})
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
            this.id = id; this.zone = zone; this.protocol = protocol; this.source = source; this.destination = destination;
            this.replySource = replySource; this.replyDestination = replyDestination; this.sourcePort = sourcePort;
            this.destinationPort = destinationPort; this.replySourcePort = replySourcePort; this.replyDestinationPort = replyDestinationPort;
        }
        public String identity() { return id + "/" + zone + "/" + protocol + "/" + source + "/" + sourcePort + "/" + destination + "/" + destinationPort
            + "/" + replySource + "/" + replySourcePort + "/" + replyDestination + "/" + replyDestinationPort; }
        public List<String> proposedDeletion() {
            return Arrays.asList("-D", "-f", source.contains(":") ? "ipv6" : "ipv4", "-p", protocol, "--id", id, "--zone", zone,
                "--orig-src", source, "--orig-dst", destination, "--sport", "" + sourcePort, "--dport", "" + destinationPort,
                "--reply-src", replySource, "--reply-dst", replyDestination, "--reply-port-src", "" + replySourcePort, "--reply-port-dst", "" + replyDestinationPort);
        }
    }
    public interface Adapter {
        boolean closedAndOffloadDrained(String generation);
        boolean completeInventory();
        List<Flow> read() throws Exception;
        void deleteExact(Flow flow) throws Exception;
    }
    /** Exact client addresses must be bound to current lease/interface generation outside conntrack. */
    public static int drain(Adapter adapter, String generation, Set<String> clients, Set<String> indispensable) throws Exception {
        if (clients.isEmpty()) throw new IllegalStateException("Client ownership unknown");
        for (String c : clients) RoutingProfile.address(c);
        if (!adapter.closedAndOffloadDrained(generation) || !adapter.completeInventory()) throw new IllegalStateException("Isolation unqualified");
        int count = 0;
        for (int pass = 0; pass < 3; pass++) {
            List<Flow> flows = adapter.read(); List<Flow> selected = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (Flow f : flows) {
                if (!ids.add(f.id + "/" + f.zone)) throw new IllegalStateException("Ambiguous conntrack ID");
                if (clients.contains(f.source) || clients.contains(f.destination) || clients.contains(f.replySource) || clients.contains(f.replyDestination)) {
                    if (indispensable.contains(f.identity())) throw new IllegalStateException("Selected flow conflicts with indispensable system transport");
                    selected.add(f);
                }
            }
            if (selected.isEmpty()) return count;
            for (Flow f : selected) {
                if (!adapter.closedAndOffloadDrained(generation) || !adapter.completeInventory()) throw new IllegalStateException("Generation/guard invalidated");
                adapter.deleteExact(f); count++;
            }
        }
        throw new IllegalStateException("Residual/new flows: keep closed");
    }
}
