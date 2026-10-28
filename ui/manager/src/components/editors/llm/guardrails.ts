import type { GuardrailActionName, GuardrailConfig, GuardrailOnExhausted } from "./types";

/** Where a guardrail runs: on the user's message, or on the model's answer. */
export type GuardrailPhaseName = "input" | "output";

/** The types the backend registers, in the order the select offers them. */
export const GUARDRAIL_TYPES = [
  "prompt-injection",
  "pii-redaction",
  "regex-filter",
  "max-length",
  "classifier",
  "judge",
  "json-object",
] as const;
export type GuardrailTypeName = (typeof GUARDRAIL_TYPES)[number];

/** In the order the select offers them. */
export const GUARDRAIL_ACTIONS: GuardrailActionName[] = ["block", "redact", "reprompt", "warn"];

/** Mirrors the backend `GuardrailAction` default: an absent or unknown action blocks. */
export const DEFAULT_GUARDRAIL_ACTION: GuardrailActionName = "block";
export const DEFAULT_REPROMPT_RETRIES = 2;
export const MAX_REPROMPT_RETRIES = 5;

/** `onExhausted` values, default first. Mirrors the backend `GuardrailConfig`. */
export const ON_EXHAUSTED: GuardrailOnExhausted[] = ["block", "deliver-best"];

/** The judge's `issues` limits, mirroring `JudgeGuardrail.validate`. */
export const JUDGE_MAX_ISSUES = 20;
export const JUDGE_MAX_FIX_CHARS = 1000;
export const JUDGE_CODE = /^[A-Z][A-Z0-9_]{0,39}$/;
/** The judge's `timeoutMs` default (the classifier's is 3000). */
export const JUDGE_DEFAULT_TIMEOUT_MS = 5000;

/** The built-in `pii-redaction` kinds, in the order the backend lists them. */
export const PII_KINDS = ["email", "ssn", "creditcard", "phone"] as const;

const PHASES_BY_TYPE: Record<GuardrailTypeName, GuardrailPhaseName[]> = {
  "prompt-injection": ["input"],
  "pii-redaction": ["input", "output"],
  "regex-filter": ["input", "output"],
  "max-length": ["input", "output"],
  classifier: ["input", "output"],
  judge: ["output"],
  "json-object": ["output"],
};

/** Types without a rewrite: `redact` has nothing to rewrite with, so the engine blocks instead. */
const NO_REWRITE: ReadonlySet<string> = new Set(["prompt-injection", "classifier", "judge", "json-object"]);

export function isKnownGuardrailType(type: string): type is GuardrailTypeName {
  return (GUARDRAIL_TYPES as readonly string[]).includes(type);
}

export function typesForPhase(phase: GuardrailPhaseName): GuardrailTypeName[] {
  return GUARDRAIL_TYPES.filter((type) => PHASES_BY_TYPE[type].includes(phase));
}

/** `reprompt` on the input side behaves as `block` — there is nothing to re-ask — so it is not offered. */
export function actionsForPhase(phase: GuardrailPhaseName): GuardrailActionName[] {
  return phase === "input" ? GUARDRAIL_ACTIONS.filter((a) => a !== "reprompt") : GUARDRAIL_ACTIONS;
}

export function typeHasRewrite(type: string): boolean {
  return !NO_REWRITE.has(type);
}

/** The action the engine will apply: an absent or unrecognised one is `block`. */
export function effectiveAction(g: GuardrailConfig): GuardrailActionName {
  return g.action && GUARDRAIL_ACTIONS.includes(g.action) ? g.action : DEFAULT_GUARDRAIL_ACTION;
}

/**
 * True when an output guardrail makes the task buffer its answer instead of
 * streaming it: tokens already sent to a client cannot be taken back, so
 * `block`, `redact` and `reprompt` (and any unknown action, treated as `block`)
 * wait for the screened text. `warn` leaves the text alone and keeps streaming.
 */
export function outputGuardrailsBuffer(list: GuardrailConfig[] | undefined): boolean {
  return (list ?? []).some((g) => effectiveAction(g) !== "warn");
}

export function newGuardrail(type: string): GuardrailConfig {
  return { type };
}

/**
 * Sets (or, for an empty value, removes) one entry of `parameters`. An emptied
 * map is dropped as well, and so is `blockMessage`-style optional state elsewhere:
 * a field the author never set must not reach an older server's strict parser.
 */
