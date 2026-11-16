import type { GuardrailActionName, GuardrailConfig, GuardrailOnExhausted } from "./types";

/**
 * Where a guardrail runs: on the user's message, on the model's answer, or on
 * the arguments of a tool call the model asks for (`toolCallGuardrails`).
 */
export type GuardrailPhaseName = "input" | "output" | "tool_call";

/** The types the backend registers, in the order the select offers them. */
export const GUARDRAIL_TYPES = [
  "prompt-injection",
  "pii-redaction",
  "regex-filter",
  "max-length",
  "classifier",
  "judge",
  "json-object",
  "expression",
  "disclosure",
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

/** The `pii-redaction` kinds that belong to no country, in the sheet's order. */
export const PII_UNIVERSAL_KINDS = ["email", "phone", "creditcard", "iban", "ip_address", "passport_mrz", "date_of_birth"] as const;

/**
 * What `pii-redaction` finds when `patterns` is absent — mirrors the backend's
 * `PiiCountries.STANDARD`.
 */
export const PII_STANDARD_KINDS = ["email", "ssn", "creditcard", "phone", "iban"] as const;

export type PiiRegion = "americas" | "europe" | "asiaPacific" | "africa";

export interface PiiCountry {
  /** ISO 3166 code, as the `countries` parameter takes it. */
  code: string;
  region: PiiRegion;
  /**
   * The country's identifiers. `name` is the identifier's own official name or
   * abbreviation (a proper noun, the same in every UI language).
   */
  kinds: { kind: string; name: string }[];
}

/** Mirrors the backend's `PiiCountries`, in the same order. */
export const PII_COUNTRIES: PiiCountry[] = [
  { code: "US", region: "americas", kinds: [{ kind: "ssn", name: "SSN" }, { kind: "us_itin", name: "ITIN" }] },
  { code: "CA", region: "americas", kinds: [{ kind: "ca_sin", name: "SIN" }] },
  { code: "BR", region: "americas", kinds: [{ kind: "br_cpf", name: "CPF" }] },
  { code: "MX", region: "americas", kinds: [{ kind: "mx_curp", name: "CURP" }] },
  {
    code: "GB",
    region: "europe",
    kinds: [
      { kind: "gb_nino", name: "NINO" },
      { kind: "gb_nhs", name: "NHS" },
    ],
  },
  { code: "IE", region: "europe", kinds: [{ kind: "ie_pps", name: "PPSN" }] },
  { code: "FR", region: "europe", kinds: [{ kind: "fr_nir", name: "NIR" }] },
  { code: "IT", region: "europe", kinds: [{ kind: "it_fiscal_code", name: "Codice fiscale" }] },
  { code: "ES", region: "europe", kinds: [{ kind: "es_dni", name: "DNI / NIE" }] },
  { code: "PT", region: "europe", kinds: [{ kind: "pt_nif", name: "NIF" }] },
  { code: "NL", region: "europe", kinds: [{ kind: "nl_bsn", name: "BSN" }] },
  { code: "BE", region: "europe", kinds: [{ kind: "be_national_number", name: "NN / RRN" }] },
  {
    code: "DE",
    region: "europe",
    kinds: [
      { kind: "de_tax_id", name: "Steuer-ID" },
      { kind: "de_social_security", name: "Rentenversicherungsnummer" },
      { kind: "de_id_card", name: "Ausweis / Reisepass" },
    ],
  },
  { code: "AT", region: "europe", kinds: [{ kind: "at_social_security", name: "SVNR" }] },
  { code: "CH", region: "europe", kinds: [{ kind: "ch_ahv", name: "AHV / AVS" }] },
  { code: "PL", region: "europe", kinds: [{ kind: "pl_pesel", name: "PESEL" }] },
  { code: "SE", region: "europe", kinds: [{ kind: "se_personnummer", name: "Personnummer" }] },
  { code: "DK", region: "europe", kinds: [{ kind: "dk_cpr", name: "CPR" }] },
  { code: "FI", region: "europe", kinds: [{ kind: "fi_hetu", name: "HETU" }] },
  {
    code: "IN",
    region: "asiaPacific",
    kinds: [
      { kind: "in_aadhaar", name: "Aadhaar" },
      { kind: "in_pan", name: "PAN" },
    ],
  },
  { code: "CN", region: "asiaPacific", kinds: [{ kind: "cn_resident_id", name: "身份证" }] },
  { code: "JP", region: "asiaPacific", kinds: [{ kind: "jp_my_number", name: "マイナンバー" }] },
  { code: "KR", region: "asiaPacific", kinds: [{ kind: "kr_rrn", name: "주민등록번호" }] },
  { code: "SG", region: "asiaPacific", kinds: [{ kind: "sg_nric", name: "NRIC / FIN" }] },
  {
    code: "AU",
    region: "asiaPacific",
    kinds: [
      { kind: "au_tfn", name: "TFN" },
      { kind: "au_medicare", name: "Medicare" },
    ],
  },
  { code: "NZ", region: "asiaPacific", kinds: [{ kind: "nz_ird", name: "IRD" }] },
  { code: "ZA", region: "africa", kinds: [{ kind: "za_id", name: "RSA ID" }] },
];

export const PII_REGIONS: PiiRegion[] = ["europe", "americas", "asiaPacific", "africa"];

/**
 * Every `pii-redaction` kind. The backend applies them in its own fixed order, so
 * the order of a stored `patterns` list does not matter.
 */
export const PII_KINDS: string[] = [...PII_UNIVERSAL_KINDS, ...PII_COUNTRIES.flatMap((c) => c.kinds.map((k) => k.kind))];

const PHASES_BY_TYPE: Record<GuardrailTypeName, GuardrailPhaseName[]> = {
  "prompt-injection": ["input", "tool_call"],
  "pii-redaction": ["input", "output", "tool_call"],
  "regex-filter": ["input", "output", "tool_call"],
  "max-length": ["input", "output", "tool_call"],
  classifier: ["input", "output", "tool_call"],
  judge: ["output", "tool_call"],
  "json-object": ["output", "tool_call"],
  expression: ["output", "tool_call"],
  disclosure: ["output", "tool_call"],
};

/** The only action `disclosure` works with; the backend refuses any other. */
export const DISCLOSURE_ACTION: GuardrailActionName = "redact";

/** Types without a rewrite: `redact` has nothing to rewrite with, so the engine blocks instead. */
const NO_REWRITE: ReadonlySet<string> = new Set([
  "prompt-injection",
  "classifier",
  "judge",
  "json-object",
  "expression",
]);

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
  return type === "disclosure" ? { type, action: DISCLOSURE_ACTION } : { type };
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
  let next: GuardrailConfig = type === "disclosure" ? withAction({ ...rest, type }, DISCLOSURE_ACTION) : { ...rest, type };
  if (failMode) next = withParameter(next, "failMode", failMode);
  if (repromptMessage) next = withParameter(next, "repromptMessage", repromptMessage);
  return next;
}

