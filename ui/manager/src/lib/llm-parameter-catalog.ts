/**
 * Every parameter EDDI can send to each LLM provider, and which model versions
 * reject or ignore it.
 *
 * The data is a generated mirror of the backend catalog,
 * `src/main/resources/llm/model-parameters.json` — the single source of truth.
 * `llm-parameter-catalog.data.ts` is written by
 * `npm run gen:llm-params` (`scripts/gen-llm-parameter-catalog.mjs`) and never
 * edited by hand; `llm-parameter-catalog.test.ts` reads the JSON and fails on
 * any drift.
 *
 * This file holds the types and a pure re-implementation of the backend's
 * `ModelParameterCatalog` semantics (inheritance, model-id resolution and rule
 * evaluation), so the Manager shows exactly the verdict the server will act on.
 * Both implementations consume `src/test/resources/llm/parameter-rule-vectors.json`.
 */

import { getCompatibleProvider } from "@/lib/llm-provider-catalog";
import { LLM_PARAMETER_CATALOG_DATA } from "@/lib/llm-parameter-catalog.data";

// ─── Raw catalog shape (the JSON, verbatim) ─────────────────────────────────

export type ParamType = "double" | "int" | "boolean" | "string" | "enum" | "stringList" | "json";

export type ParamGroup =
  | "model"
  | "sampling"
  | "output"
  | "reasoning"
  | "tools"
  | "caching"
  | "safety"
  | "connection"
  | "advanced";

/** Display order of the groups in the Manager. */
export const PARAM_GROUPS: readonly ParamGroup[] = [
  "model",
  "sampling",
  "output",
  "reasoning",
  "tools",
  "caching",
  "safety",
  "connection",
  "advanced",
];

export interface RawCondition {
  key: string;
  values?: string[];
  whenUnset?: boolean;
}

export interface RawRule {
  /** A Java regex, applied with `find()`, case-insensitively, to the model id. */
  models: string;
  effect: "rejected" | "ignored";
  note: string;
  when?: RawCondition;
  unless?: RawCondition;
  /**
   * When the rule rejects the parameter, the backend sends its value under this
   * other key instead — unless that key is set itself, in which case the value
   * is simply dropped.
   */
  migrateTo?: string;
}

export interface RawParam {
  key: string;
  type: ParamType;
  group: ParamGroup;
  label: string;
  description: string;
  enum?: string[];
  min?: number;
  max?: number;
  default?: string | number | boolean;
  wire?: string;
  body?: boolean;
  header?: string;
  /** For type `json`: the top-level shape the value must have (object when absent). */
  jsonShape?: "object" | "array";
  rules?: RawRule[];
  source: string;
}

export interface RawProvider {
  displayName: string;
  inherits?: string;
  params: RawParam[];
}

export interface RawParameterCatalog {
  schemaVersion: number;
  providers: Record<string, RawProvider>;
}

// ─── Resolved shape ─────────────────────────────────────────────────────────

export interface CompiledRule {
  models: RegExp;
  effect: "rejected" | "ignored";
  note: string;
  when?: RawCondition;
  unless?: RawCondition;
  migrateTo?: string;
}

export interface CatalogParam extends Omit<RawParam, "rules" | "default"> {
  /** The advisory default as text (Java's `asText()`), or undefined. */
  default?: string;
  rules: CompiledRule[];
}

export interface CatalogProvider {
  type: string;
  displayName: string;
  /** Catalog order: inherited keys first (in the parent's order), then own new keys. */
  params: CatalogParam[];
  byKey: ReadonlyMap<string, CatalogParam>;
}

export interface ParameterCatalog {
  providers: ReadonlyMap<string, CatalogProvider>;
}

// ─── Java regex → JavaScript ────────────────────────────────────────────────

export class UntranslatableRegexError extends Error {
  constructor(
    readonly pattern: string,
    readonly reason: string,
  ) {
    super(`Java regex ${JSON.stringify(pattern)} has no JavaScript equivalent: ${reason}`);
    this.name = "UntranslatableRegexError";
  }
}

/** Java-only escapes; in JavaScript (no `u` flag) most of them silently match a letter. */
const JAVA_ONLY_ESCAPES = new Set(["A", "Z", "z", "G", "Q", "E", "h", "H", "R", "X", "p", "P", "e", "a", "0", "c", "N", "V", "v", "K"]);

