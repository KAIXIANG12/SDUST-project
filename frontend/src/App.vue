<script setup lang="ts">
import {
  Bell,
  Calendar,
  Collection,
  DataAnalysis,
  DocumentChecked,
  Download,
  HomeFilled,
  Lock,
  Plus,
  OfficeBuilding,
  Refresh,
  Search,
  Setting,
  Upload,
  UserFilled,
  WarningFilled
} from "@element-plus/icons-vue";
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import ExcelJS from "exceljs";
import {
  apiClient,
  clearStoredToken,
  getStoredToken,
  setStoredToken
} from "./api/client";

const activeMenu = ref("dashboard");
const activeLoginTab = ref("academic");
const loading = ref(false);
const authChecking = ref(true);
const isAuthenticated = ref(false);
const submitDrawerVisible = ref(false);
const masterDataDrawerVisible = ref(false);
const taskGenerateDrawerVisible = ref(false);
const weeklyFeedbackDrawerVisible = ref(false);
const replyDrawerVisible = ref(false);
const timetableDetailVisible = ref(false);
const activeMasterResource = ref("departments");
const importingSchedule = ref(false);
const loadingAcademicCaptcha = ref(false);
const queryingGrades = ref(false);
const replyRecords = ref([]);
const timetableDetailCourses = ref([]);
const personalTimetableStorageKey = "student_feedback_personal_timetable";
const personalTimetableMetaStorageKey = "student_feedback_personal_timetable_meta";
const academicSessionStorageKey = "student_feedback_academic_session_id";
const academicSessionId = ref(localStorage.getItem(academicSessionStorageKey) || "");

const state = reactive({
  health: null,
  modules: [],
  currentUser: null,
  summary: {},
  users: [],
  masterData: {
    departments: [],
    majors: [],
    classes: [],
    teachers: [],
    courses: [],
    terms: []
  },
  weeklyTasks: [],
  weeklyTaskCompliance: [],
  weeklyFeedbacks: [],
  realtimeFeedbacks: [],
  feedbackFlags: [],
  personalTimetable: [],
  gradeRecords: [],
  gradeSummary: null,
  personalTimetableMeta: {
    termCode: "",
    weekNo: "",
    termStart: "",
    today: "",
    dateRow: []
  }
});

const realtimeForm = reactive({
  type: "HARDWARE",
  urgencyLevel: "MEDIUM",
  title: "",
  locationText: "",
  content: "",
  needReply: true
});

const loginForm = reactive({
  username: "",
  password: ""
});

const academicLoginForm = reactive({
  account: "",
  password: "",
  captchaSessionId: "",
  captchaImage: "",
  captchaCode: "",
});

const gradeQueryForm = reactive({
  termCode: ""
});

const timetableView = reactive({
  weekNo: 1
});

const masterDataForm = reactive({});

const taskGenerateForm = reactive({
  termId: 1,
  weekNo: 13,
  deadline: "",
  classGroupIdsText: ""
});

const weeklyFeedbackForm = reactive({
  taskId: null,
  courseId: null,
  teacherId: null,
  plannedTeacherName: "",
  actualTeacherName: "",
  classGroupName: "",
  weekRange: "",
  assignmentAssessment: "",
  guidanceMode: "",
  learningOutcome: "",
  contentArrangementEval: "",
  coTeacherEvaluation: "",
  issueSuggestion: "",
  hardwareIssue: "",
  remark: "",
  needReply: false
});

const replyForm = reactive({
  feedbackType: "REALTIME",
  feedbackId: null,
  status: "CLOSED",
  replyContent: ""
});

const menuItems = [
  { key: "dashboard", label: "驾驶舱", icon: HomeFilled },
  { key: "baseData", label: "基础数据", icon: OfficeBuilding },
  { key: "schedule", label: "课表任务", icon: Calendar },
  { key: "feedback", label: "反馈中心", icon: DocumentChecked },
  { key: "analytics", label: "统计分析", icon: DataAnalysis },
  { key: "governance", label: "落地治理", icon: Setting }
];

const masterDataTabs = [
  {
    key: "departments",
    label: "院系",
    fields: [
      { prop: "code", label: "院系编码", placeholder: "例如：SDUST-JN-CSE" },
      { prop: "name", label: "院系名称", placeholder: "例如：计算机科学与工程学院" }
    ]
  },
  {
    key: "majors",
    label: "专业",
    fields: [
      { prop: "departmentId", label: "院系ID", type: "number" },
      { prop: "code", label: "专业编码", placeholder: "例如：SE" },
      { prop: "name", label: "专业名称", placeholder: "例如：软件工程" }
    ]
  },
  {
    key: "classes",
    label: "班级",
    fields: [
      { prop: "majorId", label: "专业ID", type: "number" },
      { prop: "gradeYear", label: "年级", type: "number", placeholder: "例如：2024" },
      { prop: "name", label: "班级名称", placeholder: "例如：软件工程2024级1班" }
    ]
  },
  {
    key: "teachers",
    label: "教师",
    fields: [
      { prop: "departmentId", label: "院系ID", type: "number" },
      { prop: "teacherNo", label: "教师编号", placeholder: "例如：T-SDUST-001" },
      { prop: "teacherName", label: "教师姓名", placeholder: "例如：张老师" }
    ]
  },
  {
    key: "courses",
    label: "课程列表",
    fields: [
      { prop: "courseCode", label: "课程编码", placeholder: "例如：SE-001" },
      { prop: "courseName", label: "课程名称", placeholder: "例如：软件工程" },
      { prop: "departmentId", label: "院系ID", type: "number" }
    ]
  },
  {
    key: "terms",
    label: "学期",
    fields: [
      { prop: "academicYear", label: "学年", placeholder: "例如：2025-2026" },
      { prop: "semester", label: "学期", placeholder: "例如：1" },
      { prop: "startDate", label: "开始日期", type: "date" },
      { prop: "endDate", label: "结束日期", type: "date" },
      { prop: "status", label: "状态", placeholder: "ACTIVE / PLANNED" }
    ]
  }
];

const activeMasterTab = computed(() =>
  masterDataTabs.find((item) => item.key === activeMasterResource.value)
);

const statCards = computed(() => [
  {
    label: "待完成周反馈",
    value: state.summary.pendingWeeklyTasks ?? 0,
    icon: Calendar,
    tone: "teal"
  },
  {
    label: "紧急反馈待处理",
    value: state.summary.urgentRealtimeFeedbacks ?? 0,
    icon: WarningFilled,
    tone: "orange"
  },
  {
    label: "待管理员回复",
    value: state.summary.awaitingReplies ?? 0,
    icon: Bell,
    tone: "blue"
  },
  {
    label: "敏感信息标记",
    value: state.summary.markedSensitiveFeedbacks ?? 0,
    icon: Collection,
    tone: "red"
  },
  {
    label: "逾期未提交",
    value: state.summary.overdueUnsubmittedTasks ?? 0,
    icon: WarningFilled,
    tone: "orange"
  }
]);

const weekDays = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const classSections = [0, 1, 2, 3, 4];

const isStudentLike = computed(() =>
  ["STUDENT", "CLASS_REPRESENTATIVE"].includes(String(state.currentUser?.role || ""))
);

const currentDayIndex = computed(() => {
  const day = new Date().getDay();
  return day === 0 ? 6 : day - 1;
});

const activeWeekDays = computed(() =>
  timetableDateRow.value.map((item, index) => ({
    index,
    label: weekDays[index],
    shortDate: item.shortDate,
    today: item.today
  }))
);

const timetableDateRow = computed(() => {
  const termStart = state.personalTimetableMeta.termStart;
  if (!termStart) {
    return weekDays.map((_, index) => ({ shortDate: "", today: index === currentDayIndex.value }));
  }
  const start = new Date(`${termStart}T00:00:00`);
  start.setDate(start.getDate() + (Number(timetableView.weekNo || 1) - 1) * 7);
  const today = new Date();
  return weekDays.map((_, index) => {
    const date = new Date(start);
    date.setDate(start.getDate() + index);
    const shortDate = `${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")}`;
    const isToday =
      date.getFullYear() === today.getFullYear() &&
      date.getMonth() === today.getMonth() &&
      date.getDate() === today.getDate();
    return { shortDate, today: isToday };
  });
});