/**
 * Entity names the backend accepts in `patterns` as aliases of a kind
 * (`PiiRedactionGuardrail.entityOf`) whose lower-case form is not the kind itself.
 */
const PII_ENTITY_ALIASES: Record<string, string> = {
  credit_card: "creditcard",
  us_ssn: "ssn",
};

/** `pii-redaction.patterns` as the set of selected kinds; absent means the standard set. */
export function enabledPiiKinds(g: GuardrailConfig): string[] {
  const raw = g.parameters?.patterns;
  // The backend reads a blank value, whitespace included, as the standard set.
  if (!raw?.trim()) return PII_KINDS.filter((k) => (PII_STANDARD_KINDS as readonly string[]).includes(k));
  const wanted = raw.split(",").map((s) => {
    const name = s.trim().toLowerCase();
    return PII_ENTITY_ALIASES[name] ?? name;
  });
  return PII_KINDS.filter((k) => wanted.includes(k));
}

/** Writes the kinds back; exactly the standard set is the default, so it is stored as absent. */
export function withPiiKinds(g: GuardrailConfig, kinds: string[]): GuardrailConfig {
  const ordered = PII_KINDS.filter((k) => kinds.includes(k));
  const isStandard =
    ordered.length === PII_STANDARD_KINDS.length && PII_STANDARD_KINDS.every((k) => ordered.includes(k));
  return withParameter(g, "patterns", isStandard ? "" : ordered.join(","));
}

/** `pii-redaction.countries` as the known country codes it names, upper-case. */
export function enabledPiiCountries(g: GuardrailConfig): string[] {
  const wanted = (g.parameters?.countries ?? "").split(",").map((s) => s.trim().toUpperCase());
  return PII_COUNTRIES.map((c) => c.code).filter((code) => wanted.includes(code));
}

export function withPiiCountries(g: GuardrailConfig, codes: string[]): GuardrailConfig {
  const ordered = PII_COUNTRIES.map((c) => c.code).filter((code) => codes.includes(code));
  return withParameter(g, "countries", ordered.join(","));
}

