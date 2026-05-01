USE student_feedback_system;

INSERT IGNORE INTO department (id, code, name) VALUES
  (1, 'SDUST-JN-CSE', '计算机科学与工程学院'),
  (2, 'SDUST-JN-ME', '机械与自动化系'),
  (3, 'SDUST-JN-FL', '外国语学院');

INSERT IGNORE INTO major (id, department_id, code, name) VALUES
  (1, 1, 'SE', '软件工程'),
  (2, 1, 'CS', '计算机科学与技术'),
  (3, 2, 'AUTO', '自动化'),
  (4, 2, 'EE', '电气工程及其自动化');

INSERT IGNORE INTO class_group (id, major_id, grade_year, name) VALUES
  (1, 1, 2024, '软件工程2024级1班'),
  (2, 1, 2024, '软件工程2024级2班'),
  (3, 3, 2022, '自动化*2022-1'),
  (4, 4, 2023, '电气工程及其自动化*2023-1');

INSERT IGNORE INTO role (id, role_key, role_name) VALUES
  (1, 'SUPER_ADMIN', '超级管理员'),
  (2, 'DEPARTMENT_ADMIN', '院系管理员'),
  (3, 'CLASS_REPRESENTATIVE', '学委'),
  (4, 'STUDENT', '普通学生');

INSERT IGNORE INTO app_user
  (id, username, password_hash, real_name, user_type, department_id, class_group_id, phone, status)
VALUES
  (1, 'root', 'plain:admin123', '系统管理员', 'ADMIN', NULL, NULL, NULL, 'ACTIVE'),
  (2, 'cs_admin', 'plain:admin123', '计算机学院管理员', 'ADMIN', 1, NULL, NULL, 'ACTIVE'),
  (3, 'monitor_se_2401', 'plain:admin123', '软件工程一班学委', 'STUDENT', 1, 1, NULL, 'ACTIVE'),
  (4, 'student_demo', 'plain:admin123', '普通学生示例', 'STUDENT', 1, 1, NULL, 'ACTIVE');

INSERT IGNORE INTO user_role (id, user_id, role_id) VALUES
  (1, 1, 1),
  (2, 2, 2),
  (3, 3, 3),
  (4, 4, 4);

INSERT IGNORE INTO teacher (id, department_id, teacher_no, teacher_name) VALUES
  (1, 2, 'T-SDUST-001', '牛君'),
  (2, 2, 'T-SDUST-002', 'Andrew Brocklesby'),
  (3, 2, 'T-SDUST-003', '张帅帅'),
  (4, 2, 'T-SDUST-004', 'Zhenwei Cao');

INSERT IGNORE INTO term (id, academic_year, semester, start_date, end_date, status) VALUES
  (1, '2024-2025', '2', '2025-02-24', '2025-07-06', 'ACTIVE');

INSERT IGNORE INTO course (id, course_code, course_name, department_id) VALUES
  (1, 'AE-001', 'Analog Electronics', 2),
  (2, 'RCS-001', 'Robotic Control System and Design', 2),
  (3, 'SE-001', '软件工程', 1),
  (4, 'DE-001', 'Digital Electronics', 2);

INSERT IGNORE INTO teaching_task
  (id, term_id, course_id, teacher_id, class_group_id, planned_teacher_name, actual_teacher_name, week_range, guidance_mode)
VALUES
  (1, 1, 1, 1, 1, '牛君,Andrew Brocklesby', '牛君,Andrew Brocklesby', '2-4,8,10,13,15-16', '线上+线下'),
  (2, 1, 2, 3, 3, 'Zhenwei Cao,张帅帅', 'Zhenwei Cao,张帅帅', '13-14', '线下');

INSERT IGNORE INTO weekly_feedback_task
  (id, term_id, week_no, class_group_id, task_name, deadline, status)
VALUES
  (1, 1, 13, 1, '第13周课程反馈任务', '2025-05-25 20:00:00', 'PENDING'),
  (2, 1, 13, 3, '第13周课程反馈任务', '2025-05-25 20:00:00', 'IN_PROGRESS');

INSERT IGNORE INTO weekly_feedback
  (id, task_id, student_id, course_id, teacher_id, planned_teacher_name, actual_teacher_name, class_group_name, week_range, assignment_assessment, guidance_mode, learning_outcome, issue_suggestion, hardware_issue, need_reply, status)
VALUES
  (1, 1, 3, 1, 1, '牛君,Andrew Brocklesby', '牛君,Andrew Brocklesby', '软件工程2024级1班', '2-4,8,10,13,15-16', 'cloudcampus', '线上+线下', '教学效果较好，同学们上课比较积极', '课程难度较大', '无', 1, 'SUBMITTED'),
  (2, 2, 3, 2, 3, 'Zhenwei Cao,张帅帅', 'Zhenwei Cao,张帅帅', '自动化*2022-1', '13-14', 'tutorial', '线下', '学习现控知识', '无', '无', 0, 'SUBMITTED');

INSERT IGNORE INTO realtime_feedback
  (id, student_id, department_id, feedback_type, title, content, location_text, need_reply, urgency_level, status)
VALUES
  (1, 3, 2, 'HARDWARE', '1-302 教室设备故障', '播放外教录播视频声音太小且无法调节。', '1-302', 1, 'HIGH', 'PENDING_REPLY'),
  (2, 4, 1, 'TEACHING', '课堂节奏过快', '外教语速较快，部分同学难以及时跟上。', NULL, 0, 'MEDIUM', 'SUBMITTED');

INSERT IGNORE INTO sensitive_term (id, term_text, category, risk_level) VALUES
  (1, '水课', '教学质量', 'HIGH'),
  (2, '早退', '课堂纪律', 'HIGH'),
  (3, '无故离场', '课堂纪律', 'HIGH');
