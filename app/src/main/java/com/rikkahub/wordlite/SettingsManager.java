package com.rikkahub.wordlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** All API configuration stays encrypted locally; AndroidKeyStore key is never exported. */
public final class SettingsManager {
    private static final String ALIAS = "wordlite-api-settings-v1";
    private final SharedPreferences preferences;
    public SettingsManager(Context context) { preferences = context.getSharedPreferences("wordlite-secure-v1", Context.MODE_PRIVATE); }
    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    public void save(ApiConfig.Service service, ApiConfig config) throws Exception {
        config.validate(); byte[] payload = SecretCipher.encrypt(key(), service.name(), serialize(config));
        if (!preferences.edit().putString(service.name(), Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("设置保存失败");
    }
    public ApiConfig load(ApiConfig.Service service) throws Exception {
        String payload = preferences.getString(service.name(), ""); if (payload.isEmpty()) return new ApiConfig();
        return deserialize(SecretCipher.decrypt(key(), service.name(), Base64.decode(payload, Base64.NO_WRAP)));
    }
    /** Scan engines and the optional CORE key ride the same encrypted channel. */
    public void saveEngine(EngineSettings value) throws Exception {
        value.validate();
        byte[] payload = SecretCipher.encrypt(key(), "ENGINE", EngineSettings.serialize(value));
        if (!preferences.edit().putString("ENGINE", Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("设置保存失败");
    }
    public EngineSettings loadEngine() throws Exception {
        String payload = preferences.getString("ENGINE", "");
        if (payload.isEmpty()) return new EngineSettings();
        return EngineSettings.deserialize(SecretCipher.decrypt(key(), "ENGINE", Base64.decode(payload, Base64.NO_WRAP)));
    }
    public static String serialize(ApiConfig config) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("version", 1); value.put("url", config.url); value.put("key", config.key); value.put("method", config.method);
        value.put("mode", config.mode.name()); value.put("headers", config.headers); value.put("bodyTemplate", config.bodyTemplate);
        value.put("textField", config.textField); value.put("fileField", config.fileField);
        value.put("timeout", config.timeoutSeconds); value.put("retries", config.retries); value.put("mappings", config.mappings); value.put("terms", config.terms);
        value.put("rateFraction", config.rateFraction); value.put("codePointOffsets", config.codePointOffsets);
        return ApiJson.stringify(value);
    }
    public static ApiConfig deserialize(String text) {
        Object root = ApiJson.parse(text); if (!(root instanceof Map)) throw new IllegalArgumentException("设置格式无效");
        ApiConfig config = new ApiConfig();
        config.url = string(root, "url", ""); config.key = string(root, "key", ""); config.method = string(root, "method", "POST");
        config.mode = ApiConfig.Mode.valueOf(string(root, "mode", "JSON"));
        config.bodyTemplate = string(root, "bodyTemplate", config.bodyTemplate); config.textField = string(root, "textField", "text"); config.fileField = string(root, "fileField", "file");
        config.timeoutSeconds = integer(root, "timeout", 30); config.retries = integer(root, "retries", 0);
        config.rateFraction = Boolean.TRUE.equals(ApiJson.path(root, "rateFraction"));
        config.codePointOffsets = Boolean.TRUE.equals(ApiJson.path(root, "codePointOffsets"));
        maps(ApiJson.path(root, "headers"), config.headers); maps(ApiJson.path(root, "mappings"), config.mappings);
        Object terms = ApiJson.path(root, "terms"); if (terms instanceof List) for (Object term : (List<?>) terms) if (term instanceof String) config.terms.add((String) term);
        return config;
    }
    private static String string(Object root, String path, String fallback) {
        Object value = ApiJson.path(root, path); return value instanceof String ? (String) value : fallback;
    }
    private static int integer(Object root, String path, int fallback) {
        Object value = ApiJson.path(root, path); return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static void maps(Object object, Map<String, String> target) {
        if (!(object instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) if (entry.getValue() instanceof String) target.put(String.valueOf(entry.getKey()), (String) entry.getValue());
    }
}
