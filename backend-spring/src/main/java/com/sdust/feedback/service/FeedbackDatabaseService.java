package com.sdust.feedback.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FeedbackDatabaseService {
  private final JdbcTemplate db;
  private final String fallbackCurrentTerm;
  private final LocalDate fallbackTermStart;
  private final Map<String, ResourceConfig> resources;
  private static final String ROLE_PRIORITY_SQL =
      "CASE r.role_key WHEN 'SUPER_ADMIN' THEN 4 WHEN 'DEPARTMENT_ADMIN' THEN 3 WHEN 'CLASS_REPRESENTATIVE' THEN 2 WHEN 'STUDENT' THEN 1 ELSE 0 END";
  private static final String ROLE_PRIORITY_SQL_R2 =
      "CASE r2.role_key WHEN 'SUPER_ADMIN' THEN 4 WHEN 'DEPARTMENT_ADMIN' THEN 3 WHEN 'CLASS_REPRESENTATIVE' THEN 2 WHEN 'STUDENT' THEN 1 ELSE 0 END";

  public FeedbackDatabaseService(
      JdbcTemplate db,
      @Value("${app.academic-current-term:2025-2026-2}") String fallbackCurrentTerm,
      @Value("${app.academic-term-start:2026-03-09}") String fallbackTermStart
  ) {
    this.db = db;
    this.fallbackCurrentTerm = fallbackCurrentTerm;
    this.fallbackTermStart = LocalDate.parse(fallbackTermStart);
    ensureRuntimeSchema();
    this.resources = buildResources();
  }

  public List<String> supportedResources() {
    return new ArrayList<>(resources.keySet());
  }

  public String classNameById(Long classGroupId) {
    if (classGroupId == null || classGroupId <= 0) {
      return "";
    }
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT name FROM class_group WHERE id = ? LIMIT 1",
        classGroupId
    );
    return rows.isEmpty() ? "" : asString(rows.get(0).get("name"));
  }

  public Map<String, Object> currentAcademicCalendar() {
    String termCode = fallbackCurrentTerm;
    LocalDate termStart = fallbackTermStart;
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT academic_year AS academicYear, semester, start_date AS startDate, end_date AS endDate " +
            "FROM term WHERE status = 'ACTIVE' AND start_date IS NOT NULL " +
            "AND start_date <= CURDATE() AND (end_date IS NULL OR end_date >= CURDATE()) " +
            "ORDER BY id DESC LIMIT 1"
    );
    if (!rows.isEmpty()) {
      Map<String, Object> row = rows.get(0);
      termCode = asString(row.get("academicYear")) + "-" + asString(row.get("semester"));
      termStart = LocalDate.parse(asString(row.get("startDate")));
    }

    LocalDate today = LocalDate.now();
    int currentWeek = Math.max(1, (int) (Math.max(0, ChronoUnit.DAYS.between(termStart, today)) / 7) + 1);
    LocalDate weekStart = termStart.plusDays((long) (currentWeek - 1) * 7);
    List<Map<String, Object>> dateRow = new ArrayList<>();
    for (int index = 0; index < 7; index += 1) {
      LocalDate date = weekStart.plusDays(index);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("dayIndex", index);
      item.put("date", date.toString());
      item.put("shortDate", String.format("%02d/%02d", date.getMonthValue(), date.getDayOfMonth()));
      item.put("today", date.equals(today));
      dateRow.add(item);
    }

    Map<String, Object> calendar = new LinkedHashMap<>();
    calendar.put("termCode", termCode);
    calendar.put("termStart", termStart.toString());
    calendar.put("currentWeek", currentWeek);
    calendar.put("today", today.toString());
    calendar.put("dateRow", dateRow);
    return calendar;
  }

  public Map<String, Object> myTimetable(Map<String, Object> user, Integer requestedWeek) {
    Map<String, Object> calendar = currentAcademicCalendar();
    Integer weekNo = requestedWeek == null || requestedWeek <= 0
        ? asInteger(calendar.get("currentWeek"), 1)
        : requestedWeek;
    String termCode = asString(calendar.get("termCode"));
    LocalDate termStart = LocalDate.parse(asString(calendar.get("termStart")));

    Long classGroupId = classGroupId(user);
    List<Map<String, Object>> rows = Collections.emptyList();
    if (classGroupId != null && classGroupId > 0) {
      rows = db.queryForList(
          "SELECT tt.id, tt.week_range AS weeksRaw, tt.day_index AS day, tt.section_index AS serial, " +
              "tt.classroom, tt.guidance_mode AS guidanceMode, tt.planned_teacher_name AS plannedTeacherName, " +
              "tt.actual_teacher_name AS actualTeacherName, c.course_name AS courseName, " +
              "COALESCE(t.teacher_name, tt.actual_teacher_name, tt.planned_teacher_name) AS teacherName, " +
              "cg.name AS className " +
              "FROM teaching_task tt " +
              "JOIN course c ON c.id = tt.course_id " +
              "LEFT JOIN teacher t ON t.id = tt.teacher_id " +
              "JOIN class_group cg ON cg.id = tt.class_group_id " +
              "WHERE tt.class_group_id = ? " +
              "ORDER BY COALESCE(tt.day_index, 99), COALESCE(tt.section_index, 99), tt.id",
          classGroupId
      );
    }

    List<Map<String, Object>> info = rows.stream()
        .filter(row -> includesWeek(asString(row.get("weeksRaw")), weekNo))
        .map(row -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", row.get("id"));
          item.put("day", asInteger(row.get("day"), null));
          item.put("serial", asInteger(row.get("serial"), null));
          item.put("courseName", asString(row.get("courseName")));
          item.put("name", asString(row.get("courseName")));
          item.put("teacherName", asString(row.get("teacherName")));
          item.put("teacher", asString(row.get("teacherName")));
          item.put("classroom", asString(row.get("classroom")));
          item.put("className", asString(row.get("className")));
          item.put("weeksRaw", asString(row.get("weeksRaw")));
          item.put("weekRange", asString(row.get("weeksRaw")));
          item.put("weeks", splitWeeks(asString(row.get("weeksRaw"))));
          item.put("guidanceMode", asString(row.get("guidanceMode")));
          return item;
        })
        .collect(Collectors.toList());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("termCode", termCode);
    result.put("termStart", termStart.toString());
    result.put("currentWeek", calendar.get("currentWeek"));
    result.put("weekNo", weekNo);
    result.put("today", calendar.get("today"));
    result.put("dateRow", buildDateRow(termStart, weekNo));
    result.put("info", info);
    result.put("timetable", info);
    result.put("source", "database");
    return result;
  }

  public List<Map<String, Object>> enrichTimetableTeachers(Map<String, Object> user, List<Map<String, Object>> rows, Integer weekNo) {
    Long classGroupId = classGroupId(user);
    if (classGroupId == null || classGroupId <= 0 || rows == null || rows.isEmpty()) {
      return rows == null ? Collections.emptyList() : rows;
    }
    List<Map<String, Object>> enriched = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> item = new LinkedHashMap<>(row);
      String existingTeacher = firstText(item, "teacherName", "teacher", "actualTeacherName", "plannedTeacherName");
      if (existingTeacher.isBlank()) {
        Map<String, Object> matched = matchTeachingTask(classGroupId, item, weekNo);
        String matchedTeacher = firstText(matched, "teacherName", "actualTeacherName", "plannedTeacherName");
        if (!matchedTeacher.isBlank()) {
          item.put("teacherName", matchedTeacher);
          item.put("teacher", matchedTeacher);
          item.put("plannedTeacherName", firstText(matched, "plannedTeacherName", "teacherName"));
          item.put("actualTeacherName", firstText(matched, "actualTeacherName", "teacherName"));
          item.put("teacherSource", "local-teaching-task");
          item.put("teacherMissingReason", "");
          item.put("teacherRootCause", "");
        }
        if (asString(item.get("className")).isBlank()) {
          item.put("className", classNameById(classGroupId));
        }
      }
      enriched.add(item);
    }
    return enriched;
  }

  public Map<String, Object> findUserByUsername(String username) {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT u.id, u.username, u.password_hash AS passwordHash, u.real_name AS realName, " +
            "u.user_type AS userType, u.department_id AS departmentId, d.name AS departmentName, " +
            "u.class_group_id AS classGroupId, u.status, r.role_key AS role, r.role_name AS roleName " +
            "FROM app_user u " +
            "LEFT JOIN department d ON d.id = u.department_id " +
            "LEFT JOIN user_role ur ON ur.user_id = u.id " +
            "LEFT JOIN role r ON r.id = ur.role_id " +
            "WHERE u.username = ? " +
            "ORDER BY " + ROLE_PRIORITY_SQL + " DESC LIMIT 1",
        username
    );
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> findUserById(Long id) {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT u.id, u.username, u.password_hash AS passwordHash, u.real_name AS realName, " +
            "u.user_type AS userType, u.department_id AS departmentId, d.name AS departmentName, " +
            "u.class_group_id AS classGroupId, u.status, r.role_key AS role, r.role_name AS roleName " +
            "FROM app_user u " +
            "LEFT JOIN department d ON d.id = u.department_id " +
            "LEFT JOIN user_role ur ON ur.user_id = u.id " +
            "LEFT JOIN role r ON r.id = ur.role_id " +
            "WHERE u.id = ? " +
            "ORDER BY " + ROLE_PRIORITY_SQL + " DESC LIMIT 1",
        id
    );
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> ensureAcademicStudentUser(String username) {
    Map<String, Object> existing = findUserByUsername(username);
    if (existing == null) {
      Long userId = insert(
          "INSERT INTO app_user (username, password_hash, real_name, user_type, department_id, class_group_id, phone, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          username,
          "plain:EXTERNAL_ACADEMIC_LOGIN",
          username,
          "STUDENT",
          null,
          null,
          null,
          "ACTIVE"
      );
      Long roleId = ensureRole("STUDENT", "普通学生");
      insert("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
      return findUserById(userId);
    }

    Long roleId = ensureRole("STUDENT", "普通学生");
    if (count("SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_id = ?", asLong(existing.get("id"), 0L), roleId) == 0) {
      insert("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", asLong(existing.get("id"), 0L), roleId);
    }
    return findUserById(asLong(existing.get("id"), 0L));
  }

  public Map<String, Object> bindAcademicStudentProfile(String username, Map<String, Object> profile) {
    Map<String, Object> user = ensureAcademicStudentUser(username);
    if (profile == null || profile.isEmpty()) {
      return user;
    }

    String realName = firstText(profile, "xm", "xsmc", "name", "realName", "studentName", "姓名");
    String departmentName = firstText(profile, "xy", "xymc", "xyxm", "academy", "academyName", "departmentName", "院系", "学院");
    String majorName = firstText(profile, "zy", "zymc", "major", "majorName", "专业");
    String className = firstText(profile, "bj", "bjmc", "xzb", "className", "行政班", "班级");

    Long majorId = majorName.isBlank() ? null : queryId("SELECT id FROM major WHERE name = ? LIMIT 1", majorName);
    Map<String, Object> classContext = className.isBlank() ? Map.of() : findClassGroupContext(className);
    Long classGroupId = classContext.isEmpty() ? null : asLong(classContext.get("id"), 0L);
    if (majorId == null && !classContext.isEmpty()) {
      majorId = asLong(classContext.get("majorId"), 0L);
    }
    Long departmentId = null;
    if (!departmentName.isBlank()) {
      departmentId = findOrCreateDepartment(departmentName);
    } else if (majorId != null) {
      departmentId = queryId("SELECT department_id FROM major WHERE id = ? LIMIT 1", majorId);
    }

    if (departmentId == null) {
      if (!realName.isBlank()) {
        db.update("UPDATE app_user SET real_name = ? WHERE username = ?", realName, username);
        return findUserByUsername(username);
      }
      return user;
    }

    if (majorId == null) {
      majorId = majorName.isBlank() ? findOrCreateMajor(departmentId) : findOrCreateMajor(departmentId, majorName);
    }
    if (classGroupId == null && !className.isBlank()) {
      classGroupId = findOrCreateClassGroup(majorId, className);
    }
    String savedRealName = realName.isBlank() ? textOrDefault(user.get("realName"), username) : realName;
    if (classGroupId == null) {
      db.update("UPDATE app_user SET real_name = ?, department_id = ? WHERE username = ?", savedRealName, departmentId, username);
    } else {
      db.update("UPDATE app_user SET real_name = ?, department_id = ?, class_group_id = ? WHERE username = ?", savedRealName, departmentId, classGroupId, username);
    }
    return findUserByUsername(username);
  }

  public List<Map<String, Object>> users(Map<String, Object> user) {
    String where = "";
    Object[] args = new Object[] {};

    if (isDepartmentAdmin(user)) {
      where = "WHERE u.department_id = ?";
      args = new Object[] {departmentId(user)};
    } else if (!isSuperAdmin(user)) {
      where = "WHERE u.id = ?";
      args = new Object[] {userId(user)};
    }

    return db.queryForList(
        "SELECT u.id, u.username, u.real_name AS realName, u.user_type AS userType, " +
            "u.department_id AS departmentId, u.class_group_id AS classGroupId, u.status, " +
            "d.name AS departmentName, cg.name AS className, r.role_key AS role, r.role_name AS roleName " +
            "FROM app_user u " +
            "LEFT JOIN department d ON d.id = u.department_id " +
            "LEFT JOIN class_group cg ON cg.id = u.class_group_id " +
            "LEFT JOIN user_role ur ON ur.user_id = u.id AND ur.role_id = (" +
            "SELECT ur2.role_id FROM user_role ur2 JOIN role r2 ON r2.id = ur2.role_id " +
            "WHERE ur2.user_id = u.id ORDER BY " + ROLE_PRIORITY_SQL_R2 + " DESC LIMIT 1" +
            ") " +
            "LEFT JOIN role r ON r.id = ur.role_id " +
            where + " GROUP BY u.id, u.username, u.real_name, u.user_type, u.department_id, u.class_group_id, u.status, d.name, cg.name, r.role_key, r.role_name " +
            "ORDER BY u.id DESC",
        args
    );
  }

  public Map<String, Object> updateUserAuthorization(Long targetUserId, Map<String, Object> payload, Map<String, Object> operator) {
    Map<String, Object> target = findUserById(targetUserId);
    if (target == null) {
      throw new IllegalArgumentException("用户不存在");
    }
    if (isDepartmentAdmin(operator)) {
      Long operatorDepartmentId = departmentId(operator);
      Long targetDepartmentId = asLong(target.get("departmentId"), null);
      if (targetDepartmentId != null && !targetDepartmentId.equals(operatorDepartmentId)) {
        throw new IllegalArgumentException("只能维护本院系用户");
      }
    }

    String roleKey = asString(payload.get("role"));
    if (!roleKey.isBlank()) {
      if (!Arrays.asList("STUDENT", "CLASS_REPRESENTATIVE", "DEPARTMENT_ADMIN").contains(roleKey) && !isSuperAdmin(operator)) {
        throw new IllegalArgumentException("无权授予该角色");
      }
      if ("SUPER_ADMIN".equals(roleKey)) {
        throw new IllegalArgumentException("不能在页面中授予超管角色");
      }
      Long roleId = ensureRole(roleKey, roleName(roleKey));
      db.update("DELETE FROM user_role WHERE user_id = ?", targetUserId);
      insert("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)", targetUserId, roleId);
    }

    Long departmentId = asLong(payload.get("departmentId"), null);
    Long classGroupId = asLong(payload.get("classGroupId"), null);
    String realName = asString(payload.get("realName"));
    if (classGroupId != null && classGroupId > 0) {
      Long classDepartmentId = queryId(
          "SELECT m.department_id FROM class_group cg JOIN major m ON m.id = cg.major_id WHERE cg.id = ? LIMIT 1",
          classGroupId
      );
      if (classDepartmentId != null) {
        departmentId = classDepartmentId;
      }
    }
    if (isDepartmentAdmin(operator) && departmentId != null && !departmentId.equals(departmentId(operator))) {
      throw new IllegalArgumentException("只能绑定本院系用户");
    }

    db.update(
        "UPDATE app_user SET real_name = ?, department_id = ?, class_group_id = ? WHERE id = ?",
        realName.isBlank() ? asString(target.get("realName")) : realName,
        departmentId,
        classGroupId,
        targetUserId
    );
    String status = asString(payload.get("status"));
    if (Arrays.asList("ACTIVE", "DISABLED").contains(status)) {
      db.update("UPDATE app_user SET status = ? WHERE id = ?", status, targetUserId);
    }
    return findUserById(targetUserId);
  }

  public Map<String, Object> createManualUser(Map<String, Object> payload, Map<String, Object> operator) {
    String username = firstText(payload, "username", "account", "studentNo", "学号", "账号");
    if (username.isBlank()) {
      throw new IllegalArgumentException("请填写学号或账号");
    }
    if (findUserByUsername(username) != null) {
      throw new IllegalArgumentException("该账号已存在，请直接在列表中授权修改");
    }
    Long userId = insert(
        "INSERT INTO app_user (username, password_hash, real_name, user_type, department_id, class_group_id, phone, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        username,
        "plain:EXTERNAL_ACADEMIC_LOGIN",
        asString(payload.get("realName")).isBlank() ? username : asString(payload.get("realName")),
        "STUDENT",
        null,
        null,
        null,
        "ACTIVE"
    );
    Map<String, Object> auth = new HashMap<>(payload);
    auth.putIfAbsent("role", "STUDENT");
    auth.putIfAbsent("status", "ACTIVE");
    return updateUserAuthorization(userId, auth, operator);
  }

  public Map<String, Object> deleteUser(Long targetUserId, Map<String, Object> operator) {
    Map<String, Object> target = findUserById(targetUserId);
    if (target == null) {
      throw new IllegalArgumentException("用户不存在");
    }
    String targetRole = asString(target.get("role"));
    if ("SUPER_ADMIN".equals(targetRole) || "DEPARTMENT_ADMIN".equals(targetRole)) {
      throw new IllegalArgumentException("管理员账号不能在这里删除，请改为禁用");
    }
    if (Objects.equals(targetUserId, userId(operator))) {
      throw new IllegalArgumentException("不能删除当前登录账号");
    }
    if (isDepartmentAdmin(operator)) {
      Long operatorDepartmentId = departmentId(operator);
      Long targetDepartmentId = asLong(target.get("departmentId"), null);
      if (targetDepartmentId == null || !targetDepartmentId.equals(operatorDepartmentId)) {
        throw new IllegalArgumentException("只能删除本院系学生");
      }
    }
    int feedbackCount =
        count("SELECT COUNT(*) FROM weekly_feedback WHERE student_id = ?", targetUserId) +
            count("SELECT COUNT(*) FROM realtime_feedback WHERE student_id = ?", targetUserId) +
            count("SELECT COUNT(*) FROM feedback_reply WHERE replier_user_id = ?", targetUserId);
    if (feedbackCount > 0) {
      db.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", targetUserId);
      Map<String, Object> result = findUserById(targetUserId);
      result.put("deleted", false);
      result.put("disabled", true);
      result.put("message", "该学生已有反馈/回复记录，为保留审计链路，已改为禁用");
      return result;
    }
    db.update("DELETE FROM user_role WHERE user_id = ?", targetUserId);
    db.update("DELETE FROM app_user WHERE id = ?", targetUserId);
    Map<String, Object> result = new HashMap<>();
    result.put("id", targetUserId);
    result.put("deleted", true);
    result.put("disabled", false);
    return result;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> importUsers(Map<String, Object> payload, Map<String, Object> operator) {
    List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.getOrDefault("rows", Collections.emptyList());
    int importedCount = 0;
    int skippedCount = 0;
    for (Map<String, Object> row : rows) {
      String username = firstText(row, "username", "account", "studentNo", "学号", "账号");
      if (username.isBlank()) {
        skippedCount += 1;
        continue;
      }
      String realName = firstText(row, "realName", "name", "姓名");
      String departmentName = firstText(row, "departmentName", "院系", "学院");
      String majorName = firstText(row, "majorName", "专业");
      String className = firstText(row, "className", "班级", "行政班");
      String role = normalizeRole(firstText(row, "role", "角色"));
      String status = normalizeUserStatus(firstText(row, "status", "状态"));

      Long departmentId = departmentName.isBlank() ? null : findOrCreateDepartment(departmentName);
      Long majorId = null;
      Long classGroupId = null;
      if (departmentId != null) {
        majorId = majorName.isBlank() ? findOrCreateMajor(departmentId) : findOrCreateMajor(departmentId, majorName);
        if (!className.isBlank()) {
          classGroupId = findOrCreateClassGroup(majorId, className);
        }
      }
      Map<String, Object> user = ensureAcademicStudentUser(username);
      Map<String, Object> auth = new HashMap<>();
      auth.put("realName", realName);
      auth.put("departmentId", departmentId);
      auth.put("classGroupId", classGroupId);
      auth.put("role", role);
      auth.put("status", status);
      updateUserAuthorization(asLong(user.get("id"), 0L), auth, operator);
      importedCount += 1;
    }
    Map<String, Object> result = new HashMap<>();
    result.put("importedCount", importedCount);
    result.put("skippedCount", skippedCount);
    return result;
  }

  public Map<String, Object> dashboardSummary(Map<String, Object> user) {
    Scope taskScope = taskScope(user, "m", "wft");
    Scope realtimeScope = realtimeScope(user, "rf");
    Scope weeklyFeedbackScope = weeklyFeedbackScope(user, "m", "wf");
    Map<String, Object> summary = new HashMap<>();
    summary.put(
        "pendingWeeklyTasks",
        count(
            "SELECT COUNT(*) FROM weekly_feedback_task wft " +
                "JOIN class_group cg ON cg.id = wft.class_group_id " +
                "JOIN major m ON m.id = cg.major_id " +
                taskScope.where + " AND wft.status IN ('PENDING', 'IN_PROGRESS')",
            taskScope.args
        )
    );
    summary.put(
        "urgentRealtimeFeedbacks",
        count(
            "SELECT COUNT(*) FROM realtime_feedback rf " +
                realtimeScope.where + " AND rf.urgency_level = 'HIGH' AND rf.status <> 'CLOSED'",
            realtimeScope.args
        )
    );
    summary.put(
        "awaitingReplies",
        count(
            "SELECT COUNT(*) FROM realtime_feedback rf " +
                realtimeScope.where + " AND rf.need_reply = 1 AND rf.status IN ('PENDING', 'PENDING_REPLY')",
            realtimeScope.args
        )
    );
    summary.put("markedSensitiveFeedbacks", flags(user).size());
    summary.put(
        "overdueUnsubmittedTasks",
        count(
            "SELECT COUNT(*) FROM weekly_feedback_task wft " +
                "JOIN class_group cg ON cg.id = wft.class_group_id " +
                "JOIN major m ON m.id = cg.major_id " +
                "LEFT JOIN weekly_feedback wf ON wf.task_id = wft.id " +
                taskScope.where + " AND wf.id IS NULL AND wft.deadline IS NOT NULL AND wft.deadline < NOW()",
            taskScope.args
        )
    );
    summary.put(
        "lowQualityFeedbacks",
        count(
            "SELECT COUNT(*) FROM weekly_feedback wf " +
                "JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
                "JOIN class_group cg ON cg.id = wft.class_group_id " +
                "JOIN major m ON m.id = cg.major_id " +
                weeklyFeedbackScope.where + " AND CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) < 20",
            weeklyFeedbackScope.args
        )
    );
    return summary;
  }

  public List<Map<String, Object>> listResource(String resource, Map<String, Object> user) {
    ResourceConfig config = resources.get(resource);
    if (config == null) {
      return null;
    }

    if ("departments".equals(resource)) {
      if (isSuperAdmin(user)) {
        return db.queryForList(config.listSql);
      }
      return db.queryForList(
          "SELECT id, code, name, created_at AS createdAt FROM department WHERE id = ? ORDER BY id DESC",
          departmentId(user)
      );
    }

    if ("majors".equals(resource)) {
      if (isSuperAdmin(user)) {
        return db.queryForList(config.listSql);
      }
      return db.queryForList(
          "SELECT id, department_id AS departmentId, code, name, created_at AS createdAt FROM major WHERE department_id = ? ORDER BY id DESC",
          departmentId(user)
      );
    }

    if ("classes".equals(resource)) {
      if (isSuperAdmin(user)) {
        return db.queryForList(config.listSql);
      }
      if (isDepartmentAdmin(user)) {
        return db.queryForList(
            "SELECT cg.id, cg.major_id AS majorId, cg.grade_year AS gradeYear, cg.name, cg.created_at AS createdAt " +
                "FROM class_group cg JOIN major m ON m.id = cg.major_id " +
                "WHERE m.department_id = ? ORDER BY cg.id DESC",
            departmentId(user)
        );
      }
      return db.queryForList(
          "SELECT id, major_id AS majorId, grade_year AS gradeYear, name, created_at AS createdAt FROM class_group WHERE id = ? ORDER BY id DESC",
          classGroupId(user)
      );
    }

    if ("teachers".equals(resource)) {
      if (isSuperAdmin(user)) {
        return db.queryForList(config.listSql);
      }
      return db.queryForList(
          "SELECT id, department_id AS departmentId, teacher_no AS teacherNo, teacher_name AS teacherName, created_at AS createdAt FROM teacher WHERE department_id = ? ORDER BY id DESC",
          departmentId(user)
      );
    }

    if ("courses".equals(resource)) {
      if (isSuperAdmin(user)) {
        return db.queryForList(config.listSql);
      }
      return db.queryForList(
          "SELECT id, course_code AS courseCode, course_name AS courseName, department_id AS departmentId, created_at AS createdAt FROM course WHERE department_id = ? ORDER BY id DESC",
          departmentId(user)
      );
    }

    return db.queryForList(config.listSql);
  }

  public Map<String, Object> createResource(String resource, Map<String, Object> payload) {
    ResourceConfig config = resources.get(resource);
    if (config == null) {
      return null;
    }
    Long id = insert(config.insertSql, config.params(payload));
    Map<String, Object> created = new HashMap<>(payload);
    created.put("id", id);
    return created;
  }

  public Map<String, Object> updateResource(String resource, Long id, Map<String, Object> payload, Map<String, Object> user) {
    ResourceConfig config = resources.get(resource);
    if (config == null || config.updateSql == null) {
      return null;
    }
    if (!canWriteResource(resource, id, user)) {
      throw new IllegalArgumentException("无权修改该基础数据");
    }
    Object[] params = append(config.params(payload), id);
    db.update(config.updateSql, params);
    Map<String, Object> updated = new HashMap<>(payload);
    updated.put("id", id);
    return updated;
  }

  public Map<String, Object> deleteResource(String resource, Long id, Map<String, Object> user) {
    ResourceConfig config = resources.get(resource);
    if (config == null || config.deleteSql == null) {
      return null;
    }
    if (!canWriteResource(resource, id, user)) {
      throw new IllegalArgumentException("无权删除该基础数据");
    }
    db.update(config.deleteSql, id);
    Map<String, Object> result = new HashMap<>();
    result.put("id", id);
    result.put("deleted", true);
    return result;
  }

  private boolean canWriteResource(String resource, Long id, Map<String, Object> user) {
    if (isSuperAdmin(user)) {
      return true;
    }
    if (!isDepartmentAdmin(user)) {
      return false;
    }
    Long ownDepartmentId = departmentId(user);
    if ("departments".equals(resource)) {
      return Objects.equals(id, ownDepartmentId);
    }
    Long resourceDepartmentId = null;
    if ("majors".equals(resource)) {
      resourceDepartmentId = queryId("SELECT department_id FROM major WHERE id = ? LIMIT 1", id);
    } else if ("classes".equals(resource)) {
      resourceDepartmentId = queryId("SELECT m.department_id FROM class_group cg JOIN major m ON m.id = cg.major_id WHERE cg.id = ? LIMIT 1", id);
    } else if ("teachers".equals(resource)) {
      resourceDepartmentId = queryId("SELECT department_id FROM teacher WHERE id = ? LIMIT 1", id);
    } else if ("courses".equals(resource)) {
      resourceDepartmentId = queryId("SELECT department_id FROM course WHERE id = ? LIMIT 1", id);
    } else if ("terms".equals(resource)) {
      resourceDepartmentId = ownDepartmentId;
    }
    return resourceDepartmentId != null && Objects.equals(resourceDepartmentId, ownDepartmentId);
  }

  public List<Map<String, Object>> weeklyTasks(Map<String, Object> user) {
    Scope scope = taskScope(user, "m", "wft");
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT wft.id, wft.week_no AS weekNo, cg.name AS className, wft.task_name AS taskName, " +
            "d.name AS departmentName, wft.class_group_id AS classGroupId, wft.feedback_scope AS feedbackScope, " +
            "DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline, wft.status " +
            "FROM weekly_feedback_task wft " +
            "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            "LEFT JOIN department d ON d.id = m.department_id " +
            scope.where + " ORDER BY wft.week_no DESC, wft.id DESC",
        scope.args
    );
    for (Map<String, Object> row : rows) {
      row.put("feedbackScopeLabel", feedbackScopeLabel(asString(row.get("feedbackScope"))));
      row.put("reminderStatus", reminderStatus(asString(row.get("deadline")), true));
    }
    return rows;
  }

  public List<Map<String, Object>> weeklyTaskCourseItems(Map<String, Object> user) {
    Scope scope = taskScope(user, "m", "wft");
    List<Map<String, Object>> tasks = db.queryForList(
        "SELECT wft.id AS taskId, wft.term_id AS termId, wft.week_no AS weekNo, " +
            "wft.class_group_id AS classGroupId, cg.name AS className, d.name AS departmentName, wft.feedback_scope AS feedbackScope, " +
            "DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline, wft.status AS taskStatus " +
            "FROM weekly_feedback_task wft " +
            "JOIN class_group cg ON cg.id = wft.class_group_id " +
            "JOIN major m ON m.id = cg.major_id " +
            "JOIN department d ON d.id = m.department_id " +
            scope.where + " ORDER BY wft.week_no DESC, wft.id DESC",
        scope.args
    );

    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> task : tasks) {
      items.addAll(weeklyTaskCourseItemsForTask(task, user));
    }
    return items;
  }

  private List<Map<String, Object>> weeklyTaskCourseItemsForTask(Map<String, Object> task, Map<String, Object> user) {
    Long taskId = asLong(task.get("taskId"), 0L);
    Long termId = asLong(task.get("termId"), 0L);
    Long classGroupId = asLong(task.get("classGroupId"), 0L);
    Integer weekNo = asInteger(task.get("weekNo"), 1);
    String deadline = asString(task.get("deadline"));
    List<Map<String, Object>> teachingRows = db.queryForList(
        "SELECT tt.id AS teachingTaskId, tt.week_range AS weekRange, tt.guidance_mode AS guidanceMode, " +
            "tt.planned_teacher_name AS plannedTeacherName, tt.actual_teacher_name AS actualTeacherName, " +
            "tt.classroom, c.id AS courseId, c.course_name AS courseName, c.department_id AS courseDepartmentId, " +
            "d.name AS teacherDepartmentName, t.id AS teacherId, COALESCE(t.teacher_name, tt.actual_teacher_name, tt.planned_teacher_name) AS teacherName " +
            "FROM teaching_task tt " +
            "JOIN course c ON c.id = tt.course_id " +
            "LEFT JOIN department d ON d.id = c.department_id " +
            "LEFT JOIN teacher t ON t.id = tt.teacher_id " +
            "WHERE tt.term_id = ? AND tt.class_group_id = ? " +
            "ORDER BY COALESCE(tt.day_index, 99), COALESCE(tt.section_index, 99), tt.id",
        termId,
        classGroupId
    );
    if (teachingRows.isEmpty()) {
      teachingRows = mockTeachingRowsForTask(task);
    }

    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> teaching : teachingRows) {
      if (!includesWeek(asString(teaching.get("weekRange")), weekNo)) {
        continue;
      }
      String feedbackScope = asString(task.get("feedbackScope"));
      if ("FOREIGN_ONLY".equals(feedbackScope) && !isForeignTeachingCourse(teaching)) {
        continue;
      }
      Long courseId = asLong(teaching.get("courseId"), 0L);
      Map<String, Object> feedback = latestWeeklyFeedback(taskId, courseId, isSuperAdmin(user) || isDepartmentAdmin(user) ? null : userId(user));
      Integer wordCount = feedback == null ? 0 : asInteger(feedback.get("feedbackWordCount"), 0);
      String status = feedback == null
          ? (isOverdue(deadline) ? "OVERDUE_MISSING" : "PENDING")
          : (isLate(asString(feedback.get("submittedAt")), deadline) ? "LATE_SUBMITTED" : "SUBMITTED");
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("taskId", taskId);
      item.put("termId", termId);
      item.put("weekNo", weekNo);
      item.put("classGroupId", classGroupId);
      item.put("className", task.get("className"));
      item.put("departmentName", task.get("departmentName"));
      item.put("feedbackScope", task.get("feedbackScope"));
      item.put("feedbackScopeLabel", feedbackScopeLabel(asString(task.get("feedbackScope"))));
      item.put("deadline", deadline);
      item.put("teachingTaskId", teaching.get("teachingTaskId"));
      item.put("courseId", courseId);
      item.put("courseName", teaching.get("courseName"));
      item.put("teacherId", teaching.get("teacherId"));
      item.put("teacherName", teaching.get("teacherName"));
      item.put("plannedTeacherName", teaching.get("plannedTeacherName"));
      item.put("actualTeacherName", teaching.get("actualTeacherName"));
      item.put("teacherDepartmentName", teaching.get("teacherDepartmentName"));
      item.put("weekRange", teaching.get("weekRange"));
      item.put("guidanceMode", teaching.get("guidanceMode"));
      item.put("classroom", teaching.get("classroom"));
      item.put("feedbackId", feedback == null ? null : feedback.get("id"));
      item.put("submittedAt", feedback == null ? null : feedback.get("submittedAt"));
      item.put("feedbackWordCount", wordCount);
      item.put("itemStatus", status);
      item.put("reminderStatus", reminderStatus(deadline, feedback == null));
      item.put("qualityStatus", feedback == null ? "MISSING" : (wordCount < 20 ? "LOW_QUALITY" : "NORMAL"));
      item.put("qualityRemark", feedback == null ? "该课程尚未提交反馈" : (wordCount < 20 ? "字数偏少，疑似应付反馈" : "反馈内容达标"));
      items.add(item);
    }
    return items;
  }

  public List<Map<String, Object>> generateWeeklyTasks(Map<String, Object> payload) {
    Long termId = asLong(payload.get("termId"), 1L);
    Integer weekNo = asInteger(payload.get("weekNo"), 1);
    String deadline = asString(payload.get("deadline"));
    String feedbackScope = normalizeFeedbackScope(asString(payload.get("feedbackScope")), weekNo);
    List<Long> classGroupIds = asLongList(payload.get("classGroupIds"));
    List<Map<String, Object>> classRows;

    if (!classGroupIds.isEmpty()) {
      String placeholders = classGroupIds.stream().map(item -> "?").collect(Collectors.joining(","));
      classRows = db.queryForList(
          "SELECT id, name FROM class_group WHERE id IN (" + placeholders + ")",
          classGroupIds.toArray()
      );
    } else {
      List<Map<String, Object>> teachingTasks = db.queryForList(
          "SELECT DISTINCT cg.id, cg.name, tt.week_range AS weekRange " +
              "FROM teaching_task tt JOIN class_group cg ON cg.id = tt.class_group_id " +
              "LEFT JOIN course c ON c.id = tt.course_id " +
              "LEFT JOIN teacher t ON t.id = tt.teacher_id " +
              "WHERE tt.term_id = ? ORDER BY cg.id ASC",
          termId
      );
      classRows = teachingTasks.stream()
          .filter(item -> includesWeek(asString(item.get("weekRange")), weekNo))
          .collect(Collectors.toList());
      if (classRows.isEmpty()) {
        classRows = db.queryForList("SELECT id, name FROM class_group ORDER BY id ASC");
      }
    }

    List<Map<String, Object>> created = new ArrayList<>();
    for (Map<String, Object> classGroup : classRows) {
      Long classGroupId = asLong(classGroup.get("id"), null);
      Integer existing = db.queryForObject(
          "SELECT COUNT(*) FROM weekly_feedback_task WHERE term_id = ? AND week_no = ? AND class_group_id = ?",
          Integer.class,
          termId,
          weekNo,
          classGroupId
      );
      if (existing != null && existing > 0) {
        continue;
      }

      String taskName = "第" + weekNo + "周" + feedbackScopeLabel(feedbackScope) + "反馈任务";
      Long id = insert(
          "INSERT INTO weekly_feedback_task (term_id, week_no, class_group_id, task_name, deadline, status, feedback_scope) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
          termId,
          weekNo,
          classGroupId,
          taskName,
          deadline.isBlank() ? null : deadline,
          feedbackScope
      );

      Map<String, Object> item = new HashMap<>();
      item.put("id", id);
      item.put("weekNo", weekNo);
      item.put("className", classGroup.get("name"));
      item.put("taskName", taskName);
      item.put("deadline", deadline);
      item.put("status", "PENDING");
      item.put("feedbackScope", feedbackScope);
      item.put("feedbackScopeLabel", feedbackScopeLabel(feedbackScope));
      created.add(item);
    }
    logSyncEvent(
        asLong(payload.get("operatorId"), null),
        "WEEKLY_TASK_GENERATE",
        "SUCCESS",
        "生成第" + weekNo + "周" + feedbackScopeLabel(feedbackScope) + "反馈任务",
        created.size(),
        0,
        "termId=" + termId + ", weekNo=" + weekNo
    );
    return created;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> importTeachingTasks(Map<String, Object> payload) {
    Long termId = asLong(payload.get("termId"), 1L);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.getOrDefault("rows", Collections.emptyList());
    int importedCount = 0;
    int skippedCount = 0;

    for (Map<String, Object> row : rows) {
      String departmentName = firstText(row, "departmentName", "教师所在院系");
      String plannedTeacherName = firstText(row, "plannedTeacherName", "计划授课教师", "授课教师");
      String actualTeacherName = firstText(row, "actualTeacherName", "实际授课教师", "授课教师");
      String className = firstText(row, "className", "上课班级");
      String courseName = firstText(row, "courseName", "开课课程");
      String weekRange = firstText(row, "weeksRaw", "weeks_raw", "weekRange", "上课周次");
      String guidanceMode = firstText(row, "guidanceMode", "辅导方式");
      Integer dayIndex = asInteger(firstText(row, "day", "dayIndex", "weekDay"), null);
      Integer sectionIndex = asInteger(firstText(row, "serial", "sectionIndex", "section"), null);
      String classroom = firstText(row, "classroom", "locationText", "教室", "上课地点");

      if (className.isBlank() || courseName.isBlank() || weekRange.isBlank()) {
        skippedCount += 1;
        continue;
      }

      Long departmentId = findOrCreateDepartment(departmentName);
      Long majorId = findOrCreateMajor(departmentId);
      Long classGroupId = findOrCreateClassGroup(majorId, className);
      Long courseId = findOrCreateCourse(courseName, departmentId);
      Long teacherId = findOrCreateTeacher(departmentId, !actualTeacherName.isBlank() ? actualTeacherName : plannedTeacherName);

      insert(
          "INSERT INTO teaching_task (term_id, course_id, teacher_id, class_group_id, planned_teacher_name, actual_teacher_name, week_range, day_index, section_index, classroom, guidance_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          termId,
          courseId,
          teacherId,
          classGroupId,
          plannedTeacherName,
          actualTeacherName,
          weekRange,
          dayIndex,
          sectionIndex,
          classroom,
          guidanceMode
      );
      importedCount += 1;
    }

    Map<String, Object> result = new HashMap<>();
    result.put("importedCount", importedCount);
    result.put("skippedCount", skippedCount);
    logSyncEvent(
        asLong(payload.get("operatorId"), null),
        "TEACHING_TASK_IMPORT",
        skippedCount > 0 ? "PARTIAL_SUCCESS" : "SUCCESS",
        "导入课表任课关系",
        importedCount,
        skippedCount,
        "termId=" + termId
    );
    return result;
  }

  public List<Map<String, Object>> weeklyFeedbacks(Map<String, Object> user) {
    Scope scope = weeklyFeedbackScope(user, "m", "wf");
    return db.queryForList(
        "SELECT wf.id, wf.class_group_name AS className, c.course_name AS courseName, " +
            "d.name AS teacherDepartmentName, " +
            "wf.planned_teacher_name AS plannedTeacherName, wf.actual_teacher_name AS actualTeacherName, " +
            "wf.week_range AS weekRange, wf.assignment_assessment AS assignmentAssessment, " +
            "wf.guidance_mode AS guidanceMode, wf.learning_outcome AS learningOutcome, " +
            "wf.content_arrangement_eval AS contentArrangementEval, wf.co_teacher_evaluation AS coTeacherEvaluation, " +
            "wf.issue_suggestion AS issueSuggestion, wf.hardware_issue AS hardwareIssue, wf.remark, " +
            "wf.need_reply AS needReply, wf.status, " +
            "CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) AS feedbackWordCount, " +
            "CASE WHEN CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) < 20 THEN 'LOW_QUALITY' ELSE 'NORMAL' END AS qualityStatus, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id AND ff.flag_type = 'AI_RISK_LEVEL' ORDER BY ff.id DESC LIMIT 1) AS aiRiskLevel, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id AND ff.flag_type = 'AI_QUALITY_LEVEL' ORDER BY ff.id DESC LIMIT 1) AS aiQualityLevel, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id AND ff.flag_type = 'AI_CATEGORY' ORDER BY ff.id DESC LIMIT 1) AS aiCategory, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id AND ff.flag_type = 'AI_SUGGESTION' ORDER BY ff.id DESC LIMIT 1) AS aiSuggestion, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id AND ff.flag_type = 'AI_ERROR' ORDER BY ff.id DESC LIMIT 1) AS aiError, " +
            "(SELECT fr.reply_content FROM feedback_reply fr WHERE fr.feedback_type = 'WEEKLY' AND fr.feedback_id = wf.id ORDER BY fr.created_at DESC, fr.id DESC LIMIT 1) AS latestReplyContent, " +
            "(SELECT DATE_FORMAT(fr.created_at, '%Y-%m-%d %H:%i:%s') FROM feedback_reply fr WHERE fr.feedback_type = 'WEEKLY' AND fr.feedback_id = wf.id ORDER BY fr.created_at DESC, fr.id DESC LIMIT 1) AS latestReplyAt " +
            "FROM weekly_feedback wf " +
            "LEFT JOIN course c ON c.id = wf.course_id " +
            "LEFT JOIN department d ON d.id = c.department_id " +
            "LEFT JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
            "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            scope.where + " ORDER BY wf.id DESC",
        scope.args
    );
  }

  public Map<String, Object> createWeeklyFeedback(Map<String, Object> feedback) {
    Long taskId = asLong(feedback.get("taskId"), 1L);
    Long studentId = asLong(feedback.get("studentId"), 3L);
    Long courseId = asLong(feedback.get("courseId"), 1L);
    Long existingId = queryId(
        "SELECT id FROM weekly_feedback WHERE task_id = ? AND student_id = ? AND course_id = ? ORDER BY id DESC LIMIT 1",
        taskId,
        studentId,
        courseId
    );
    Long id;
    if (existingId == null) {
      id = insert(
          "INSERT INTO weekly_feedback (task_id, student_id, course_id, teacher_id, planned_teacher_name, actual_teacher_name, class_group_name, week_range, assignment_assessment, guidance_mode, learning_outcome, content_arrangement_eval, co_teacher_evaluation, issue_suggestion, hardware_issue, remark, need_reply, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED')",
          taskId,
          studentId,
          courseId,
          asLong(feedback.get("teacherId"), null),
          asString(feedback.get("plannedTeacherName")),
          asString(feedback.get("actualTeacherName")),
          asString(feedback.get("classGroupName")),
          asString(feedback.get("weekRange")),
          asString(feedback.get("assignmentAssessment")),
          asString(feedback.get("guidanceMode")),
          asString(feedback.get("learningOutcome")),
          asString(feedback.get("contentArrangementEval")),
          asString(feedback.get("coTeacherEvaluation")),
          asString(feedback.get("issueSuggestion")),
          asString(feedback.get("hardwareIssue")),
          asString(feedback.get("remark")),
          asBoolean(feedback.get("needReply")) ? 1 : 0
      );
    } else {
      id = existingId;
      db.update(
          "UPDATE weekly_feedback SET teacher_id = ?, planned_teacher_name = ?, actual_teacher_name = ?, class_group_name = ?, " +
              "week_range = ?, assignment_assessment = ?, guidance_mode = ?, learning_outcome = ?, content_arrangement_eval = ?, " +
              "co_teacher_evaluation = ?, issue_suggestion = ?, hardware_issue = ?, remark = ?, need_reply = ?, status = 'SUBMITTED', created_at = NOW() " +
              "WHERE id = ?",
          asLong(feedback.get("teacherId"), null),
          asString(feedback.get("plannedTeacherName")),
          asString(feedback.get("actualTeacherName")),
          asString(feedback.get("classGroupName")),
          asString(feedback.get("weekRange")),
          asString(feedback.get("assignmentAssessment")),
          asString(feedback.get("guidanceMode")),
          asString(feedback.get("learningOutcome")),
          asString(feedback.get("contentArrangementEval")),
          asString(feedback.get("coTeacherEvaluation")),
          asString(feedback.get("issueSuggestion")),
          asString(feedback.get("hardwareIssue")),
          asString(feedback.get("remark")),
          asBoolean(feedback.get("needReply")) ? 1 : 0,
          id
      );
    }
    db.update("UPDATE weekly_feedback_task SET status = 'IN_PROGRESS' WHERE id = ? AND status = 'PENDING'", taskId);
    updateWeeklyTaskCompletion(taskId, studentId);
    db.update("DELETE FROM feedback_flag WHERE feedback_type = 'WEEKLY' AND feedback_id = ?", id);
    markSensitiveTerms("WEEKLY", id, Arrays.asList(
        asString(feedback.get("learningOutcome")),
        asString(feedback.get("contentArrangementEval")),
        asString(feedback.get("coTeacherEvaluation")),
        asString(feedback.get("issueSuggestion")),
        asString(feedback.get("hardwareIssue")),
        asString(feedback.get("remark"))
    ));
    Map<String, Object> created = new HashMap<>(feedback);
    created.put("id", id);
    created.put("status", "SUBMITTED");
    return created;
  }

  public boolean canSubmitWeeklyFeedbackForCourse(Map<String, Object> feedback, Map<String, Object> user) {
    if (!Arrays.asList("STUDENT", "CLASS_REPRESENTATIVE").contains(role(user))) {
      return false;
    }
    Long taskId = asLong(feedback.get("taskId"), null);
    Long courseId = asLong(feedback.get("courseId"), null);
    Long classGroupId = classGroupId(user);
    if (taskId == null || courseId == null || classGroupId == null || classGroupId <= 0) {
      return false;
    }
    List<Map<String, Object>> tasks = db.queryForList(
        "SELECT wft.id AS taskId, wft.term_id AS termId, wft.week_no AS weekNo, " +
            "wft.class_group_id AS classGroupId, cg.name AS className, d.name AS departmentName, " +
            "DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline, wft.status AS taskStatus " +
            "FROM weekly_feedback_task wft " +
            "JOIN class_group cg ON cg.id = wft.class_group_id " +
            "JOIN major m ON m.id = cg.major_id " +
            "JOIN department d ON d.id = m.department_id " +
            "WHERE wft.id = ? AND wft.class_group_id = ? LIMIT 1",
        taskId,
        classGroupId
    );
    if (tasks.isEmpty()) {
      return false;
    }
    return weeklyTaskCourseItemsForTask(tasks.get(0), user).stream()
        .anyMatch(item -> courseId.equals(asLong(item.get("courseId"), null)));
  }

  public List<Map<String, Object>> realtimeFeedbacks(Map<String, Object> user) {
    Scope scope = realtimeScope(user, "rf");
    return db.queryForList(
        "SELECT rf.id, rf.feedback_type AS type, rf.title, rf.content, rf.location_text AS locationText, " +
            "rf.urgency_level AS urgencyLevel, rf.status, rf.need_reply AS needReply, " +
            "u.real_name AS studentName, d.name AS departmentName, " +
            "(SELECT COUNT(*) FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id) AS flagCount, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id AND ff.flag_type = 'AI_RISK_LEVEL' ORDER BY ff.id DESC LIMIT 1) AS aiRiskLevel, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id AND ff.flag_type = 'AI_QUALITY_LEVEL' ORDER BY ff.id DESC LIMIT 1) AS aiQualityLevel, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id AND ff.flag_type = 'AI_CATEGORY' ORDER BY ff.id DESC LIMIT 1) AS aiCategory, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id AND ff.flag_type = 'AI_SUGGESTION' ORDER BY ff.id DESC LIMIT 1) AS aiSuggestion, " +
            "(SELECT ff.flag_value FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id AND ff.flag_type = 'AI_ERROR' ORDER BY ff.id DESC LIMIT 1) AS aiError, " +
            "(SELECT fr.reply_content FROM feedback_reply fr WHERE fr.feedback_type = 'REALTIME' AND fr.feedback_id = rf.id ORDER BY fr.created_at DESC, fr.id DESC LIMIT 1) AS latestReplyContent, " +
            "(SELECT DATE_FORMAT(fr.created_at, '%Y-%m-%d %H:%i:%s') FROM feedback_reply fr WHERE fr.feedback_type = 'REALTIME' AND fr.feedback_id = rf.id ORDER BY fr.created_at DESC, fr.id DESC LIMIT 1) AS latestReplyAt, " +
            "DATE_FORMAT(rf.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
            "FROM realtime_feedback rf " +
            "LEFT JOIN app_user u ON u.id = rf.student_id " +
            "LEFT JOIN department d ON d.id = rf.department_id " +
            scope.where + " ORDER BY rf.created_at DESC, rf.id DESC",
        scope.args
    );
  }

  public Map<String, Object> createRealtimeFeedback(Map<String, Object> feedback) {
    boolean needReply = asBoolean(feedback.get("needReply"));
    String status = needReply ? "PENDING_REPLY" : "SUBMITTED";
    Long studentId = asLong(feedback.get("studentId"), 1L);
    Long departmentId = asLong(feedback.get("departmentId"), null);
    if (departmentId == null || departmentId <= 0) {
      Map<String, Object> student = findUserById(studentId);
      departmentId = resolveUserDepartmentId(student);
    }
    Long id = insert(
        "INSERT INTO realtime_feedback (student_id, department_id, feedback_type, title, content, location_text, need_reply, urgency_level, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        studentId,
        departmentId,
        asString(feedback.get("type")),
        asString(feedback.get("title")),
        asString(feedback.get("content")),
        asString(feedback.get("locationText")),
        needReply ? 1 : 0,
        asString(feedback.get("urgencyLevel")).isBlank() ? "MEDIUM" : asString(feedback.get("urgencyLevel")),
        status
    );
    markSensitiveTerms("REALTIME", id, Arrays.asList(
        asString(feedback.get("title")),
        asString(feedback.get("content")),
        asString(feedback.get("locationText"))
    ));
    Map<String, Object> created = new HashMap<>(feedback);
    created.put("id", id);
    created.put("departmentId", departmentId);
    created.put("status", status);
    return created;
  }

  public Map<String, Object> updateRealtimeFeedback(Long feedbackId, Map<String, Object> feedback, Map<String, Object> user) {
    if (!canModifyRealtimeFeedback(feedbackId, user)) {
      throw new IllegalArgumentException("只能修改本人尚未处理的实时反馈");
    }
    boolean needReply = asBoolean(feedback.get("needReply"));
    String status = needReply ? "PENDING_REPLY" : "SUBMITTED";
    db.update(
        "UPDATE realtime_feedback SET feedback_type = ?, title = ?, content = ?, location_text = ?, need_reply = ?, urgency_level = ?, status = ? WHERE id = ?",
        asString(feedback.get("type")),
        asString(feedback.get("title")),
        asString(feedback.get("content")),
        asString(feedback.get("locationText")),
        needReply ? 1 : 0,
        asString(feedback.get("urgencyLevel")).isBlank() ? "MEDIUM" : asString(feedback.get("urgencyLevel")),
        status,
        feedbackId
    );
    db.update("DELETE FROM feedback_flag WHERE feedback_type = 'REALTIME' AND feedback_id = ?", feedbackId);
    markSensitiveTerms("REALTIME", feedbackId, Arrays.asList(
        asString(feedback.get("title")),
        asString(feedback.get("content")),
        asString(feedback.get("locationText"))
    ));
    return realtimeFeedbackById(feedbackId);
  }

  public Map<String, Object> deleteRealtimeFeedback(Long feedbackId, Map<String, Object> user) {
    if (!canModifyRealtimeFeedback(feedbackId, user)) {
      throw new IllegalArgumentException("只能删除本人尚未处理的实时反馈");
    }
    db.update("DELETE FROM feedback_flag WHERE feedback_type = 'REALTIME' AND feedback_id = ?", feedbackId);
    db.update("DELETE FROM realtime_feedback WHERE id = ?", feedbackId);
    Map<String, Object> result = new HashMap<>();
    result.put("id", feedbackId);
    result.put("deleted", true);
    return result;
  }

  private boolean canModifyRealtimeFeedback(Long feedbackId, Map<String, Object> user) {
    return count(
        "SELECT COUNT(*) FROM realtime_feedback WHERE id = ? AND student_id = ? AND status IN ('SUBMITTED', 'PENDING_REPLY')",
        feedbackId,
        userId(user)
    ) > 0;
  }

  private Map<String, Object> realtimeFeedbackById(Long feedbackId) {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT id, feedback_type AS type, title, content, location_text AS locationText, urgency_level AS urgencyLevel, status, need_reply AS needReply, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt FROM realtime_feedback WHERE id = ?",
        feedbackId
    );
    return rows.isEmpty() ? Map.of("id", feedbackId) : rows.get(0);
  }

  public Long resolveUserDepartmentId(Map<String, Object> user) {
    Long explicitDepartmentId = departmentId(user);
    if (explicitDepartmentId != null && explicitDepartmentId > 0) {
      return explicitDepartmentId;
    }
    Long classGroupId = classGroupId(user);
    if (classGroupId == null || classGroupId <= 0) {
      return null;
    }
    Long inferred = queryId(
        "SELECT m.department_id FROM class_group cg JOIN major m ON m.id = cg.major_id WHERE cg.id = ? LIMIT 1",
        classGroupId
    );
    return inferred == null || inferred <= 0 ? null : inferred;
  }

  public List<Map<String, Object>> feedbackReplies(String feedbackType, Long feedbackId) {
    return db.queryForList(
        "SELECT fr.id, fr.feedback_type AS feedbackType, fr.feedback_id AS feedbackId, " +
            "fr.replier_user_id AS replierUserId, u.real_name AS replierName, fr.reply_content AS replyContent, " +
            "DATE_FORMAT(fr.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
            "FROM feedback_reply fr " +
            "LEFT JOIN app_user u ON u.id = fr.replier_user_id " +
            "WHERE fr.feedback_type = ? AND fr.feedback_id = ? " +
            "ORDER BY fr.created_at DESC, fr.id DESC",
        normalizeFeedbackType(feedbackType),
        feedbackId
    );
  }

  public boolean canAccessFeedback(Map<String, Object> feedback, Map<String, Object> user) {
    String feedbackType = normalizeFeedbackType(asString(feedback.get("feedbackType")));
    Long feedbackId = asLong(feedback.get("feedbackId"), null);
    if (feedbackId == null) {
      feedbackId = asLong(feedback.get("id"), null);
    }
    if (feedbackId == null) {
      return false;
    }
    if ("REALTIME".equals(feedbackType)) {
      Scope scope = realtimeScope(user, "rf");
      return count(
          "SELECT COUNT(*) FROM realtime_feedback rf " + scope.where + " AND rf.id = ?",
          append(scope.args, feedbackId)
      ) > 0;
    }
    if ("WEEKLY".equals(feedbackType)) {
      Scope scope = weeklyFeedbackScope(user, "m", "wf");
      return count(
          "SELECT COUNT(*) FROM weekly_feedback wf " +
              "LEFT JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
              "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
              "LEFT JOIN major m ON m.id = cg.major_id " +
              scope.where + " AND wf.id = ?",
          append(scope.args, feedbackId)
      ) > 0;
    }
    return false;
  }

  public Map<String, Object> replyFeedback(Map<String, Object> reply, Map<String, Object> user) {
    String feedbackType = asString(reply.get("feedbackType")).toUpperCase(Locale.ROOT);
    Long feedbackId = asLong(reply.get("feedbackId"), null);
    String nextStatus = normalizeFeedbackStatus(asString(reply.get("status")), "CLOSED");
    String replyContent = asString(reply.get("replyContent"));
    if (replyContent.isBlank()) {
      replyContent = "管理员已更新处理状态：" + nextStatus;
    }
    insert(
        "INSERT INTO feedback_reply (feedback_type, feedback_id, replier_user_id, reply_content) VALUES (?, ?, ?, ?)",
        feedbackType,
        feedbackId,
        userId(user),
        replyContent
    );
    updateStatus(feedbackType, feedbackId, nextStatus);
    Map<String, Object> result = new HashMap<>(reply);
    result.put("status", nextStatus);
    return result;
  }

  public Map<String, Object> updateFeedbackStatus(Map<String, Object> feedback) {
    String feedbackType = normalizeFeedbackType(asString(feedback.get("feedbackType")));
    Long feedbackId = asLong(feedback.get("feedbackId"), null);
    String status = normalizeFeedbackStatus(asString(feedback.get("status")), "IN_PROGRESS");
    updateStatus(feedbackType, feedbackId, status);
    Map<String, Object> result = new HashMap<>(feedback);
    result.put("status", status);
    return result;
  }

  public List<Map<String, Object>> flags(Map<String, Object> user) {
    if (isSuperAdmin(user)) {
      return db.queryForList(
          "SELECT id, feedback_type AS feedbackType, feedback_id AS feedbackId, flag_type AS flagType, " +
              "flag_value AS flagValue, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
              "FROM feedback_flag ORDER BY created_at DESC, id DESC"
      );
    }

    if (isDepartmentAdmin(user)) {
      return db.queryForList(
          "SELECT ff.id, ff.feedback_type AS feedbackType, ff.feedback_id AS feedbackId, ff.flag_type AS flagType, " +
              "ff.flag_value AS flagValue, DATE_FORMAT(ff.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
              "FROM feedback_flag ff " +
              "LEFT JOIN realtime_feedback rf ON ff.feedback_type = 'REALTIME' AND rf.id = ff.feedback_id " +
              "LEFT JOIN weekly_feedback wf ON ff.feedback_type = 'WEEKLY' AND wf.id = ff.feedback_id " +
              "LEFT JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
              "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
              "LEFT JOIN major m ON m.id = cg.major_id " +
              "WHERE rf.department_id = ? OR m.department_id = ? " +
              "ORDER BY ff.created_at DESC, ff.id DESC",
          departmentId(user),
          departmentId(user)
      );
    }

    return db.queryForList(
        "SELECT ff.id, ff.feedback_type AS feedbackType, ff.feedback_id AS feedbackId, ff.flag_type AS flagType, " +
            "ff.flag_value AS flagValue, DATE_FORMAT(ff.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
            "FROM feedback_flag ff " +
            "LEFT JOIN realtime_feedback rf ON ff.feedback_type = 'REALTIME' AND rf.id = ff.feedback_id " +
            "LEFT JOIN weekly_feedback wf ON ff.feedback_type = 'WEEKLY' AND wf.id = ff.feedback_id " +
            "WHERE rf.student_id = ? OR wf.student_id = ? " +
            "ORDER BY ff.created_at DESC, ff.id DESC",
        userId(user),
        userId(user)
    );
  }

  public void saveAiFeedbackAnalysis(String feedbackType, Long feedbackId, Map<String, Object> analysis) {
    if (feedbackId == null || feedbackId <= 0 || analysis == null || analysis.isEmpty()) {
      return;
    }
    db.update("DELETE FROM feedback_flag WHERE feedback_type = ? AND feedback_id = ? AND flag_type LIKE 'AI_%'", feedbackType, feedbackId);
    insertAiFlag(feedbackType, feedbackId, "AI_RISK_LEVEL", asString(analysis.get("riskLevel")));
    insertAiFlag(feedbackType, feedbackId, "AI_QUALITY_LEVEL", asString(analysis.get("qualityLevel")));
    insertAiFlag(feedbackType, feedbackId, "AI_CATEGORY", asString(analysis.get("category")));
    insertAiFlag(feedbackType, feedbackId, "AI_REASON", asString(analysis.get("reason")));
    insertAiFlag(feedbackType, feedbackId, "AI_SUGGESTION", asString(analysis.get("suggestion")));
    if (asBoolean(analysis.get("sensitive"))) {
      insertAiFlag(feedbackType, feedbackId, "AI_SENSITIVE", "AI 判断存在敏感风险");
    }
  }

  public void saveAiFeedbackError(String feedbackType, Long feedbackId, String message) {
    if (feedbackId == null || feedbackId <= 0) {
      return;
    }
    db.update("DELETE FROM feedback_flag WHERE feedback_type = ? AND feedback_id = ? AND flag_type LIKE 'AI_%'", feedbackType, feedbackId);
    insertAiFlag(feedbackType, feedbackId, "AI_ERROR", message == null || message.isBlank() ? "AI 分析失败" : message);
  }

  public Map<String, Object> feedbackForAiAnalysis(String feedbackType, Long feedbackId) {
    String normalizedType = normalizeFeedbackType(feedbackType);
    List<Map<String, Object>> rows;
    if ("WEEKLY".equals(normalizedType)) {
      rows = db.queryForList(
          "SELECT wf.id, c.course_name AS courseName, wf.actual_teacher_name AS actualTeacherName, " +
              "wf.learning_outcome AS learningOutcome, wf.issue_suggestion AS issueSuggestion, " +
              "wf.hardware_issue AS hardwareIssue, wf.remark " +
              "FROM weekly_feedback wf LEFT JOIN course c ON c.id = wf.course_id WHERE wf.id = ? LIMIT 1",
          feedbackId
      );
    } else {
      rows = db.queryForList(
          "SELECT id, title, content, location_text AS locationText, urgency_level AS urgencyLevel FROM realtime_feedback WHERE id = ? LIMIT 1",
          feedbackId
      );
    }
    return rows.isEmpty() ? null : rows.get(0);
  }

  private void insertAiFlag(String feedbackType, Long feedbackId, String flagType, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    insert(
        "INSERT INTO feedback_flag (feedback_type, feedback_id, flag_type, flag_value) VALUES (?, ?, ?, ?)",
        feedbackType,
        feedbackId,
        flagType,
        value
    );
  }

  public List<Map<String, Object>> weeklyTaskCompliance(Map<String, Object> user) {
    Scope scope = taskScope(user, "m", "wft");
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT " +
            "wft.id AS taskId, wft.week_no AS weekNo, d.name AS departmentName, cg.name AS className, " +
            "monitor.id AS monitorUserId, monitor.real_name AS monitorName, " +
            "DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline, " +
            "wf.id AS feedbackId, DATE_FORMAT(wf.created_at, '%Y-%m-%d %H:%i:%s') AS submittedAt, " +
            "CASE " +
            "WHEN wf.id IS NULL AND wft.deadline IS NOT NULL AND wft.deadline < NOW() THEN 'OVERDUE_MISSING' " +
            "WHEN wf.id IS NULL THEN 'PENDING' " +
            "WHEN wft.deadline IS NOT NULL AND wf.created_at > wft.deadline THEN 'LATE_SUBMITTED' " +
            "ELSE 'SUBMITTED' END AS complianceStatus, " +
            "CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) AS feedbackWordCount, " +
            "CASE " +
            "WHEN wf.id IS NULL THEN '未提交' " +
            "WHEN CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) < 20 THEN '字数偏少，建议标记为低质量反馈' " +
            "ELSE '反馈内容达标' END AS qualityRemark " +
            "FROM weekly_feedback_task wft " +
            "JOIN class_group cg ON cg.id = wft.class_group_id " +
            "JOIN major m ON m.id = cg.major_id " +
            "JOIN department d ON d.id = m.department_id " +
            "LEFT JOIN app_user monitor ON monitor.class_group_id = cg.id " +
            "AND EXISTS (SELECT 1 FROM user_role mur JOIN role mr ON mr.id = mur.role_id " +
            "WHERE mur.user_id = monitor.id AND mr.role_key = 'CLASS_REPRESENTATIVE') " +
            "LEFT JOIN weekly_feedback wf ON wf.task_id = wft.id AND (monitor.id IS NULL OR wf.student_id = monitor.id) " +
            scope.where + " " +
            "ORDER BY wft.week_no DESC, wft.id DESC",
        scope.args
    );
    for (Map<String, Object> row : rows) {
      List<Map<String, Object>> items = weeklyTaskCourseItemsForTask(row, user);
      int total = items.size();
      long submitted = items.stream().filter(item -> item.get("feedbackId") != null).count();
      long missing = total - submitted;
      long lowQuality = items.stream().filter(item -> "LOW_QUALITY".equals(asString(item.get("qualityStatus")))).count();
      row.put("totalCourseCount", total);
      row.put("submittedCourseCount", submitted);
      row.put("missingCourseCount", missing);
      row.put("lowQualityCount", lowQuality);
      if (total > 0 && missing == 0) {
        row.put("complianceStatus", lowQuality > 0 ? "SUBMITTED_WITH_LOW_QUALITY" : "SUBMITTED");
      } else if (missing > 0 && isOverdue(asString(row.get("deadline")))) {
        row.put("complianceStatus", "OVERDUE_MISSING");
      } else if (missing > 0) {
        row.put("complianceStatus", "PENDING");
      }
      row.put("qualityRemark", missing > 0
          ? "仍有 " + missing + " 门课未反馈"
          : (lowQuality > 0 ? lowQuality + " 门课字数偏少" : "本周逐门反馈完成"));
    }
    return rows;
  }

  public Map<String, Object> weeklyFeedbackAnalytics(Map<String, Object> user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("byClass", aggregateWeeklyFeedback(user, "cg.id", "cg.name", "className"));
    result.put("byCourse", aggregateWeeklyFeedback(user, "c.id", "c.course_name", "courseName"));
    result.put("byTeacher", aggregateWeeklyFeedback(user, "COALESCE(wf.teacher_id, 0)", "COALESCE(t.teacher_name, wf.actual_teacher_name, wf.planned_teacher_name, '待确认教师')", "teacherName"));
    return result;
  }

  public List<Map<String, Object>> weeklyFeedbackSummaries(Map<String, Object> user) {
    Scope scope = weeklyFeedbackScope(user, "m", "wf");
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT COALESCE(t.teacher_name, wf.actual_teacher_name, wf.planned_teacher_name, '待确认教师') AS teacherName, " +
            "c.course_name AS courseName, wf.class_group_name AS className, " +
            "wf.learning_outcome AS learningOutcome, wf.content_arrangement_eval AS contentArrangementEval, " +
            "wf.co_teacher_evaluation AS coTeacherEvaluation, wf.issue_suggestion AS issueSuggestion, " +
            "wf.hardware_issue AS hardwareIssue, wf.remark, " +
            "COUNT(ff.id) AS sensitiveFlagCount " +
            "FROM weekly_feedback wf " +
            "LEFT JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
            "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            "LEFT JOIN course c ON c.id = wf.course_id " +
            "LEFT JOIN teacher t ON t.id = wf.teacher_id " +
            "LEFT JOIN feedback_flag ff ON ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id " +
            scope.where + " GROUP BY wf.id, teacherName, courseName, className, wf.learning_outcome, wf.content_arrangement_eval, wf.co_teacher_evaluation, wf.issue_suggestion, wf.hardware_issue, wf.remark " +
            "ORDER BY MAX(wf.created_at) DESC, wf.id DESC",
        scope.args
    );
    Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String key = asString(row.get("teacherName")) + "||" + asString(row.get("courseName"));
      Map<String, Object> group = groups.computeIfAbsent(key, ignored -> {
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("teacherName", asString(row.get("teacherName")));
        next.put("courseName", asString(row.get("courseName")));
        next.put("classes", new ArrayList<String>());
        next.put("feedbackCount", 0);
        next.put("sensitiveFlagCount", 0);
        next.put("positivePoints", new ArrayList<String>());
        next.put("issues", new ArrayList<String>());
        next.put("hardwareIssues", new ArrayList<String>());
        return next;
      });
      ((List<String>) group.get("classes")).add(asString(row.get("className")));
      group.put("feedbackCount", asInteger(group.get("feedbackCount"), 0) + 1);
      group.put("sensitiveFlagCount", asInteger(group.get("sensitiveFlagCount"), 0) + asInteger(row.get("sensitiveFlagCount"), 0));
      appendIfUseful((List<String>) group.get("positivePoints"), row.get("learningOutcome"));
      appendIfUseful((List<String>) group.get("positivePoints"), row.get("contentArrangementEval"));
      appendIfUseful((List<String>) group.get("positivePoints"), row.get("coTeacherEvaluation"));
      appendIfUseful((List<String>) group.get("issues"), row.get("issueSuggestion"));
      appendIfUseful((List<String>) group.get("hardwareIssues"), row.get("hardwareIssue"));
      appendIfUseful((List<String>) group.get("issues"), row.get("remark"));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> group : groups.values()) {
      List<String> classes = uniqueStrings((List<String>) group.get("classes"));
      List<String> positives = uniqueStrings((List<String>) group.get("positivePoints"));
      List<String> issues = uniqueStrings((List<String>) group.get("issues"));
      List<String> hardware = uniqueStrings((List<String>) group.get("hardwareIssues"));
      int sensitiveCount = asInteger(group.get("sensitiveFlagCount"), 0);
      String riskLevel = sensitiveCount > 0 || !hardware.isEmpty() ? "HIGH" : (!issues.isEmpty() ? "MEDIUM" : "LOW");
      group.put("classes", String.join("、", classes));
      group.put("positiveSummary", positives.isEmpty() ? "暂无明显正向反馈" : String.join("；", positives));
      group.put("issueSummary", issues.isEmpty() ? "暂无集中问题" : String.join("；", issues));
      group.put("hardwareSummary", hardware.isEmpty() ? "暂无硬件问题" : String.join("；", hardware));
      group.put("riskLevel", riskLevel);
      group.put("modelSummary", buildRuleBasedSummary(group));
      mergePersistedAiSummary(group);
      result.add(group);
    }
    return result;
  }

  public List<Map<String, Object>> saveWeeklyAiSummaries(List<Map<String, Object>> summaries) {
    for (Map<String, Object> summary : summaries) {
      String targetKey = weeklySummaryTargetKey(summary);
      String aiSummary = asString(summary.get("aiSummary"));
      if (targetKey.isBlank() || aiSummary.isBlank()) {
        continue;
      }
      db.update("DELETE FROM ai_summary WHERE target_type = 'WEEKLY_FEEDBACK' AND target_key = ?", targetKey);
      insert(
          "INSERT INTO ai_summary (target_type, target_id, target_key, summary_text, model_name, risk_level, suggestions_text) VALUES (?, ?, ?, ?, ?, ?, ?)",
          "WEEKLY_FEEDBACK",
          stableTargetId(targetKey),
          targetKey,
          aiSummary,
          asString(summary.get("aiProvider")).isBlank() ? "deepseek" : asString(summary.get("aiProvider")),
          asString(summary.get("aiRiskLevel")),
          aiSuggestionsText(summary.get("aiSuggestions"))
      );
    }
    return summaries;
  }

  public List<Map<String, Object>> syncLogs(Map<String, Object> user) {
    if (!isSuperAdmin(user) && !isDepartmentAdmin(user)) {
      return Collections.emptyList();
    }
    return db.queryForList(
        "SELECT sl.id, sl.operator_user_id AS operatorUserId, u.real_name AS operatorName, " +
            "sl.action_type AS actionType, sl.status, sl.message, sl.success_count AS successCount, " +
            "sl.failure_count AS failureCount, sl.detail_text AS detailText, " +
            "DATE_FORMAT(sl.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
            "FROM sync_log sl LEFT JOIN app_user u ON u.id = sl.operator_user_id " +
            "ORDER BY sl.id DESC LIMIT 100"
    );
  }

  public void logSyncEvent(Long operatorUserId, String actionType, String status, String message, int successCount, int failureCount, String detailText) {
    db.update(
        "INSERT INTO sync_log (operator_user_id, action_type, status, message, success_count, failure_count, detail_text) VALUES (?, ?, ?, ?, ?, ?, ?)",
        operatorUserId,
        actionType,
        status,
        message,
        successCount,
        failureCount,
        detailText
    );
  }

  public List<Map<String, Object>> reminderRules(Map<String, Object> user) {
    if (!isSuperAdmin(user) && !isDepartmentAdmin(user)) {
      return Collections.emptyList();
    }
    if (isSuperAdmin(user)) {
      return db.queryForList(
          "SELECT rr.id, rr.department_id AS departmentId, d.name AS departmentName, rr.rule_name AS ruleName, " +
              "rr.due_day_of_week AS dueDayOfWeek, rr.due_time AS dueTime, rr.remind_before_hours AS remindBeforeHours, " +
              "rr.min_word_count AS minWordCount, rr.status, DATE_FORMAT(rr.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
              "FROM reminder_rule rr LEFT JOIN department d ON d.id = rr.department_id ORDER BY rr.id DESC"
      );
    }
    return db.queryForList(
        "SELECT rr.id, rr.department_id AS departmentId, d.name AS departmentName, rr.rule_name AS ruleName, " +
            "rr.due_day_of_week AS dueDayOfWeek, rr.due_time AS dueTime, rr.remind_before_hours AS remindBeforeHours, " +
            "rr.min_word_count AS minWordCount, rr.status, DATE_FORMAT(rr.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt " +
            "FROM reminder_rule rr LEFT JOIN department d ON d.id = rr.department_id WHERE rr.department_id = ? OR rr.department_id IS NULL ORDER BY rr.id DESC",
        departmentId(user)
    );
  }

  public Map<String, Object> saveReminderRule(Map<String, Object> payload, Map<String, Object> user) {
    if (!isSuperAdmin(user) && !isDepartmentAdmin(user)) {
      throw new IllegalArgumentException("无权配置提醒规则");
    }
    Long departmentId = asLong(payload.get("departmentId"), isDepartmentAdmin(user) ? departmentId(user) : null);
    if (isDepartmentAdmin(user)) {
      departmentId = departmentId(user);
    }
    String ruleName = asString(payload.get("ruleName"));
    if (ruleName.isBlank()) {
      ruleName = "周反馈提醒规则";
    }
    Long id = asLong(payload.get("id"), null);
    Object[] params = new Object[] {
        departmentId,
        ruleName,
        asInteger(payload.get("dueDayOfWeek"), 5),
        asString(payload.get("dueTime")).isBlank() ? "18:00" : asString(payload.get("dueTime")),
        asInteger(payload.get("remindBeforeHours"), 24),
        asInteger(payload.get("minWordCount"), 20),
        asString(payload.get("status")).isBlank() ? "ACTIVE" : asString(payload.get("status"))
    };
    if (id == null || id <= 0) {
      id = insert(
          "INSERT INTO reminder_rule (department_id, rule_name, due_day_of_week, due_time, remind_before_hours, min_word_count, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
          params
      );
    } else {
      db.update(
          "UPDATE reminder_rule SET department_id = ?, rule_name = ?, due_day_of_week = ?, due_time = ?, remind_before_hours = ?, min_word_count = ?, status = ? WHERE id = ?",
          append(params, id)
      );
    }
    Map<String, Object> result = new HashMap<>(payload);
    result.put("id", id);
    result.put("departmentId", departmentId);
    return result;
  }

  public List<Map<String, Object>> monitorDossiers(Map<String, Object> user) {
    if (!isSuperAdmin(user) && !isDepartmentAdmin(user) && !"CLASS_REPRESENTATIVE".equals(role(user))) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> compliance = weeklyTaskCompliance(user);
    Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
    for (Map<String, Object> item : compliance) {
      String key = asString(item.get("monitorUserId")) + "|" + asString(item.get("className"));
      Map<String, Object> dossier = grouped.computeIfAbsent(key, ignored -> {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("monitorUserId", item.get("monitorUserId"));
        row.put("monitorName", item.get("monitorName"));
        row.put("departmentName", item.get("departmentName"));
        row.put("className", item.get("className"));
        row.put("taskCount", 0);
        row.put("requiredCourseCount", 0);
        row.put("submittedCourseCount", 0);
        row.put("missingCourseCount", 0);
        row.put("overdueCount", 0);
        row.put("lowQualityCount", 0);
        return row;
      });
      addInt(dossier, "taskCount", 1);
      addInt(dossier, "requiredCourseCount", asInteger(item.get("requiredCourseCount"), 0));
      addInt(dossier, "submittedCourseCount", asInteger(item.get("submittedCourseCount"), 0));
      addInt(dossier, "missingCourseCount", asInteger(item.get("missingCourseCount"), 0));
      addInt(dossier, "lowQualityCount", asInteger(item.get("lowQualityCount"), 0));
      if ("OVERDUE_MISSING".equals(asString(item.get("complianceStatus")))) {
        addInt(dossier, "overdueCount", 1);
      }
    }
    for (Map<String, Object> row : grouped.values()) {
      int required = asInteger(row.get("requiredCourseCount"), 0);
      int submitted = asInteger(row.get("submittedCourseCount"), 0);
      row.put("completionRate", required == 0 ? "0%" : Math.round(submitted * 100.0 / required) + "%");
      row.put("dossierRemark", asInteger(row.get("overdueCount"), 0) > 0 ? "存在逾期未交，建议提醒或约谈" : asInteger(row.get("lowQualityCount"), 0) > 0 ? "存在低质量反馈，建议关注" : "履职正常");
    }
    return new ArrayList<>(grouped.values());
  }

  private void addInt(Map<String, Object> row, String key, Integer delta) {
    row.put(key, asInteger(row.get(key), 0) + (delta == null ? 0 : delta));
  }

  private void mergePersistedAiSummary(Map<String, Object> group) {
    String targetKey = weeklySummaryTargetKey(group);
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT summary_text AS aiSummary, model_name AS aiProvider, risk_level AS aiRiskLevel, suggestions_text AS aiSuggestionsText, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS aiGeneratedAt " +
            "FROM ai_summary WHERE target_type = 'WEEKLY_FEEDBACK' AND target_key = ? ORDER BY created_at DESC, id DESC LIMIT 1",
        targetKey
    );
    if (rows.isEmpty()) {
      return;
    }
    Map<String, Object> row = rows.get(0);
    group.put("aiSummary", row.get("aiSummary"));
    group.put("aiProvider", row.get("aiProvider"));
    group.put("aiRiskLevel", row.get("aiRiskLevel"));
    group.put("aiGeneratedAt", row.get("aiGeneratedAt"));
    String suggestionsText = asString(row.get("aiSuggestionsText"));
    group.put("aiSuggestions", suggestionsText.isBlank() ? Collections.emptyList() : Arrays.asList(suggestionsText.split("；")));
  }

  private String weeklySummaryTargetKey(Map<String, Object> summary) {
    return asString(summary.get("teacherName")) + "||" + asString(summary.get("courseName"));
  }

  private Long stableTargetId(String targetKey) {
    return (long) Math.abs(targetKey.hashCode());
  }

  private String aiSuggestionsText(Object suggestions) {
    if (suggestions instanceof List) {
      return ((List<?>) suggestions).stream()
          .map(this::asString)
          .filter(value -> !value.isBlank())
          .collect(Collectors.joining("；"));
    }
    return asString(suggestions);
  }

  private Map<String, Object> latestWeeklyFeedback(Long taskId, Long courseId, Long studentId) {
    String studentFilter = studentId == null ? "" : " AND wf.student_id = ?";
    Object[] args = studentId == null
        ? new Object[] {taskId, courseId}
        : new Object[] {taskId, courseId, studentId};
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT wf.id, DATE_FORMAT(wf.created_at, '%Y-%m-%d %H:%i:%s') AS submittedAt, " +
            "CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) AS feedbackWordCount " +
            "FROM weekly_feedback wf WHERE wf.task_id = ? AND wf.course_id = ?" + studentFilter + " ORDER BY wf.created_at DESC, wf.id DESC LIMIT 1",
        args
    );
    return rows.isEmpty() ? null : rows.get(0);
  }

  private List<Map<String, Object>> mockTeachingRowsForTask(Map<String, Object> task) {
    List<Map<String, Object>> courses = db.queryForList(
        "SELECT c.id AS courseId, c.course_name AS courseName, c.department_id AS courseDepartmentId, " +
            "d.name AS teacherDepartmentName FROM course c LEFT JOIN department d ON d.id = c.department_id ORDER BY c.id ASC LIMIT 4"
    );
    List<Map<String, Object>> rows = new ArrayList<>();
    int index = 0;
    for (Map<String, Object> course : courses) {
      Map<String, Object> row = new LinkedHashMap<>(course);
      row.put("teachingTaskId", null);
      row.put("teacherId", null);
      row.put("teacherName", "模拟教师" + (index + 1));
      row.put("plannedTeacherName", row.get("teacherName"));
      row.put("actualTeacherName", row.get("teacherName"));
      row.put("weekRange", String.valueOf(task.get("weekNo")));
      row.put("guidanceMode", index % 2 == 0 ? "线下" : "线上+线下");
      row.put("classroom", "Mock-" + (index + 1) + "01");
      rows.add(row);
      index += 1;
    }
    return rows;
  }

  private void updateWeeklyTaskCompletion(Long taskId, Long studentId) {
    List<Map<String, Object>> tasks = db.queryForList(
        "SELECT wft.id AS taskId, wft.term_id AS termId, wft.week_no AS weekNo, wft.class_group_id AS classGroupId, cg.name AS className, DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline " +
            "FROM weekly_feedback_task wft JOIN class_group cg ON cg.id = wft.class_group_id WHERE wft.id = ?",
        taskId
    );
    if (tasks.isEmpty()) {
      return;
    }
    List<Map<String, Object>> items = weeklyTaskCourseItemsForTask(tasks.get(0), Map.of("id", studentId, "role", "CLASS_REPRESENTATIVE"));
    if (!items.isEmpty() && items.stream().allMatch(item -> item.get("feedbackId") != null)) {
      db.update("UPDATE weekly_feedback_task SET status = 'SUBMITTED' WHERE id = ?", taskId);
    }
  }

  private List<Map<String, Object>> aggregateWeeklyFeedback(Map<String, Object> user, String groupIdExpr, String groupNameExpr, String labelKey) {
    Scope scope = weeklyFeedbackScope(user, "m", "wf");
    return db.queryForList(
        "SELECT " + groupIdExpr + " AS targetId, " + groupNameExpr + " AS " + labelKey + ", " +
            "COUNT(DISTINCT wf.id) AS feedbackCount, " +
            "COUNT(DISTINCT wf.student_id) AS participantCount, " +
            "COUNT(DISTINCT CASE WHEN wf.need_reply = 1 THEN wf.id END) AS needReplyCount, " +
            "COUNT(DISTINCT CASE WHEN CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) < 20 THEN wf.id END) AS lowQualityCount, " +
            "COUNT(DISTINCT CASE WHEN ff.id IS NOT NULL THEN wf.id END) AS sensitiveFlagCount, " +
            "MAX(DATE_FORMAT(wf.created_at, '%Y-%m-%d %H:%i:%s')) AS latestSubmittedAt " +
            "FROM weekly_feedback wf " +
            "LEFT JOIN weekly_feedback_task wft ON wft.id = wf.task_id " +
            "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            "LEFT JOIN course c ON c.id = wf.course_id " +
            "LEFT JOIN teacher t ON t.id = wf.teacher_id " +
            "LEFT JOIN feedback_flag ff ON ff.feedback_type = 'WEEKLY' AND ff.feedback_id = wf.id " +
            scope.where + " GROUP BY targetId, " + labelKey + " ORDER BY feedbackCount DESC, targetId DESC",
        scope.args
    );
  }

  private boolean isOverdue(String deadline) {
    if (deadline == null || deadline.isBlank()) {
      return false;
    }
    return LocalDateTime.now().isAfter(LocalDateTime.parse(deadline, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
  }

  private boolean isLate(String submittedAt, String deadline) {
    if (submittedAt == null || submittedAt.isBlank() || deadline == null || deadline.isBlank()) {
      return false;
    }
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    return LocalDateTime.parse(submittedAt, formatter).isAfter(LocalDateTime.parse(deadline, formatter));
  }

  private void markSensitiveTerms(String feedbackType, Long feedbackId, List<String> values) {
    String content = values.stream().filter(Objects::nonNull).collect(Collectors.joining(" "));
    if (content.isBlank()) {
      return;
    }
    List<Map<String, Object>> terms = db.queryForList(
        "SELECT term_text AS termText, category, risk_level AS riskLevel FROM sensitive_term"
    );
    for (Map<String, Object> term : terms) {
      String termText = asString(term.get("termText"));
      if (!termText.isBlank() && content.contains(termText)) {
        insert(
            "INSERT INTO feedback_flag (feedback_type, feedback_id, flag_type, flag_value) VALUES (?, ?, ?, ?)",
            feedbackType,
            feedbackId,
            asString(term.get("category")).isBlank() ? "敏感信息" : asString(term.get("category")),
            termText + ":" + (asString(term.get("riskLevel")).isBlank() ? "MEDIUM" : asString(term.get("riskLevel")))
        );
      }
    }
  }

  private Map<String, Object> matchTeachingTask(Long classGroupId, Map<String, Object> row, Integer weekNo) {
    String courseName = normalizeMatchText(firstText(row, "courseName", "name", "课程名称", "开课课程"));
    if (courseName.isBlank()) {
      return Collections.emptyMap();
    }
    List<Map<String, Object>> candidates = db.queryForList(
        "SELECT tt.id, tt.week_range AS weekRange, tt.day_index AS dayIndex, tt.section_index AS sectionIndex, " +
            "tt.classroom, tt.planned_teacher_name AS plannedTeacherName, tt.actual_teacher_name AS actualTeacherName, " +
            "c.course_name AS courseName, COALESCE(t.teacher_name, tt.actual_teacher_name, tt.planned_teacher_name) AS teacherName " +
            "FROM teaching_task tt " +
            "JOIN course c ON c.id = tt.course_id " +
            "LEFT JOIN teacher t ON t.id = tt.teacher_id " +
            "WHERE tt.class_group_id = ?",
        classGroupId
    );
    Integer day = asInteger(row.get("day"), null);
    Integer serial = asInteger(row.get("serial"), null);
    String classroom = normalizeMatchText(firstText(row, "classroom", "locationText", "教室"));
    Map<String, Object> best = Collections.emptyMap();
    int bestScore = 0;
    for (Map<String, Object> candidate : candidates) {
      String candidateCourse = normalizeMatchText(asString(candidate.get("courseName")));
      if (candidateCourse.isBlank() || (!candidateCourse.contains(courseName) && !courseName.contains(candidateCourse))) {
        continue;
      }
      int score = 20;
      if (weekNo != null && includesWeek(asString(candidate.get("weekRange")), weekNo)) {
        score += 8;
      }
      if (day != null && day.equals(asInteger(candidate.get("dayIndex"), null))) {
        score += 4;
      }
      if (serial != null && serial.equals(asInteger(candidate.get("sectionIndex"), null))) {
        score += 4;
      }
      String candidateClassroom = normalizeMatchText(asString(candidate.get("classroom")));
      if (!classroom.isBlank() && !candidateClassroom.isBlank() && classroom.equals(candidateClassroom)) {
        score += 3;
      }
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return bestScore >= 20 ? best : Collections.emptyMap();
  }

  private void appendIfUseful(List<String> target, Object value) {
    String text = asString(value);
    if (text.isBlank() || Arrays.asList("无", "暂无", "没有", "无问题").contains(text)) {
      return;
    }
    target.add(text);
  }

  private List<String> uniqueStrings(List<String> values) {
    return values.stream()
        .map(this::asString)
        .filter(value -> !value.isBlank())
        .distinct()
        .limit(6)
        .collect(Collectors.toList());
  }

  private String buildRuleBasedSummary(Map<String, Object> group) {
    StringBuilder builder = new StringBuilder();
    builder.append(asString(group.get("courseName"))).append("（").append(asString(group.get("teacherName"))).append("）");
    builder.append("共收到 ").append(group.get("feedbackCount")).append(" 条反馈。");
    builder.append("教学反馈：").append(group.get("positiveSummary")).append("。");
    builder.append("主要问题：").append(group.get("issueSummary")).append("。");
    if (!"暂无硬件问题".equals(asString(group.get("hardwareSummary")))) {
      builder.append("硬件问题：").append(group.get("hardwareSummary")).append("。");
    }
    if (asInteger(group.get("sensitiveFlagCount"), 0) > 0) {
      builder.append("存在敏感标记，建议管理员优先核实。");
    }
    return builder.toString();
  }

  private Long findOrCreateDepartment(String name) {
    String normalized = name.isBlank() ? "未归属院系" : name;
    Long existing = queryId("SELECT id FROM department WHERE name = ? LIMIT 1", normalized);
    return existing != null ? existing : insert("INSERT INTO department (code, name) VALUES (?, ?)", "DEPT-" + System.currentTimeMillis(), normalized);
  }

  private Long findOrCreateMajor(Long departmentId) {
    Long existing = queryId("SELECT id FROM major WHERE department_id = ? ORDER BY id ASC LIMIT 1", departmentId);
    return existing != null ? existing : insert("INSERT INTO major (department_id, code, name) VALUES (?, ?, ?)", departmentId, "MAJOR-" + System.currentTimeMillis(), "默认专业");
  }

  private Long findOrCreateMajor(Long departmentId, String name) {
    String normalized = name.isBlank() ? "默认专业" : name;
    Long existing = queryId("SELECT id FROM major WHERE department_id = ? AND name = ? LIMIT 1", departmentId, normalized);
    return existing != null ? existing : insert("INSERT INTO major (department_id, code, name) VALUES (?, ?, ?)", departmentId, "MAJOR-" + System.currentTimeMillis(), normalized);
  }

  private Long findOrCreateClassGroup(Long majorId, String name) {
    Long existing = queryId("SELECT id FROM class_group WHERE name = ? LIMIT 1", name);
    if (existing != null) {
      return existing;
    }
    Map<String, Object> context = findClassGroupContext(name);
    if (!context.isEmpty()) {
      return asLong(context.get("id"), 0L);
    }
    int gradeYear = 2024;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("20\\d{2}").matcher(name);
    if (matcher.find()) {
      gradeYear = Integer.parseInt(matcher.group());
    }
    return insert("INSERT INTO class_group (major_id, grade_year, name) VALUES (?, ?, ?)", majorId, gradeYear, name);
  }

  private Map<String, Object> findClassGroupContext(String name) {
    if (name == null || name.isBlank()) {
      return Map.of();
    }
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT cg.id, cg.name, cg.major_id AS majorId, m.department_id AS departmentId, d.name AS departmentName " +
            "FROM class_group cg " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            "LEFT JOIN department d ON d.id = m.department_id " +
            "ORDER BY CASE WHEN d.name = '未归属院系' OR d.id IS NULL THEN 1 ELSE 0 END, cg.id"
    );
    for (Map<String, Object> row : rows) {
      if (isSameAcademicClass(name, asString(row.get("name")))) {
        return row;
      }
    }
    return Map.of();
  }

  private boolean isSameAcademicClass(String left, String right) {
    String normalizedLeft = normalizeClassText(left);
    String normalizedRight = normalizeClassText(right);
    if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
      return false;
    }
    if (normalizedLeft.equals(normalizedRight)) {
      return true;
    }
    String leftTail = classYearAndNo(normalizedLeft);
    String rightTail = classYearAndNo(normalizedRight);
    if (leftTail.isBlank() || !leftTail.equals(rightTail)) {
      return false;
    }
    String leftMajor = normalizedLeft.replace(leftTail, "");
    String rightMajor = normalizedRight.replace(rightTail, "");
    return !leftMajor.isBlank() && !rightMajor.isBlank() && (leftMajor.contains(rightMajor) || rightMajor.contains(leftMajor));
  }

  private String normalizeClassText(String value) {
    String normalized = asString(value)
        .replaceAll("\\s+", "")
        .replace("级", "")
        .replace("班", "")
        .replace("*", "")
        .replace("软件工程", "软件");
    java.util.regex.Matcher shortYear = java.util.regex.Pattern.compile("(?<!20)(\\d{2})(-\\d+)$").matcher(normalized);
    if (shortYear.find()) {
      normalized = normalized.substring(0, shortYear.start(1)) + "20" + shortYear.group(1) + shortYear.group(2);
    }
    return normalized;
  }

  private String classYearAndNo(String normalizedClassName) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(20\\d{2}-\\d+)$").matcher(normalizedClassName);
    return matcher.find() ? matcher.group(1) : "";
  }

  private Long findOrCreateCourse(String name, Long departmentId) {
    Long existing = queryId("SELECT id FROM course WHERE course_name = ? LIMIT 1", name);
    return existing != null ? existing : insert("INSERT INTO course (course_code, course_name, department_id) VALUES (?, ?, ?)", "COURSE-" + System.currentTimeMillis(), name, departmentId);
  }

  private Long findOrCreateTeacher(Long departmentId, String name) {
    String normalized = name.isBlank() ? "待确认教师" : name;
    Long existing = queryId("SELECT id FROM teacher WHERE teacher_name = ? LIMIT 1", normalized);
    return existing != null ? existing : insert("INSERT INTO teacher (department_id, teacher_no, teacher_name) VALUES (?, ?, ?)", departmentId, "T-" + System.currentTimeMillis(), normalized);
  }

  private String normalizeMatchText(String value) {
    return asString(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .replace("（", "(")
        .replace("）", ")")
        .replace("，", ",")
        .replace("。", ".");
  }

  private Long queryId(String sql, Object... args) {
    List<Map<String, Object>> rows = db.queryForList(sql, args);
    if (rows.isEmpty()) {
      return null;
    }
    return asLong(rows.get(0).values().iterator().next(), null);
  }

  private Long ensureRole(String roleKey, String roleName) {
    Long existing = queryId("SELECT id FROM role WHERE role_key = ? LIMIT 1", roleKey);
    return existing != null ? existing : insert("INSERT INTO role (role_key, role_name) VALUES (?, ?)", roleKey, roleName);
  }

  private String roleName(String roleKey) {
    if ("SUPER_ADMIN".equals(roleKey)) {
      return "超管员";
    }
    if ("DEPARTMENT_ADMIN".equals(roleKey)) {
      return "院系管理员";
    }
    if ("CLASS_REPRESENTATIVE".equals(roleKey)) {
      return "学委";
    }
    return "普通学生";
  }

  private String normalizeRole(String value) {
    String role = asString(value);
    if (role.isBlank() || "普通学生".equals(role)) {
      return "STUDENT";
    }
    if ("学委".equals(role)) {
      return "CLASS_REPRESENTATIVE";
    }
    if ("院系管理员".equals(role) || "系管理员".equals(role)) {
      return "DEPARTMENT_ADMIN";
    }
    if (Arrays.asList("STUDENT", "CLASS_REPRESENTATIVE", "DEPARTMENT_ADMIN").contains(role)) {
      return role;
    }
    return "STUDENT";
  }

  private String normalizeUserStatus(String value) {
    String status = asString(value);
    if ("禁用".equals(status) || "DISABLED".equals(status)) {
      return "DISABLED";
    }
    return "ACTIVE";
  }

  private Long insert(String sql, Object... args) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    db.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
      for (int i = 0; i < args.length; i += 1) {
        ps.setObject(i + 1, args[i]);
      }
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    return key == null ? 0L : key.longValue();
  }

  private Integer count(String sql, Object... args) {
    Integer result = db.queryForObject(sql, Integer.class, args);
    return result == null ? 0 : result;
  }

  private void ensureRuntimeSchema() {
    ensureColumn("weekly_feedback", "remark", "TEXT");
    ensureColumn("weekly_feedback_task", "feedback_scope", "VARCHAR(30) NOT NULL DEFAULT 'ALL_COURSES'");
    ensureColumn("teaching_task", "day_index", "INT");
    ensureColumn("teaching_task", "section_index", "INT");
    ensureColumn("teaching_task", "classroom", "VARCHAR(200)");
    ensureColumn("ai_summary", "target_key", "VARCHAR(255)");
    ensureColumn("ai_summary", "risk_level", "VARCHAR(20)");
    ensureColumn("ai_summary", "suggestions_text", "TEXT");
    ensureOperationTables();
    ensureFeedbackSupportTables();
    seedSensitiveTerms();
    repairUnassignedClassBindings();
  }

  private void ensureOperationTables() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS sync_log (" +
            "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
            "operator_user_id BIGINT, " +
            "action_type VARCHAR(60) NOT NULL, " +
            "status VARCHAR(30) NOT NULL, " +
            "message VARCHAR(255), " +
            "success_count INT NOT NULL DEFAULT 0, " +
            "failure_count INT NOT NULL DEFAULT 0, " +
            "detail_text TEXT, " +
            "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "INDEX idx_sync_log_created (created_at), " +
            "INDEX idx_sync_log_action (action_type)" +
            ")"
    );
    db.execute(
        "CREATE TABLE IF NOT EXISTS reminder_rule (" +
            "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
            "department_id BIGINT, " +
            "rule_name VARCHAR(120) NOT NULL, " +
            "due_day_of_week INT NOT NULL DEFAULT 5, " +
            "due_time VARCHAR(10) NOT NULL DEFAULT '18:00', " +
            "remind_before_hours INT NOT NULL DEFAULT 24, " +
            "min_word_count INT NOT NULL DEFAULT 20, " +
            "status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', " +
            "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
            ")"
    );
    if (count("SELECT COUNT(*) FROM reminder_rule") == 0) {
      db.update(
          "INSERT INTO reminder_rule (department_id, rule_name, due_day_of_week, due_time, remind_before_hours, min_word_count, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
          null,
          "默认周反馈提醒规则",
          5,
          "18:00",
          24,
          20,
          "ACTIVE"
      );
    }
  }

  private void repairUnassignedClassBindings() {
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT u.id AS userId, cg.name AS className, d.name AS departmentName " +
            "FROM app_user u " +
            "LEFT JOIN department d ON d.id = u.department_id " +
            "LEFT JOIN class_group cg ON cg.id = u.class_group_id " +
            "WHERE u.class_group_id IS NOT NULL AND (u.department_id IS NULL OR d.name = '未归属院系')"
    );
    for (Map<String, Object> row : rows) {
      Map<String, Object> context = findClassGroupContext(asString(row.get("className")));
      Long classGroupId = asLong(context.get("id"), 0L);
      Long departmentId = asLong(context.get("departmentId"), 0L);
      if (classGroupId > 0 && departmentId > 0) {
        db.update(
            "UPDATE app_user SET department_id = ?, class_group_id = ? WHERE id = ?",
            departmentId,
            classGroupId,
            asLong(row.get("userId"), 0L)
        );
      }
    }
  }

  private void ensureFeedbackSupportTables() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS sensitive_term (" +
            "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
            "term_text VARCHAR(100) NOT NULL, " +
            "category VARCHAR(80), " +
            "risk_level VARCHAR(20), " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "UNIQUE KEY uk_sensitive_term_text (term_text)" +
            ")"
    );
    db.execute(
        "CREATE TABLE IF NOT EXISTS feedback_flag (" +
            "id BIGINT PRIMARY KEY AUTO_INCREMENT, " +
            "feedback_type VARCHAR(20) NOT NULL, " +
            "feedback_id BIGINT NOT NULL, " +
            "flag_type VARCHAR(80), " +
            "flag_value VARCHAR(200), " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "INDEX idx_feedback_flag_target (feedback_type, feedback_id)" +
            ")"
    );
  }

  private void seedSensitiveTerms() {
    if (count("SELECT COUNT(*) FROM sensitive_term") > 0) {
      return;
    }
    List<Object[]> terms = Arrays.asList(
        new Object[] {"水课", "教学质量", "HIGH"},
        new Object[] {"早退", "课堂纪律", "HIGH"},
        new Object[] {"离场", "课堂纪律", "HIGH"},
        new Object[] {"无故离场", "课堂纪律", "HIGH"},
        new Object[] {"不适", "课堂内容", "HIGH"},
        new Object[] {"辱骂", "课堂内容", "HIGH"},
        new Object[] {"歧视", "课堂内容", "HIGH"},
        new Object[] {"设备故障", "硬件问题", "MEDIUM"},
        new Object[] {"投影", "硬件问题", "MEDIUM"},
        new Object[] {"话筒", "硬件问题", "MEDIUM"},
        new Object[] {"多媒体", "硬件问题", "MEDIUM"},
        new Object[] {"声音太小", "硬件问题", "MEDIUM"},
        new Object[] {"作业量过大", "教学质量", "MEDIUM"},
        new Object[] {"语速过快", "教学质量", "MEDIUM"},
        new Object[] {"讲不清", "教学质量", "MEDIUM"}
    );
    for (Object[] term : terms) {
      db.update(
          "INSERT IGNORE INTO sensitive_term (term_text, category, risk_level) VALUES (?, ?, ?)",
          term
      );
    }
  }

  private void ensureColumn(String tableName, String columnName, String definition) {
    Integer columnCount = db.queryForObject(
        "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
        Integer.class,
        tableName,
        columnName
    );
    if (columnCount == null || columnCount == 0) {
      db.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }
  }

  private List<Map<String, Object>> buildDateRow(LocalDate termStart, Integer weekNo) {
    LocalDate today = LocalDate.now();
    LocalDate weekStart = termStart.plusDays((long) (Math.max(1, weekNo) - 1) * 7);
    List<Map<String, Object>> dateRow = new ArrayList<>();
    for (int index = 0; index < 7; index += 1) {
      LocalDate date = weekStart.plusDays(index);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("dayIndex", index);
      item.put("date", date.toString());
      item.put("shortDate", String.format("%02d/%02d", date.getMonthValue(), date.getDayOfMonth()));
      item.put("today", date.equals(today));
      dateRow.add(item);
    }
    return dateRow;
  }

  private List<String> splitWeeks(String weeksRaw) {
    if (weeksRaw == null || weeksRaw.isBlank()) {
      return Collections.emptyList();
    }
    return Arrays.stream(weeksRaw.replaceAll("[，、;]", ",").split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toList());
  }

  private Object[] append(Object[] args, Object value) {
    Object[] next = Arrays.copyOf(args, args.length + 1);
    next[args.length] = value;
    return next;
  }

  private String normalizeFeedbackType(String value) {
    String type = asString(value).toUpperCase(Locale.ROOT);
    return "WEEKLY".equals(type) ? "WEEKLY" : "REALTIME";
  }

  private String normalizeFeedbackStatus(String value, String defaultStatus) {
    String status = asString(value).toUpperCase(Locale.ROOT);
    if (Arrays.asList("PENDING", "PENDING_REPLY", "IN_PROGRESS", "REPLIED", "CLOSED", "SUBMITTED").contains(status)) {
      return status;
    }
    return defaultStatus;
  }

  private void updateStatus(String feedbackType, Long feedbackId, String status) {
    if (feedbackId == null) {
      return;
    }
    if ("REALTIME".equals(feedbackType)) {
      db.update("UPDATE realtime_feedback SET status = ? WHERE id = ?", status, feedbackId);
    }
    if ("WEEKLY".equals(feedbackType)) {
      db.update("UPDATE weekly_feedback SET status = ? WHERE id = ?", status, feedbackId);
    }
  }

  private Scope taskScope(Map<String, Object> user, String majorAlias, String taskAlias) {
    if (isSuperAdmin(user)) {
      return new Scope("WHERE 1 = 1");
    }
    if (isDepartmentAdmin(user)) {
      return new Scope("WHERE " + majorAlias + ".department_id = ?", departmentId(user));
    }
    return new Scope("WHERE " + taskAlias + ".class_group_id = ?", classGroupId(user));
  }

  private Scope weeklyFeedbackScope(Map<String, Object> user, String majorAlias, String feedbackAlias) {
    if (isSuperAdmin(user)) {
      return new Scope("WHERE 1 = 1");
    }
    if (isDepartmentAdmin(user)) {
      return new Scope("WHERE " + majorAlias + ".department_id = ?", departmentId(user));
    }
    return new Scope("WHERE " + feedbackAlias + ".student_id = ?", userId(user));
  }

  private Scope realtimeScope(Map<String, Object> user, String realtimeAlias) {
    if (isSuperAdmin(user)) {
      return new Scope("WHERE 1 = 1");
    }
    if (isDepartmentAdmin(user)) {
      return new Scope("WHERE " + realtimeAlias + ".department_id = ?", departmentId(user));
    }
    return new Scope("WHERE " + realtimeAlias + ".student_id = ?", userId(user));
  }

  private boolean isSuperAdmin(Map<String, Object> user) {
    return "SUPER_ADMIN".equals(role(user));
  }

  private boolean isDepartmentAdmin(Map<String, Object> user) {
    return "DEPARTMENT_ADMIN".equals(role(user));
  }

  private String role(Map<String, Object> user) {
    return asString(user == null ? "" : user.get("role"));
  }

  private Long userId(Map<String, Object> user) {
    return asLong(user == null ? null : user.get("id"), 0L);
  }

  private Long departmentId(Map<String, Object> user) {
    return asLong(user == null ? null : user.get("departmentId"), 0L);
  }

  private Long classGroupId(Map<String, Object> user) {
    return asLong(user == null ? null : user.get("classGroupId"), 0L);
  }

  private boolean includesWeek(String weekRange, Integer weekNo) {
    if (weekRange == null || weekRange.isBlank()) {
      return false;
    }
    String normalized = weekRange
        .replace("（", "(")
        .replace("）", ")")
        .replaceAll("[()]", "")
        .replaceAll("[，、;]", ",")
        .replace("周", "");
    for (String part : normalized.split(",")) {
      String value = part.trim();
      if (value.isBlank()) {
        continue;
      }
      Integer parity = null;
      if (value.contains("单") || value.endsWith("/1")) {
        parity = 1;
      }
      if (value.contains("双") || value.endsWith("/2")) {
        parity = 0;
      }
      String cleaned = value.replaceAll("[单双]", "").replaceAll("/[12]", "");
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)(?:-(\\d+))?").matcher(cleaned);
      if (!matcher.find()) {
        continue;
      }
      int start = Integer.parseInt(matcher.group(1));
      int end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
      if (weekNo >= start && weekNo <= end && (parity == null || weekNo % 2 == parity)) {
        return true;
      }
    }
    return false;
  }

  private String normalizeFeedbackScope(String value, Integer weekNo) {
    String scope = asString(value).toUpperCase(Locale.ROOT);
    if ("FOREIGN_ONLY".equals(scope) || "ALL_COURSES".equals(scope)) {
      return scope;
    }
    return weekNo != null && weekNo % 2 == 1 ? "FOREIGN_ONLY" : "ALL_COURSES";
  }

  private String feedbackScopeLabel(String scope) {
    return "FOREIGN_ONLY".equals(asString(scope)) ? "外教课程" : "全部课程";
  }

  private boolean isForeignTeachingCourse(Map<String, Object> teaching) {
    String joined = String.join(" ",
        asString(teaching.get("courseName")),
        asString(teaching.get("teacherName")),
        asString(teaching.get("plannedTeacherName")),
        asString(teaching.get("actualTeacherName")));
    if (joined.isBlank()) {
      return false;
    }
    if (containsAny(joined, "外教", "Academic", "English", "Culture", "Research", "Skills", "Advanced", "Tutorial")) {
      return true;
    }
    return joined.matches(".*[A-Za-z]{3,}.*");
  }

  private boolean containsAny(String source, String... keywords) {
    if (source == null || source.isBlank()) {
      return false;
    }
    for (String keyword : keywords) {
      if (!asString(keyword).isBlank() && source.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private String reminderStatus(String deadline, boolean pending) {
    if (!pending) {
      return "DONE";
    }
    if (deadline == null || deadline.isBlank()) {
      return "NO_DEADLINE";
    }
    LocalDateTime due = LocalDateTime.parse(deadline, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    LocalDateTime now = LocalDateTime.now();
    if (now.isAfter(due)) {
      return "OVERDUE";
    }
    if (now.plusHours(24).isAfter(due)) {
      return "DUE_SOON";
    }
    return "PENDING";
  }

  private String firstText(Map<String, Object> row, String... keys) {
    for (String key : keys) {
      String value = asString(row.get(key));
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String asString(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String textOrDefault(Object value, String defaultValue) {
    String text = asString(value);
    return text.isBlank() ? defaultValue : text;
  }

  private Long asLong(Object value, Long defaultValue) {
    if (value == null || String.valueOf(value).isBlank()) {
      return defaultValue;
    }
    return Long.parseLong(String.valueOf(value));
  }

  private Integer asInteger(Object value, Integer defaultValue) {
    if (value == null || String.valueOf(value).isBlank()) {
      return defaultValue;
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private boolean asBoolean(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
  }

  private int rolePriority(String roleKey) {
    if ("SUPER_ADMIN".equals(roleKey)) {
      return 4;
    }
    if ("DEPARTMENT_ADMIN".equals(roleKey)) {
      return 3;
    }
    if ("CLASS_REPRESENTATIVE".equals(roleKey)) {
      return 2;
    }
    if ("STUDENT".equals(roleKey)) {
      return 1;
    }
    return 0;
  }

  @SuppressWarnings("unchecked")
  private List<Long> asLongList(Object value) {
    if (!(value instanceof List)) {
      return Collections.emptyList();
    }
    return ((List<Object>) value).stream()
        .map(item -> asLong(item, null))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private Map<String, ResourceConfig> buildResources() {
    Map<String, ResourceConfig> map = new HashMap<>();
    map.put("departments", new ResourceConfig(
        "SELECT id, code, name, created_at AS createdAt FROM department ORDER BY id DESC",
        "INSERT INTO department (code, name) VALUES (?, ?)",
        "UPDATE department SET code = ?, name = ? WHERE id = ?",
        "DELETE FROM department WHERE id = ?",
        payload -> new Object[] {payload.get("code"), payload.get("name")}
    ));
    map.put("majors", new ResourceConfig(
        "SELECT id, department_id AS departmentId, code, name, created_at AS createdAt FROM major ORDER BY id DESC",
        "INSERT INTO major (department_id, code, name) VALUES (?, ?, ?)",
        "UPDATE major SET department_id = ?, code = ?, name = ? WHERE id = ?",
        "DELETE FROM major WHERE id = ?",
        payload -> new Object[] {payload.get("departmentId"), payload.get("code"), payload.get("name")}
    ));
    map.put("classes", new ResourceConfig(
        "SELECT id, major_id AS majorId, grade_year AS gradeYear, name, created_at AS createdAt FROM class_group ORDER BY id DESC",
        "INSERT INTO class_group (major_id, grade_year, name) VALUES (?, ?, ?)",
        "UPDATE class_group SET major_id = ?, grade_year = ?, name = ? WHERE id = ?",
        "DELETE FROM class_group WHERE id = ?",
        payload -> new Object[] {payload.get("majorId"), payload.get("gradeYear"), payload.get("name")}
    ));
    map.put("teachers", new ResourceConfig(
        "SELECT id, department_id AS departmentId, teacher_no AS teacherNo, teacher_name AS teacherName, created_at AS createdAt FROM teacher ORDER BY id DESC",
        "INSERT INTO teacher (department_id, teacher_no, teacher_name) VALUES (?, ?, ?)",
        "UPDATE teacher SET department_id = ?, teacher_no = ?, teacher_name = ? WHERE id = ?",
        "DELETE FROM teacher WHERE id = ?",
        payload -> new Object[] {payload.get("departmentId"), payload.get("teacherNo"), payload.get("teacherName")}
    ));
    map.put("courses", new ResourceConfig(
        "SELECT id, course_code AS courseCode, course_name AS courseName, department_id AS departmentId, created_at AS createdAt FROM course ORDER BY id DESC",
        "INSERT INTO course (course_code, course_name, department_id) VALUES (?, ?, ?)",
        "UPDATE course SET course_code = ?, course_name = ?, department_id = ? WHERE id = ?",
        "DELETE FROM course WHERE id = ?",
        payload -> new Object[] {payload.get("courseCode"), payload.get("courseName"), payload.get("departmentId")}
    ));
    map.put("terms", new ResourceConfig(
        "SELECT id, academic_year AS academicYear, semester, start_date AS startDate, end_date AS endDate, status FROM term ORDER BY id DESC",
        "INSERT INTO term (academic_year, semester, start_date, end_date, status) VALUES (?, ?, ?, ?, ?)",
        "UPDATE term SET academic_year = ?, semester = ?, start_date = ?, end_date = ?, status = ? WHERE id = ?",
        "DELETE FROM term WHERE id = ?",
        payload -> new Object[] {payload.get("academicYear"), payload.get("semester"), payload.get("startDate"), payload.get("endDate"), payload.getOrDefault("status", "PLANNED")}
    ));
    return map;
  }

  private interface ParamBuilder {
    Object[] build(Map<String, Object> payload);
  }

  private static class ResourceConfig {
    private final String listSql;
    private final String insertSql;
    private final String updateSql;
    private final String deleteSql;
    private final ParamBuilder paramBuilder;

    private ResourceConfig(String listSql, String insertSql, String updateSql, String deleteSql, ParamBuilder paramBuilder) {
      this.listSql = listSql;
      this.insertSql = insertSql;
      this.updateSql = updateSql;
      this.deleteSql = deleteSql;
      this.paramBuilder = paramBuilder;
    }

    private Object[] params(Map<String, Object> payload) {
      return paramBuilder.build(payload);
    }
  }

  private static class Scope {
    private final String where;
    private final Object[] args;

    private Scope(String where, Object... args) {
      this.where = where;
      this.args = args;
    }
  }
}
