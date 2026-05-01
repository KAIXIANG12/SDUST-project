package com.sdust.feedback.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class QzAcademicClient {
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String webBaseUrl;
  private final String currentTermCode;
  private final LocalDate currentTermStart;
  private final Map<String, String> captchaCookies = new ConcurrentHashMap<>();
  private final Map<String, AcademicSession> academicSessions = new ConcurrentHashMap<>();

  public QzAcademicClient(
      ObjectMapper objectMapper,
      @Value("${app.academic-base-url:http://jwgl.sdust.edu.cn/app.do}") String baseUrl,
      @Value("${app.academic-web-base-url:https://jwgl.sdust.edu.cn/jsxsd/}") String webBaseUrl,
      @Value("${app.academic-current-term:2025-2026-2}") String currentTermCode,
      @Value("${app.academic-term-start:2026-03-09}") String currentTermStart
  ) {
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl : webBaseUrl + "/";
    this.currentTermCode = currentTermCode;
    this.currentTermStart = LocalDate.parse(currentTermStart);
  }

  public CaptchaResult requestCaptcha() {
    HttpPayload homePayload = doGetUrl(webBaseUrl, orderedParams(), formHeaders(""));
    String bootstrapCookie = homePayload.cookie;
    Map<String, String> captchaParams = orderedParams();
    captchaParams.put("t", String.valueOf(System.currentTimeMillis()));
    HttpPayload payload = doGetUrl(webBaseUrl + "verifycode.servlet", captchaParams, formHeaders(bootstrapCookie));
    if (payload.bytes.length == 0) {
      throw new IllegalArgumentException("验证码获取失败：教务系统没有返回图片");
    }
    String captchaSessionId = UUID.randomUUID().toString();
    captchaCookies.put(captchaSessionId, mergeCookie(bootstrapCookie, payload.cookie));
    String imageBase64 = Base64.getEncoder().encodeToString(payload.bytes);
    return new CaptchaResult(captchaSessionId, "data:image/jpeg;base64," + imageBase64);
  }

  public SyncResult syncPersonalTimetable(Map<String, Object> request, String fallbackClassName) {
    if (!text(request.get("captchaCode")).isBlank()) {
      return syncWebTimetable(request, fallbackClassName);
    }

    String account = text(request.get("account"));
    String password = text(request.get("password"));
    if (account.isBlank() || password.isBlank()) {
      throw new IllegalArgumentException("请填写教务账号和密码");
    }

    Map<String, String> authParams = orderedParams();
    authParams.put("method", "authUser");
    authParams.put("xh", account);
    authParams.put("pwd", password);
    Map<String, Object> auth = get(authParams, defaultHeaders(), "教务登录 authUser");
    String token = text(auth.get("token"));
    String flag = text(auth.get("flag"));
    String success = text(auth.get("success"));
    if (token.isBlank() || ("0".equals(flag) && success.isBlank())) {
      throw new IllegalArgumentException("教务认证失败，请检查账号密码或接口状态");
    }

    HttpHeaders headers = defaultHeaders();
    headers.set("token", token);

    Map<String, String> currentTimeParams = orderedParams();
    currentTimeParams.put("method", "getCurrentTime");
    currentTimeParams.put("currDate", LocalDate.now().toString());
    Map<String, Object> currentTime = get(currentTimeParams, headers, "获取当前学期周次 getCurrentTime");
    String termCode = firstText(request, "xnxqid", "termCode");
    if (termCode.isBlank()) {
      termCode = firstText(currentTime, "xnxqh", "xnxqid", "termCode");
    }
    String weekNo = firstText(request, "weekNo", "zc");
    if (weekNo.isBlank()) {
      weekNo = firstText(currentTime, "zc", "weekNo");
    }
    if (termCode.isBlank() || weekNo.isBlank()) {
      throw new IllegalArgumentException("无法确定学年学期或周次，请手动填写后重试");
    }

    Map<String, String> timetableParams = orderedParams();
    timetableParams.put("method", "getKbcxAzc");
    timetableParams.put("xnxqid", termCode);
    timetableParams.put("zc", weekNo);
    timetableParams.put("xh", account);
    Object timetable = getRaw(timetableParams, headers, "获取指定周课表 getKbcxAzc");

    List<Map<String, Object>> rawRows = extractRows(timetable);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> raw : rawRows) {
      rows.add(normalizeCourse(raw, weekNo, fallbackClassName));
    }

    return new SyncResult(termCode, weekNo, rows, rawRows.size());
  }

  public SyncResult loginAndReadPersonalTimetable(Map<String, Object> request) {
    return fetchWebTimetable(request, "", false);
  }

  public SyncResult readPersonalTimetableFromSession(Map<String, Object> request, String academicSessionId) {
    return readWebTimetableFromSession(request, "", false, academicSessionId);
  }

  public AcademicSessionResult loginWebSession(Map<String, Object> request) {
    String account = text(request.get("account"));
    String password = text(request.get("password"));
    String captchaSessionId = text(request.get("captchaSessionId"));
    String captchaCode = text(request.get("captchaCode"));
    if (account.isBlank() || password.isBlank() || captchaSessionId.isBlank() || captchaCode.isBlank()) {
      throw new IllegalArgumentException("请填写教务账号、密码和验证码");
    }

    String cookie = captchaCookies.getOrDefault(captchaSessionId, "");
    if (cookie.isBlank()) {
      throw new IllegalArgumentException("验证码会话已失效，请刷新验证码后重试");
    }

    Map<String, String> loginParams = orderedParams();
    loginParams.put("encoded", base64(account) + "%%%" + base64(password));
    loginParams.put("RANDOMCODE", captchaCode);
    HttpPayload loginPayload = doPostUrl(webBaseUrl + "xk/LoginToXk", loginParams, formHeaders(cookie));
    String mergedCookie = mergeCookie(cookie, loginPayload.cookie);
    String loginBody = loginPayload.text();
    if (!(loginPayload.statusCode == 302
        || loginBody.contains("calender_user_schedule")
        || loginBody.contains("TopUserSetting"))) {
      String message = extractLoginError(loginBody);
      throw new IllegalArgumentException(message.isBlank() ? "教务网页登录失败，请检查账号、密码和验证码" : message);
    }

    String academicSessionId = UUID.randomUUID().toString();
    academicSessions.put(academicSessionId, new AcademicSession(account, mergedCookie, Instant.now().plusSeconds(30 * 60)));
    return new AcademicSessionResult(academicSessionId, account);
  }

  public boolean isAcademicSessionOwner(String academicSessionId, String account) {
    AcademicSession session = academicSessions.get(academicSessionId);
    return session != null && session.account.equals(account) && session.expiresAt.isAfter(Instant.now());
  }

  public Map<String, Object> queryGrades(Map<String, Object> request) {
    String academicSessionId = text(request.get("academicSessionId"));
    String requestedTermCode = firstText(request, "termCode", "xnxqid");
    String termCode = requestedTermCode;
    AcademicSession academicSession;
    if (academicSessionId.isBlank()) {
      AcademicSessionResult session = loginWebSession(request);
      academicSessionId = session.getAcademicSessionId();
      academicSession = requireAcademicSession(academicSessionId);
    } else {
      academicSession = requireAcademicSession(academicSessionId);
    }

    // Mimic SHST flow: open grade page first, then request grade list.
    doGetUrl(webBaseUrl + "kscj/cjcx_query", orderedParams(), formHeaders(academicSession.cookie));

    Map<String, String> gradeParams = orderedParams();
    gradeParams.put("kksj", termCode);
    gradeParams.put("xsfs", "all");
    gradeParams.put("showType", "2");
    gradeParams.put("kcxz", "");
    gradeParams.put("kcmc", "");

    HttpPayload gradePayload = doPostUrl(webBaseUrl + "kscj/cjcx_list_new", gradeParams, formHeaders(academicSession.cookie), true);
    String gradeHtml = gradePayload.text();
    List<Map<String, Object>> grades = parseGradeHtml(gradeHtml);
    if (grades.isEmpty() && (gradePayload.statusCode == 302 || gradeHtml.isBlank())) {
      // Compatibility fallback for some deployments.
      HttpPayload fallbackPayload = doPostUrl(webBaseUrl + "kscj/cjcx_list", gradeParams, formHeaders(academicSession.cookie), true);
      gradeHtml = fallbackPayload.text();
      grades = parseGradeHtml(gradeHtml);
      gradePayload = fallbackPayload;
    }
    if (grades.isEmpty() && (gradeHtml.contains("用户没有登录") || gradeHtml.contains("登录页面") || gradePayload.statusCode == 302)) {
      throw new IllegalArgumentException("教务登录成功，但成绩接口会话失效，请重新学校账号登录后重试。");
    }

    if (grades.isEmpty() && !requestedTermCode.isBlank()) {
      // Some deployments return empty for an unsupported term code. Retry once with all terms.
      Map<String, String> allTermParams = orderedParams();
      allTermParams.put("kksj", "");
      allTermParams.put("xsfs", "all");
      allTermParams.put("showType", "2");
      allTermParams.put("kcxz", "");
      allTermParams.put("kcmc", "");
      HttpPayload retryPayload = doPostUrl(webBaseUrl + "kscj/cjcx_list_new", allTermParams, formHeaders(academicSession.cookie), true);
      String retryHtml = retryPayload.text();
      List<Map<String, Object>> retryGrades = parseGradeHtml(retryHtml);
      if (retryGrades.isEmpty() && (retryPayload.statusCode == 302 || retryHtml.isBlank())) {
        HttpPayload retryFallbackPayload = doPostUrl(webBaseUrl + "kscj/cjcx_list", allTermParams, formHeaders(academicSession.cookie), true);
        retryHtml = retryFallbackPayload.text();
        retryGrades = parseGradeHtml(retryHtml);
      }
      if (!retryGrades.isEmpty()) {
        grades = retryGrades;
        termCode = "";
      }
    }

    if (grades.isEmpty()
        && !containsAny(gradeHtml, "id=\"dataList\"", "id='dataList'", "未查询到", "暂无", "无成绩", "成绩查询")) {
      throw new IllegalArgumentException("成绩页面结构解析失败，可能被网关拦截或页面结构变化。返回片段：" + preview(gradeHtml));
    }

    double creditTotal = 0.0;
    double gpaTotal = 0.0;
    double weightedGpaTotal = 0.0;
    int gpaCount = 0;
    for (Map<String, Object> grade : grades) {
      if ("公选".equals(text(grade.get("type")))) {
        continue;
      }
      double credit = parseDouble(text(grade.get("credit")));
      double gpa = parseDouble(text(grade.get("gpa")));
      creditTotal += credit;
      gpaTotal += gpa;
      weightedGpaTotal += credit * gpa;
      gpaCount += 1;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("termCode", termCode);
    result.put("count", grades.size());
    result.put("creditTotal", round2(creditTotal));
    result.put("averageGpa", gpaCount == 0 ? 0 : round2(gpaTotal / gpaCount));
    result.put("weightedGpa", creditTotal == 0 ? 0 : round2(weightedGpaTotal / creditTotal));
    result.put("academicSessionId", academicSessionId);
    result.put("grades", grades);
    return result;
  }

  private SyncResult syncWebTimetable(Map<String, Object> request, String fallbackClassName) {
    return fetchWebTimetable(request, fallbackClassName, true);
  }

  private SyncResult fetchWebTimetable(Map<String, Object> request, String fallbackClassName, boolean requireClassName) {
    AcademicSessionResult session = loginWebSession(request);
    return readWebTimetableFromSession(request, fallbackClassName, requireClassName, session.getAcademicSessionId());
  }

  private SyncResult readWebTimetableFromSession(
      Map<String, Object> request,
      String fallbackClassName,
      boolean requireClassName,
      String academicSessionId
  ) {
    String termCode = firstText(request, "termCode", "xnxqid");
    String weekNo = firstText(request, "weekNo", "zc");
    if (termCode.isBlank()) {
      termCode = currentTermCode;
    }
    if (weekNo.isBlank()) {
      weekNo = String.valueOf(currentWeekNo());
    }

    AcademicSession academicSession = requireAcademicSession(academicSessionId);

    Map<String, String> timetableParams = orderedParams();
    timetableParams.put("week", weekNo);
    timetableParams.put("term", termCode);
    String importClassName = firstText(request, "className", "上课班级");
    String resolvedClassName = importClassName.isBlank() ? fallbackClassName : importClassName;
    HttpPayload timetablePayload = doGetUrl(webBaseUrl + "xskb/xskb_list.do", timetableParams, formHeaders(academicSession.cookie));
    String timetableHtml = timetablePayload.text();
    List<Map<String, Object>> rawRows = parseTimetableHtml(timetableHtml, weekNo, resolvedClassName);
    if (rawRows.isEmpty()) {
      throw new IllegalArgumentException("教务登录成功，但没有解析到课表数据。请检查学年学期代码、周次是否正确，或该周是否确实有课。返回片段：" + preview(timetableHtml));
    }
    if (requireClassName && resolvedClassName.isBlank()) {
      throw new IllegalArgumentException("已读取到课表，但当前系统账号没有绑定班级。请填写“导入班级名称”后再同步。");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> raw : rawRows) {
      rows.add(normalizeCourse(raw, weekNo, fallbackClassName));
    }
    return new SyncResult(termCode, weekNo, rows, rawRows.size(), academicSessionId);
  }

  private int currentWeekNo() {
    long days = ChronoUnit.DAYS.between(currentTermStart, LocalDate.now());
    if (days < 0) {
      return 1;
    }
    return Math.max(1, (int) (days / 7) + 1);
  }

  private AcademicSession requireAcademicSession(String academicSessionId) {
    AcademicSession session = academicSessions.get(academicSessionId);
    if (session == null || session.cookie.isBlank()) {
      throw new IllegalArgumentException("教务登录会话已失效，请重新登录学校账号");
    }
    if (session.expiresAt.isBefore(Instant.now())) {
      academicSessions.remove(academicSessionId);
      throw new IllegalArgumentException("教务登录会话已过期，请重新登录学校账号");
    }
    return session;
  }

  private Map<String, Object> get(Map<String, String> params, HttpHeaders headers, String stage) {
    Object raw = getRaw(params, headers, stage);
    if (raw instanceof Map) {
      return castMap((Map<?, ?>) raw);
    }
    throw new IllegalArgumentException("教务接口返回格式异常");
  }

  private Object getRaw(Map<String, String> params, HttpHeaders headers, String stage) {
    return parseJson(doGet(params, headers), stage);
  }

  private String doGet(Map<String, String> params, HttpHeaders headers) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(baseUrl + queryString(params));
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(60000);
      for (Map.Entry<String, List<String>> header : headers.entrySet()) {
        if (!header.getValue().isEmpty()) {
          connection.setRequestProperty(header.getKey(), header.getValue().get(0));
        }
      }
      connection.connect();
      InputStream stream = connection.getResponseCode() >= 400
          ? connection.getErrorStream()
          : connection.getInputStream();
      return readStream(stream, connection.getContentEncoding());
    } catch (Exception error) {
      throw new IllegalArgumentException("教务接口请求失败：" + error.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private String queryString(Map<String, String> params) {
    if (params.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder("?");
    params.forEach((key, value) -> builder
        .append(urlEncode(key))
        .append("=")
        .append(urlEncode(value == null ? "" : value))
        .append("&"));
    return builder.toString();
  }

  private HttpPayload doGetUrl(String urlValue, Map<String, String> params, HttpHeaders headers) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(urlValue + queryString(params));
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(60000);
      applyHeaders(connection, headers);
      connection.connect();
      return readPayload(connection);
    } catch (Exception error) {
      throw new IllegalArgumentException("教务网页请求失败：" + error.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private HttpPayload doPostUrl(String urlValue, Map<String, String> params, HttpHeaders headers) {
    return doPostUrl(urlValue, params, headers, false);
  }

  private HttpPayload doPostUrl(String urlValue, Map<String, String> params, HttpHeaders headers, boolean followRedirect) {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(urlValue);
      connection = (HttpURLConnection) url.openConnection();
      connection.setInstanceFollowRedirects(followRedirect);
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(60000);
      connection.setDoOutput(true);
      applyHeaders(connection, headers);
      connection.setRequestProperty("Referer", webBaseUrl);
      connection.setRequestProperty("Origin", "https://jwgl.sdust.edu.cn");
      byte[] body = formBody(params).getBytes(StandardCharsets.UTF_8);
      connection.getOutputStream().write(body);
      return readPayload(connection);
    } catch (Exception error) {
      throw new IllegalArgumentException("教务网页登录请求失败：" + error.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private void applyHeaders(HttpURLConnection connection, HttpHeaders headers) {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (!header.getValue().isEmpty()) {
        connection.setRequestProperty(header.getKey(), header.getValue().get(0));
      }
    }
  }

  private HttpPayload readPayload(HttpURLConnection connection) throws Exception {
    InputStream stream = connection.getResponseCode() >= 400
        ? connection.getErrorStream()
        : connection.getInputStream();
    byte[] bytes = readBytes(stream, connection.getContentEncoding());
    return new HttpPayload(connection.getResponseCode(), bytes, extractCookie(connection.getHeaderFields()));
  }

  private String formBody(Map<String, String> params) {
    StringBuilder builder = new StringBuilder();
    params.forEach((key, value) -> {
      if (builder.length() > 0) {
        builder.append("&");
      }
      builder.append(urlEncode(key)).append("=").append(urlEncode(value == null ? "" : value));
    });
    return builder.toString();
  }

  private String extractCookie(Map<String, List<String>> headers) {
    Map<String, String> cookies = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      String key = header.getKey();
      List<String> values = header.getValue();
      if (key != null && "set-cookie".equalsIgnoreCase(key)) {
        for (String value : values) {
          String pair = value.split(";", 2)[0].trim();
          int equalsIndex = pair.indexOf("=");
          if (equalsIndex > 0) {
            cookies.put(pair.substring(0, equalsIndex), pair.substring(equalsIndex + 1));
          }
        }
      }
    }
    return cookieHeader(cookies);
  }

  private String mergeCookie(String first, String second) {
    Map<String, String> cookies = new LinkedHashMap<>();
    readCookieHeader(first, cookies);
    readCookieHeader(second, cookies);
    return cookieHeader(cookies);
  }

  private void readCookieHeader(String cookie, Map<String, String> cookies) {
    if (cookie == null || cookie.isBlank()) {
      return;
    }
    for (String part : cookie.split(";")) {
      String pair = part.trim();
      int equalsIndex = pair.indexOf("=");
      if (equalsIndex > 0) {
        cookies.put(pair.substring(0, equalsIndex), pair.substring(equalsIndex + 1));
      }
    }
  }

  private String cookieHeader(Map<String, String> cookies) {
    return cookies.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(java.util.stream.Collectors.joining("; "));
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private byte[] readBytes(InputStream stream, String contentEncoding) throws Exception {
    if (stream == null) {
      return new byte[0];
    }
    byte[] bytes = stream.readAllBytes();
    InputStream decodedStream = new ByteArrayInputStream(bytes);
    String encoding = contentEncoding == null ? "" : contentEncoding.toLowerCase();
    if (encoding.contains("gzip") || isGzip(bytes)) {
      decodedStream = new GZIPInputStream(new ByteArrayInputStream(bytes));
    } else if (encoding.contains("deflate")) {
      decodedStream = new InflaterInputStream(new ByteArrayInputStream(bytes));
    }

    return decodedStream.readAllBytes();
  }

  private String readStream(InputStream stream, String contentEncoding) throws Exception {
    byte[] bytes = readBytes(stream, contentEncoding);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
      StringBuilder builder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        builder.append(line);
      }
      return builder.toString();
    }
  }

  private boolean isGzip(byte[] bytes) {
    return bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
  }

  private String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private String extractLoginError(String html) {
    Matcher matcher = Pattern.compile("<font[\\s\\S]*?>(.*?)</font>").matcher(html);
    if (matcher.find()) {
      return stripHtml(matcher.group(1)).contains("!!") ? "验证码错误" : "账号或密码错误";
    }
    return "";
  }

  private List<Map<String, Object>> parseTimetableHtml(String html, String weekNo, String fallbackClassName) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Matcher matcher = Pattern.compile("<div[^>]*class=['\"][^'\"]*kbcontent[^'\"]*['\"][^>]*>(.*?)</div>", Pattern.CASE_INSENSITIVE).matcher(html);
    int index = 0;
    while (matcher.find()) {
      String cell = matcher.group(1);
      for (String item : cell.split("-{10,}")) {
        if (item.trim().startsWith("&nbsp;")) {
          continue;
        }
        String[] nameGroup = item.split("(<\\/br>)|(<br\\/>)|(<br>)", 2);
        String courseName = stripHtml(nameGroup.length > 0 ? nameGroup[0] : "");
        if (courseName.isBlank()) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        String weeksRaw = extractFontByTitle(item, "周次\\(节次\\)").replace("(", "").replace(")", "");
        row.put("courseName", courseName);
        row.put("teacher", extractFontByTitle(item, "老师"));
        row.put("weeks_raw", weeksRaw);
        row.put("classroom", extractFontByTitle(item, "教室"));
        row.put("className", fallbackClassName);
        row.put("weekRange", weeksRaw.isBlank() ? weekNo : weeksRaw);
        row.put("day", index % 7);
        row.put("serial", index / 7);
        rows.add(row);
      }
      index += 1;
    }
    return rows;
  }

  private String extractFontByTitle(String html, String titleRegex) {
    Matcher matcher = Pattern.compile("<font[\\s\\S]*?title='" + titleRegex + "'[\\s\\S]*?>(.*?)</font>", Pattern.CASE_INSENSITIVE).matcher(html);
    return matcher.find() ? stripHtml(matcher.group(1)) : "";
  }

  private List<Map<String, Object>> parseGradeHtml(String html) {
    List<Map<String, Object>> grades = new ArrayList<>();
    Matcher tableMatcher = Pattern.compile(
        "<table[^>]*id\\s*=\\s*['\"]?dataList['\"]?[^>]*>([\\s\\S]*?)</table>",
        Pattern.CASE_INSENSITIVE
    ).matcher(html);
    if (!tableMatcher.find()) {
      return grades;
    }
    Matcher rowMatcher = Pattern.compile("<tr[\\s\\S]*?>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE).matcher(tableMatcher.group(1));
    while (rowMatcher.find()) {
      List<String> cells = new ArrayList<>();
      Matcher cellMatcher = Pattern.compile("<td[\\s\\S]*?>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE).matcher(rowMatcher.group(1));
      while (cellMatcher.find()) {
        cells.add(stripHtml(cellMatcher.group(1)));
      }
      if (cells.size() < 4) {
        continue;
      }
      String no = cell(cells, 2, cell(cells, 1, ""));
      String name = cell(cells, 3, cell(cells, 2, ""));
      if (no.isBlank() && name.isBlank()) {
        continue;
      }
      Map<String, Object> grade = new LinkedHashMap<>();
      grade.put("no", no);
      grade.put("name", name);
      grade.put("grade", cell(cells, 4, ""));
      grade.put("makeup", cell(cells, 5, ""));
      grade.put("rebuild", cell(cells, 6, ""));
      grade.put("type", cell(cells, 7, ""));
      grade.put("credit", cell(cells, 8, ""));
      grade.put("gpa", cell(cells, 9, ""));
      grade.put("minor", cell(cells, 10, ""));
      grades.add(grade);
    }
    return grades;
  }

  private String cell(List<String> cells, int index, String fallback) {
    if (index >= 0 && index < cells.size()) {
      return text(cells.get(index));
    }
    return fallback;
  }

  private boolean containsAny(String source, String... keywords) {
    if (source == null || source.isBlank()) {
      return false;
    }
    for (String keyword : keywords) {
      if (source.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private String stripHtml(String value) {
    return value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replaceAll("<[^>]+>", "")
        .replace("（", "(")
        .replace("）", ")")
        .trim();
  }

  private double parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return 0.0;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException error) {
      return 0.0;
    }
  }

  private double round2(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private Object parseJson(String body, String stage) {
    String content = body == null ? "" : body.trim();
    if (content.isBlank()) {
      throw new IllegalArgumentException(stage + " 返回为空，请确认是否在校园网/VPN内或接口地址是否可访问");
    }
    if (!content.startsWith("{") && !content.startsWith("[")) {
      throw new IllegalArgumentException(stage + " 返回不是 JSON，可能返回了统一认证页面、网关拦截页或错误页。返回片段：" + preview(content));
    }
    try {
      return objectMapper.readValue(content, new TypeReference<Object>() {});
    } catch (Exception error) {
      throw new IllegalArgumentException(stage + " 返回不是合法 JSON。返回片段：" + preview(content));
    }
  }

  private String preview(String content) {
    String singleLine = content.replaceAll("\\s+", " ");
    return singleLine.substring(0, Math.min(singleLine.length(), 180));
  }

  private List<Map<String, Object>> extractRows(Object raw) {
    if (raw instanceof List) {
      return castList((List<?>) raw);
    }
    if (raw instanceof Map) {
      Map<String, Object> map = castMap((Map<?, ?>) raw);
      for (String key : List.of("data", "rows", "list", "kbList", "kblist", "result")) {
        Object value = map.get(key);
        if (value instanceof List) {
          return castList((List<?>) value);
        }
      }
      if (map.containsKey("kcmc") || map.containsKey("courseName")) {
        return List.of(map);
      }
    }
    return Collections.emptyList();
  }

  private Map<String, Object> normalizeCourse(Map<String, Object> raw, String weekNo, String fallbackClassName) {
    Map<String, Object> row = new LinkedHashMap<>();
    String teacherName = firstText(raw, "teacher", "jsxm", "jsmc", "skjs", "rkjs", "teacherName", "教师");
    row.put("departmentName", firstText(raw, "kkxymc", "jsxymc", "jsszdw", "departmentName", "教师所在院系"));
    row.put("plannedTeacherName", teacherName);
    row.put("actualTeacherName", teacherName);
    String className = firstText(raw, "bjmc", "bj", "xzb", "className", "上课班级");
    row.put("className", className.isBlank() ? fallbackClassName : className);
    row.put("courseName", firstText(raw, "kcmc", "courseName", "课程名称", "开课课程"));
    String weekRange = firstText(raw, "weeks_raw", "weeksRaw", "zc", "weekRange", "上课周次");
    row.put("weekRange", weekRange.isBlank() ? weekNo : weekRange);
    row.put("guidanceMode", firstText(raw, "jxfs", "skfs", "guidanceMode", "辅导方式"));
    row.put("scheduleTime", firstText(raw, "sksj", "kcsj", "jcs", "jc", "上课时间"));
    row.put("locationText", firstText(raw, "jxdd", "jsmc", "cdmc", "教室", "上课地点"));
    row.put("day", raw.get("day"));
    row.put("serial", raw.get("serial"));
    row.put("teacherName", firstText(raw, "teacher", "jsxm", "jsmc", "skjs", "rkjs", "teacherName", "教师"));
    row.put("classroom", firstText(raw, "classroom", "jxdd", "jsmc", "cdmc", "教室", "上课地点"));
    row.put("weeksRaw", weekRange.isBlank() ? weekNo : weekRange);
    row.put("raw", raw);
    return row;
  }

  private HttpHeaders defaultHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
    headers.set("Referer", webBaseUrl);
    headers.set("Accept-Encoding", "gzip, deflate");
    headers.set("Accept-Language", "zh-CN,zh-TW;q=0.8,zh;q=0.6,en;q=0.4,ja;q=0.2");
    headers.set("Cache-Control", "no-cache");
    headers.set("Pragma", "no-cache");
    return headers;
  }

  private HttpHeaders formHeaders(String cookie) {
    HttpHeaders headers = defaultHeaders();
    headers.set("Content-Type", "application/x-www-form-urlencoded");
    if (cookie != null && !cookie.isBlank()) {
      headers.set("Cookie", cookie);
    }
    return headers;
  }

  private Map<String, String> orderedParams() {
    return new LinkedHashMap<>();
  }

  private String firstText(Map<String, Object> row, String... keys) {
    for (String key : keys) {
      String value = text(row.get(key));
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private Map<String, Object> castMap(Map<?, ?> map) {
    Map<String, Object> result = new HashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private List<Map<String, Object>> castList(List<?> list) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map) {
        result.add(castMap((Map<?, ?>) item));
      }
    }
    return result;
  }

  public static class SyncResult {
    private final String termCode;
    private final String weekNo;
    private final List<Map<String, Object>> rows;
    private final int rawCount;
    private final String academicSessionId;

    public SyncResult(String termCode, String weekNo, List<Map<String, Object>> rows, int rawCount) {
      this(termCode, weekNo, rows, rawCount, "");
    }

    public SyncResult(String termCode, String weekNo, List<Map<String, Object>> rows, int rawCount, String academicSessionId) {
      this.termCode = termCode;
      this.weekNo = weekNo;
      this.rows = rows;
      this.rawCount = rawCount;
      this.academicSessionId = academicSessionId;
    }

    public String getTermCode() {
      return termCode;
    }

    public String getWeekNo() {
      return weekNo;
    }

    public List<Map<String, Object>> getRows() {
      return rows;
    }

    public int getRawCount() {
      return rawCount;
    }

    public String getAcademicSessionId() {
      return academicSessionId;
    }
  }

  public static class AcademicSessionResult {
    private final String academicSessionId;
    private final String account;

    public AcademicSessionResult(String academicSessionId, String account) {
      this.academicSessionId = academicSessionId;
      this.account = account;
    }

    public String getAcademicSessionId() {
      return academicSessionId;
    }

    public String getAccount() {
      return account;
    }
  }

  private static class AcademicSession {
    private final String account;
    private final String cookie;
    private final Instant expiresAt;

    private AcademicSession(String account, String cookie, Instant expiresAt) {
      this.account = account;
      this.cookie = cookie;
      this.expiresAt = expiresAt;
    }
  }

  public static class CaptchaResult {
    private final String captchaSessionId;
    private final String imageBase64;

    public CaptchaResult(String captchaSessionId, String imageBase64) {
      this.captchaSessionId = captchaSessionId;
      this.imageBase64 = imageBase64;
    }

    public String getCaptchaSessionId() {
      return captchaSessionId;
    }

    public String getImageBase64() {
      return imageBase64;
    }
  }

  private static class HttpPayload {
    private final int statusCode;
    private final byte[] bytes;
    private final String cookie;

    private HttpPayload(int statusCode, byte[] bytes, String cookie) {
      this.statusCode = statusCode;
      this.bytes = bytes;
      this.cookie = cookie;
    }

    private String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
}
