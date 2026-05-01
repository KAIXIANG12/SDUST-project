# 教学反馈管理系统

面向山东科技大学济南校区教学质量反馈场景的毕业设计项目。当前技术主线已经收敛为前后端分离架构：

```text
前端：Vue 3 + TypeScript + Vite + Element Plus
后端：Java 11 + Spring Boot 2.7 + Spring JDBC
数据库：MySQL 8
```

## 目录结构

```text
.
├─ backend-spring/          # Spring Boot 后端
├─ frontend/                # Vue TypeScript 前端
├─ database/                # MySQL 建表与种子数据
├─ docs/                    # 设计文档与接口契约
├─ package.json             # 常用启动脚本
└─ 毕业设计需求梳理-学生反馈系统.md
```

## 启动后端

```powershell
cd "E:\山东科技大学毕业设计\backend-spring"
$env:DB_PASSWORD="你的MySQL密码"
mvn spring-boot:run
```

后端地址：

```text
http://localhost:8080/api/v1
```

## 启动前端

另开一个 PowerShell：

```powershell
cd "E:\山东科技大学毕业设计"
npm.cmd --prefix frontend run dev
```

前端地址：

```text
http://localhost:5173
```

## 测试账号

需要先导入 [database/seed.sql](E:/山东科技大学毕业设计/database/seed.sql)。

```text
root / admin123
cs_admin / admin123
monitor_se_2401 / admin123
student_demo / admin123
```

## 已实现主线

1. 登录、token 保存、刷新保持登录、退出登录
2. MySQL 基础数据读取与新增
3. 课表 `.xlsx` 导入
4. 周反馈任务生成
5. 学委周反馈提交
6. 普通实时反馈提交
7. 管理员处理闭环：受理、回复、关闭、历史处理记录
8. 敏感词强标
9. 高校风格管理端 UI
10. 前端 API 类型契约
11. Spring Boot 统一响应结构
12. 院系/班级/本人数据范围过滤
13. 学委未提交、迟交、低质量反馈履职标记

## 核心接口

完整接口说明见 [docs/接口契约规范.md](E:/山东科技大学毕业设计/docs/接口契约规范.md)。

```text
GET  /api/v1/health
POST /api/v1/auth/login
GET  /api/v1/auth/me
GET  /api/v1/dashboard/summary
GET  /api/v1/users
GET  /api/v1/master-data/{resource}
POST /api/v1/master-data/{resource}
GET  /api/v1/schedules/weekly-tasks
GET  /api/v1/schedules/weekly-task-compliance
POST /api/v1/schedules/weekly-tasks/generate
POST /api/v1/schedules/teaching-tasks/import
GET  /api/v1/feedbacks/weekly
POST /api/v1/feedbacks/weekly
GET  /api/v1/feedbacks/realtime
POST /api/v1/feedbacks/realtime
POST /api/v1/feedbacks/reply
GET  /api/v1/feedbacks/replies
PATCH /api/v1/feedbacks/status
GET  /api/v1/feedbacks/flags
```

## 构建验证

```powershell
npm.cmd --prefix frontend run build
```

Spring Boot 后端启动成功标志：

```text
Tomcat started on port(s): 8080
Started StudentFeedbackApplication
```