/**
 * Translate a catalog `models` regex (Java `java.util.regex` syntax) into a
 * JavaScript `RegExp` with the same meaning under `find()` + `CASE_INSENSITIVE`.
 *
 * The constructs the catalog uses — anchors, groups, alternation, classes,
 * `\d`, quantifiers, lookahead — mean the same in both engines, so a supported
 * pattern is used verbatim with the `i` flag. Anything whose meaning differs
 * (inline flags such as `(?i)`, atomic groups, possessive quantifiers, `\A`/`\Z`,
 * `\Q…\E`, `\p{…}`, nested or intersected classes, Java-only escapes) is
 * refused rather than guessed at, and the drift test fails on it.
 *
 * Remaining, documented differences: Java's ASCII-only case folding versus
 * JavaScript's Unicode one, and Java's `$` also matching before a final line
 * terminator. Neither can arise for a trimmed, ASCII model id.
 */
export function javaRegexToJs(pattern: string): RegExp {
  let inClass = false;
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i]!;
    if (c === "\\") {
      const next = pattern[i + 1];
      if (next === undefined) throw new UntranslatableRegexError(pattern, "trailing backslash");
      // `\x{…}` is Java's code-point form; `\x41` means the same in both.
      const codePointForm = next === "x" && pattern[i + 2] === "{";
      if (JAVA_ONLY_ESCAPES.has(next) || codePointForm || (inClass && /[1-9]/.test(next))) {
        throw new UntranslatableRegexError(pattern, `escape \\${next}`);
      }
      i++;
      continue;
    }
    if (inClass) {
      if (c === "[") throw new UntranslatableRegexError(pattern, "nested character class");
      if (c === "&" && pattern[i + 1] === "&") {
        throw new UntranslatableRegexError(pattern, "character class intersection");
      }
      if (c === "]") inClass = false;
      continue;
    }
    if (c === "[") {
      inClass = true;
      // A leading `]` (or `^]`) is a literal in Java but closes an empty class in JS.
      const first = pattern[i + 1] === "^" ? pattern[i + 2] : pattern[i + 1];
      if (first === "]") throw new UntranslatableRegexError(pattern, "leading ] in a class");
      if (pattern[i + 1] === "^") i++;
      continue;
    }
    if (c === "(" && pattern[i + 1] === "?") {
      const kind = pattern[i + 2];
      const ok =
        kind === ":" ||
        kind === "=" ||
        kind === "!" ||
        (kind === "<" && (pattern[i + 3] === "=" || pattern[i + 3] === "!" || /[A-Za-z]/.test(pattern[i + 3] ?? "")));
      if (!ok) throw new UntranslatableRegexError(pattern, `group (?${kind ?? ""}`);
      continue;
    }
    if ((c === "*" || c === "+" || c === "?" || c === "}") && pattern[i + 1] === "+") {
      throw new UntranslatableRegexError(pattern, "possessive quantifier");
    }
  }
  if (inClass) throw new UntranslatableRegexError(pattern, "unterminated character class");
  try {
    return new RegExp(pattern, "i");
  } catch (e) {
    throw new UntranslatableRegexError(pattern, (e as Error).message);
  }
}

// ─── Building ───────────────────────────────────────────────────────────────

function compileParam(raw: RawParam, withRules: boolean): CatalogParam {
  const { rules, default: def, ...rest } = raw;
  return {
    ...rest,
    default: def === undefined ? undefined : String(def),
    rules: withRules
      ? (rules ?? []).map((r) => ({
          models: javaRegexToJs(r.models),
          effect: r.effect,
          note: r.note,
          when: r.when,
          unless: r.unless,
          migrateTo: r.migrateTo,
        }))
      : [],
  };
}

/**
 * Resolve a raw catalog: a provider with `inherits` receives every parameter of
 * its parent *without the parent's rules* (they are about the parent's models),
 * and its own entries replace inherited ones key by key — in the parent's
 * position, as Java's `LinkedHashMap.put` keeps it.
 */
