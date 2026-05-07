package com.sdust.feedback.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final String model;

  public AiAnalysisService(
      ObjectMapper objectMapper,
      @Value("${app.ai.base-url:https://api.deepseek.com}") String baseUrl,
      @Value("${app.ai.api-key:}") String apiKey,
      @Value("${app.ai.model:deepseek-chat}") String model
  ) {
    this.objectMapper = objectMapper;
    this.baseUrl = trimRight(baseUrl, "/");
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12))
        .build();
  }

  public boolean enabled() {
    return !apiKey.isBlank();
  }

  public List<Map<String, Object>> enhanceWeeklySummaries(List<Map<String, Object>> summaries) {
    if (!enabled()) {
      throw new IllegalArgumentException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    int limit = Math.min(summaries.size(), 10);
    for (int index = 0; index < limit; index += 1) {
      Map<String, Object> source = summaries.get(index);
      Map<String, Object> enhanced = new LinkedHashMap<>(source);
      try {
        Map<String, Object> ai = analyzeOne(source);
        enhanced.put("aiSummary", text(ai.get("summary")));
        enhanced.put("aiRiskLevel", text(ai.get("riskLevel")));
        enhanced.put("aiSensitivePoints", ai.getOrDefault("sensitivePoints", List.of()));
        enhanced.put("aiSuggestions", ai.getOrDefault("suggestions", List.of()));
        enhanced.put("aiProvider", "deepseek");
      } catch (Exception error) {
        enhanced.put("aiError", error.getMessage());
      }
      result.add(enhanced);
    }
    return result;
  }

  private Map<String, Object> analyzeOne(Map<String, Object> summary) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("temperature", 0.2);
    body.put("stream", false);
    body.put("messages", List.of(
        Map.of(
            "role", "system",
            "content", "你是高校教学质量监控助手。只输出 JSON，不要输出 Markdown。"
        ),
        Map.of(
            "role", "user",
            "content", buildPrompt(summary)
        )
    ));

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/chat/completions"))
        .timeout(Duration.ofSeconds(35))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalArgumentException("DeepSeek 调用失败：" + response.statusCode());
    }
    Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
    List<Map<String, Object>> choices = (List<Map<String, Object>>) payload.getOrDefault("choices", List.of());
    if (choices.isEmpty()) {
      throw new IllegalArgumentException("DeepSeek 未返回结果");
    }
    Map<String, Object> message = (Map<String, Object>) choices.get(0).getOrDefault("message", Map.of());
    String content = stripJsonFence(text(message.get("content")));
    return objectMapper.readValue(content, new TypeReference<>() {});
  }

  private String buildPrompt(Map<String, Object> summary) {
    return "请根据以下课程反馈生成教学质量总评，返回 JSON："
        + "{\"summary\":\"100字以内总评\",\"riskLevel\":\"LOW|MEDIUM|HIGH\","
        + "\"sensitivePoints\":[\"敏感点\"],\"suggestions\":[\"处理建议\"]}。"
        + "课程：" + text(summary.get("courseName"))
        + "；教师：" + text(summary.get("teacherName"))
        + "；班级：" + text(summary.get("classes"))
        + "；反馈数：" + text(summary.get("feedbackCount"))
        + "；正向反馈：" + text(summary.get("positiveSummary"))
        + "；问题反馈：" + text(summary.get("issueSummary"))
        + "；硬件问题：" + text(summary.get("hardwareSummary"))
        + "；规则风险：" + text(summary.get("riskLevel"));
  }

  private String stripJsonFence(String value) {
    String text = value.trim();
    if (text.startsWith("```")) {
      text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
    }
    return text;
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String trimRight(String value, String suffix) {
    String next = value == null ? "" : value.trim();
    while (next.endsWith(suffix)) {
      next = next.substring(0, next.length() - suffix.length());
    }
    return next;
  }
}
