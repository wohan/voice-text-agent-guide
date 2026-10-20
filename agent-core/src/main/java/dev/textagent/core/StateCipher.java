package dev.textagent.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Encrypts durable state; this is storage encryption, not LLM data masking. */
public final class StateCipher {
  private static final int NONCE_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  private final byte[] key;

  public StateCipher(String base64Key) {
    try {
      key = Base64.getDecoder().decode(base64Key);
    } catch (Exception e) {
      throw new IllegalArgumentException("State encryption requires a base64 AES-256 key");
    }
    if (key.length != 32)
      throw new IllegalArgumentException("State encryption requires a 32-byte key");
  }

  public String ownerHash(String owner) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal(("owner:" + owner).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String encrypt(String id, byte[] plaintext) {
    try {
      byte[] nonce = new byte[NONCE_LENGTH_BYTES];
      new SecureRandom().nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plaintext);
      byte[] output = new byte[NONCE_LENGTH_BYTES + encrypted.length];
      System.arraycopy(nonce, 0, output, 0, NONCE_LENGTH_BYTES);
      System.arraycopy(encrypted, 0, output, NONCE_LENGTH_BYTES, encrypted.length);
      return Base64.getEncoder().encodeToString(output);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public byte[] decrypt(String id, String text) {
    try {
      byte[] data = Base64.getDecoder().decode(text);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(
              GCM_TAG_LENGTH_BITS, Arrays.copyOfRange(data, 0, NONCE_LENGTH_BYTES)));
      cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(data, NONCE_LENGTH_BYTES, data.length - NONCE_LENGTH_BYTES);
    } catch (Exception e) {
      throw new AgentException(503, "Хранилище агента недоступно");
    }
  }
}
