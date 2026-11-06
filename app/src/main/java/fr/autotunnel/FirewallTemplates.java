package io.github.syldegil.autotunnel;

import static io.github.syldegil.autotunnel.RoutingProfile.*;

public final class FirewallTemplates {
    private FirewallTemplates() { }
    public static Rule homeDns(Path path, Protocol protocol, int priority, String dnsAddress) {
        if (protocol != Protocol.TCP && protocol != Protocol.UDP) throw new IllegalArgumentException();
        return new Rule(Direction.OUT, protocol, Family.IPV4, path, Action.ALLOW, 53, 53, "", dnsAddress + "/32", priority, true);
    }
    public static Rule blockPort(Path path, Protocol protocol, int first, int last, int priority) {
        return new Rule(Direction.OUT, protocol, Family.BOTH, path, Action.BLOCK, first, last, "", "", priority, true);
    }
    // Mandatory VPN, LAN access and IPv6 block are generated as routing gates before user rules,
    // not insecure ALLOW/! -o rules that could be bypassed by a higher user priority.
}
