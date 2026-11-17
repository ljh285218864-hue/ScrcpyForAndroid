package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Beta4 credential encryption at rest. The AES-GCM key remains non-exportable
 * in AndroidKeyStore; only ciphertext is saved to app-private preferences.
 *
 * Android backup is disabled in the manifest. Encryption is independent of
 * the Android VPN consent and does not affect system security indicators.
 */
final class DeviceSecretStore {
    private static final String PREF = "screensync_remote_v4";
    private static final String ALIAS = "screensync_v4_device_secrets_aes";
    private static final String PREFIX = "sealed_";
    private static final int TAG_BITS = 128;
    private static final int IV_SIZE = 12;

    private DeviceSecretStore() { }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    static boolean exists(Context context, String name) {
        SharedPreferences p = prefs(context);
        return p.contains(PREFIX + name) || p.contains(name);
    }

    static void write(Context context, String name, String value) throws Exception {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("empty secret");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        if (iv.length != IV_SIZE) throw new IllegalStateException("unsupported GCM IV size");
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
        String encoded = Base64.encodeToString(envelope, Base64.NO_WRAP);
        // A successful single commit atomically removes any earlier plaintext.
        if (!prefs(context).edit()
                .putString(PREFIX + name, encoded)
                .remove(name)
                .commit()) {
            throw new IllegalStateException("unable to save secured device credential");
        }
    }

    static String read(Context context, String name) throws Exception {
        SharedPreferences p = prefs(context);
        String envelope = p.getString(PREFIX + name, null);
        if (envelope == null) {
            // One-time migration for Beta4 builds issued before Keystore
            // hardening. Do not delete the legacy value if encryption fails.
            String legacy = p.getString(name, "");
            if (!legacy.isEmpty()) {
                write(context, name, legacy);
                return legacy;
            }
            return "";
        }
        byte[] payload = Base64.decode(envelope, Base64.DEFAULT);
        if (payload.length < IV_SIZE + TAG_BITS / 8)
            throw new SecurityException("invalid encrypted credential");
        byte[] iv = new byte[IV_SIZE];
        byte[] data = new byte[payload.length - IV_SIZE];
        System.arraycopy(payload, 0, iv, 0, IV_SIZE);
        System.arraycopy(payload, IV_SIZE, data, 0, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    static void remove(Context context, String name) {
        // A removed pairing must not leave its authentication secret behind.
        prefs(context).edit().remove(PREFIX + name).remove(name).commit();
    }

    static void clear(Context context) throws Exception {
        // Revoke server authorization first, then erase the local credentials.
        if (!prefs(context).edit().clear().commit())
            throw new IllegalStateException("could not clear local credentials");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