export function buildParameterCatalog(raw: RawParameterCatalog): ParameterCatalog {
  const providers = new Map<string, CatalogProvider>();
  for (const [type, provider] of Object.entries(raw.providers)) {
    const params = new Map<string, CatalogParam>();
    if (provider.inherits) {
      const parent = raw.providers[provider.inherits];
      if (!parent) throw new Error(`${type} inherits unknown provider ${provider.inherits}`);
      for (const p of parent.params) params.set(p.key, compileParam(p, false));
    }
    for (const p of provider.params) params.set(p.key, compileParam(p, true));
    providers.set(type, {
      type,
      displayName: provider.displayName,
      params: [...params.values()],
      byKey: params,
    });
  }
  return { providers };
}

/** The catalog shipped with this Manager build. */
export const PARAMETER_CATALOG: ParameterCatalog = buildParameterCatalog(LLM_PARAMETER_CATALOG_DATA);

// ─── Lookups ────────────────────────────────────────────────────────────────

/** Java's `String.trim()`: strips code points <= U+0020 only. */
function javaTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start++;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end--;
  return value.slice(start, end);
}

function trimmedOrNull(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  const trimmed = javaTrim(String(value));
  return trimmed === "" ? null : trimmed;
}

function normaliseType(type: string | undefined | null): string {
  return type == null ? "" : javaTrim(type).toLowerCase();
}

export function findProvider(
  catalog: ParameterCatalog,
  type: string | undefined | null,
): CatalogProvider | undefined {
  return catalog.providers.get(normaliseType(type));
}

export function findParam(
  catalog: ParameterCatalog,
  type: string | undefined | null,
  key: string,
): CatalogParam | undefined {
  return findProvider(catalog, type)?.byKey.get(key);
}

/** Every catalogued key of a provider type; empty for an unknown type. */
export function catalogKeys(catalog: ParameterCatalog, type: string | undefined | null): Set<string> {
  return new Set(findProvider(catalog, type)?.byKey.keys() ?? []);
}

/** Every key a builder reads the model from, in lookup order (`ModelParameterCatalog.MODEL_KEYS`). */
export const MODEL_KEYS: readonly string[] = ["modelName", "model", "modelId", "modelID", "deploymentName"];

/**
 * The key each provider's builder reads its model from; every other type
 * (including the OpenAI-compatible family) reads `modelName`.
 */
const OWN_MODEL_KEY: Readonly<Record<string, string>> = {
  ollama: "model",
  bedrock: "modelId",
  huggingface: "modelId",
  "gemini-vertex": "modelId",
  "azure-openai": "deploymentName",
};

/**
 * The model id rules are evaluated against: `baseModel` on Azure when set, else
 * the provider's own model key, else the first non-blank of {@link MODEL_KEYS},
 * else an OpenAI-compatible provider's default model; null when none is known.
 *
 * The provider's own key comes first because a cascade step's parameters are
 * merged over the task's: a task on openai/gpt-5 with an azure-openai step
 * deployed as gpt4o-prod carries both `modelName` and `deploymentName`, and the
 * step runs gpt4o-prod.
 */
export function modelIdFor(
  type: string | undefined | null,
  params: Readonly<Record<string, string | undefined>> | undefined | null,
): string | null {
  if (!params) return null;
  const normalised = normaliseType(type);
  if (normalised === "azure-openai") {
    const base = trimmedOrNull(params.baseModel);
    if (base !== null) return base;
  }
  for (const key of [OWN_MODEL_KEY[normalised] ?? "modelName", ...MODEL_KEYS]) {
    const value = trimmedOrNull(params[key]);
    if (value !== null) return value;
  }
  return getCompatibleProvider(normalised)?.defaultModel ?? null;
}

// ─── Evaluation ─────────────────────────────────────────────────────────────

