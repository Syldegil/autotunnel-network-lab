package io.github.syldegil.autotunnel;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClientIdentityTest {
    private final String apk = NetworkPlan.hash("signature verified APK"), cert = NetworkPlan.hash("approved certificate");
    @Test public void acceptsOnlyKernelUidMappedUniquelyToExactPackageAndArtifact() {
        ClientIdentity p = new ClientIdentity(10123, apk, cert);
        assertTrue(p.packageMapping(10123, "package:android uid:1000\npackage:io.github.syldegil.autotunnel uid:10123\n"));
        assertTrue(p.apkMatches(apk)); assertFalse(p.apkMatches(NetworkPlan.hash("modified apk")));
        assertFalse(p.packageMapping(10124, "package:io.github.syldegil.autotunnel uid:10123"));
        assertFalse(p.packageMapping(10123, "package:io.github.syldegil.autotunnel uid:10123\npackage:evil.app uid:10123"));
    }
    @Test public void rejectsUidReuseUnknownOutputDuplicateAndPackagePrefix() {
        ClientIdentity p = new ClientIdentity(10123, apk, cert);
        for (String list : new String[]{"", "package:evil.app uid:10123", "package:io.github.syldegil.autotunnel.fake uid:10123", "Permission denied",
            "package:io.github.syldegil.autotunnel uid:10123\npackage:io.github.syldegil.autotunnel uid:10123", "package:io.github.syldegil.autotunnel uid:10123\nunknown"}) assertFalse(p.packageMapping(10123, list));
    }
    @Test public void refusesSplitApkTraversalAndNonApplicationPaths() {
        ClientIdentity p = new ClientIdentity(10123, apk, cert);
        assertEquals("/data/app/~~token/io.github.syldegil.autotunnel-token/base.apk", p.apkPath("package:/data/app/~~token/io.github.syldegil.autotunnel-token/base.apk\n"));
        for (String s : new String[]{"package:/sdcard/base.apk", "package:/data/app/../evil/base.apk", "package:/data/app/a/base.apk\npackage:/data/app/a/split.apk", "$(id)"})
            try { p.apkPath(s); fail(); } catch (SecurityException expected) { }
    }
    @Test public void refusesOldProvisioningAndUnqualifiedAndroidUsers() {
        for (int uid : new int[]{0,2000,9999,110123}) try { new ClientIdentity(uid, apk, cert); fail(); } catch (IllegalArgumentException expected) { }
        try { new ClientIdentity(10123, "", cert); fail(); } catch (IllegalArgumentException expected) { }
    }
}
