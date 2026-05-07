export type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  message?: string;
};

export type RoleKey =
  | "SUPER_ADMIN"
  | "DEPARTMENT_ADMIN"
  | "CLASS_REPRESENTATIVE"
  | "STUDENT"
  | string;

export type CurrentUser = {
  id: number;
  username: string;
  realName: string;
  userType: string;
  role: RoleKey;
  roleName?: string;
  departmentId?: number | null;
  departmentName?: string;
  classGroupId?: number | null;
  status?: string;
};

export type LoginRequest = {
  username: string;
  password: string;
};

export type LoginResponse = {
  token: string;
  user: CurrentUser;
};

export type PersonalTimetableItem = Record<string, unknown> & {
  courseName?: string;
  teacherName?: string;
  classroom?: string;
  weeksRaw?: string;
  weekRange?: string;
  day?: number;
  serial?: number;
};

export type AcademicCalendarDay = {
  dayIndex: number;
  date: string;
  shortDate: string;
  today: boolean;
};

export type AcademicCalendar = {
  termCode: string;
  termStart: string;
  currentWeek: number;
  today: string;
  dateRow: AcademicCalendarDay[];
};

export type AcademicLoginRequest = {
  account: string;
  password: string;
  captchaSessionId: string;
  captchaCode: string;
  termCode?: string;
  weekNo?: number | string;
};

export type AcademicLoginResponse = LoginResponse & {
  termCode: string;
  weekNo: string;
  termStart?: string;
  dateRow?: AcademicCalendarDay[];
  rawCount: number;
  academicSessionId?: string;
  warning?: string;
  source?: string;
  timetable: PersonalTimetableItem[];
};

export type AcademicTimetableRequest = {
  academicSessionId: string;
  termCode?: string;
  weekNo?: number | string;
  enrichTeachers?: boolean | string;
};

export type MyTimetableResponse = {
  termCode: string;
  termStart: string;
  currentWeek: number;
  weekNo: number;
  today: string;
  dateRow: AcademicCalendarDay[];
  source?: string;
  info: PersonalTimetableItem[];
  timetable: PersonalTimetableItem[];
};

export type GradeRecord = {
  no: string;
  name: string;
  grade: string;
  makeup: string;
  rebuild: string;
  type: string;
  credit: string;
  gpa: string;
  minor: string;
};

export type GradeQueryRequest = {
  academicSessionId?: string;
  password?: string;
  captchaSessionId?: string;
  captchaCode?: string;
  termCode?: string;
};

export type GradeQueryResponse = {
  termCode: string;
  count: number;
  creditTotal: number;
  averageGpa: number;
  weightedGpa: number;
  grades: GradeRecord[];
};

export type AcademicDebugRequest = {
  academicSessionId: string;
  password?: string;
  termCode?: string;
  weekNo?: string | number;
};

export type AcademicDebugResponse = Record<string, unknown>;

export type HealthStatus = {
  status: string;
  appName: string;
  timestamp: string;
};

export type DashboardSummary = {
  pendingWeeklyTasks: number;
  urgentRealtimeFeedbacks: number;
  awaitingReplies: number;
  markedSensitiveFeedbacks: number;
  overdueUnsubmittedTasks?: number;
  lowQualityFeedbacks?: number;
};

export type MasterResource =
  | "departments"
  | "majors"
  | "classes"
  | "teachers"
  | "courses"
  | "terms";

export type MasterRecord = Record<string, unknown> & {
  id: number;
};

export type WeeklyTask = {
  id: number;
  weekNo: number;
  className: string;
  taskName: string;
  deadline?: string | null;
  status: string;
};

export type WeeklyTaskCompliance = {
  taskId: number;
  weekNo: number;
  departmentName: string;
  className: string;
  monitorUserId?: number | null;
  monitorName?: string | null;
  deadline?: string | null;
  feedbackId?: number | null;
  submittedAt?: string | null;
  complianceStatus: "OVERDUE_MISSING" | "PENDING" | "LATE_SUBMITTED" | "SUBMITTED" | string;
  feedbackWordCount?: number | null;
  totalCourseCount?: number;
  submittedCourseCount?: number;
  missingCourseCount?: number;
  lowQualityCount?: number;
  qualityRemark?: string;
};

