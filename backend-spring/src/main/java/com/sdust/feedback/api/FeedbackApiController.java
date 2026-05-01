package com.sdust.feedback.api;

import com.sdust.feedback.common.ApiResponse;
import com.sdust.feedback.security.PasswordService;
import com.sdust.feedback.security.TokenService;
import com.sdust.feedback.service.FeedbackDatabaseService;
import com.sdust.feedback.service.QzAcademicClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FeedbackApiController {
  private final FeedbackDatabaseService databaseService;
  private final QzAcademicClient academicClient;
  private final PasswordService passwordService;
  private final TokenService tokenService;

  public FeedbackApiController(
      FeedbackDatabaseService databaseService,
      QzAcademicClient academicClient,
      PasswordService passwordService,
      TokenService tokenService
  ) {
    this.databaseService = databaseService;
    this.academicClient = academicClient;
    this.passwordService = passwordService;
    this.tokenService = tokenService;
  }

  @GetMapping("/health")
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> data = new HashMap<>();
    data.put("status", "UP");
    data.put("appName", "学生反馈系统");
    data.put("timestamp", Instant.now().toString());
    return ApiResponse.ok(data);
  }

  @GetMapping("/meta/modules")
  public ApiResponse<?> modules() {
    return ApiResponse.ok(Arrays.asList(
        "auth",
        "user",
        "schedule",
        "feedback",
        "master-data",
        "dashboard",
        "analytics"
    ));
  }

  @PostMapping("/auth/login")
  public ResponseEntity<ApiResponse<?>> login(@RequestBody Map<String, Object> body) {
    String username = text(body.get("username"));
    String password = text(body.get("password"));
    if (username.isBlank() || password.isBlank()) {
      return badRequest("用户名和密码不能为空");
    }

    Map<String, Object> user = databaseService.findUserByUsername(username);
    if (user == null) {
      return badRequest("用户不存在");
    }

    if (!passwordService.verify(password, text(user.get("passwordHash")))) {
      return badRequest("密码错误");
    }

    Map<String, Object> safeUser = safeUser(user);
    Map<String, Object> data = new HashMap<>();
    data.put("token", tokenService.createToken(
        number(user.get("id")),
        text(user.get("username")),
        text(user.get("role"))
    ));
    data.put("user", safeUser);
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  @PostMapping("/auth/academic-login")
  public ResponseEntity<ApiResponse<?>> academicLogin(@RequestBody Map<String, Object> body) {
    String account = text(body.get("account"));
    if (account.isBlank()) {
      return badRequest("学号不能为空");
    }

    try {
      Map<String, Object> calendar = databaseService.currentAcademicCalendar();
      body.putIfAbsent("termCode", calendar.get("termCode"));
      body.putIfAbsent("weekNo", calendar.get("currentWeek"));
      QzAcademicClient.AcademicSessionResult academicSession = academicClient.loginWebSession(body);
      Map<String, Object> user = databaseService.ensureAcademicStudentUser(account);
      Map<String, Object> safeUser = safeUser(user);
      Map<String, Object> data = new HashMap<>();
      data.put("token", tokenService.createToken(
          number(user.get("id")),
          text(user.get("username")),
          text(user.get("role"))
      ));
      data.put("user", safeUser);
      data.put("termCode", calendar.get("termCode"));
      data.put("weekNo", calendar.get("currentWeek"));
      data.put("termStart", calendar.get("termStart"));
      data.put("dateRow", calendar.get("dateRow"));
      data.put("academicSessionId", academicSession.getAcademicSessionId());
      try {
        QzAcademicClient.SyncResult timetable = academicClient.readPersonalTimetableFromSession(body, academicSession.getAcademicSessionId());
        data.put("termCode", timetable.getTermCode());
        data.put("weekNo", timetable.getWeekNo());
        data.put("rawCount", timetable.getRawCount());
        data.put("timetable", timetable.getRows());
      } catch (IllegalArgumentException warning) {
        data.put("rawCount", 0);
        data.put("timetable", java.util.Collections.emptyList());
        data.put("warning", warning.getMessage());
      }
      return ResponseEntity.ok(ApiResponse.ok(data));
    } catch (IllegalArgumentException error) {
      if (error.getMessage() != null && error.getMessage().startsWith("教务登录成功")) {
        Map<String, Object> calendar = databaseService.currentAcademicCalendar();
        Map<String, Object> user = databaseService.ensureAcademicStudentUser(account);
        Map<String, Object> data = new HashMap<>();
        data.put("token", tokenService.createToken(
            number(user.get("id")),
            text(user.get("username")),
            text(user.get("role"))
        ));
        data.put("user", safeUser(user));
        data.put("termCode", calendar.get("termCode"));
        data.put("weekNo", calendar.get("currentWeek"));
        data.put("termStart", calendar.get("termStart"));
        data.put("dateRow", calendar.get("dateRow"));
        data.put("rawCount", 0);
        data.put("timetable", java.util.Collections.emptyList());
        data.put("warning", error.getMessage());
        return ResponseEntity.ok(ApiResponse.ok(data));
      }
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("学校账号登录失败：" + error.getMessage()));
    }
  }

  @GetMapping("/schedules/academic-calendar/current")
  public ResponseEntity<ApiResponse<?>> currentAcademicCalendar(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.currentAcademicCalendar()));
  }

  @GetMapping("/schedules/my-timetable")
  public ResponseEntity<ApiResponse<?>> myTimetable(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "week", required = false) Integer week
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.myTimetable(user, week)));
  }

  @GetMapping("/auth/me")
  public ResponseEntity<ApiResponse<?>> me(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> tokenPayload = tokenService.verify(tokenService.readBearer(authorization));
    if (tokenPayload == null || tokenPayload.get("userId") == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("登录状态已失效"));
    }

    Map<String, Object> user = databaseService.findUserById(number(tokenPayload.get("userId")));
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("用户不存在"));
    }

    return ResponseEntity.ok(ApiResponse.ok(safeUser(user)));
  }

  @GetMapping("/dashboard/summary")
  public ResponseEntity<ApiResponse<?>> dashboardSummary(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.dashboardSummary(user)));
  }

  @GetMapping("/users")
  public ResponseEntity<ApiResponse<?>> users(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.users(user)));
  }

  @GetMapping("/master-data")
  public ApiResponse<?> supportedMasterData() {
    return ApiResponse.ok(databaseService.supportedResources());
  }

  @GetMapping("/master-data/{resource}")
  public ResponseEntity<ApiResponse<?>> listMasterData(
      @PathVariable String resource,
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    Object data = databaseService.listResource(resource, user);
    if (data == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("资源不存在"));
    }
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  @PostMapping("/master-data/{resource}")
  public ResponseEntity<ApiResponse<?>> createMasterData(
      @PathVariable String resource,
      @RequestBody Map<String, Object> body
  ) {
    Object data = databaseService.createResource(resource, body);
    if (data == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("资源不存在"));
    }
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  @GetMapping("/schedules/weekly-tasks")
  public ResponseEntity<ApiResponse<?>> weeklyTasks(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyTasks(user)));
  }

  @GetMapping("/schedules/weekly-task-compliance")
  public ResponseEntity<ApiResponse<?>> weeklyTaskCompliance(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyTaskCompliance(user)));
  }

  @PostMapping("/schedules/weekly-tasks/generate")
  public ApiResponse<?> generateWeeklyTasks(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(databaseService.generateWeeklyTasks(body));
  }

  @PostMapping("/schedules/teaching-tasks/import")
  public ApiResponse<?> importTeachingTasks(@RequestBody Map<String, Object> body) {
    return ApiResponse.ok(databaseService.importTeachingTasks(body));
  }

  @GetMapping("/schedules/teaching-tasks/captcha")
  public ResponseEntity<ApiResponse<?>> academicCaptcha() {
    try {
      return ResponseEntity.ok(ApiResponse.ok(academicClient.requestCaptcha()));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("验证码获取失败：" + error.getMessage()));
    }
  }

  @PostMapping("/academic/grades/query")
  public ResponseEntity<ApiResponse<?>> queryGrades(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    String requestedAccount = text(body.get("account"));
    String currentAccount = text(user.get("username"));
    String academicSessionId = text(body.get("academicSessionId"));
    if (!requestedAccount.isBlank() && !requestedAccount.equals(currentAccount)) {
      return forbidden("成绩只能由本人账号临时查询，不能代查他人成绩");
    }
    if (!academicSessionId.isBlank() && !academicClient.isAcademicSessionOwner(academicSessionId, currentAccount)) {
      return forbidden("教务登录会话不属于当前账号，请重新使用学校账号登录");
    }
    if (academicSessionId.isBlank()) {
      body.put("account", currentAccount);
    }
    try {
      return ResponseEntity.ok(ApiResponse.ok(academicClient.queryGrades(body)));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("成绩查询失败：" + error.getMessage()));
    }
  }

  @PostMapping("/schedules/teaching-tasks/sync-personal")
  public ResponseEntity<ApiResponse<?>> syncPersonalTeachingTasks(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    try {
      Map<String, Object> calendar = databaseService.currentAcademicCalendar();
      body.putIfAbsent("termCode", calendar.get("termCode"));
      body.putIfAbsent("weekNo", calendar.get("currentWeek"));
      Object classGroupIdValue = user.get("classGroupId");
      String fallbackClassName = databaseService.classNameById(classGroupIdValue == null ? 0L : number(classGroupIdValue));
      QzAcademicClient.SyncResult syncResult = academicClient.syncPersonalTimetable(body, fallbackClassName);
      Map<String, Object> importPayload = new HashMap<>();
      importPayload.put("termId", body.getOrDefault("termId", 1));
      importPayload.put("rows", syncResult.getRows());
      Map<String, Object> importResult = databaseService.importTeachingTasks(importPayload);
      Map<String, Object> result = new HashMap<>(importResult);
      result.put("termCode", syncResult.getTermCode());
      result.put("weekNo", syncResult.getWeekNo());
      result.put("rawCount", syncResult.getRawCount());
      result.put("normalizedCount", syncResult.getRows().size());
      return ResponseEntity.ok(ApiResponse.ok(result));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("教务系统同步失败：" + error.getMessage()));
    }
  }

  @GetMapping("/feedbacks/weekly")
  public ResponseEntity<ApiResponse<?>> weeklyFeedbacks(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyFeedbacks(user)));
  }

  @PostMapping("/feedbacks/weekly")
  public ResponseEntity<ApiResponse<?>> createWeeklyFeedback(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    body.put("studentId", user.get("id"));
    return ResponseEntity.ok(ApiResponse.ok(databaseService.createWeeklyFeedback(body)));
  }

  @GetMapping("/feedbacks/realtime")
  public ResponseEntity<ApiResponse<?>> realtimeFeedbacks(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.realtimeFeedbacks(user)));
  }

  @PostMapping("/feedbacks/realtime")
  public ResponseEntity<ApiResponse<?>> createRealtimeFeedback(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    body.put("studentId", user.get("id"));
    body.put("departmentId", user.get("departmentId"));
    return ResponseEntity.ok(ApiResponse.ok(databaseService.createRealtimeFeedback(body)));
  }

  @PostMapping("/feedbacks/reply")
  public ResponseEntity<ApiResponse<?>> replyFeedback(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以处理反馈");
    }
    if (!databaseService.canAccessFeedback(body, user)) {
      return forbidden("无权处理该反馈");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.replyFeedback(body, user)));
  }

  @GetMapping("/feedbacks/replies")
  public ResponseEntity<ApiResponse<?>> feedbackReplies(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam String feedbackType,
      @RequestParam Long feedbackId
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    Map<String, Object> query = new HashMap<>();
    query.put("feedbackType", feedbackType);
    query.put("feedbackId", feedbackId);
    if (!databaseService.canAccessFeedback(query, user)) {
      return forbidden("无权查看该反馈回复");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.feedbackReplies(feedbackType, feedbackId)));
  }

  @PatchMapping("/feedbacks/status")
  public ResponseEntity<ApiResponse<?>> updateFeedbackStatus(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以更新反馈状态");
    }
    if (!databaseService.canAccessFeedback(body, user)) {
      return forbidden("无权更新该反馈");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.updateFeedbackStatus(body)));
  }

  @GetMapping("/feedbacks/flags")
  public ResponseEntity<ApiResponse<?>> flags(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.flags(user)));
  }

  private ResponseEntity<ApiResponse<?>> badRequest(String message) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(message));
  }

  private ResponseEntity<ApiResponse<?>> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("登录状态已失效"));
  }

  private ResponseEntity<ApiResponse<?>> forbidden(String message) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(message));
  }

  private Map<String, Object> authenticatedUser(String authorization) {
    Map<String, Object> tokenPayload = tokenService.verify(tokenService.readBearer(authorization));
    if (tokenPayload == null || tokenPayload.get("userId") == null) {
      return null;
    }
    return databaseService.findUserById(number(tokenPayload.get("userId")));
  }

  private Map<String, Object> safeUser(Map<String, Object> user) {
    Map<String, Object> safe = new HashMap<>(user);
    safe.remove("passwordHash");
    return safe;
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private Long number(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private boolean isAdmin(Map<String, Object> user) {
    String role = String.valueOf(user.getOrDefault("role", ""));
    return "SUPER_ADMIN".equals(role) || "DEPARTMENT_ADMIN".equals(role);
  }
}