const filteredPersonalTimetable = computed(() =>
  state.personalTimetable.filter((item) => courseIncludesWeek(item, Number(timetableView.weekNo || 1)))
);

const definedTimetable = computed(() => {
  const record = {};
  for (const course of state.personalTimetable) {
    const day = Number(course.day);
    const serial = Number(course.serial);
    if (Number.isNaN(day) || Number.isNaN(serial)) {
      continue;
    }
    const node = normalizeTimetableCourse(course);
    const key = `${day}-${serial}`;
    const current = record[key] || { simple: node, all: [] };
    if (node.isCurWeek) {
      current.simple = node;
    }
    current.all.push(node);
    record[key] = current;
  }
  return record;
});

const timetableGridStyle = computed(() => ({
  gridTemplateColumns: `92px repeat(${activeWeekDays.value.length}, minmax(150px, 1fr))`
}));

function timetableCell(day, serial) {
  return definedTimetable.value[`${day}-${serial}`];
}

function normalizeTimetableCourse(course) {
  const courseName = String(course.courseName || course.name || "未命名课程");
  const isCurWeek = courseIncludesWeek(course, Number(timetableView.weekNo || 1));
  return {
    ...course,
    courseName,
    teacherName: course.teacherName || course.teacher || "",
    classroom: course.classroom || course.locationText || "",
    weeksRaw: course.weeksRaw || course.weekRange || "",
    isCurWeek,
    background: isCurWeek ? courseColor(courseName) : "#aab4c0"
  };
}

function courseColor(name) {
  const colors = ["#0b4ea2", "#168a55", "#d9822b", "#8b5a2b", "#3b6ea8", "#7c3aed", "#be123c"];
  const total = String(name).split("").reduce((sum, char) => sum + char.charCodeAt(0), 0);
  return colors[total % colors.length];
}

function sectionLabel(serial) {
  const start = serial * 2 + 1;
  return `第${start}-${start + 1}节`;
}

function courseIncludesWeek(course, weekNo) {
  const raw = String(course.weeksRaw || course.weekRange || "");
  if (!raw.trim()) {
    return true;
  }
  const normalized = raw
    .replace(/[()（）]/g, "")
    .replace(/[，、;]/g, ",")
    .replace(/周/g, "");
  return normalized.split(",").some((part) => {
    const value = part.trim();
    if (!value) {
      return false;
    }
    const oddEven = value.includes("单") || value.endsWith("/1")
      ? 1
      : value.includes("双") || value.endsWith("/2")
        ? 0
        : null;
    const cleaned = value.replace(/[单双]/g, "").replace(/\/[12]/g, "");
    const match = cleaned.match(/(\d+)(?:-(\d+))?/);
    if (!match) {
      return false;
    }
    const start = Number(match[1]);
    const end = Number(match[2] || match[1]);
    const inRange = weekNo >= start && weekNo <= end;
    if (!inRange) {
      return false;
    }
    return oddEven === null ? true : weekNo % 2 === oddEven;
  });
}

function changeTimetableWeek(offset) {
  const next = Number(timetableView.weekNo || 1) + offset;
  timetableView.weekNo = Math.min(30, Math.max(1, next));
  loadMyTimetable(timetableView.weekNo);
}

function openTimetableDetail(cell) {
  timetableDetailCourses.value = cell?.all || [];
  timetableDetailVisible.value = timetableDetailCourses.value.length > 0;
}

const pageTitle = computed(() => {
  const matched = menuItems.find((item) => item.key === activeMenu.value);
  return matched?.label || "学生反馈系统";
});

const highPriorityFeedbacks = computed(() =>
  state.realtimeFeedbacks.filter((item) => item.urgencyLevel === "HIGH")
);

const canManageFeedback = computed(() =>
  ["SUPER_ADMIN", "DEPARTMENT_ADMIN"].includes(state.currentUser?.role || "")
);

async function loadAllData() {
  loading.value = true;
  try {
    const [
      health,
      modules,
      currentUser,
      summary,
      users,
      departments,
      majors,
      classes,
      teachers,
      courses,
      terms,
      academicCalendar,
      myTimetable,
      weeklyTasks,
      weeklyTaskCompliance,
      weeklyFeedbacks,
      realtimeFeedbacks,
      feedbackFlags
    ] = await Promise.all([
      apiClient.getHealth(),
      apiClient.getModules(),
      apiClient.getCurrentUser(),
      apiClient.getDashboardSummary(),
      apiClient.getUsers(),
      apiClient.getMasterData("departments"),
      apiClient.getMasterData("majors"),
      apiClient.getMasterData("classes"),
      apiClient.getMasterData("teachers"),
      apiClient.getMasterData("courses"),
      apiClient.getMasterData("terms"),
      apiClient.getAcademicCalendar(),
      apiClient.getMyTimetable(Number(timetableView.weekNo || 0) || undefined),
      apiClient.getWeeklyTasks(),
      apiClient.getWeeklyTaskCompliance(),
      apiClient.getWeeklyFeedbacks(),
      apiClient.getRealtimeFeedbacks(),
      apiClient.getFeedbackFlags()
    ]);

    state.health = health;
    state.modules = modules;
    state.currentUser = currentUser;
    state.summary = summary;
    state.users = users;
    state.masterData.departments = departments;
    state.masterData.majors = majors;
    state.masterData.classes = classes;
    state.masterData.teachers = teachers;
    state.masterData.courses = courses;
    state.masterData.terms = terms;
    applyTimetableResult(myTimetable, { keepExistingWhenEmpty: state.personalTimetable.length > 0 });
    if (!state.personalTimetableMeta.termCode) {
      state.personalTimetableMeta.termCode = academicCalendar.termCode;
      state.personalTimetableMeta.termStart = academicCalendar.termStart;
      state.personalTimetableMeta.today = academicCalendar.today;
      state.personalTimetableMeta.dateRow = academicCalendar.dateRow;
      state.personalTimetableMeta.weekNo = String(academicCalendar.currentWeek);
      timetableView.weekNo = academicCalendar.currentWeek;
    }
    state.weeklyTasks = weeklyTasks;
    state.weeklyTaskCompliance = weeklyTaskCompliance;
    state.weeklyFeedbacks = weeklyFeedbacks;
    state.realtimeFeedbacks = realtimeFeedbacks;
    state.feedbackFlags = feedbackFlags;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "系统数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function loadMyTimetable(week) {
  if (!isAuthenticated.value) {
    return;
  }
  try {
    const result = await apiClient.getMyTimetable(Number(week || timetableView.weekNo || 1));
    applyTimetableResult(result, { keepExistingWhenEmpty: false });
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "课表加载失败");
  }
}

function applyTimetableResult(result, options = {}) {
  if (!result) {
    return;
  }
  const nextTimetable = result.info || result.timetable || [];
  const nextMeta = {
    termCode: result.termCode || state.personalTimetableMeta.termCode || "",
    weekNo: String(result.weekNo || result.currentWeek || timetableView.weekNo || 1),
    termStart: result.termStart || state.personalTimetableMeta.termStart || "",
    today: result.today || state.personalTimetableMeta.today || "",
    dateRow: result.dateRow || state.personalTimetableMeta.dateRow || []
  };
  const shouldKeepExisting = options.keepExistingWhenEmpty && nextTimetable.length === 0;
  if (!shouldKeepExisting) {
    state.personalTimetable = nextTimetable;
  }
  state.personalTimetableMeta = nextMeta;
  timetableView.weekNo = Number(nextMeta.weekNo || 1);
  localStorage.setItem(personalTimetableStorageKey, JSON.stringify(state.personalTimetable));
  localStorage.setItem(personalTimetableMetaStorageKey, JSON.stringify(state.personalTimetableMeta));
}

async function initializeAuth() {
  const token = getStoredToken();
  restorePersonalTimetable();

  if (!token) {
    isAuthenticated.value = false;
    authChecking.value = false;
    refreshLoginCaptcha();
    return;
  }

  loading.value = true;
  try {
    state.currentUser = await apiClient.getCurrentUser();
    isAuthenticated.value = true;
    await loadAllData();
  } catch (error) {
    clearStoredToken();
    isAuthenticated.value = false;
    state.currentUser = null;
    ElMessage.warning("登录状态已失效，请重新登录");
  } finally {
    loading.value = false;
    authChecking.value = false;
  }
}