export function withParameter(g: GuardrailConfig, key: string, value: string | undefined): GuardrailConfig {
  const parameters = { ...(g.parameters ?? {}) };
  if (value === undefined || value === "") delete parameters[key];
  else parameters[key] = value;
  const { parameters: _drop, ...rest } = g;
  void _drop;
  return Object.keys(parameters).length > 0 ? { ...rest, parameters } : rest;
}

/** Sets a top-level text field, removing it when emptied. */
export function withText(g: GuardrailConfig, field: "blockMessage", value: string): GuardrailConfig {
  const { [field]: _drop, ...rest } = g;
  void _drop;
  return value === "" ? rest : { ...rest, [field]: value };
}

/**
 * Sets the action. `maxRetries` only means something for `reprompt`, so moving
 * to another action drops it rather than leaving a dead field behind.
 */
export function withAction(g: GuardrailConfig, action: GuardrailActionName): GuardrailConfig {
  if (action === "reprompt") return { ...g, action };
  // onExhausted means nothing without retries to exhaust, and the backend
  // refuses deliver-best on any other action.
  const { maxRetries: _drop, onExhausted: _dropToo, ...rest } = g;
  void _drop;
  void _dropToo;
  return { ...rest, action };
}

/** Sets `onExhausted`; `block` is the default, so it is stored as absent. */
export function withOnExhausted(g: GuardrailConfig, value: GuardrailOnExhausted): GuardrailConfig {
  const { onExhausted: _drop, ...rest } = g;
  void _drop;
  return value === "deliver-best" ? { ...rest, onExhausted: value } : rest;
}

/** One declared judge issue: its code and the author's fix text. */
export interface JudgeIssue {
  code: string;
  fix: string;
}

/**
 * The judge's `issues` parameter (a JSON object string) as rows, or null when
 * the stored value is not a JSON object of strings — the sheet then shows the
 * raw text rather than silently dropping it.
 */
export function parseJudgeIssues(raw: string | undefined): JudgeIssue[] | null {
  if (!raw || !raw.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    const rows: JudgeIssue[] = [];
    for (const [code, fix] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof fix !== "string") return null;
      rows.push({ code, fix });
    }
    return rows;
  } catch {
    return null;
  }
}

/**
 * Rows back to the `issues` string; no rows is stored as absent. A row whose
 * code repeats an earlier one would be lost in a JSON object, so rows keep
 * their own order and the deploy check reports the duplicate.
 */
export function serializeJudgeIssues(rows: JudgeIssue[]): string {
  if (rows.length === 0) return "";
  return `{${rows.map((r) => `${JSON.stringify(r.code)}: ${JSON.stringify(r.fix)}`).join(", ")}}`;
}

export type JudgeIssuesProblem = "none" | "tooMany" | "badCode" | "duplicate" | "blankFix" | "longFix";

/**
 * Why the backend would refuse these issues at deploy time, or null when they
 * are fine.
 */
export function judgeIssuesProblem(rows: JudgeIssue[]): JudgeIssuesProblem | null {
  if (rows.length === 0) return "none";
  if (rows.length > JUDGE_MAX_ISSUES) return "tooMany";
  const seen = new Set<string>();
  for (const row of rows) {
    if (!JUDGE_CODE.test(row.code)) return "badCode";
    if (seen.has(row.code)) return "duplicate";
    seen.add(row.code);
    if (!row.fix.trim()) return "blankFix";
    if (row.fix.length > JUDGE_MAX_FIX_CHARS) return "longFix";
  }
  return null;
}

export function withMaxRetries(g: GuardrailConfig, value: number | undefined): GuardrailConfig {
  const { maxRetries: _drop, ...rest } = g;
  void _drop;
  return value === undefined ? rest : { ...rest, maxRetries: value };
}

/** Changing the type resets what belongs to the old one: parameters are per-type. */
export function withType(g: GuardrailConfig, type: string): GuardrailConfig {
  if (g.type === type) return g;
  const { parameters: _drop, ...rest } = g;
  void _drop;
  const failMode = g.parameters?.failMode;
  const repromptMessage = g.parameters?.repromptMessage;
  let next: GuardrailConfig = { ...rest, type };
  if (failMode) next = withParameter(next, "failMode", failMode);
  if (repromptMessage) next = withParameter(next, "repromptMessage", repromptMessage);
  return next;
}

