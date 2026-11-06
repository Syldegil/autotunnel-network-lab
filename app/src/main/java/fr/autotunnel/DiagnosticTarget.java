package io.github.syldegil.autotunnel;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/** A single host and port, never a URL with credentials, path, query or fragment. */
final class DiagnosticTarget {
    final String host; final int port;
    private DiagnosticTarget(String host, int port) { this.host=host; this.port=port; }
    static DiagnosticTarget parse(String input) {
        if(input==null || input.isEmpty() || input.length()>300 || !input.equals(input.trim()) || input.matches(".*[\\s\\p{Cntrl}].*")) throw new IllegalArgumentException("Saisir un hôte ou hôte:port, sans espace.");
        if(input.contains("/") || input.contains("@") || input.contains("?") || input.contains("#") || input.contains("%") || input.contains("\\")) throw new IllegalArgumentException("Hôte uniquement : aucun chemin, identifiant ou paramètre.");
        String host=input; int port=443;
        if(input.startsWith("[")) {
            int end=input.indexOf(']'); if(end<0) throw new IllegalArgumentException("IPv6 entre crochets attendue.");
            host=input.substring(1,end);
            if(!host.contains(":") || !host.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException("IPv6 invalide.");
            if(end+1<input.length()) { if(input.charAt(end+1)!=':') throw new IllegalArgumentException("Port invalide."); port=port(input.substring(end+2)); }
            try { new URI("https://["+host+"]:"+port); } catch(Exception e) { throw new IllegalArgumentException("IPv6 invalide."); }
        } else {
            int colon=input.indexOf(':');
            if(colon>=0) { if(colon!=input.lastIndexOf(':')) throw new IllegalArgumentException("Entourer l’IPv6 de crochets."); host=input.substring(0,colon); port=port(input.substring(colon+1)); }
            try { host=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); } catch(Exception e) { throw new IllegalArgumentException("Nom d’hôte invalide."); }
            if(host.isEmpty() || host.length()>253 || host.endsWith(".")) throw new IllegalArgumentException("Nom d’hôte invalide.");
            for(String label:host.split("\\.",-1)) if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IllegalArgumentException("Nom d’hôte invalide.");
            if(host.matches("[0-9.]+")) { String[] octets=host.split("\\."); if(octets.length!=4) throw new IllegalArgumentException("IPv4 invalide."); for(String n:octets) if(n.length()>1&&n.startsWith("0") || Integer.parseInt(n)>255) throw new IllegalArgumentException("IPv4 invalide."); }
        }
        return new DiagnosticTarget(host,port);
    }
    private static int port(String raw) { if(!raw.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Port invalide."); int p=Integer.parseInt(raw); if(p<1||p>65535) throw new IllegalArgumentException("Port hors limites."); return p; }
    String label() { return (host.contains(":")?"["+host+"]":host)+":"+port; }
}
