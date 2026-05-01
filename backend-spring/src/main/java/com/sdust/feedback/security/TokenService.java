package com.sdust.feedback.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenService {
  private final String secret;
  private final ObjectMapper objectMapper;

  public TokenService(
      @Value("${app.auth-token-secret}") String secret,
      ObjectMapper objectMapper
  ) {
    this.secret = secret;
    this.objectMapper = objectMapper;
  }

  public String createToken(Long userId, String username, String role) {
    try {
      Map<String, Object> header = new HashMap<>();
      header.put("alg", "HS256");
      header.put("typ", "JWT");

      Map<String, Object> body = new HashMap<>();
      body.put("userId", userId);
      body.put("username", username);
      body.put("role", role);
      body.put("iat", Instant.now().getEpochSecond());

      String headerPart = encodeJson(header);
      String bodyPart = encodeJson(body);
      String signature = sign(headerPart + "." + bodyPart);
      return headerPart + "." + bodyPart + "." + signature;
    } catch (Exception ex) {
      throw new IllegalStateException("token生成失败", ex);
    }
  }

  public Map<String, Object> verify(String token) {
    try {
      String[] parts = String.valueOf(token == null ? "" : token).split("\\.");
      if (parts.length != 3) {
        return null;
      }

      String expected = sign(parts[0] + "." + parts[1]);
      if (!expected.equals(parts[2])) {
        return null;
      }

      byte[] bodyBytes = Base64.getUrlDecoder().decode(parts[1]);
      return objectMapper.readValue(bodyBytes, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      return null;
    }
  }

  public String readBearer(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return "";
    }
    return authorization.substring("Bearer ".length());
  }

  private String encodeJson(Map<String, Object> value) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(objectMapper.writeValueAsBytes(value));
  }

  private String sign(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }
}
