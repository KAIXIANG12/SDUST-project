package com.sdust.feedback.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordService {
  private static final int KEY_LENGTH = 256;

  public boolean verify(String password, String storedHash) {
    if (storedHash == null || storedHash.isBlank()) {
      return false;
    }

    if (storedHash.startsWith("plain:")) {
      return storedHash.substring("plain:".length()).equals(password);
    }

    String[] parts = storedHash.split("\\$");
    if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
      return false;
    }

    try {
      int iterations = Integer.parseInt(parts[1]);
      byte[] salt = parts[2].getBytes(StandardCharsets.UTF_8);
      byte[] expected = hexToBytes(parts[3]);
      byte[] actual = pbkdf2(password, salt, iterations);
      return MessageDigest.isEqual(actual, expected);
    } catch (NumberFormatException | NoSuchAlgorithmException | InvalidKeySpecException ex) {
      return false;
    }
  }

  private byte[] pbkdf2(String password, byte[] salt, int iterations)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    PBEKeySpec spec = new PBEKeySpec(
        password.toCharArray(),
        salt,
        iterations,
        KEY_LENGTH
    );
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
  }

  private byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i += 1) {
      int index = i * 2;
      bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
    }
    return bytes;
  }
}