function restorePersonalTimetable() {
  try {
    const timetable = localStorage.getItem(personalTimetableStorageKey);
    const meta = localStorage.getItem(personalTimetableMetaStorageKey);
    state.personalTimetable = timetable ? JSON.parse(timetable) : [];
    state.personalTimetableMeta = meta
      ? JSON.parse(meta)
      : { termCode: "", weekNo: "", termStart: "", today: "", dateRow: [] };
    timetableView.weekNo = Number(state.personalTimetableMeta.weekNo || 1);
  } catch {
    state.personalTimetable = [];
    state.personalTimetableMeta = { termCode: "", weekNo: "", termStart: "", today: "", dateRow: [] };
  }
}

function savePersonalTimetable(timetable, meta) {
  state.personalTimetable = timetable || [];
  state.personalTimetableMeta = meta || { termCode: "", weekNo: "", termStart: "", today: "", dateRow: [] };
  localStorage.setItem(personalTimetableStorageKey, JSON.stringify(state.personalTimetable));
  localStorage.setItem(personalTimetableMetaStorageKey, JSON.stringify(state.personalTimetableMeta));
}

async function login() {
  if (!loginForm.username.trim() || !loginForm.password.trim()) {
    ElMessage.warning("请输入账号和密码");
    return;
  }

  loading.value = true;
  try {
    const result = await apiClient.login({ ...loginForm });
    setStoredToken(result.token);
    state.currentUser = result.user;
    isAuthenticated.value = true;
    ElMessage.success("登录成功");
    await loadAllData();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "登录失败");
  } finally {
    loading.value = false;
  }
}

async function refreshLoginCaptcha() {
  loadingAcademicCaptcha.value = true;
  try {
    const result = await apiClient.getAcademicCaptcha();
    academicLoginForm.captchaSessionId = result.captchaSessionId;
    academicLoginForm.captchaImage = result.imageBase64;
    academicLoginForm.captchaCode = "";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "验证码获取失败");
  } finally {
    loadingAcademicCaptcha.value = false;
  }
}

async function queryMyGrades() {
  if (!academicSessionId.value) {
    ElMessage.warning("教务登录会话已失效，请重新进行学校账号认证");
    returnToAcademicLogin();
    return;
  }
  queryingGrades.value = true;
  try {
    const result = await apiClient.queryGrades({
      academicSessionId: academicSessionId.value,
      termCode: gradeQueryForm.termCode.trim()
    });
    state.gradeRecords = result.grades || [];
    state.gradeSummary = result;
    if (result.count > 0) {
      ElMessage.success(`已读取 ${result.count} 条成绩`);
    } else if (gradeQueryForm.termCode.trim()) {
      ElMessage.warning("该学期暂未查到成绩，可清空学期代码后查询全部学期成绩");
    } else {
      ElMessage.info("当前未查到成绩记录");
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "成绩查询失败");
    clearAcademicSession();
    if (error instanceof Error && error.message.includes("会话")) {
      returnToAcademicLogin();
    }
  } finally {
    queryingGrades.value = false;
  }
}

function saveAcademicSession(nextAcademicSessionId) {
  academicSessionId.value = nextAcademicSessionId || "";
  if (academicSessionId.value) {
    localStorage.setItem(academicSessionStorageKey, academicSessionId.value);
  } else {
    localStorage.removeItem(academicSessionStorageKey);
  }
}

function clearAcademicSession() {
  saveAcademicSession("");
}

function returnToAcademicLogin() {
  const username = String(state.currentUser?.username || academicLoginForm.account || "");
  clearStoredToken();
  clearAcademicSession();
  isAuthenticated.value = false;
  activeLoginTab.value = "academic";
  activeMenu.value = "dashboard";
  state.currentUser = null;
  academicLoginForm.account = username;
  academicLoginForm.password = "";
  academicLoginForm.captchaCode = "";
  refreshLoginCaptcha();
}

async function academicLogin() {
  if (
    !academicLoginForm.account.trim() ||
    !academicLoginForm.password.trim() ||
    !academicLoginForm.captchaSessionId ||
    !academicLoginForm.captchaCode.trim()
  ) {
    ElMessage.warning("请填写学号、密码和验证码");
    return;
  }

  loading.value = true;
  try {
    const result = await apiClient.academicLogin({
      account: academicLoginForm.account.trim(),
      password: academicLoginForm.password,
      captchaSessionId: academicLoginForm.captchaSessionId,
      captchaCode: academicLoginForm.captchaCode.trim()
    });
    setStoredToken(result.token);
    saveAcademicSession(result.academicSessionId || "");
    state.currentUser = result.user;
    savePersonalTimetable(result.timetable, {
      termCode: result.termCode,
      weekNo: result.weekNo,
      termStart: result.termStart || "",
      today: "",
      dateRow: result.dateRow || []
    });
    timetableView.weekNo = Number(result.weekNo || 1);
    isAuthenticated.value = true;
    activeMenu.value = "dashboard";
    academicLoginForm.password = "";
    ElMessage.success(result.warning ? "学校账号登录成功，课表稍后再同步" : `学校账号登录成功，已读取 ${result.rawCount} 条课表`);
    await loadAllData();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "学校账号登录失败");
    refreshLoginCaptcha();
  } finally {
    loading.value = false;
  }
}

function logout() {
  clearStoredToken();
  clearAcademicSession();
  isAuthenticated.value = false;
  state.currentUser = null;
  savePersonalTimetable([], { termCode: "", weekNo: "", termStart: "", today: "", dateRow: [] });
  ElMessage.success("已退出登录");
}

function resetRealtimeForm() {
  realtimeForm.type = "HARDWARE";
  realtimeForm.urgencyLevel = "MEDIUM";
  realtimeForm.title = "";
  realtimeForm.locationText = "";
  realtimeForm.content = "";
  realtimeForm.needReply = true;
}

async function submitRealtimeFeedback() {
  if (!realtimeForm.title.trim() || !realtimeForm.content.trim()) {
    ElMessage.warning("请填写标题和详细内容");
    return;
  }

  try {
    await apiClient.createRealtimeFeedback({ ...realtimeForm });
    ElMessage.success("实时反馈已提交，管理员待办已更新");
    submitDrawerVisible.value = false;
    resetRealtimeForm();
    state.realtimeFeedbacks = await apiClient.getRealtimeFeedbacks();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交失败");
  }
}

function openMasterDataDrawer(resource) {
  activeMasterResource.value = resource;
  Object.keys(masterDataForm).forEach((key) => delete masterDataForm[key]);
  activeMasterTab.value?.fields.forEach((field) => {
    masterDataForm[field.prop] = field.type === "number" ? null : "";
  });
  masterDataDrawerVisible.value = true;
}

async function submitMasterData() {
  const resource = activeMasterResource.value;
  const payload = { ...masterDataForm };

  Object.keys(payload).forEach((key) => {
    if (payload[key] === "") {
      delete payload[key];
    }
  });

  try {
    await apiClient.createMasterData(resource, payload);
    ElMessage.success("基础数据已新增");
    masterDataDrawerVisible.value = false;
    state.masterData[resource] = await apiClient.getMasterData(resource);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "新增失败");
  }
}

async function generateWeeklyTasks() {
  try {
    const classGroupIds = taskGenerateForm.classGroupIdsText
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean)
      .map(Number);

    await apiClient.generateWeeklyTasks({
      termId: Number(taskGenerateForm.termId),
      weekNo: Number(taskGenerateForm.weekNo),
      deadline: taskGenerateForm.deadline || null,
      classGroupIds
    });

    ElMessage.success("周反馈任务已生成");
    taskGenerateDrawerVisible.value = false;
    state.weeklyTasks = await apiClient.getWeeklyTasks();
    state.weeklyTaskCompliance = await apiClient.getWeeklyTaskCompliance();
    state.summary = await apiClient.getDashboardSummary();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "任务生成失败");
  }
}

