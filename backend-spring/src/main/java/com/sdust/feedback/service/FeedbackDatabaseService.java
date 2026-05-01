package com.sdust.feedback.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
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
    return result;
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
            "WHERE u.username = ? LIMIT 1",
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
            "WHERE u.id = ? LIMIT 1",
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
        "SELECT u.id, u.username, u.real_name AS realName, u.user_type AS userType, u.status, " +
            "d.name AS departmentName, cg.name AS className, r.role_key AS role, r.role_name AS roleName " +
            "FROM app_user u " +
            "LEFT JOIN department d ON d.id = u.department_id " +
            "LEFT JOIN class_group cg ON cg.id = u.class_group_id " +
            "LEFT JOIN user_role ur ON ur.user_id = u.id " +
            "LEFT JOIN role r ON r.id = ur.role_id " +
            where + " ORDER BY u.id DESC",
        args
    );
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

  public List<Map<String, Object>> weeklyTasks(Map<String, Object> user) {
    Scope scope = taskScope(user, "m", "wft");
    return db.queryForList(
        "SELECT wft.id, wft.week_no AS weekNo, cg.name AS className, wft.task_name AS taskName, " +
            "d.name AS departmentName, wft.class_group_id AS classGroupId, " +
            "DATE_FORMAT(wft.deadline, '%Y-%m-%d %H:%i:%s') AS deadline, wft.status " +
            "FROM weekly_feedback_task wft " +
            "LEFT JOIN class_group cg ON cg.id = wft.class_group_id " +
            "LEFT JOIN major m ON m.id = cg.major_id " +
            "LEFT JOIN department d ON d.id = m.department_id " +
            scope.where + " ORDER BY wft.week_no DESC, wft.id DESC",
        scope.args
    );
  }

  public List<Map<String, Object>> generateWeeklyTasks(Map<String, Object> payload) {
    Long termId = asLong(payload.get("termId"), 1L);
    Integer weekNo = asInteger(payload.get("weekNo"), 1);
    String deadline = asString(payload.get("deadline"));
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

      String taskName = "第" + weekNo + "周课程反馈任务";
      Long id = insert(
          "INSERT INTO weekly_feedback_task (term_id, week_no, class_group_id, task_name, deadline, status) VALUES (?, ?, ?, ?, ?, 'PENDING')",
          termId,
          weekNo,
          classGroupId,
          taskName,
          deadline.isBlank() ? null : deadline
      );

      Map<String, Object> item = new HashMap<>();
      item.put("id", id);
      item.put("weekNo", weekNo);
      item.put("className", classGroup.get("name"));
      item.put("taskName", taskName);
      item.put("deadline", deadline);
      item.put("status", "PENDING");
      created.add(item);
    }
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
            "wf.issue_suggestion AS issueSuggestion, wf.hardware_issue AS hardwareIssue, wf.remark, " +
            "wf.need_reply AS needReply, wf.status, " +
            "CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) AS feedbackWordCount, " +
            "CASE WHEN CHAR_LENGTH(CONCAT(IFNULL(wf.learning_outcome, ''), IFNULL(wf.issue_suggestion, ''), IFNULL(wf.hardware_issue, ''), IFNULL(wf.co_teacher_evaluation, ''))) < 20 THEN 'LOW_QUALITY' ELSE 'NORMAL' END AS qualityStatus, " +
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
    Long id = insert(
        "INSERT INTO weekly_feedback (task_id, student_id, course_id, teacher_id, planned_teacher_name, actual_teacher_name, class_group_name, week_range, assignment_assessment, guidance_mode, learning_outcome, content_arrangement_eval, co_teacher_evaluation, issue_suggestion, hardware_issue, remark, need_reply, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED')",
        asLong(feedback.get("taskId"), 1L),
        asLong(feedback.get("studentId"), 3L),
        asLong(feedback.get("courseId"), 1L),
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

  public List<Map<String, Object>> realtimeFeedbacks(Map<String, Object> user) {
    Scope scope = realtimeScope(user, "rf");
    return db.queryForList(
        "SELECT rf.id, rf.feedback_type AS type, rf.title, rf.content, rf.location_text AS locationText, " +
            "rf.urgency_level AS urgencyLevel, rf.status, rf.need_reply AS needReply, " +
            "u.real_name AS studentName, d.name AS departmentName, " +
            "(SELECT COUNT(*) FROM feedback_flag ff WHERE ff.feedback_type = 'REALTIME' AND ff.feedback_id = rf.id) AS flagCount, " +
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
    Long id = insert(
        "INSERT INTO realtime_feedback (student_id, department_id, feedback_type, title, content, location_text, need_reply, urgency_level, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        asLong(feedback.get("studentId"), 1L),
        asLong(feedback.get("departmentId"), null),
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
    created.put("status", status);
    return created;
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

  public List<Map<String, Object>> weeklyTaskCompliance(Map<String, Object> user) {
    Scope scope = taskScope(user, "m", "wft");
    return db.queryForList(
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

  private Long findOrCreateDepartment(String name) {
    String normalized = name.isBlank() ? "未归属院系" : name;
    Long existing = queryId("SELECT id FROM department WHERE name = ? LIMIT 1", normalized);
    return existing != null ? existing : insert("INSERT INTO department (code, name) VALUES (?, ?)", "DEPT-" + System.currentTimeMillis(), normalized);
  }

  private Long findOrCreateMajor(Long departmentId) {
    Long existing = queryId("SELECT id FROM major WHERE department_id = ? ORDER BY id ASC LIMIT 1", departmentId);
    return existing != null ? existing : insert("INSERT INTO major (department_id, code, name) VALUES (?, ?, ?)", departmentId, "MAJOR-" + System.currentTimeMillis(), "默认专业");
  }

  private Long findOrCreateClassGroup(Long majorId, String name) {
    Long existing = queryId("SELECT id FROM class_group WHERE name = ? LIMIT 1", name);
    if (existing != null) {
      return existing;
    }
    int gradeYear = 2024;
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("20\\d{2}").matcher(name);
    if (matcher.find()) {
      gradeYear = Integer.parseInt(matcher.group());
    }
    return insert("INSERT INTO class_group (major_id, grade_year, name) VALUES (?, ?, ?)", majorId, gradeYear, name);
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
    ensureColumn("teaching_task", "day_index", "INT");
    ensureColumn("teaching_task", "section_index", "INT");
    ensureColumn("teaching_task", "classroom", "VARCHAR(200)");
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
        payload -> new Object[] {payload.get("code"), payload.get("name")}
    ));
    map.put("majors", new ResourceConfig(
        "SELECT id, department_id AS departmentId, code, name, created_at AS createdAt FROM major ORDER BY id DESC",
        "INSERT INTO major (department_id, code, name) VALUES (?, ?, ?)",
        payload -> new Object[] {payload.get("departmentId"), payload.get("code"), payload.get("name")}
    ));
    map.put("classes", new ResourceConfig(
        "SELECT id, major_id AS majorId, grade_year AS gradeYear, name, created_at AS createdAt FROM class_group ORDER BY id DESC",
        "INSERT INTO class_group (major_id, grade_year, name) VALUES (?, ?, ?)",
        payload -> new Object[] {payload.get("majorId"), payload.get("gradeYear"), payload.get("name")}
    ));
    map.put("teachers", new ResourceConfig(
        "SELECT id, department_id AS departmentId, teacher_no AS teacherNo, teacher_name AS teacherName, created_at AS createdAt FROM teacher ORDER BY id DESC",
        "INSERT INTO teacher (department_id, teacher_no, teacher_name) VALUES (?, ?, ?)",
        payload -> new Object[] {payload.get("departmentId"), payload.get("teacherNo"), payload.get("teacherName")}
    ));
    map.put("courses", new ResourceConfig(
        "SELECT id, course_code AS courseCode, course_name AS courseName, department_id AS departmentId, created_at AS createdAt FROM course ORDER BY id DESC",
        "INSERT INTO course (course_code, course_name, department_id) VALUES (?, ?, ?)",
        payload -> new Object[] {payload.get("courseCode"), payload.get("courseName"), payload.get("departmentId")}
    ));
    map.put("terms", new ResourceConfig(
        "SELECT id, academic_year AS academicYear, semester, start_date AS startDate, end_date AS endDate, status FROM term ORDER BY id DESC",
        "INSERT INTO term (academic_year, semester, start_date, end_date, status) VALUES (?, ?, ?, ?, ?)",
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
    private final ParamBuilder paramBuilder;

    private ResourceConfig(String listSql, String insertSql, ParamBuilder paramBuilder) {
      this.listSql = listSql;
      this.insertSql = insertSql;
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
