package dev.textagent.core;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Encrypts durable state; this is storage encryption, not LLM data masking. */
public final class StateCipher {
    private final byte[] key;
    public StateCipher(String base64Key) {
        try { key = Base64.getDecoder().decode(base64Key); }
        catch (Exception e) { throw new IllegalArgumentException("State encryption requires a base64 AES-256 key"); }
        if (key.length != 32) throw new IllegalArgumentException("State encryption requires a 32-byte key");
    }
    public String ownerHash(String owner) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(("owner:" + owner).getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public String encrypt(String id, byte[] plaintext) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8)); byte[] encrypted = cipher.doFinal(plaintext);
            byte[] output = new byte[12 + encrypted.length]; System.arraycopy(nonce,0,output,0,12); System.arraycopy(encrypted,0,output,12,encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public byte[] decrypt(String id, String text) {
        try {
            byte[] data = Base64.getDecoder().decode(text);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(data,0,12)));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(data,12,data.length-12);
        } catch (Exception e) { throw new AgentException(503, "Хранилище агента недоступно"); }
    }
}
