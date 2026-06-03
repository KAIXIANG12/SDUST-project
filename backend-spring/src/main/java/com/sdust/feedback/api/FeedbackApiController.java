package com.sdust.feedback.api;

import com.sdust.feedback.common.ApiResponse;
import com.sdust.feedback.security.PasswordService;
import com.sdust.feedback.security.TokenService;
import com.sdust.feedback.service.AiAnalysisService;
import com.sdust.feedback.service.FeedbackDatabaseService;
import com.sdust.feedback.service.QzAcademicClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  private static final Logger LOGGER = LoggerFactory.getLogger(FeedbackApiController.class);

  private final FeedbackDatabaseService databaseService;
  private final QzAcademicClient academicClient;
  private final AiAnalysisService aiAnalysisService;
  private final PasswordService passwordService;
  private final TokenService tokenService;

  public FeedbackApiController(
      FeedbackDatabaseService databaseService,
      QzAcademicClient academicClient,
      AiAnalysisService aiAnalysisService,
      PasswordService passwordService,
      TokenService tokenService
  ) {
    this.databaseService = databaseService;
    this.academicClient = academicClient;
    this.aiAnalysisService = aiAnalysisService;
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
    if (!"ACTIVE".equals(text(user.get("status")))) {
      return forbidden("账号已被禁用，请联系管理员");
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
      body.put("academicSessionId", academicSession.getAcademicSessionId());
      body.put("profileFastOnly", true);
      body.put("enrichTeachers", "false");
      Map<String, Object> user = databaseService.ensureAcademicStudentUser(account);
      if (!"ACTIVE".equals(text(user.get("status")))) {
        return forbidden("账号已被禁用，请联系管理员");
      }
      Map<String, Object> profileSync = tryBindAcademicProfile(body, account, user);
      user = castUser(profileSync.remove("user"), user);
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
      data.put("profileSync", profileSync);
      data.put("source", "academic");
      data.put("rawCount", 0);
      data.put("timetable", java.util.Collections.emptyList());
      return ResponseEntity.ok(ApiResponse.ok(data));
    } catch (IllegalArgumentException error) {
      if (error.getMessage() != null && error.getMessage().startsWith("教务登录成功")) {
        Map<String, Object> calendar = databaseService.currentAcademicCalendar();
        body.put("profileFastOnly", true);
        body.put("enrichTeachers", "false");
        Map<String, Object> user = databaseService.ensureAcademicStudentUser(account);
        if (!"ACTIVE".equals(text(user.get("status")))) {
          return forbidden("账号已被禁用，请联系管理员");
        }
        Map<String, Object> profileSync = tryBindAcademicProfile(body, account, user);
        user = castUser(profileSync.remove("user"), user);
        Map<String, Object> data = new HashMap<>();
        data.put("token", tokenService.createToken(
            number(user.get("id")),
            text(user.get("username")),
            text(user.get("role"))
        ));
        data.put("user", safeUser(user));
        data.put("profileSync", profileSync);
        data.put("termCode", calendar.get("termCode"));
        data.put("weekNo", calendar.get("currentWeek"));
        data.put("termStart", calendar.get("termStart"));
        data.put("dateRow", calendar.get("dateRow"));
        data.put("rawCount", 0);
        data.put("timetable", java.util.Collections.emptyList());
        data.put("source", "academic");
        logAcademicError("学校账号登录后读取课表失败", error);
        data.put("warning", publicAcademicMessage(error.getMessage()));
        return ResponseEntity.ok(ApiResponse.ok(data));
      }
      return academicBadRequest("学校账号登录失败", error);
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

  @PatchMapping("/users/{id}/authorization")
  public ResponseEntity<ApiResponse<?>> updateUserAuthorization(
      @PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以维护用户角色");
    }
    try {
      return ResponseEntity.ok(ApiResponse.ok(databaseService.updateUserAuthorization(id, body, user)));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    }
  }

  @PostMapping("/users/import")
  public ResponseEntity<ApiResponse<?>> importUsers(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以导入用户");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.importUsers(body, user)));
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

  @GetMapping("/schedules/weekly-task-items")
  public ResponseEntity<ApiResponse<?>> weeklyTaskItems(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyTaskCourseItems(user)));
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
      return academicBadRequest("成绩查询失败", error);
    } catch (Exception error) {
      logAcademicError("成绩查询异常", error);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("成绩查询失败：" + publicAcademicMessage(error.getMessage())));
    }
  }

  @PostMapping("/academic/timetable/query")
  public ResponseEntity<ApiResponse<?>> queryAcademicTimetable(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    String currentAccount = text(user.get("username"));
    String academicSessionId = text(body.get("academicSessionId"));
    if (academicSessionId.isBlank()) {
      return badRequest("缺少教务会话，请先学校账号登录");
    }
    if (!academicClient.isAcademicSessionOwner(academicSessionId, currentAccount)) {
      return forbidden("教务登录会话不属于当前账号，请重新使用学校账号登录");
    }
    Map<String, Object> calendar = databaseService.currentAcademicCalendar();
    body.put("account", currentAccount);
    body.putIfAbsent("termCode", calendar.get("termCode"));
    body.putIfAbsent("weekNo", calendar.get("currentWeek"));
    body.putIfAbsent("enrichTeachers", "false");
    try {
      QzAcademicClient.SyncResult timetable = academicClient.readPersonalTimetableFromSession(body, academicSessionId);
      Map<String, Object> data = new HashMap<>();
      data.put("termCode", timetable.getTermCode());
      data.put("weekNo", timetable.getWeekNo());
      data.put("termStart", calendar.get("termStart"));
      data.put("today", calendar.get("today"));
      data.put("dateRow", calendar.get("dateRow"));
      data.put("rawCount", timetable.getRawCount());
      data.put("academicSessionId", academicSessionId);
      data.put("source", "academic");
      List<Map<String, Object>> rows = databaseService.enrichTimetableTeachers(
          user,
          timetable.getRows(),
          number(timetable.getWeekNo()).intValue()
      );
      data.put("timetable", rows);
      data.put("info", rows);
      return ResponseEntity.ok(ApiResponse.ok(data));
    } catch (IllegalArgumentException error) {
      return academicBadRequest("教务课表查询失败", error);
    } catch (Exception error) {
      logAcademicError("教务课表查询异常", error);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("教务课表查询失败：" + publicAcademicMessage(error.getMessage())));
    }
  }

  @PostMapping("/academic/debug/probe")
  public ResponseEntity<ApiResponse<?>> academicDebugProbe(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    String currentAccount = text(user.get("username"));
    String academicSessionId = text(body.get("academicSessionId"));
    if (academicSessionId.isBlank()) {
      return badRequest("缺少教务会话，请先学校账号登录");
    }
    if (!academicClient.isAcademicSessionOwner(academicSessionId, currentAccount)) {
      return forbidden("教务登录会话不属于当前账号，请重新使用学校账号登录");
    }
    body.put("account", currentAccount);
    try {
      return ResponseEntity.ok(ApiResponse.ok(academicClient.diagnoseAcademicSession(body)));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("教务诊断失败：" + error.getMessage()));
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
      return academicBadRequest("教务系统同步失败", error);
    } catch (Exception error) {
      logAcademicError("教务系统同步异常", error);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("教务系统同步失败：" + publicAcademicMessage(error.getMessage())));
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
    if (!databaseService.canSubmitWeeklyFeedbackForCourse(body, user)) {
      return forbidden("只能提交自己当前周课表中存在的课程反馈");
    }
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
    body.put("departmentId", databaseService.resolveUserDepartmentId(user));
    return ResponseEntity.ok(ApiResponse.ok(databaseService.createRealtimeFeedback(body)));
  }

  @PatchMapping("/feedbacks/realtime/{id}")
  public ResponseEntity<ApiResponse<?>> updateRealtimeFeedback(
      @PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    try {
      return ResponseEntity.ok(ApiResponse.ok(databaseService.updateRealtimeFeedback(id, body, user)));
    } catch (IllegalArgumentException error) {
      return forbidden(error.getMessage());
    }
  }

  @DeleteMapping("/feedbacks/realtime/{id}")
  public ResponseEntity<ApiResponse<?>> deleteRealtimeFeedback(
      @PathVariable Long id,
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    try {
      return ResponseEntity.ok(ApiResponse.ok(databaseService.deleteRealtimeFeedback(id, user)));
    } catch (IllegalArgumentException error) {
      return forbidden(error.getMessage());
    }
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

  @GetMapping("/analytics/weekly-feedback")
  public ResponseEntity<ApiResponse<?>> weeklyFeedbackAnalytics(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以查看聚合统计");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyFeedbackAnalytics(user)));
  }

  @GetMapping("/analytics/weekly-feedback/summaries")
  public ResponseEntity<ApiResponse<?>> weeklyFeedbackSummaries(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以查看反馈总评");
    }
    return ResponseEntity.ok(ApiResponse.ok(databaseService.weeklyFeedbackSummaries(user)));
  }

  @PostMapping("/analytics/weekly-feedback/ai-summaries")
  public ResponseEntity<ApiResponse<?>> weeklyFeedbackAiSummaries(
      @RequestHeader(value = "Authorization", required = false) String authorization
  ) {
    Map<String, Object> user = authenticatedUser(authorization);
    if (user == null) {
      return unauthorized();
    }
    if (!isAdmin(user)) {
      return forbidden("只有管理员可以生成 AI 总评");
    }
    try {
      List<Map<String, Object>> summaries = databaseService.weeklyFeedbackSummaries(user);
      return ResponseEntity.ok(ApiResponse.ok(aiAnalysisService.enhanceWeeklySummaries(summaries)));
    } catch (IllegalArgumentException error) {
      return badRequest(error.getMessage());
    } catch (Exception error) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.fail("AI 总评生成失败：" + error.getMessage()));
    }
  }

  private ResponseEntity<ApiResponse<?>> badRequest(String message) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(message));
  }

  private ResponseEntity<ApiResponse<?>> academicBadRequest(String operation, Exception error) {
    logAcademicError(operation, error);
    return badRequest(publicAcademicMessage(error.getMessage()));
  }

  private void logAcademicError(String operation, Exception error) {
    LOGGER.warn("{}：{}", operation, error.getMessage());
  }

  private String publicAcademicMessage(String message) {
    if (message == null || message.isBlank()) {
      return "教务系统响应异常，请稍后重试";
    }
    if (message.startsWith("教务登录成功，但没有解析到课表数据")) {
      return "教务登录成功，但本周课表暂未读取到。请稍后刷新课表，或先使用管理员导入的本地课表。";
    }
    if (message.startsWith("验证码错误")) {
      return "验证码错误，请刷新验证码后重新输入";
    }
    if (message.contains("返回片段") || message.contains("页面特征") || message.contains("最后尝试参数")) {
      return "教务系统返回页面结构异常，请稍后重试；如需排查，请使用教务诊断功能。";
    }
    return message.length() > 120 ? message.substring(0, 120) + "..." : message;
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

  private Map<String, Object> tryBindAcademicProfile(Map<String, Object> body, String account, Map<String, Object> currentUser) {
    Map<String, Object> result = new HashMap<>();
    result.put("bound", false);
    result.put("user", currentUser);
    String webWarning = "";
    try {
      String academicSessionId = text(body.get("academicSessionId"));
      Map<String, Object> profile;
      if (!academicSessionId.isBlank()) {
        result.put("source", "HTML grxx/xsxx");
        profile = academicClient.readStudentProfileFromWebSession(academicSessionId);
      } else {
        result.put("source", "app.do getUserInfo");
        profile = academicClient.readStudentProfileFromAppApi(body);
      }
      Map<String, Object> user = databaseService.bindAcademicStudentProfile(account, profile);
      result.put("user", user);
      String realName = firstText(profile, "xm", "xsmc", "name", "realName", "studentName", "姓名");
      String departmentName = firstText(profile, "xy", "xymc", "xyxm", "academy", "academyName", "departmentName", "院系", "学院");
      String majorName = firstText(profile, "zy", "zymc", "major", "majorName", "专业");
      String className = firstText(profile, "bj", "bjmc", "xzb", "className", "行政班", "班级");
      boolean recognized = !realName.isBlank() || !departmentName.isBlank() || !majorName.isBlank() || !className.isBlank();
      result.put("bound", recognized);
      result.put("realName", realName);
      result.put("departmentName", departmentName);
      result.put("majorName", majorName);
      result.put("className", className);
      if (!recognized) {
        result.put("warning", "教务 getUserInfo 返回了数据，但没有发现姓名、学院、专业或班级字段");
      }
    } catch (Exception error) {
      webWarning = error.getMessage();
      if (Boolean.TRUE.equals(body.get("profileFastOnly"))) {
        result.put("warning", "HTML 个人信息页失败：" + webWarning);
        return result;
      }
      try {
        result.put("source", "app.do getUserInfo");
        Map<String, Object> profile = academicClient.readStudentProfileFromAppApi(body);
        Map<String, Object> user = databaseService.bindAcademicStudentProfile(account, profile);
        result.put("user", user);
        String realName = firstText(profile, "xm", "xsmc", "name", "realName", "studentName", "姓名");
        String departmentName = firstText(profile, "xy", "xymc", "xyxm", "academy", "academyName", "departmentName", "院系", "学院");
        String majorName = firstText(profile, "zy", "zymc", "major", "majorName", "专业");
        String className = firstText(profile, "bj", "bjmc", "xzb", "className", "行政班", "班级");
        boolean recognized = !realName.isBlank() || !departmentName.isBlank() || !majorName.isBlank() || !className.isBlank();
        result.put("bound", recognized);
        result.put("realName", realName);
        result.put("departmentName", departmentName);
        result.put("majorName", majorName);
        result.put("className", className);
        if (!recognized) {
          result.put("warning", "HTML 个人信息页失败：" + webWarning + "；app.do getUserInfo 返回了数据，但没有发现姓名、学院、专业或班级字段");
        }
      } catch (Exception appError) {
        result.put("warning", "HTML 个人信息页失败：" + webWarning + "；app.do getUserInfo 失败：" + appError.getMessage());
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castUser(Object value, Map<String, Object> fallback) {
    return value instanceof Map ? (Map<String, Object>) value : fallback;
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