function resetWeeklyFeedbackForm() {
  weeklyFeedbackForm.taskId = state.weeklyTasks[0]?.id || null;
  weeklyFeedbackForm.courseId = state.masterData.courses[0]?.id || null;
  weeklyFeedbackForm.teacherId = state.masterData.teachers[0]?.id || null;
  weeklyFeedbackForm.plannedTeacherName = "";
  weeklyFeedbackForm.actualTeacherName = "";
  weeklyFeedbackForm.classGroupName = "";
  weeklyFeedbackForm.weekRange = "";
  weeklyFeedbackForm.assignmentAssessment = "";
  weeklyFeedbackForm.guidanceMode = "";
  weeklyFeedbackForm.learningOutcome = "";
  weeklyFeedbackForm.contentArrangementEval = "";
  weeklyFeedbackForm.coTeacherEvaluation = "";
  weeklyFeedbackForm.issueSuggestion = "";
  weeklyFeedbackForm.hardwareIssue = "";
  weeklyFeedbackForm.remark = "";
  weeklyFeedbackForm.needReply = false;
}

async function submitWeeklyFeedback() {
  try {
    await apiClient.createWeeklyFeedback({ ...weeklyFeedbackForm });
    ElMessage.success("周反馈已提交");
    weeklyFeedbackDrawerVisible.value = false;
    resetWeeklyFeedbackForm();
    state.weeklyFeedbacks = await apiClient.getWeeklyFeedbacks();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "周反馈提交失败");
  }
}

async function exportWeeklyFeedbackTable() {
  if (state.weeklyFeedbacks.length === 0) {
    ElMessage.warning("暂无周反馈记录可导出");
    return;
  }

  const workbook = new ExcelJS.Workbook();
  workbook.creator = "教学反馈管理系统";
  workbook.created = new Date();
  const worksheet = workbook.addWorksheet("学生层面反馈");
  const headers = [
    "教师所在院系",
    "计划授课教师",
    "实际授课教师",
    "上课班级",
    "开课课程",
    "上课周次",
    "作业考核",
    "辅导方式",
    "教学效果或收获",
    "难点或存在的问题或建议",
    "硬件问题（一定写明楼层房间号，如主楼-103；东楼233，并附上详细要求或问题状况）",
    "备注"
  ];

  worksheet.addRow(headers);
  state.weeklyFeedbacks.forEach((item) => {
    worksheet.addRow([
      item.teacherDepartmentName || item.departmentName || "",
      item.plannedTeacherName || "",
      item.actualTeacherName || "",
      item.className || "",
      item.courseName || "",
      item.weekRange || "",
      item.assignmentAssessment || "",
      item.guidanceMode || "",
      item.learningOutcome || "",
      item.issueSuggestion || "",
      item.hardwareIssue || "",
      item.remark || ""
    ]);
  });

  worksheet.views = [{ state: "frozen", ySplit: 1 }];
  worksheet.autoFilter = "A1:L1";
  worksheet.columns.forEach((column, index) => {
    const widths = [18, 24, 24, 24, 28, 10, 18, 16, 34, 34, 46, 20];
    column.width = widths[index] || 18;
  });
  worksheet.getRow(1).height = 36;
  worksheet.getRow(1).font = { bold: true, color: { argb: "FFFFFFFF" } };
  worksheet.getRow(1).fill = {
    type: "pattern",
    pattern: "solid",
    fgColor: { argb: "FF0B4A7A" }
  };
  worksheet.getRow(1).alignment = { vertical: "middle", horizontal: "center", wrapText: true };
  worksheet.eachRow((row) => {
    if (row.number > 1) {
      row.height = 42;
    }
    row.eachCell((cell) => {
      cell.alignment = { vertical: "top", wrapText: true };
      cell.border = {
        top: { style: "thin" },
        left: { style: "thin" },
        bottom: { style: "thin" },
        right: { style: "thin" }
      };
    });
  });

  const buffer = await workbook.xlsx.writeBuffer();
  const blob = new Blob([buffer], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  const weekValues = [...new Set(state.weeklyFeedbacks.map((item) => item.weekRange).filter(Boolean))];
  const weekLabel = weekValues.length === 1 ? `第${weekValues[0]}周` : "周反馈";
  link.href = url;
  link.download = `${weekLabel}外教课程反馈--学生层面.xlsx`;
  link.click();
  URL.revokeObjectURL(url);
  ElMessage.success("已按老师表格模板导出");
}

async function openReplyDrawer(feedbackType, feedbackId, currentStatus = "IN_PROGRESS") {
  replyForm.feedbackType = feedbackType;
  replyForm.feedbackId = feedbackId;
  replyForm.status = currentStatus === "CLOSED" ? "CLOSED" : "IN_PROGRESS";
  replyForm.replyContent = "";
  replyRecords.value = [];
  replyDrawerVisible.value = true;
  try {
    replyRecords.value = await apiClient.getFeedbackReplies(feedbackType, feedbackId);
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : "回复记录加载失败");
  }
}

async function submitReply() {
  try {
    await apiClient.replyFeedback({ ...replyForm });
    ElMessage.success("处理意见已提交，反馈状态已更新");
    replyDrawerVisible.value = false;
    state.realtimeFeedbacks = await apiClient.getRealtimeFeedbacks();
    state.weeklyFeedbacks = await apiClient.getWeeklyFeedbacks();
    state.summary = await apiClient.getDashboardSummary();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "回复失败");
  }
}

async function updateFeedbackStatus(feedbackType, feedbackId, status) {
  try {
    await apiClient.updateFeedbackStatus({ feedbackType, feedbackId, status });
    ElMessage.success("反馈状态已更新");
    state.realtimeFeedbacks = await apiClient.getRealtimeFeedbacks();
    state.weeklyFeedbacks = await apiClient.getWeeklyFeedbacks();
    state.summary = await apiClient.getDashboardSummary();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "状态更新失败");
  }
}

function cellText(value) {
  if (value === null || value === undefined) {
    return "";
  }

  if (typeof value === "object" && "text" in value) {
    return String(value.text || "").trim();
  }

  if (typeof value === "object" && "result" in value) {
    return String(value.result || "").trim();
  }

  return String(value).trim();
}

async function importScheduleExcel(file) {
  importingSchedule.value = true;
  try {
    const workbook = new ExcelJS.Workbook();
    const buffer = await file.arrayBuffer();
    await workbook.xlsx.load(buffer);

    const worksheet = workbook.worksheets[0];
    if (!worksheet) {
      throw new Error("Excel 中没有可读取的工作表");
    }

    const headerRow = worksheet.getRow(1);
    const headers = [];
    headerRow.eachCell((cell, colNumber) => {
      headers[colNumber] = cellText(cell.value);
    });

    const rows = [];
    worksheet.eachRow((row, rowNumber) => {
      if (rowNumber === 1) {
        return;
      }

      const item = {};
      row.eachCell((cell, colNumber) => {
        const header = headers[colNumber];
        if (header) {
          item[header] = cellText(cell.value);
        }
      });

      if (Object.values(item).some(Boolean)) {
        rows.push(item);
      }
    });

    if (rows.length === 0) {
      throw new Error("没有读取到有效课表数据");
    }

    const result = await apiClient.importTeachingTasks({
      termId: taskGenerateForm.termId || 1,
      rows
    });

    ElMessage.success(
      `课表导入完成：成功 ${result.importedCount} 行，跳过 ${result.skippedCount} 行`
    );
    state.masterData.classes = await apiClient.getMasterData("classes");
    state.masterData.courses = await apiClient.getMasterData("courses");
    state.masterData.teachers = await apiClient.getMasterData("teachers");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "课表导入失败");
  } finally {
    importingSchedule.value = false;
  }
}

function handleScheduleFileChange(file) {
  if (!file?.raw) {
    return;
  }

  if (!file.name.endsWith(".xlsx")) {
    ElMessage.warning("当前安全导入仅支持 .xlsx，请先将 .xls 另存为 .xlsx");
    return;
  }

  importScheduleExcel(file.raw);
}

function statusType(status) {
  const map = {
    PENDING: "warning",
    IN_PROGRESS: "primary",
    PENDING_REPLY: "danger",
    SUBMITTED: "info",
    CLOSED: "success"
  };
  return map[status] || "info";
}