export type WeeklyTaskItem = Record<string, unknown> & {
  taskId: number;
  weekNo: number;
  className: string;
  courseId: number;
  courseName: string;
  teacherId?: number | null;
  teacherName?: string;
  plannedTeacherName?: string;
  actualTeacherName?: string;
  teacherDepartmentName?: string;
  weekRange?: string;
  guidanceMode?: string;
  classroom?: string;
  feedbackId?: number | null;
  itemStatus: string;
  qualityStatus: string;
  qualityRemark?: string;
  feedbackWordCount?: number;
};

export type WeeklyFeedbackAnalytics = {
  byClass: Array<Record<string, unknown>>;
  byCourse: Array<Record<string, unknown>>;
  byTeacher: Array<Record<string, unknown>>;
};

export type WeeklyFeedbackSummary = Record<string, unknown> & {
  teacherName: string;
  courseName: string;
  classes: string;
  feedbackCount: number;
  sensitiveFlagCount: number;
  riskLevel: "HIGH" | "MEDIUM" | "LOW" | string;
  positiveSummary: string;
  issueSummary: string;
  hardwareSummary: string;
  modelSummary: string;
  aiSummary?: string;
  aiRiskLevel?: "HIGH" | "MEDIUM" | "LOW" | string;
  aiSensitivePoints?: string[];
  aiSuggestions?: string[];
  aiProvider?: string;
  aiError?: string;
};

export type WeeklyFeedback = Record<string, unknown> & {
  id: number;
  className?: string;
  courseName?: string;
  status: string;
  needReply?: boolean | number;
  teacherDepartmentName?: string;
  plannedTeacherName?: string;
  actualTeacherName?: string;
  weekRange?: string;
  assignmentAssessment?: string;
  guidanceMode?: string;
  learningOutcome?: string;
  contentArrangementEval?: string;
  coTeacherEvaluation?: string;
  issueSuggestion?: string;
  hardwareIssue?: string;
  remark?: string;
  latestReplyContent?: string | null;
  latestReplyAt?: string | null;
};

export type RealtimeFeedback = {
  id: number;
  type: string;
  title: string;
  content: string;
  locationText?: string | null;
  urgencyLevel: "HIGH" | "MEDIUM" | "LOW" | string;
  status: string;
  needReply: boolean | number;
  flagCount?: number;
  createdAt?: string;
  latestReplyContent?: string | null;
  latestReplyAt?: string | null;
};

export type FeedbackFlag = {
  id: number;
  feedbackType: string;
  feedbackId: number;
  flagType: string;
  flagValue: string;
  createdAt?: string;
};

export type ImportTeachingTasksRequest = {
  termId: number;
  rows: Array<Record<string, unknown>>;
};

export type ImportTeachingTasksResponse = {
  importedCount: number;
  skippedCount: number;
};

export type SyncPersonalTimetableRequest = {
  termId: number;
  account: string;
  password: string;
  className?: string;
  captchaSessionId?: string;
  captchaCode?: string;
  termCode?: string;
  weekNo?: number | string;
};

export type SyncPersonalTimetableResponse = ImportTeachingTasksResponse & {
  termCode: string;
  weekNo: string;
  rawCount: number;
  normalizedCount: number;
};

export type AcademicCaptchaResponse = {
  captchaSessionId: string;
  imageBase64: string;
};

export type GenerateWeeklyTasksRequest = {
  termId: number;
  weekNo: number;
  deadline?: string | null;
  classGroupIds?: number[];
};

export type ReplyFeedbackRequest = {
  feedbackType: "REALTIME" | "WEEKLY" | string;
  feedbackId: number;
  replyContent: string;
  status?: string;
};

export type FeedbackReply = {
  id: number;
  feedbackType: string;
  feedbackId: number;
  replierUserId: number;
  replierName?: string;
  replyContent: string;
  createdAt: string;
};
