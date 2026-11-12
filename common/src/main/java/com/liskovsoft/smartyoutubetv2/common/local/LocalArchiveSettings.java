package com.liskovsoft.smartyoutubetv2.common.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.RequiresApi;

import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager;

import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device connection settings and active-profile Local Library permission. */
public final class LocalArchiveSettings {
    private static final String PREFS = "local_archive_settings";
    private static final String KEY_ALIAS = "smarttube-local-archive-credentials-v1";
    private static final String KEY_HOST = "host";
    private static final String KEY_SHARE = "share";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_USERNAME = "username_enc";
    private static final String KEY_PASSWORD = "password_enc";
    private static final String KEY_LIBRARY_ENABLED_PREFIX = "library_enabled_";
    private final SharedPreferences prefs;

    public static final class Config {
        public final String host;
        public final String share;
        public final String archiveFolder;
        public final String username;
        public final String password;

        Config(String host, String share, String archiveFolder, String username, String password) {
            this.host = host;
            this.share = share;
            this.archiveFolder = archiveFolder;
            this.username = username;
            this.password = password;
        }

        public boolean isReady() {
            return !host.trim().isEmpty() && !share.trim().isEmpty() &&
                    !archiveFolder.trim().isEmpty() && !username.trim().isEmpty();
        }
    }

    public LocalArchiveSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Config getConfig() {
        return new Config(prefs.getString(KEY_HOST, "192.168.1.1"),
                prefs.getString(KEY_SHARE, "volume(sda1)"),
                prefs.getString(KEY_FOLDER, "YouTube-Local"),
                decrypt(prefs.getString(KEY_USERNAME, "")),
                decrypt(prefs.getString(KEY_PASSWORD, "")));
    }

    public void setHost(String value) {
        prefs.edit().putString(KEY_HOST, clean(value)).apply();
    }

    public void setShare(String value) {
        prefs.edit().putString(KEY_SHARE, clean(value)).apply();
    }

    public void setArchiveFolder(String value) {
        prefs.edit().putString(KEY_FOLDER, clean(value)).apply();
    }

    public void setUsername(String value) {
        prefs.edit().putString(KEY_USERNAME, encrypt(clean(value))).apply();
    }

    public void setPassword(String value) {
        prefs.edit().putString(KEY_PASSWORD, encrypt(value == null ? "" : value)).apply();
    }

    public void clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply();
    }

    public boolean isLibraryEnabledForActiveProfile() {
        return prefs.getBoolean(KEY_LIBRARY_ENABLED_PREFIX + activeProfileKey(), false);
    }

    public void setLibraryEnabledForActiveProfile(boolean enabled) {
        prefs.edit().putBoolean(KEY_LIBRARY_ENABLED_PREFIX + activeProfileKey(), enabled).apply();
    }

    private String activeProfileKey() {
        String activeId = LocalProfileManager.instance().getActiveId();
        if (activeId == null || activeId.isEmpty()) activeId = "default";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(activeId.getBytes(Charset.forName("UTF-8")));
            return Base64.encodeToString(digest, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            return Integer.toHexString(activeId.hashCode());
        }
    }

    private String encrypt(String value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalArgumentException("Encrypted SMB credentials require Android 6.0 or newer");
        }
        return Api23.encrypt(value);
    }

    private String decrypt(String value) {
        if (value == null || value.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "";
        return Api23.decrypt(value);
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static final class Api23 {
        private static String encrypt(String value) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                byte[] encrypted = cipher.doFinal(value.getBytes(Charset.forName("UTF-8")));
                byte[] iv = cipher.getIV();
                byte[] packed = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, packed, 0, iv.length);
                System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
                return Base64.encodeToString(packed, Base64.NO_WRAP);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to protect SMB credentials on this device", e);
            }
        }

        private static String decrypt(String value) {
            if (value == null || value.isEmpty()) return "";
            try {
                byte[] packed = Base64.decode(value, Base64.NO_WRAP);
                int ivLength = 12;
                byte[] iv = new byte[ivLength];
                byte[] encrypted = new byte[packed.length - ivLength];
                System.arraycopy(packed, 0, iv, 0, ivLength);
                System.arraycopy(packed, ivLength, encrypted, 0, encrypted.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(encrypted), Charset.forName("UTF-8"));
            } catch (Exception e) {
                return "";
            }
        }

        private static SecretKey getOrCreateKey() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

}