function urgencyType(level) {
  const map = {
    HIGH: "danger",
    MEDIUM: "warning",
    LOW: "info"
  };
  return map[level] || "info";
}

function complianceType(status) {
  const map = {
    OVERDUE_MISSING: "danger",
    LATE_SUBMITTED: "warning",
    PENDING: "info",
    SUBMITTED: "success"
  };
  return map[status] || "info";
}

onMounted(initializeAuth);
</script>

<template>
  <main v-if="authChecking" v-loading="true" class="login-page">
    <section class="login-card">
      <div class="login-brand">
        <div class="brand-mark">
          <img src="/images/sdust-logo.png" alt="山东科技大学校徽" />
        </div>
        <div>
          <h1>教学反馈管理系统</h1>
        </div>
      </div>
      <h2>正在检查登录状态</h2>
      <p class="login-copy">请稍候，系统正在校验当前登录信息。</p>
    </section>
  </main>

  <main v-else-if="!isAuthenticated" v-loading="loading" class="login-page">
    <section class="login-card">
      <div class="login-brand">
        <div class="brand-mark">
          <img src="/images/sdust-logo.png" alt="山东科技大学校徽" />
        </div>
        <div>
          <h1>教学反馈管理系统</h1>
        </div>
      </div>
      <h2>登录系统</h2>
      <p class="login-copy">
        学生可使用学校教务账号登录并查看本周课表；管理员继续使用系统账号进入管理端。
      </p>
      <el-tabs v-model="activeLoginTab" class="login-tabs">
        <el-tab-pane label="学校账号登录" name="academic">
          <el-form label-position="top" @keyup.enter="academicLogin">
            <el-form-item label="学号">
              <el-input v-model="academicLoginForm.account" :prefix-icon="UserFilled" placeholder="请输入学校学号" />
            </el-form-item>
            <el-form-item label="教务密码">
              <el-input
                v-model="academicLoginForm.password"
                type="password"
                show-password
                :prefix-icon="Lock"
                placeholder="仅用于本次教务认证，不保存"
              />
            </el-form-item>
            <el-form-item label="验证码">
              <div class="captcha-row">
                <el-input v-model="academicLoginForm.captchaCode" placeholder="验证码" />
                <img
                  v-if="academicLoginForm.captchaImage"
                  class="captcha-image"
                  :src="academicLoginForm.captchaImage"
                  alt="验证码"
                  @click="refreshLoginCaptcha"
                />
                <el-button :loading="loadingAcademicCaptcha" @click="refreshLoginCaptcha">
                  刷新
                </el-button>
              </div>
            </el-form-item>
            <el-button type="primary" class="full-width" size="large" @click="academicLogin">
              学校账号登录并查看课表
            </el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="管理员登录" name="admin">
          <el-form label-position="top" @keyup.enter="login">
            <el-form-item label="账号">
              <el-input v-model="loginForm.username" :prefix-icon="UserFilled" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                :prefix-icon="Lock"
              />
            </el-form-item>
            <el-button type="primary" class="full-width" size="large" @click="login">
              管理员登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <el-alert
        class="login-tip"
        title="管理员测试账号：root / admin123，cs_admin / admin123。学校账号密码只用于本次教务认证，不写入数据库。"
        type="info"
        :closable="false"
        show-icon
      />
    </section>
  </main>

  <el-container v-else class="app-shell">
    <el-aside width="284px" class="app-aside">
      <section class="brand-panel">
        <div class="brand-mark">
          <img src="/images/sdust-logo.png" alt="山东科技大学校徽" />
        </div>
        <div>
          <h1>教学反馈管理系统</h1>
        </div>
      </section>

      <el-menu
        :default-active="activeMenu"
        class="main-menu"
        background-color="transparent"
        text-color="#44515f"
        active-text-color="#0f766e"
        @select="activeMenu = $event"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.key"
          :index="item.key"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>

      <section class="aside-card">
        <p class="eyebrow">系统提示</p>
        <p>下一阶段重点：统一认证、教务只读接口、院系权限隔离、操作日志。</p>
      </section>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <div>
          <p class="eyebrow">教学质量监控与反馈管理</p>
          <h2>{{ pageTitle }}</h2>
        </div>
        <div class="header-actions">
          <el-tag effect="dark" type="success">
            后端 {{ state.health?.status || "UNKNOWN" }}
          </el-tag>
          <el-button :icon="Refresh" @click="loadAllData">刷新</el-button>
          <el-dropdown>
            <el-button type="primary" plain>
              {{ state.currentUser?.realName || "当前用户" }}
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item>
                  {{ state.currentUser?.role || "未加载角色" }}
                </el-dropdown-item>
                <el-dropdown-item>
                  {{ state.currentUser?.departmentName || "未绑定院系" }}
                </el-dropdown-item>
                <el-dropdown-item divided @click="logout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main v-loading="loading" class="app-main">
        <template v-if="activeMenu === 'dashboard'">
          <section class="hero-grid">
            <el-card class="hero-card" shadow="never">
              <p class="eyebrow">系统概览</p>
              <h3>面向山东科技大学济南校区落地的教学反馈平台</h3>
              <p>
                当前版本已经从毕设演示骨架升级为工程化管理端，后续可逐步接入统一认证、教务课表、院系管理员和质量监控流程。
              </p>
              <div class="hero-actions">
                <el-button type="primary" :icon="Upload" @click="activeMenu = 'schedule'">
                  课表接入
                </el-button>
                <el-button :icon="DocumentChecked" @click="activeMenu = 'feedback'">
                  处理反馈
                </el-button>
              </div>
            </el-card>

            <el-card class="risk-card" shadow="never">
              <template #header>
                <div class="card-header">
                  <span>高优先级待办</span>
                  <el-tag type="danger">{{ highPriorityFeedbacks.length }}</el-tag>
                </div>
              </template>
              <el-timeline>
                <el-timeline-item
                  v-for="item in highPriorityFeedbacks"
                  :key="item.id"
                  type="danger"
                  :timestamp="item.status"
                >
                  {{ item.title }}
                </el-timeline-item>
                <el-empty
                  v-if="highPriorityFeedbacks.length === 0"
                  description="暂无高优先级事项"
                />
              </el-timeline>
            </el-card>
          </section>

          <el-card
            v-if="isStudentLike"
            shadow="never"
            class="section-card timetable-card"
          >
            <template #header>
              <div class="card-header">
                <span>我的本周课表</span>
                <div class="header-actions">
                  <el-button size="small" @click="changeTimetableWeek(-1)">上一周</el-button>
                  <el-tag>
                    {{ state.personalTimetableMeta.termCode || "当前学期" }} 第{{ timetableView.weekNo || "-" }}周
                  </el-tag>
                  <el-button size="small" @click="changeTimetableWeek(1)">下一周</el-button>
                </div>
              </div>
            </template>
            <el-alert
              v-if="state.personalTimetable.length === 0"
              class="section-alert"
              title="暂无课程数据，先显示课表日历；后续刷新或重新登录成功读取课表后会自动填入课程。"
              type="info"
              :closable="false"
              show-icon
            />
            <div class="timetable-grid" :style="timetableGridStyle">
              <div class="timetable-head">节次</div>
              <div v-for="day in activeWeekDays" :key="day.index" class="timetable-head">
                <span>{{ day.label }}</span>
                <small :class="{ active: day.today }">
                  {{ day.shortDate || (day.today ? "今天" : "") }}
                </small>
              </div>
              <template v-for="serial in classSections" :key="serial">
                <div class="timetable-section">{{ sectionLabel(serial) }}</div>
                <div v-for="day in activeWeekDays" :key="`${serial}-${day.index}`" class="timetable-cell">
                  <article
                    v-if="timetableCell(day.index, serial)"
                    class="course-block"
                    :class="{ 'is-muted': !timetableCell(day.index, serial).simple.isCurWeek }"
                    :style="{ background: timetableCell(day.index, serial).simple.background }"
                    @click="openTimetableDetail(timetableCell(day.index, serial))"
                  >
                    <strong>{{ timetableCell(day.index, serial).simple.courseName }}</strong>
                    <span>{{ timetableCell(day.index, serial).simple.classroom || "教室待确认" }}</span>
                    <small>{{ timetableCell(day.index, serial).simple.teacherName || "教师待确认" }}</small>
                    <small>{{ timetableCell(day.index, serial).simple.weeksRaw }}</small>
                    <i v-if="timetableCell(day.index, serial).all.length > 1" class="course-corner" />
                  </article>
                  <span v-else class="empty-course">暂无课程</span>
                </div>
              </template>
            </div>
          </el-card>

          <el-card
            v-if="isStudentLike"
            shadow="never"
            class="section-card grade-card"
          >
            <template #header>
              <div class="card-header">
                <span>本人查成绩试验</span>
                <el-tag type="warning">仅临时查询，不保存成绩</el-tag>
              </div>
            </template>
            <el-alert
              class="section-alert"
              :title="academicSessionId ? '和山科小站一样复用学校账号登录态，只能查询当前登录学号本人成绩；正式反馈系统不保存成绩。' : '当前只有本系统登录态，教务会话已失效；查询成绩前需要重新进行学校账号认证。'"
              :type="academicSessionId ? 'warning' : 'info'"
              :closable="false"
              show-icon
            />
            <div class="grade-query">
              <el-input
                v-model="gradeQueryForm.termCode"
                placeholder="学期代码，可空表示全部，例如：2025-2026-2"
              />
              <el-button
                type="primary"
                :loading="queryingGrades"
                @click="queryMyGrades"
              >
                {{ academicSessionId ? "查询本人成绩" : "重新学校认证" }}
              </el-button>
            </div>
            <div v-if="state.gradeSummary" class="grade-summary">
              <el-tag>条数：{{ state.gradeSummary.count }}</el-tag>
              <el-tag type="success">总学分：{{ state.gradeSummary.creditTotal }}</el-tag>
              <el-tag type="info">平均绩点：{{ state.gradeSummary.averageGpa }}</el-tag>
              <el-tag type="warning">加权绩点：{{ state.gradeSummary.weightedGpa }}</el-tag>
            </div>
            <el-table
              v-if="state.gradeRecords.length > 0"
              :data="state.gradeRecords"
              border
              stripe
              class="grade-table"
            >
              <el-table-column prop="name" label="课程名称" min-width="220" />
              <el-table-column prop="grade" label="成绩" width="90" />
              <el-table-column prop="credit" label="学分" width="90" />
              <el-table-column prop="gpa" label="绩点" width="90" />
              <el-table-column prop="type" label="课程类型" width="120" />
              <el-table-column prop="makeup" label="补考" width="100" />
              <el-table-column prop="rebuild" label="重修" width="100" />
            </el-table>
            <el-empty
              v-else-if="state.gradeSummary"
              description="未查到成绩记录，可尝试清空学期代码后再次查询"
            />
          </el-card>

          <section class="stat-grid">
            <el-card
              v-for="card in statCards"
              :key="card.label"
              class="stat-card"
              shadow="never"
            >
              <div :class="['stat-icon', card.tone]">
                <el-icon><component :is="card.icon" /></el-icon>
              </div>
              <p>{{ card.label }}</p>
              <strong>{{ card.value }}</strong>
            </el-card>
          </section>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>系统模块状态</span>
                <el-tag>API /api/v1</el-tag>
              </div>
            </template>
            <el-space wrap>
              <el-tag v-for="moduleName in state.modules" :key="moduleName" round>
                {{ moduleName }}
              </el-tag>
            </el-space>
          </el-card>
        </template>

        <template v-if="activeMenu === 'baseData'">
          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>用户与角色</span>
                <el-input
                  class="table-search"
                  placeholder="搜索用户、角色、院系"
                  :prefix-icon="Search"
                />
              </div>
            </template>
            <el-table :data="state.users" stripe border>
              <el-table-column prop="id" label="ID" width="80" />
              <el-table-column prop="username" label="账号" />
              <el-table-column prop="realName" label="姓名" />
              <el-table-column prop="role" label="角色">
                <template #default="{ row }">
                  <el-tag>{{ row.role }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="departmentName" label="院系" />
            </el-table>
          </el-card>

          <section class="todo-grid">
            <el-card shadow="never">
              <template #header>建议接入方式</template>
              <el-steps direction="vertical" :active="1" finish-status="success">
                <el-step title="Excel 导入先跑通" />
                <el-step title="申请教务只读 API" />
                <el-step title="接入统一身份认证" />
              </el-steps>
            </el-card>
            <el-card shadow="never">
              <template #header>数据库接口状态</template>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="接口前缀">/api/v1/master-data</el-descriptions-item>
                <el-descriptions-item label="数据源">Spring Boot 直连 MySQL</el-descriptions-item>
                <el-descriptions-item label="用途">支撑课表导入、权限隔离、周任务生成</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </section>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>基础数据管理</span>
                <el-button
                  type="primary"
                  :icon="Plus"
                  @click="openMasterDataDrawer(activeMasterResource)"
                >
                  新增{{ activeMasterTab?.label }}
                </el-button>
              </div>
            </template>
            <el-tabs v-model="activeMasterResource">
              <el-tab-pane
                v-for="tab in masterDataTabs"
                :key="tab.key"
                :label="tab.label"
              >
                <el-alert
                  v-if="tab.key === 'courses'"
                  class="section-alert"
                  title="教务课表同步成功后，解析到的课程会出现在这里。"
                  type="success"
                  :closable="false"
                  show-icon
                />
                <el-table :data="state.masterData[tab.key]" border stripe>
                  <el-table-column prop="id" label="ID" width="80" />
                  <el-table-column
                    v-for="column in Object.keys(state.masterData[tab.key][0] || {}).filter((key) => key !== 'id')"
                    :key="column"
                    :prop="column"
                    :label="column"
                    min-width="130"
                    show-overflow-tooltip
                  />
                </el-table>
              </el-tab-pane>
            </el-tabs>
          </el-card>
        </template>

        <template v-if="activeMenu === 'schedule'">
          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>周反馈任务</span>
                <div v-if="canManageFeedback" class="header-actions">
                  <el-button :icon="Plus" @click="taskGenerateDrawerVisible = true">
                    生成周任务
                  </el-button>
                  <el-upload
                    :auto-upload="false"
                    :show-file-list="false"
                    accept=".xlsx"
                    :on-change="handleScheduleFileChange"
                  >
                    <el-button
                      type="primary"
                      :icon="Upload"
                      :loading="importingSchedule"
                    >
                      导入课表 .xlsx
                    </el-button>
                  </el-upload>
                </div>
              </div>
            </template>
            <el-alert
              v-if="canManageFeedback"
              class="section-alert"
              title="这里导入的是教务课表或教学任务数据，用于生成周反馈任务；老师给的学生层面反馈表是最终汇总导出格式，不作为课表导入模板。"
              type="info"
              :closable="false"
              show-icon
            />
            <el-alert
              v-else
              class="section-alert"
              title="学生个人课表已在登录后自动展示；这里仅显示你所在班级的周反馈任务。"
              type="info"
              :closable="false"
              show-icon
            />
            <el-table :data="state.weeklyTasks" border stripe>
              <el-table-column prop="weekNo" label="周次" width="90" />
              <el-table-column prop="className" label="班级" />
              <el-table-column prop="taskName" label="任务名称" />
              <el-table-column prop="deadline" label="截止时间" />
              <el-table-column prop="status" label="状态">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.status)">
                    {{ row.status }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>学委履职标记</span>
                <el-tag type="warning">
                  {{ state.summary.overdueUnsubmittedTasks || 0 }} 个逾期未提交
                </el-tag>
              </div>
            </template>
            <el-alert
              class="section-alert"
              title="系统根据周反馈任务、截止时间、学委提交记录自动标记：未提交、逾期未提交、迟交、已提交，并同步计算反馈字数用于质量评价。"
              type="warning"
              :closable="false"
              show-icon
            />
            <el-table :data="state.weeklyTaskCompliance" border stripe>
              <el-table-column prop="weekNo" label="周次" width="80" />
              <el-table-column prop="departmentName" label="院系" min-width="150" />
              <el-table-column prop="className" label="班级" min-width="160" />
              <el-table-column prop="monitorName" label="学委" width="140">
                <template #default="{ row }">
                  {{ row.monitorName || "未绑定学委" }}
                </template>
              </el-table-column>
              <el-table-column prop="deadline" label="截止时间" min-width="170" />
              <el-table-column prop="submittedAt" label="提交时间" min-width="170">
                <template #default="{ row }">
                  {{ row.submittedAt || "未提交" }}
                </template>
              </el-table-column>
              <el-table-column prop="complianceStatus" label="履职状态" width="150">
                <template #default="{ row }">
                  <el-tag :type="complianceType(row.complianceStatus)">
                    {{ row.complianceStatus }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="feedbackWordCount" label="反馈字数" width="110" />
              <el-table-column prop="qualityRemark" label="质量提示" min-width="220" />
            </el-table>
          </el-card>
        </template>

        <template v-if="activeMenu === 'feedback'">
          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>实时反馈处理</span>
                <el-button type="primary" :icon="DocumentChecked" @click="submitDrawerVisible = true">
                  新增实时反馈
                </el-button>
              </div>
            </template>
            <el-table :data="state.realtimeFeedbacks" border stripe>
              <el-table-column prop="id" label="编号" width="90" />
              <el-table-column prop="type" label="类型" width="120" />
              <el-table-column prop="title" label="标题" min-width="180" />
              <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
              <el-table-column prop="urgencyLevel" label="紧急程度" width="120">
                <template #default="{ row }">
                  <el-tag :type="urgencyType(row.urgencyLevel)">
                    {{ row.urgencyLevel }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="140">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.status)">
                    {{ row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="latestReplyContent" label="最新处理意见" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.latestReplyContent || "暂无处理意见" }}
                </template>
              </el-table-column>
              <el-table-column prop="latestReplyAt" label="最近处理时间" width="180">
                <template #default="{ row }">
                  {{ row.latestReplyAt || "-" }}
                </template>
              </el-table-column>
              <el-table-column label="回复需求" width="120">
                <template #default="{ row }">
                  <el-tag :type="row.needReply ? 'danger' : 'info'">
                    {{ row.needReply ? "需要回复" : "无需回复" }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="敏感标记" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.flagCount > 0 ? 'danger' : 'info'">
                    {{ row.flagCount || 0 }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="210" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canManageFeedback"
                    size="small"
                    type="primary"
                    plain
                    @click="openReplyDrawer('REALTIME', row.id, row.status)"
                  >
                    处理
                  </el-button>
                  <el-button
                    v-else
                    size="small"
                    type="primary"
                    plain
                    @click="openReplyDrawer('REALTIME', row.id, row.status)"
                  >
                    查看记录
                  </el-button>
                  <el-button
                    v-if="canManageFeedback && row.status !== 'IN_PROGRESS' && row.status !== 'CLOSED'"
                    size="small"
                    type="warning"
                    plain
                    @click="updateFeedbackStatus('REALTIME', row.id, 'IN_PROGRESS')"
                  >
                    受理
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>敏感信息强标</span>
                <el-tag type="danger">{{ state.feedbackFlags.length }}</el-tag>
              </div>
            </template>
            <el-table :data="state.feedbackFlags" border stripe>
              <el-table-column prop="feedbackType" label="反馈类型" width="120" />
              <el-table-column prop="feedbackId" label="反馈编号" width="100" />
              <el-table-column prop="flagType" label="标记类型" width="140" />
              <el-table-column prop="flagValue" label="命中内容" min-width="180" />
              <el-table-column prop="createdAt" label="标记时间" width="180" />
            </el-table>
          </el-card>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="card-header">
                <span>周反馈记录</span>
                <el-button
                  type="primary"
                  :icon="Plus"
                  @click="resetWeeklyFeedbackForm(); weeklyFeedbackDrawerVisible = true"
                >
                  学委提交周反馈
                </el-button>
                <el-button :icon="Download" @click="exportWeeklyFeedbackTable">
                  导出学生层面反馈表
                </el-button>
              </div>
            </template>
            <el-alert
              class="section-alert"
              title="此处导出字段严格对应老师表格：教师所在院系、计划授课教师、实际授课教师、上课班级、开课课程、上课周次、作业考核、辅导方式、教学效果或收获、问题建议、硬件问题、备注。"
              type="success"
              :closable="false"
              show-icon
            />
            <el-table :data="state.weeklyFeedbacks" border stripe>
              <el-table-column prop="teacherDepartmentName" label="教师所在院系" min-width="150" />
              <el-table-column prop="plannedTeacherName" label="计划授课教师" min-width="160" />
              <el-table-column prop="className" label="班级" />
              <el-table-column prop="courseName" label="课程" />
              <el-table-column prop="actualTeacherName" label="授课教师" min-width="160" />
              <el-table-column prop="weekRange" label="周次" width="90" />
              <el-table-column prop="assignmentAssessment" label="作业考核" min-width="130" />
              <el-table-column prop="guidanceMode" label="辅导方式" />
              <el-table-column prop="learningOutcome" label="教学效果或收获" min-width="180" show-overflow-tooltip />
              <el-table-column prop="issueSuggestion" label="问题建议" min-width="180" />
              <el-table-column prop="hardwareIssue" label="硬件问题" min-width="180" />
              <el-table-column prop="remark" label="备注" min-width="140" />
              <el-table-column prop="status" label="状态" width="130">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.status)">
                    {{ row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="latestReplyContent" label="最新处理意见" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.latestReplyContent || "暂无处理意见" }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canManageFeedback"
                    size="small"
                    type="primary"
                    plain
                    @click="openReplyDrawer('WEEKLY', row.id, row.status)"
                  >
                    处理
                  </el-button>
                  <el-button
                    v-else
                    size="small"
                    type="primary"
                    plain
                    @click="openReplyDrawer('WEEKLY', row.id, row.status)"
                  >
                    查看记录
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </template>

        <template v-if="activeMenu === 'analytics'">
          <section class="todo-grid">
            <el-card shadow="never">
              <template #header>统计分析规划</template>
              <el-result
                icon="success"
                title="统计模块待接真实数据库"
                sub-title="后续按院系、班级、课程、教师、周次、敏感词维度聚合。"
              />
            </el-card>
            <el-card shadow="never">
              <template #header>AI 总评规划</template>
              <el-alert
                title="落地时必须支持开关和脱敏"
                type="warning"
                :closable="false"
                show-icon
              />
              <el-divider />
              <el-descriptions :column="1" border>
                <el-descriptions-item label="输入">多班级文本反馈</el-descriptions-item>
                <el-descriptions-item label="输出">课程总评、风险摘要、处理建议</el-descriptions-item>
                <el-descriptions-item label="安全">敏感信息脱敏后再调用模型</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </section>
        </template>

        <template v-if="activeMenu === 'governance'">
          <el-card shadow="never" class="section-card">
            <template #header>学校落地对接清单</template>
            <el-timeline>
              <el-timeline-item type="primary" timestamp="业务">
                明确业务负责人：教务处、学院教学秘书、教学质量监控部门。
              </el-timeline-item>
              <el-timeline-item type="success" timestamp="数据">
                申请教务系统只读接口或定期 Excel 数据交换。
              </el-timeline-item>
              <el-timeline-item type="warning" timestamp="认证">
                接入统一身份认证，避免学生和教师重复注册。
              </el-timeline-item>
              <el-timeline-item type="danger" timestamp="安全">
                落实权限隔离、日志审计、数据脱敏、导出审批。
              </el-timeline-item>
            </el-timeline>
          </el-card>
        </template>
      </el-main>
    </el-container>

    <el-drawer v-model="submitDrawerVisible" title="新增实时反馈" size="420px">
      <el-form label-position="top">
        <el-form-item label="反馈类型">
          <el-select v-model="realtimeForm.type" class="full-width">
            <el-option label="硬件问题" value="HARDWARE" />
            <el-option label="教学问题" value="TEACHING" />
            <el-option label="课堂纪律" value="DISCIPLINE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="紧急程度">
          <el-segmented
            v-model="realtimeForm.urgencyLevel"
            :options="['LOW', 'MEDIUM', 'HIGH']"
          />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="realtimeForm.title" placeholder="例如：1-302 教室设备故障" />
        </el-form-item>
        <el-form-item label="地点">
          <el-input v-model="realtimeForm.locationText" placeholder="例如：主楼 1-302" />
        </el-form-item>
        <el-form-item label="详细内容">
          <el-input
            v-model="realtimeForm.content"
            type="textarea"
            :rows="5"
            placeholder="请描述发生了什么、影响范围、是否需要回复。"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="realtimeForm.needReply">需要管理员回复</el-checkbox>
        </el-form-item>
        <el-button type="primary" class="full-width" @click="submitRealtimeFeedback">
          提交
        </el-button>
      </el-form>
    </el-drawer>

    <el-drawer
      v-model="masterDataDrawerVisible"
      :title="`新增${activeMasterTab?.label || '基础数据'}`"
      size="420px"
    >
      <el-form label-position="top">
        <el-form-item
          v-for="field in activeMasterTab?.fields || []"
          :key="field.prop"
          :label="field.label"
        >
          <el-date-picker
            v-if="field.type === 'date'"
            v-model="masterDataForm[field.prop]"
            value-format="YYYY-MM-DD"
            class="full-width"
            placeholder="选择日期"
          />
          <el-input-number
            v-else-if="field.type === 'number'"
            v-model="masterDataForm[field.prop]"
            class="full-width"
            :min="1"
          />
          <el-input
            v-else
            v-model="masterDataForm[field.prop]"
            :placeholder="field.placeholder || ''"
          />
        </el-form-item>
        <el-button type="primary" class="full-width" @click="submitMasterData">
          保存
        </el-button>
      </el-form>
    </el-drawer>

    <el-dialog v-model="timetableDetailVisible" title="课程详情" width="420px">
      <section class="course-detail-list">
        <article
          v-for="course in timetableDetailCourses"
          :key="`${course.courseName}-${course.teacherName}-${course.classroom}`"
          class="course-detail-item"
        >
          <strong>{{ course.courseName }}</strong>
          <p>教室：{{ course.classroom || "教室待确认" }}</p>
          <p>教师：{{ course.teacherName || "教师待确认" }}</p>
          <p>周次：{{ course.weeksRaw || course.weekRange || "当前周" }}</p>
        </article>
      </section>
    </el-dialog>

    <el-drawer
      v-model="taskGenerateDrawerVisible"
      title="生成周反馈任务"
      size="420px"
    >
      <el-form label-position="top">
        <el-form-item label="学期 ID">
          <el-input-number v-model="taskGenerateForm.termId" class="full-width" :min="1" />
        </el-form-item>
        <el-form-item label="周次">
          <el-input-number v-model="taskGenerateForm.weekNo" class="full-width" :min="1" />
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker
            v-model="taskGenerateForm.deadline"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            class="full-width"
            placeholder="选择截止时间"
          />
        </el-form-item>
        <el-form-item label="班级 ID，逗号分隔，留空代表全部班级">
          <el-input
            v-model="taskGenerateForm.classGroupIdsText"
            placeholder="例如：1,2,3"
          />
        </el-form-item>
        <el-alert
          title="当前生成的是周反馈任务，后续会接课表导入后自动匹配本周课程。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-divider />
        <el-button type="primary" class="full-width" @click="generateWeeklyTasks">
          生成任务
        </el-button>
      </el-form>
    </el-drawer>

    <el-drawer
      v-model="weeklyFeedbackDrawerVisible"
      title="学委提交周反馈"
      size="520px"
    >
      <el-form label-position="top">
        <el-form-item label="周反馈任务">
          <el-select v-model="weeklyFeedbackForm.taskId" class="full-width" filterable>
            <el-option
              v-for="task in state.weeklyTasks"
              :key="task.id"
              :label="`${task.taskName} / ${task.className}`"
              :value="task.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="课程">
          <el-select v-model="weeklyFeedbackForm.courseId" class="full-width" filterable>
            <el-option
              v-for="course in state.masterData.courses"
              :key="course.id"
              :label="course.courseName || course.name"
              :value="course.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="教师">
          <el-select v-model="weeklyFeedbackForm.teacherId" class="full-width" filterable clearable>
            <el-option
              v-for="teacher in state.masterData.teachers"
              :key="teacher.id"
              :label="teacher.teacherName"
              :value="teacher.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="计划授课教师">
          <el-input v-model="weeklyFeedbackForm.plannedTeacherName" />
        </el-form-item>
        <el-form-item label="实际授课教师">
          <el-input v-model="weeklyFeedbackForm.actualTeacherName" />
        </el-form-item>
        <el-form-item label="班级">
          <el-input v-model="weeklyFeedbackForm.classGroupName" />
        </el-form-item>
        <el-form-item label="上课周次">
          <el-input v-model="weeklyFeedbackForm.weekRange" placeholder="例如：13 或 2-4,8,10,13" />
        </el-form-item>
        <el-form-item label="作业考核">
          <el-input v-model="weeklyFeedbackForm.assignmentAssessment" />
        </el-form-item>
        <el-form-item label="辅导方式">
          <el-input v-model="weeklyFeedbackForm.guidanceMode" />
        </el-form-item>
        <el-form-item label="教学效果或收获">
          <el-input
            v-model="weeklyFeedbackForm.learningOutcome"
            type="textarea"
            :rows="4"
            placeholder="请填写本周课程教学效果、学生收获或整体情况"
          />
        </el-form-item>
        <el-form-item label="外教教学内容安排是否合理">
          <el-input v-model="weeklyFeedbackForm.contentArrangementEval" />
        </el-form-item>
        <el-form-item label="共课教师评价">
          <el-input
            v-model="weeklyFeedbackForm.coTeacherEvaluation"
            type="textarea"
            :rows="3"
          />
        </el-form-item>
        <el-form-item label="难点或存在的问题或建议">
          <el-input
            v-model="weeklyFeedbackForm.issueSuggestion"
            type="textarea"
            :rows="3"
          />
        </el-form-item>
        <el-form-item label="硬件问题">
          <el-input
            v-model="weeklyFeedbackForm.hardwareIssue"
            type="textarea"
            :rows="3"
            placeholder="如有硬件问题，请写明楼层房间号和具体情况"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="weeklyFeedbackForm.remark"
            type="textarea"
            :rows="2"
            placeholder="填写其他补充说明，可留空"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="weeklyFeedbackForm.needReply">需要管理员回复</el-checkbox>
        </el-form-item>
        <el-button type="primary" class="full-width" @click="submitWeeklyFeedback">
          提交周反馈
        </el-button>
      </el-form>
    </el-drawer>

    <el-drawer v-model="replyDrawerVisible" title="反馈处理" size="460px">
      <el-form label-position="top">
        <el-form-item label="反馈类型">
          <el-input v-model="replyForm.feedbackType" disabled />
        </el-form-item>
        <el-form-item label="反馈编号">
          <el-input-number v-model="replyForm.feedbackId" class="full-width" disabled />
        </el-form-item>
        <el-form-item v-if="canManageFeedback" label="处理状态">
          <el-select v-model="replyForm.status" class="full-width">
            <el-option label="已受理 / 处理中" value="IN_PROGRESS" />
            <el-option label="已回复" value="REPLIED" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="canManageFeedback" label="回复内容">
          <el-input
            v-model="replyForm.replyContent"
            type="textarea"
            :rows="6"
            placeholder="请输入处理意见、协调结果或给学生的回复"
          />
        </el-form-item>
        <el-alert
          v-else
          title="当前为学生视角，只能查看管理员处理记录，不能修改处理状态。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-card shadow="never" class="reply-history-card">
          <template #header>历史处理记录</template>
          <el-empty v-if="replyRecords.length === 0" description="暂无回复记录" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="item in replyRecords"
              :key="item.id"
              :timestamp="item.createdAt"
            >
              <strong>{{ item.replierName || "管理员" }}</strong>
              <p>{{ item.replyContent }}</p>
            </el-timeline-item>
          </el-timeline>
        </el-card>
        <el-alert
          v-if="canManageFeedback"
          title="处理意见会留痕，状态可从待回复流转为处理中、已回复或已关闭，方便形成教学反馈闭环。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-divider v-if="canManageFeedback" />
        <el-button v-if="canManageFeedback" type="primary" class="full-width" @click="submitReply">
          提交处理意见
        </el-button>
      </el-form>
    </el-drawer>
  </el-container>
</template>
