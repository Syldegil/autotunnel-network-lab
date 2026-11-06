package io.github.syldegil.autotunnel;

import java.util.*;
import java.util.regex.*;

/** Root-provisioned identity, bound to a signature-verified exact APK, never to client claims. */
public final class ClientIdentity {
    public final int uid;
    public final String apkSha256, certificateSha256;
    public ClientIdentity(int uid, String apkSha256, String certificateSha256) {
        if (uid < 10000 || uid >= 100000 || !apkSha256.matches("[0-9a-f]{64}") || !certificateSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid root provisioning");
        this.uid = uid; this.apkSha256 = apkSha256; this.certificateSha256 = certificateSha256;
    }
    public boolean packageMapping(int kernelUid, String packageList) {
        if (kernelUid != uid || packageList.length() > 524288) return false;
        int matches = 0;
        for (String line : packageList.split("\n")) {
            Matcher m = Pattern.compile("^package:([a-zA-Z0-9_.]+) uid:([0-9]{1,9})$").matcher(line.trim());
            if (!m.matches()) return false; // fail closed on unknown OEM format, not substring matches
            if (Integer.parseInt(m.group(2)) == uid) {
                if (!m.group(1).equals("io.github.syldegil.autotunnel")) return false;
                matches++;
            }
        }
        return matches == 1;
    }
    public String apkPath(String paths) {
        String value = paths.trim();
        if (!value.matches("package:/data/app/[a-zA-Z0-9_+./=~-]+/base\\.apk") || value.contains(".."))
            throw new SecurityException("Unknown or split APK path");
        return value.substring("package:".length());
    }
    public boolean apkMatches(String digest) { return apkSha256.equals(digest); }
}
