import { api } from "../api-client";

// ==================== Types ====================

export type TriggerType = "CRON" | "HEARTBEAT";

export type FireStatus =
  | "PENDING"
  | "CLAIMED"
  | "EXECUTING"
  | "COMPLETED"
  | "FAILED"
  | "DEAD_LETTERED";

export interface ScheduleConfiguration {
  id?: string;
  name: string;

  // Type
  triggerType: TriggerType;

  // Target
  agentId: string;
  agentVersion: number; // 0 = latest deployed
  environment: string;
  tenantId?: string;

  // Timing
  cronExpression?: string;
  heartbeatIntervalSeconds?: number;
  oneTimeAt?: string;
  timeZone?: string;

  // Trigger
  message: string;
  userId?: string;
  conversationStrategy?: "new" | "persistent";
  persistentConversationId?: string;

  // State (read-only from server)
  enabled: boolean;
  nextFire?: number;
  lastFired?: number;
  fireStatus: FireStatus;
  claimedBy?: string;
  claimedAt?: number;
  fireId?: string;
  failCount: number;
  nextRetryAt?: number;

  // Security
  maxCostPerFire?: number;
  allowSelfScheduling?: boolean;
  createdBy?: string;

  // Metadata
  metadata?: Record<string, unknown>;
  createdAt?: number;
  updatedAt?: number;

  // Computed
  cronDescription?: string;
}

/** Terminal outcome of a fire, mirroring the backend ScheduleFireLog.status. */
export type FireLogStatus = "COMPLETED" | "FAILED" | "DEAD_LETTERED";

/**
 * One fire-execution record. JSON keys are the backend record component names.
 * The Instant timestamps (fireTime/startedAt/completedAt) are serialized by
 * Quarkus as FRACTIONAL EPOCH SECONDS (write-dates-as-timestamps), e.g.
 * 1719964800.123 — parse them with {@link parseInstant}, never `new Date(x)`.
 */
export interface ScheduleFireLog {
  id?: string;
  scheduleId: string;
  fireId?: string;
  fireTime: string | number;
  startedAt?: string | number;
  completedAt?: string | number;
  status: FireLogStatus;
  instanceId?: string;
  conversationId?: string;
  errorMessage?: string;
  attemptNumber?: number;
  cost?: number;
}

/**
 * Parse a backend timestamp into a Date. EDDI runs Quarkus with
 * `quarkus.jackson.write-dates-as-timestamps=true`, so a `java.time.Instant`
 * serializes as FRACTIONAL EPOCH SECONDS (e.g. 1719964800.123), NOT epoch millis
 * and NOT (by default) an ISO string. This also tolerates an ISO string (should a
 * field gain @JsonFormat) and an epoch-millis number, so the UI renders correctly
 * regardless of the backend's date format. Returns null for missing/unparseable.
 */
