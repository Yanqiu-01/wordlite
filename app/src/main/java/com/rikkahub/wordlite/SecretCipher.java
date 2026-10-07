package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AEAD settings payload; profile name/version bind ciphertext to its local setting. */
public final class SecretCipher {
    private SecretCipher() { }
    public static byte[] encrypt(SecretKey key, String name, String clear) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(("WordLite:1:" + name).getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV(), bytes = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
        byte[] packet = new byte[1 + iv.length + bytes.length]; packet[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packet, 1, iv.length); System.arraycopy(bytes, 0, packet, 1 + iv.length, bytes.length); return packet;
    }
    public static String decrypt(SecretKey key, String name, byte[] packet) throws Exception {
        if (packet.length < 29 || packet[0] != 12) throw new java.security.GeneralSecurityException("设置数据无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, packet, 1, 12));
        cipher.updateAAD(("WordLite:1:" + name).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(packet, 13, packet.length - 13), StandardCharsets.UTF_8);
    }
}
