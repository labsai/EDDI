import { ALERT_LIMITS, MASKED_URL_SUFFIX, type AlertCondition, type AlertRule, type MatchOp } from "@/lib/api/alerts";

/** Mirrors the backend's OutputMatchCondition grammar. */
export const OUTPUT_MATCH_LIMITS = { maxDepth: 8, maxInValues: 50, maxValueLength: 1000 } as const;

const SEGMENT = /^[A-Za-z0-9_-]{1,100}$/;

/** The source of an OUTPUT_MATCH path and the rest of it. */
export type PathSource = "properties" | "output.json" | "actions";

export function splitPath(path: string | null | undefined): { source: PathSource; rest: string } {
  const p = (path ?? "").trim();
  if (p === "actions") return { source: "actions", rest: "" };
  if (p.startsWith("output.json.")) return { source: "output.json", rest: p.slice("output.json.".length) };
  if (p.startsWith("properties.")) return { source: "properties", rest: p.slice("properties.".length) };
  return { source: "properties", rest: p };
}

export function joinPath(source: PathSource, rest: string): string {
  return source === "actions" ? "actions" : `${source}.${rest.trim()}`;
}

/**
 * Whether a path follows the grammar: `actions`, `properties.<a.b…>` or
 * `output.json.<a.b…>`, dot segments of letters, digits, `_` and `-`, at most 8
 * segments after the source, no wildcards.
 */
export function pathProblem(path: string | null | undefined): string | null {
  const raw = (path ?? "").trim();
  if (raw === "actions") return null;
  if (!raw.startsWith("properties.") && !raw.startsWith("output.json.")) return "path";
  const { rest } = splitPath(raw);
  if (!rest) return "path";
  const segments = rest.split(".");
  if (segments.length > OUTPUT_MATCH_LIMITS.maxDepth) return "depth";
  return segments.every((s) => SEGMENT.test(s)) ? null : "path";
}

export type ValueType = "text" | "number" | "boolean" | "list";

export function valueTypeOf(value: unknown): ValueType {
  if (Array.isArray(value)) return "list";
  if (typeof value === "number") return "number";
  if (typeof value === "boolean") return "boolean";
  return "text";
}

/** The operators that make sense for a path source and a value type. */
export function opsFor(source: PathSource, type: ValueType): MatchOp[] {
  if (source === "actions") return ["contains", "exists"];
  switch (type) {
    case "number":
      return ["eq", "neq", "gt", "gte", "lt", "lte", "exists"];
    case "boolean":
      return ["eq", "neq", "exists"];
    case "list":
      return ["in"];
    default:
      return ["eq", "neq", "contains", "exists"];
  }
}

/** Problems of an OUTPUT_MATCH condition (path grammar, op/value compatibility). */
export function outputMatchProblems(c: AlertCondition): string[] {
  const problems: string[] = [];
  const pp = pathProblem(c.path);
  if (pp) problems.push(pp);
  if (!c.op) {
    problems.push("op");
    return problems;
  }
  const { source } = splitPath(c.path);
  if (c.op === "exists") return problems;
  const v = c.value;
  if (v === undefined || v === null || v === "") problems.push("value");
  else if (!opsFor(source, valueTypeOf(v)).includes(c.op)) problems.push("op");
  if (Array.isArray(v) && (v.length === 0 || v.length > OUTPUT_MATCH_LIMITS.maxInValues)) problems.push("value");
  if (typeof v === "string" && v.length > OUTPUT_MATCH_LIMITS.maxValueLength) problems.push("value");
  return problems;
}

/**
 * The throttle fields out of range: whole numbers, 1–1440 minutes and 1–1000 per
 * window, exactly AlertRuleValidator's bounds. No throttle (the defaults) is fine.
 */
export function throttleProblems(throttle: AlertRule["throttle"]): ("windowMinutes" | "maxPerWindow")[] {
  if (!throttle) return [];
  const problems: ("windowMinutes" | "maxPerWindow")[] = [];
  const within = (n: number, max: number) => Number.isInteger(n) && n >= 1 && n <= max;
  if (!within(throttle.windowMinutes, ALERT_LIMITS.maxWindowMinutes)) problems.push("windowMinutes");
  if (!within(throttle.maxPerWindow, ALERT_LIMITS.maxPerWindow)) problems.push("maxPerWindow");
  return problems;
}

/** What the page refuses before the backend would (AlertRuleValidator mirrors). */
export function ruleProblems(rule: AlertRule): string[] {
  const problems: string[] = [];
  if (!rule.name.trim()) problems.push("name");
  if (!rule.scope.allAgents && rule.scope.agentIds.length === 0) problems.push("scope");
  if (rule.source === "RATING") {
    const c = rule.condition;
    const any = c.maxNormalizedScore != null || c.onComment === true || (c.tags?.length ?? 0) > 0;
    if (!any) problems.push("condition");
  } else if (rule.source === "OUTPUT_MATCH") {
    if (outputMatchProblems(rule.condition).length > 0) problems.push("condition");
  } else if (!rule.condition.errorKinds?.length) {
    problems.push("condition");
  }
  rule.delivery.forEach((target, i) => {
    if (target.type === "SLACK" && !/^[CGD][A-Z0-9]{2,30}$/.test(target.channelId ?? "")) problems.push(`delivery${i}`);
    const url = (target.url ?? "").trim();
    // https only, as the server requires; a vault reference is checked when the alert is sent.
    // A masked URL stands for the stored one only together with its targetId.
    const masked = url.endsWith(MASKED_URL_SUFFIX);
    const urlOk = (/^https:\/\/[^/\s]+/i.test(url) && (!masked || !!target.targetId)) || /^\$\{vault:[^}]+\}$/.test(url);
    if (target.type === "WEBHOOK" && (!urlOk || !/^\$\{vault:[^}]+\}$/.test((target.signingSecret ?? "").trim()))) {
      problems.push(`delivery${i}`);
    }
  });
  if (throttleProblems(rule.throttle).length > 0) problems.push("throttle");
  return problems;
}
