package io.github.syldegil.autotunnel;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import com.wireguard.config.Config;

final class Vault {
    private static final String ALIAS = "autotunnel-profile";
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (javax.crypto.SecretKey) ks.getKey(ALIAS, null);
    }
    static File file(Context c) { return new File(c.getNoBackupFilesDir(), "profile.enc"); }
    static void save(Context c, Config config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(config.toWgQuickString().getBytes(StandardCharsets.UTF_8));
        AtomicFile atomic = new AtomicFile(file(c)); FileOutputStream out = atomic.startWrite();
        try { out.write(cipher.getIV()); out.write(encrypted); atomic.finishWrite(out); }
        catch (Exception e) { atomic.failWrite(out); throw e; }
    }
    static Config load(Context c) throws Exception {
        byte[] bytes = new AtomicFile(file(c)).readFully();
        if (bytes.length < 28) throw new IOException("Invalid profile");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
        return Config.parse(new ByteArrayInputStream(cipher.doFinal(bytes, 12, bytes.length - 12)));
    }
}
