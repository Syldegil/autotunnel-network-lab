package io.github.syldegil.autotunnel;

import java.net.InetAddress;
import java.util.Arrays;

final class IpPrefix {
    final byte[] bytes;
    final int bits;
    IpPrefix(String cidr) {
        RoutingProfile.Family f = cidr.contains(":") ? RoutingProfile.Family.IPV6 : RoutingProfile.Family.IPV4;
        RoutingProfile.cidr(cidr, f);
        String[] parts = cidr.split("/"); bits = Integer.parseInt(parts[1]);
        try { bytes = InetAddress.getByName(parts[0]).getAddress(); }
        catch (Exception e) { throw new IllegalArgumentException("Préfixe invalide"); }
        for (int i = bits; i < bytes.length * 8; i++) bytes[i / 8] &= ~(1 << (7 - i % 8));
    }
    boolean contains(IpPrefix other) {
        if (bytes.length != other.bytes.length || bits > other.bits) return false;
        for (int i = 0; i < bits; i++) if (((bytes[i / 8] ^ other.bytes[i / 8]) & (1 << (7 - i % 8))) != 0) return false;
        return true;
    }
    String network() {
        try { return InetAddress.getByAddress(Arrays.copyOf(bytes, bytes.length)).getHostAddress() + "/" + bits; }
        catch (Exception e) { throw new IllegalStateException(); }
    }
}