/** Every kind the guardrail will look for: the selected kinds plus those of the selected countries. */
export function effectivePiiKinds(g: GuardrailConfig): string[] {
  const countries = enabledPiiCountries(g);
  const fromCountries = PII_COUNTRIES.filter((c) => countries.includes(c.code)).flatMap((c) => c.kinds.map((k) => k.kind));
  const all = new Set([...enabledPiiKinds(g), ...fromCountries]);
  return PII_KINDS.filter((k) => all.has(k));
}

/**
 * The classifier needs an explicit `safeLabel` once `unsafeLabel` is anything but
 * `yes` — the backend refuses the deploy otherwise.
 */
export function classifierNeedsSafeLabel(g: GuardrailConfig): boolean {
  const unsafe = (g.parameters?.unsafeLabel ?? "").trim();
  return g.type === "classifier" && unsafe !== "" && unsafe.toLowerCase() !== "yes" && !(g.parameters?.safeLabel ?? "").trim();
}

/** Mirrors the backend `ExpressionGuardrail` bounds. */
export const EXPRESSION_MAX_CHARS = 2000;
export const EXPRESSION_FIX_MAX_CHARS = 1000;
/** An issue code: upper-case letters, digits and underscores, starting with a letter, at most 40. */
export const ISSUE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,39}$/;

/** What the backend's deploy-time check of an `expression` guardrail would refuse. */
export type ExpressionProblem =
  | "exprRequired"
  | "exprTooLong"
  | "issueRequired"
  | "issueInvalid"
  | "fixRequired"
  | "fixTooLong";

/**
 * The problems `ExpressionGuardrail.validate` refuses the deploy for, minus the
 * expression's syntax, which only the server's parser can judge (it names the
 * position). An empty list for any other type.
 */
export function expressionProblems(g: GuardrailConfig): ExpressionProblem[] {
  if (g.type !== "expression") return [];
  const p = g.parameters ?? {};
  const problems: ExpressionProblem[] = [];
  const expr = p.expr ?? "";
  if (!expr.trim()) problems.push("exprRequired");
  else if (expr.length > EXPRESSION_MAX_CHARS) problems.push("exprTooLong");
  const issue = p.issue ?? "";
  if (!issue) problems.push("issueRequired");
  else if (!ISSUE_CODE_PATTERN.test(issue)) problems.push("issueInvalid");
  const fix = p.fix ?? "";
  if (!fix.trim()) problems.push("fixRequired");
  else if (fix.length > EXPRESSION_FIX_MAX_CHARS) problems.push("fixTooLong");
  return problems;
}

/** One line for the list row: the parameters that say what this guardrail does. */
export function summarizeGuardrail(g: GuardrailConfig): string {
  const p = g.parameters ?? {};
  switch (g.type) {
    case "classifier":
    case "judge":
      return [p.provider, p.modelName ?? p.model ?? p.modelId ?? p.deploymentName].filter(Boolean).join(" · ");
    case "pii-redaction":
      return effectivePiiKinds(g).join(", ");
    case "max-length":
      return p.maxChars ? `${p.maxChars}` : "";
    case "expression":
      return [p.issue, p.expr && p.expr.length > 60 ? `${p.expr.slice(0, 60)}…` : p.expr].filter(Boolean).join(" · ");
    case "disclosure":
      return p.text ?? "";
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
    "Asks a safety-classifier model you run (Granite Guardian, ShieldGemma, a moderation API) for a verdict. {{productName}} ships no classifier model.",
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
  expression: [
    "llmEditor.grTypeExpression",
    "Expression check",
    "llmEditor.grTypeExpressionDesc",
    "Evaluates your boolean expression over the JSON answer, e.g. sum(lines[*].amount) == total. When it is false, a reprompt tells the model your fix text and nothing else.",
  ],
  disclosure: [
    "toolCallGuardrails.typeDisclosure",
    "Disclosure label",
    "toolCallGuardrails.typeDisclosureDesc",
    "Adds a fixed label, such as “— AI-assisted reply”, to a text that does not carry it yet. Works with redact only, and adds the label once however often the text is screened.",
  ],
};

export const ACTION_TEXT: Record<GuardrailActionName, [string, string]> = {
  block: ["llmEditor.grActionBlock", "Block"],
  redact: ["llmEditor.grActionRedact", "Redact"],
  reprompt: ["llmEditor.grActionReprompt", "Reprompt"],
  warn: ["llmEditor.grActionWarn", "Warn"],
};
