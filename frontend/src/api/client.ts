import { APP_CONFIG } from "../config";
import type {
  ApiEnvelope,
  AcademicLoginRequest,
  AcademicLoginResponse,
  AcademicCalendar,
  AcademicCaptchaResponse,
  CurrentUser,
  DashboardSummary,
  FeedbackFlag,
  FeedbackReply,
  GenerateWeeklyTasksRequest,
  GradeQueryRequest,
  GradeQueryResponse,
  HealthStatus,
  ImportTeachingTasksRequest,
  ImportTeachingTasksResponse,
  LoginRequest,
  LoginResponse,
  MasterRecord,
  MasterResource,
  MyTimetableResponse,
  RealtimeFeedback,
  ReplyFeedbackRequest,
  SyncPersonalTimetableRequest,
  SyncPersonalTimetableResponse,
  WeeklyFeedback,
  WeeklyTask,
  WeeklyTaskCompliance
} from "./types";

const TOKEN_KEY = "student_feedback_token";

export function getStoredToken(): string {
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function setStoredToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearStoredToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getStoredToken();
  const response = await fetch(`${APP_CONFIG.apiBaseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {})
    }
  });

  const payload = (await response.json()) as ApiEnvelope<T>;

  if (!response.ok || payload.success === false) {
    throw new Error(payload.message || "请求失败");
  }

  return payload.data;
}

export const apiClient = {
  login(payload: LoginRequest) {
    return request<LoginResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  academicLogin(payload: AcademicLoginRequest) {
    return request<AcademicLoginResponse>("/auth/academic-login", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getCurrentUser() {
    return request<CurrentUser>("/auth/me");
  },
  getHealth() {
    return request<HealthStatus>("/health");
  },
  getModules() {
    return request<string[]>("/meta/modules");
  },
  getDashboardSummary() {
    return request<DashboardSummary>("/dashboard/summary");
  },
  getUsers() {
    return request<CurrentUser[]>("/users");
  },
  getMasterData(resource: MasterResource) {
    return request<MasterRecord[]>(`/master-data/${resource}`);
  },
  createMasterData(resource: MasterResource, payload: Record<string, unknown>) {
    return request<MasterRecord>(`/master-data/${resource}`, {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getWeeklyTasks() {
    return request<WeeklyTask[]>("/schedules/weekly-tasks");
  },
  getWeeklyTaskCompliance() {
    return request<WeeklyTaskCompliance[]>("/schedules/weekly-task-compliance");
  },
  importTeachingTasks(payload: ImportTeachingTasksRequest) {
    return request<ImportTeachingTasksResponse>("/schedules/teaching-tasks/import", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getAcademicCaptcha() {
    return request<AcademicCaptchaResponse>("/schedules/teaching-tasks/captcha");
  },
  getAcademicCalendar() {
    return request<AcademicCalendar>("/schedules/academic-calendar/current");
  },
  getMyTimetable(week?: number) {
    const params = week ? `?week=${encodeURIComponent(String(week))}` : "";
    return request<MyTimetableResponse>(`/schedules/my-timetable${params}`);
  },
  queryGrades(payload: GradeQueryRequest) {
    return request<GradeQueryResponse>("/academic/grades/query", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  syncPersonalTimetable(payload: SyncPersonalTimetableRequest) {
    return request<SyncPersonalTimetableResponse>("/schedules/teaching-tasks/sync-personal", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  generateWeeklyTasks(payload: GenerateWeeklyTasksRequest) {
    return request<WeeklyTask[]>("/schedules/weekly-tasks/generate", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getWeeklyFeedbacks() {
    return request<WeeklyFeedback[]>("/feedbacks/weekly");
  },
  createWeeklyFeedback(payload: Record<string, unknown>) {
    return request<WeeklyFeedback>("/feedbacks/weekly", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  getRealtimeFeedbacks() {
    return request<RealtimeFeedback[]>("/feedbacks/realtime");
  },
  getFeedbackFlags() {
    return request<FeedbackFlag[]>("/feedbacks/flags");
  },
  getFeedbackReplies(feedbackType: string, feedbackId: number) {
    const params = new URLSearchParams({
      feedbackType,
      feedbackId: String(feedbackId)
    });
    return request<FeedbackReply[]>(`/feedbacks/replies?${params.toString()}`);
  },
  replyFeedback(payload: ReplyFeedbackRequest) {
    return request<ReplyFeedbackRequest & { status: string }>("/feedbacks/reply", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  },
  updateFeedbackStatus(payload: Pick<ReplyFeedbackRequest, "feedbackType" | "feedbackId" | "status">) {
    return request<ReplyFeedbackRequest & { status: string }>("/feedbacks/status", {
      method: "PATCH",
      body: JSON.stringify(payload)
    });
  },
  createRealtimeFeedback(payload: Record<string, unknown>) {
    return request<RealtimeFeedback>("/feedbacks/realtime", {
      method: "POST",
      body: JSON.stringify(payload)
    });
  }
};
