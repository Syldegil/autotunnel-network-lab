package io.github.syldegil.autotunnel;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

/** No hidden API. PackageManager CLI is an OEM-dependent read-only adapter; unknown output denies access. */
final class InstalledClientVerifier {
    private static String packages() throws Exception {
        return checked(Arrays.asList("package", "list", "packages", "-U", "--user", "0"));
    }
    private static String paths() throws Exception { return checked(Arrays.asList("package", "path", "--user", "0", "io.github.syldegil.autotunnel")); }
    private static String checked(List<String> args) throws Exception {
        RootNetworkBackend.Result r = DeviceCommandRunner.command("/system/bin/cmd", args);
        if (r.code != 0) throw new SecurityException(); return r.output;
    }
    static boolean verify(int kernelUid, ClientIdentity pin) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 27) return false; // Atomic CLOEXEC required for privileged authentication.
            if (!pin.packageMapping(kernelUid, packages())) return false;
            String path = pin.apkPath(paths()); File apk = new File(path);
            if (!apk.getCanonicalPath().equals(path) || !OsConstants.S_ISREG(Os.lstat(path).st_mode)) return false;
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); long size = 0;
            StructStat before, after;
            try (FileInputStream in = new FileInputStream(Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0))) {
                before = Os.fstat(in.getFD());
                if (!OsConstants.S_ISREG(before.st_mode) || (before.st_mode & 0022) != 0
                    || before.st_uid != 0 && before.st_uid != 1000) return false;
                byte[] bytes = new byte[32768]; int n;
                while ((n = in.read(bytes)) >= 0) { size += n; if (size > 134217728) return false; hash.update(bytes, 0, n); }
                after = Os.fstat(in.getFD());
            }
            StructStat current = Os.lstat(path);
            if (size != before.st_size || after.st_size != before.st_size || after.st_mtime != before.st_mtime
                || current.st_dev != before.st_dev || current.st_ino != before.st_ino || !OsConstants.S_ISREG(current.st_mode)) return false;
            StringBuilder digest = new StringBuilder(); for (byte b : hash.digest()) digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            return pin.apkMatches(digest.toString()) && path.equals(pin.apkPath(paths())) && pin.packageMapping(kernelUid, packages());
        } catch (Exception ignored) { return false; }
    }
}
