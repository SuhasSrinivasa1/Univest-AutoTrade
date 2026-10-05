package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Android-Keystore-backed secret store for Groww credentials.
 * Existing plaintext values are migrated lazily by AppPrefs.
 */
final class SecurePrefs {
    private static final String FILE = "secure_delivery_buy_prefs";
    private static final String ALIAS = "univest_autotrade_credentials_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private SecurePrefs() {}

    static String get(Context c, String key, String fallback) {
        try {
            String encoded = prefs(c).getString(key, "");
            if (encoded == null || encoded.isEmpty()) return fallback == null ? "" : fallback;
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            if (all.length < 13) return fallback == null ? "" : fallback;
            int ivLen = all[0] & 0xff;
            if (ivLen < 12 || 1 + ivLen >= all.length) return fallback == null ? "" : fallback;
            byte[] iv = new byte[ivLen];
            byte[] encrypted = new byte[all.length - 1 - ivLen];
            System.arraycopy(all, 1, iv, 0, ivLen);
            System.arraycopy(all, 1 + ivLen, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return fallback == null ? "" : fallback;
        }
    }

    static boolean put(Context c, String key, String value) {
        try {
            String clean = value == null ? "" : value.trim();
            if (clean.isEmpty()) {
                prefs(c).edit().remove(key).apply();
                return true;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[1 + iv.length + encrypted.length];
            all[0] = (byte)iv.length;
            System.arraycopy(iv, 0, all, 1, iv.length);
            System.arraycopy(encrypted, 0, all, 1 + iv.length, encrypted.length);
            prefs(c).edit().putString(key, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void remove(Context c, String key) {
        prefs(c).edit().remove(key).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey)existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