export function parseInstant(
  value: string | number | null | undefined
): Date | null {
  if (value == null || value === "") return null;
  if (typeof value === "number") {
    // Distinguish epoch seconds (~1.7e9) from already-millis (~1.7e12).
    const ms = value < 1e12 ? value * 1000 : value;
    const d = new Date(ms);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const s = value.trim();
  if (/^\d+(\.\d+)?$/.test(s)) return parseInstant(Number(s));
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Duration in ms between startedAt and completedAt, or null if unavailable. */
export function fireLogDurationMs(log: ScheduleFireLog): number | null {
  const start = parseInstant(log.startedAt);
  const end = parseInstant(log.completedAt);
  if (!start || !end) return null;
  return Math.max(0, end.getTime() - start.getTime());
}

// ==================== Scheduling constants & helpers ====================

/**
 * Server default when a schedule omits a timezone
 * (backend config `eddi.schedule.default-timezone`, defaults to "UTC").
 */
export const DEFAULT_TIME_ZONE = "UTC";

/**
 * Backend min-interval floor in seconds
 * (backend config `eddi.schedule.min-interval-seconds`, defaults to 60).
 * The backend rejects heartbeats below this and any cron whose smallest gap
 * between consecutive fires is below it.
 */
export const MIN_INTERVAL_SECONDS = 60;

/** Sentinel for an unlimited per-fire cost cap (backend default). */
export const UNLIMITED_COST = -1;

export interface CronPreset {
  expression: string;
  /** i18n key suffix under `schedules.cronPreset.*`. */
  key: string;
  /** English fallback label. */
  label: string;
}

/** Common cron presets offered in the create/edit form. */
export const CRON_PRESETS: CronPreset[] = [
  { expression: "*/15 * * * *", key: "every15Minutes", label: "Every 15 minutes" },
  { expression: "0 * * * *", key: "hourly", label: "Hourly (on the hour)" },
  { expression: "0 9 * * *", key: "dailyMorning", label: "Daily at 09:00" },
  { expression: "0 9 * * MON-FRI", key: "weekdays", label: "Weekdays at 09:00" },
  { expression: "0 0 * * MON", key: "weeklyMonday", label: "Weekly (Mon 00:00)" },
  { expression: "0 0 1 * *", key: "monthly", label: "Monthly (1st, 00:00)" },
];

const MONTH_TOKENS = [
  "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
  "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
];
const DOW_TOKENS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];
const DOW_NAMES = [
  "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
];

interface ParsedCron {
  minutes: Set<number>;
  hours: Set<number>;
  doms: Set<number>;
  months: Set<number>;
  dows: Set<number>;
  domRestricted: boolean;
  dowRestricted: boolean;
}

function tokenToNum(
  token: string,
  names: string[] | null,
  min: number
): number | null {
  const t = token.trim();
  if (/^\d+$/.test(t)) return parseInt(t, 10);
  if (names) {
    const idx = names.indexOf(t.toUpperCase());
    if (idx !== -1) return idx + min;
  }
  return null;
}

/** Parse a single 5-field cron field into the set of allowed values. */
function parseField(
  field: string,
  min: number,
  max: number,
  names: string[] | null
): Set<number> | null {
  const out = new Set<number>();
  for (const raw of field.split(",")) {
    const part = raw.trim();
    if (part === "") return null;
    let step = 1;
    let rangePart = part;
    const slash = part.indexOf("/");
    if (slash !== -1) {
      const stepStr = part.slice(slash + 1);
      if (!/^\d+$/.test(stepStr)) return null;
      step = parseInt(stepStr, 10);
      if (step <= 0) return null;
      rangePart = part.slice(0, slash);
    }
    let lo: number | null;
    let hi: number | null;
    if (rangePart === "*") {
      lo = min;
      hi = max;
    } else {
      const dash = rangePart.indexOf("-");
      if (dash > 0) {
        lo = tokenToNum(rangePart.slice(0, dash), names, min);
        hi = tokenToNum(rangePart.slice(dash + 1), names, min);
      } else {
        lo = tokenToNum(rangePart, names, min);
        // "N/step" (single value with a step) means N..max.
        hi = slash !== -1 ? max : lo;
      }
    }
    if (lo == null || hi == null) return null;
    if (lo < min || hi > max || lo > hi) return null;
    for (let v = lo; v <= hi; v += step) out.add(v);
  }
  return out;
}

/**
 * Parse a standard 5-field cron expression (minute hour day-of-month month
 * day-of-week), supporting `*`, lists, ranges, steps and named months/days.
 * Returns null for anything invalid. Mirrors the fields the backend accepts.
 */
export function parseCron(expression: string): ParsedCron | null {
  if (!expression) return null;
  const parts = expression.trim().split(/\s+/);
  if (parts.length !== 5) return null;
  const minutes = parseField(parts[0]!, 0, 59, null);
  const hours = parseField(parts[1]!, 0, 23, null);
  const doms = parseField(parts[2]!, 1, 31, null);
  const months = parseField(parts[3]!, 1, 12, MONTH_TOKENS);
  const dows = parseField(parts[4]!, 0, 7, DOW_TOKENS);
  if (!minutes || !hours || !doms || !months || !dows) return null;
  if (dows.has(7)) {
    dows.delete(7);
    dows.add(0); // both 0 and 7 mean Sunday
  }
  return {
    minutes,
    hours,
    doms,
    months,
    dows,
    domRestricted: parts[2] !== "*",
    dowRestricted: parts[4] !== "*",
  };
}

/** True when a cron expression is syntactically valid. */
export function isValidCron(expression: string): boolean {
  return parseCron(expression) != null;
}

/** Wall-clock parts of an instant in a given IANA timezone. */
function tzWallParts(date: Date, timeZone: string) {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  const map: Record<string, string> = {};
  for (const p of dtf.formatToParts(date)) {
    if (p.type !== "literal") map[p.type] = p.value;
  }
  let hour = parseInt(map.hour!, 10);
  if (hour === 24) hour = 0; // some engines emit 24 for midnight
  return {
    year: +map.year!,
    month: +map.month!,
    day: +map.day!,
    hour,
    minute: +map.minute!,
    second: +map.second!,
  };
}

/** Convert a wall-clock time in `timeZone` back to an absolute instant. */
function zonedWallToInstant(
  y: number,
  mo: number,
  d: number,
  h: number,
  mi: number,
  timeZone: string
): Date {
  const asIfUtc = Date.UTC(y, mo - 1, d, h, mi, 0);
  const wp = tzWallParts(new Date(asIfUtc), timeZone);
  const wallOfGuess = Date.UTC(
    wp.year,
    wp.month - 1,
    wp.day,
    wp.hour,
    wp.minute,
    wp.second
  );
  const offset = wallOfGuess - asIfUtc;
  return new Date(asIfUtc - offset);
}

function dayMatches(p: ParsedCron, dom: number, dow: number): boolean {
  if (p.domRestricted && p.dowRestricted) {
    return p.doms.has(dom) || p.dows.has(dow); // Vixie cron OR semantics
  }
  if (p.domRestricted) return p.doms.has(dom);
  if (p.dowRestricted) return p.dows.has(dow);
  return true;
}

/**
 * Compute the next `count` fire instants for a cron expression, evaluated
 * against wall-clock time in `timeZone`. Client-side preview only — the
 * backend remains authoritative.
 */
export function nextCronFires(
  expression: string,
  count: number,
  from: Date = new Date(),
  timeZone: string = DEFAULT_TIME_ZONE
): Date[] {
  const parsed = parseCron(expression);
  if (!parsed || count <= 0) return [];
  let zone = timeZone || DEFAULT_TIME_ZONE;
  try {
    Intl.DateTimeFormat("en-US", { timeZone: zone });
  } catch {
    zone = "UTC";
  }
  const start = tzWallParts(from, zone);
  let y = start.year;
  let mo = start.month;
  let d = start.day;
  const hoursArr = [...parsed.hours].sort((a, b) => a - b);
  const minsArr = [...parsed.minutes].sort((a, b) => a - b);
  const res: Date[] = [];
  const fromMs = from.getTime();
  for (let day = 0; day < 1500 && res.length < count; day++) {
    const cal = new Date(Date.UTC(y, mo - 1, d));
    const yy = cal.getUTCFullYear();
    const mm = cal.getUTCMonth() + 1;
    const dd = cal.getUTCDate();
    const dow = cal.getUTCDay();
    if (parsed.months.has(mm) && dayMatches(parsed, dd, dow)) {
      for (const h of hoursArr) {
        for (const mi of minsArr) {
          const inst = zonedWallToInstant(yy, mm, dd, h, mi, zone);
          if (inst.getTime() > fromMs) {
            res.push(inst);
            if (res.length >= count) break;
          }
        }
        if (res.length >= count) break;
      }
    }
    const nxt = new Date(Date.UTC(yy, mm - 1, dd + 1));
    y = nxt.getUTCFullYear();
    mo = nxt.getUTCMonth() + 1;
    d = nxt.getUTCDate();
  }
  return res;
}

/**
 * The smallest gap (in seconds) between consecutive fires of a cron
 * expression, or null when it cannot be determined. Used to enforce the
 * {@link MIN_INTERVAL_SECONDS} floor client-side, mirroring the backend.
 */
export function cronMinIntervalSeconds(
  expression: string,
  timeZone: string = DEFAULT_TIME_ZONE
): number | null {
  const fires = nextCronFires(expression, 5, new Date(), timeZone);
  if (fires.length < 2) return null;
  let min = Infinity;
  for (let i = 1; i < fires.length; i++) {
    min = Math.min(min, (fires[i]!.getTime() - fires[i - 1]!.getTime()) / 1000);
  }
  return Number.isFinite(min) ? Math.round(min) : null;
}

/** A short, human-readable description of a cron expression (live preview). */
export function describeCron(expression: string): string | null {
  const parsed = parseCron(expression);
  if (!parsed) return null;
  const fields = expression.trim().split(/\s+/);
  const [minF, hourF, domF, monF, dowF] = fields as [
    string,
    string,
    string,
    string,
    string,
  ];
  const p2 = (n: number) => String(n).padStart(2, "0");
  const mins = [...parsed.minutes].sort((a, b) => a - b);
  const hrs = [...parsed.hours].sort((a, b) => a - b);
  const stepMin = /^\*\/(\d+)$/.exec(minF);
  const stepHour = /^\*\/(\d+)$/.exec(hourF);

  let time: string;
  if (minF === "*" && hourF === "*") {
    time = "Every minute";
  } else if (stepMin && hourF === "*") {
    time = `Every ${stepMin[1]} minutes`;
  } else if (mins.length === 1 && hourF === "*") {
    time = `Hourly at :${p2(mins[0]!)}`;
  } else if (stepHour && mins.length === 1) {
    time = `Every ${stepHour[1]} hours at :${p2(mins[0]!)}`;
  } else if (mins.length === 1 && hrs.length === 1) {
    time = `At ${p2(hrs[0]!)}:${p2(mins[0]!)}`;
  } else if (mins.length === 1 && hrs.length <= 4) {
    time = `At ${hrs.map((h) => `${p2(h)}:${p2(mins[0]!)}`).join(", ")}`;
  } else {
    time = `At minute ${mins.join(",")} past ${
      hourF === "*" ? "every hour" : `hour ${hrs.join(",")}`
    }`;
  }

  const quals: string[] = [];
  if (dowF !== "*") {
    quals.push(
      `on ${[...parsed.dows]
        .sort((a, b) => a - b)
        .map((d) => DOW_NAMES[d])
        .join(", ")}`
    );
  }
  if (domF !== "*") {
    quals.push(
      `on day-of-month ${[...parsed.doms].sort((a, b) => a - b).join(", ")}`
    );
  }
  if (monF !== "*") {
    quals.push(
      `in ${[...parsed.months]
        .sort((a, b) => a - b)
        .map((m) => MONTH_NAMES[m - 1])
        .join(", ")}`
    );
  }
  return [time, ...quals].join(" ");
}

/**
 * Convert an ISO-8601 instant into a value suitable for a
 * `<input type="datetime-local">` (local wall-clock, minute precision).
 */
export function isoToLocalInput(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(
    d.getHours()
  )}:${p(d.getMinutes())}`;
}

/**
 * Convert a `datetime-local` value (interpreted in the browser's local zone)
 * into an ISO-8601 instant the backend can `Instant.parse`, or null if empty
 * or invalid.
 */
export function localInputToIso(local: string): string | null {
  if (!local) return null;
  const d = new Date(local);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString();
}

const COMMON_TIME_ZONES = [
  "UTC",
  "Europe/London",
  "Europe/Vienna",
  "Europe/Berlin",
  "Europe/Paris",
  "America/New_York",
  "America/Chicago",
  "America/Denver",
  "America/Los_Angeles",
  "Asia/Kolkata",
  "Asia/Shanghai",
  "Asia/Tokyo",
  "Australia/Sydney",
];

/** Full list of IANA timezones (or a common subset if unavailable). */
export function listTimeZones(): string[] {
  const svo = (
    Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
  ).supportedValuesOf;
  let all: string[] | null = null;
  try {
    all = typeof svo === "function" ? svo("timeZone") : null;
  } catch {
    all = null;
  }
  if (all && all.length > 0) {
    return all.includes("UTC") ? all : ["UTC", ...all];
  }
  return COMMON_TIME_ZONES;
}

// ==================== API Functions ====================

const BASE = "/schedulestore/schedules";

export async function getSchedules(
  agentId?: string
): Promise<ScheduleConfiguration[]> {
  const query = agentId ? `?agentId=${encodeURIComponent(agentId)}` : "";
  return api.get<ScheduleConfiguration[]>(`${BASE}${query}`);
}

export async function getSchedule(
  scheduleId: string
): Promise<ScheduleConfiguration> {
  return api.get<ScheduleConfiguration>(`${BASE}/${scheduleId}`);
}

export async function createSchedule(
  config: Partial<ScheduleConfiguration>
): Promise<{ location: string }> {
  return api.post<{ location: string }>(BASE, config);
}

export async function updateSchedule(
  scheduleId: string,
  config: Partial<ScheduleConfiguration>
): Promise<void> {
  return api.put(`${BASE}/${scheduleId}`, config);
}

export async function deleteSchedule(scheduleId: string): Promise<void> {
  return api.delete(`${BASE}/${scheduleId}`);
}

export async function enableSchedule(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/enable`);
}

export async function disableSchedule(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/disable`);
}

/**
 * Manually trigger a fire. The backend responds `200 OK` with the resulting
 * {@link ScheduleFireLog}, letting the caller show the real outcome. May
 * resolve to `undefined` if the server returns an empty body.
 */
export async function fireNow(
  scheduleId: string
): Promise<ScheduleFireLog | undefined> {
  return api.post<ScheduleFireLog | undefined>(`${BASE}/${scheduleId}/fire`);
}

export async function retryDeadLetter(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/retry`);
}

export async function dismissDeadLetter(scheduleId: string): Promise<void> {
  return api.post(`${BASE}/${scheduleId}/dismiss`);
}

export async function getFireLogs(
  scheduleId: string,
  limit = 20
): Promise<ScheduleFireLog[]> {
  return api.get<ScheduleFireLog[]>(
    `${BASE}/${scheduleId}/fires?limit=${limit}`
  );
}

export async function getFailedFires(limit = 50): Promise<ScheduleFireLog[]> {
  return api.get<ScheduleFireLog[]>(`${BASE}/admin/failed?limit=${limit}`);
}