/** `pii-redaction.patterns` as the set of enabled kinds; absent means every kind. */
export function enabledPiiKinds(g: GuardrailConfig): string[] {
  const raw = g.parameters?.patterns;
  if (!raw) return [...PII_KINDS];
  const wanted = raw.split(",").map((s) => s.trim().toLowerCase());
  return PII_KINDS.filter((k) => wanted.includes(k));
}

/** Writes the kinds back; all four is the default, so it is stored as absent. */
export function withPiiKinds(g: GuardrailConfig, kinds: string[]): GuardrailConfig {
  const ordered = PII_KINDS.filter((k) => kinds.includes(k));
  return withParameter(g, "patterns", ordered.length === PII_KINDS.length ? "" : ordered.join(","));
}

/**
 * The classifier needs an explicit `safeLabel` once `unsafeLabel` is anything but
 * `yes` — the backend refuses the deploy otherwise.
 */
export function classifierNeedsSafeLabel(g: GuardrailConfig): boolean {
  const unsafe = (g.parameters?.unsafeLabel ?? "").trim();
  return g.type === "classifier" && unsafe !== "" && unsafe.toLowerCase() !== "yes" && !(g.parameters?.safeLabel ?? "").trim();
}

/** One line for the list row: the parameters that say what this guardrail does. */
export function summarizeGuardrail(g: GuardrailConfig): string {
  const p = g.parameters ?? {};
  switch (g.type) {
    case "classifier":
    case "judge":
      return [p.provider, p.modelName ?? p.model ?? p.modelId ?? p.deploymentName].filter(Boolean).join(" · ");
    case "pii-redaction":
      return enabledPiiKinds(g).join(", ");
    case "max-length":
      return p.maxChars ? `${p.maxChars}` : "";
    default:
      return "";
  }
}

/** Type → [label key, label, one-line description key, description]. */
export const TYPE_TEXT: Record<GuardrailTypeName, [string, string, string, string]> = {
  "prompt-injection": [
    "llmEditor.grTypePromptInjection",
    "Prompt injection",
    "llmEditor.grTypePromptInjectionDesc",
    "Finds common instruction-override phrasings (“ignore all previous instructions”, chat-template tags). A heuristic: it catches verbatim attacks, not paraphrased or encoded ones.",
  ],
  "pii-redaction": [
    "llmEditor.grTypePii",
    "PII redaction",
    "llmEditor.grTypePiiDesc",
    "Finds e-mail addresses, US social security numbers, payment card numbers and phone numbers. Pattern-based: not names or free-form identifiers.",
  ],
  "regex-filter": [
    "llmEditor.grTypeRegex",
    "Regex filter",
    "llmEditor.grTypeRegexDesc",
    "Finds your own regular expressions (case-insensitive) and rewrites each match.",
  ],
  "max-length": [
    "llmEditor.grTypeMaxLength",
    "Maximum length",
    "llmEditor.grTypeMaxLengthDesc",
    "Finds text longer than a character limit. Redact truncates it; reprompt asks for a shorter answer.",
  ],
  classifier: [
    "llmEditor.grTypeClassifier",
    "Safety classifier model",
    "llmEditor.grTypeClassifierDesc",
    "Asks a safety-classifier model you run (Granite Guardian, ShieldGemma, a moderation API) for a verdict. EDDI ships no classifier model.",
  ],
  judge: [
    "llmEditor.grTypeJudge",
    "Judge model",
    "llmEditor.grTypeJudgeDesc",
    "Asks a model to grade the answer against your criteria. It may only name the issue codes you declare; a reprompt sends your fix text for each code found.",
  ],
  "json-object": [
    "llmEditor.grTypeJson",
    "JSON object",
    "llmEditor.grTypeJsonDesc",
    "Finds an answer that is not exactly one JSON object (a Markdown code fence is tolerated). Checks the shape only.",
  ],
};

export const ACTION_TEXT: Record<GuardrailActionName, [string, string]> = {
  block: ["llmEditor.grActionBlock", "Block"],
  redact: ["llmEditor.grActionRedact", "Redact"],
  reprompt: ["llmEditor.grActionReprompt", "Reprompt"],
  warn: ["llmEditor.grActionWarn", "Warn"],
};