const JAVA_DECIMAL = /^[+-]?(?:NaN|Infinity|(?:(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?))$/;
const JAVA_HEX_FLOAT = /^([+-]?)0[xX]([0-9a-fA-F]*)\.?([0-9a-fA-F]*)[pP]([+-]?\d+)[fFdD]?$/;

/** Java's `Double.parseDouble`, or undefined where it would throw. */
function parseJavaDouble(raw: string): number | undefined {
  const s = javaTrim(raw);
  if (JAVA_DECIMAL.test(s)) return Number(s.replace(/[fFdD]$/, ""));
  const hex = JAVA_HEX_FLOAT.exec(s);
  if (hex && (hex[2] || hex[3])) {
    const digits = (hex[2] ?? "") + (hex[3] ?? "");
    const mantissa = parseInt(digits, 16) / Math.pow(16, (hex[3] ?? "").length);
    const value = mantissa * Math.pow(2, Number(hex[4]));
    return hex[1] === "-" ? -value : value;
  }
  return undefined;
}

/** Case-insensitive equality, else numeric equality (`1` equals `1.0`). */
function sameValue(expected: string, actual: string): boolean {
  if (expected.toLowerCase() === actual.toLowerCase()) return true;
  const a = parseJavaDouble(expected);
  const b = parseJavaDouble(actual);
  return a !== undefined && b !== undefined && a === b;
}

function conditionHolds(condition: RawCondition, params: Readonly<Record<string, string | undefined>>): boolean {
  const raw = trimmedOrNull(params[condition.key]);
  if (raw === null) return condition.whenUnset ?? false;
  if (!condition.values) return true;
  return condition.values.some((candidate) => sameValue(candidate, raw));
}

function ruleMatches(rule: CompiledRule, modelId: string, params: Readonly<Record<string, string | undefined>>): boolean {
  rule.models.lastIndex = 0;
  return (
    rule.models.test(modelId) &&
    (!rule.when || conditionHolds(rule.when, params)) &&
    (!rule.unless || !conditionHolds(rule.unless, params))
  );
}

export type ParameterState = "ok" | "rejected" | "ignored";

export interface ParameterVerdict {
  state: ParameterState;
  /** The matching rule's note; absent when the parameter applies. */
  note?: string;
  /** The official documentation the parameter (and its rules) cite. */
  source?: string;
  /** The model id the verdict was reached for; null when none is configured. */
  model: string | null;
  /**
   * Set on a rejection the backend repairs rather than drops: the value is sent
   * as this other parameter, because that one is not set. Absent when the
   * target is set (the value is then dropped like any other rejection).
   */
  migratesTo?: string;
}

/**
 * What the backend will do with `key` for this provider and these parameters:
 * the first rule whose model regex is found in the model id, whose `when` holds
 * and whose `unless` does not, decides; none matching means the parameter
 * applies. An unknown provider, key or model restricts nothing (fail open).
 *
 * Unlike `ModelParameterCatalog.adapt`, which only looks at parameters that are
 * set, this evaluates `key` whatever its value, so the editor can say "rejected"
 * before the user types anything. For a set key the two agree.
 */
export function evaluateParameter(
  catalog: ParameterCatalog,
  type: string | undefined | null,
  key: string,
  params: Readonly<Record<string, string | undefined>>,
): ParameterVerdict {
  const provider = findProvider(catalog, type);
  const param = provider?.byKey.get(key);
  const model = provider ? modelIdFor(type, params) : null;
  if (!param || model === null) return { state: "ok", source: param?.source, model };
  for (const rule of param.rules) {
    if (ruleMatches(rule, model, params)) {
      const migratesTo =
        rule.effect === "rejected" && rule.migrateTo && trimmedOrNull(params[rule.migrateTo]) === null
          ? rule.migrateTo
          : undefined;
      return { state: rule.effect, note: rule.note, source: param.source, model, ...(migratesTo ? { migratesTo } : {}) };
    }
  }
  return { state: "ok", source: param.source, model };
}

/**
 * Whether `key` is rejected whatever value it is given — the editor disables its
 * control only then. Probes the parameter's enum values, both booleans, the
 * values its own rules mention, and an arbitrary sentinel.
 */
export function rejectedForEveryValue(
  catalog: ParameterCatalog,
  type: string | undefined | null,
  key: string,
  params: Readonly<Record<string, string | undefined>>,
): boolean {
  const param = findParam(catalog, type, key);
  if (!param) return false;
  const probes = new Set<string>(["\u0000probe"]);
  for (const v of param.enum ?? []) probes.add(v);
  if (param.type === "boolean") {
    probes.add("true");
    probes.add("false");
  }
  for (const rule of param.rules) {
    for (const c of [rule.when, rule.unless]) {
      if (c?.key === key) for (const v of c.values ?? []) probes.add(v);
    }
  }
  for (const probe of probes) {
    if (evaluateParameter(catalog, type, key, { ...params, [key]: probe }).state !== "rejected") return false;
  }
  return true;
}
