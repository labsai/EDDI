import {
  PARAM_GROUPS,
  evaluateParameter,
  findProvider,
  rejectedForEveryValue,
  type CatalogParam,
  type ParamGroup,
  type ParameterCatalog,
  type ParameterVerdict,
} from "@/lib/llm-parameter-catalog";

/**
 * Settings almost every agent designer reaches for; a group holding one of them
 * opens by default. Everything else waits in a collapsed group unless it is set.
 */
export const COMMON_PARAM_KEYS: ReadonlySet<string> = new Set([
  "temperature",
  "topP",
  "maxTokens",
  "maxCompletionTokens",
  "maxOutputTokens",
  "maxNewTokens",
  "reasoningEffort",
  "effort",
  "thinkingType",
  "thinkingLevel",
  "thinkingBudget",
  "thinkingBudgetTokens",
  "reasoningBudgetTokens",
  "enableThinking",
  "think",
]);

/**
 * The parameters as text. The type says `Record<string, string>`, but a config
 * edited in the JSON tab can hold `"topP": 0.9` or `true`; the backend reads
 * those as their text, and so does the panel. Null and undefined are absent.
 */
export function asStringParams(params: Readonly<Record<string, unknown>> | undefined | null): Readonly<Record<string, string>> {
  const out: Record<string, string> = {};
  if (!params) return out;
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    out[key] = typeof value === "string" ? value : typeof value === "object" ? JSON.stringify(value) : String(value);
  }
  return out;
}

export function isSet(value: string | undefined): boolean {
  return value !== undefined && value.trim() !== "";
}

/** The backend's `stringList`: a JSON array of strings, or a comma list. */
export function parseStringList(raw: string | undefined): { items: string[]; invalid: boolean } {
  const value = raw?.trim() ?? "";
  if (value === "") return { items: [], invalid: false };
  if (value.startsWith("[")) {
    try {
      const parsed: unknown = JSON.parse(value);
      if (Array.isArray(parsed) && parsed.every((x) => typeof x === "string")) {
        return { items: parsed.filter((x) => x !== ""), invalid: false };
      }
    } catch {
      // fall through
    }
    return { items: [], invalid: true };
  }
  return {
    items: value
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s !== ""),
    invalid: false,
  };
}

/**
 * Written as a JSON array: a comma list cannot hold a value that contains a
 * comma, and stop sequences routinely do. An empty list removes the key.
 */
export function serializeStringList(items: readonly string[]): string | undefined {
  return items.length === 0 ? undefined : JSON.stringify(items);
}

export type JsonProblem = "syntax" | "shape" | null;

/** A `json` parameter must parse, and be an object or an array (never a bare scalar). */
export function jsonProblem(raw: string): JsonProblem {
  if (raw.trim() === "") return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed !== null && typeof parsed === "object" ? null : "shape";
  } catch {
    return "syntax";
  }
}

export type NumberProblem = "notNumber" | "notInteger" | "belowMin" | "aboveMax" | null;

export function numberProblem(raw: string, param: CatalogParam): NumberProblem {
  if (raw.trim() === "") return null;
  const n = Number(raw.trim());
  if (!Number.isFinite(n)) return "notNumber";
  if (param.type === "int" && !Number.isInteger(n)) return "notInteger";
  if (param.min !== undefined && n < param.min) return "belowMin";
  if (param.max !== undefined && n > param.max) return "aboveMax";
  return null;
}

/** Case-insensitive match on the label, key and description. */
export function matchesFilter(param: CatalogParam, filter: string): boolean {
  const q = filter.trim().toLowerCase();
  if (q === "") return true;
  return (
    param.key.toLowerCase().includes(q) ||
    param.label.toLowerCase().includes(q) ||
    param.description.toLowerCase().includes(q)
  );
}

export interface ParamView {
  param: CatalogParam;
  value: string | undefined;
  configured: boolean;
  verdict: ParameterVerdict;
  /** Rejected whatever its value: the control is disabled. */
  locked: boolean;
}

export interface GroupView {
  group: ParamGroup;
  params: ParamView[];
  configured: number;
  rejectedConfigured: number;
  hasCommon: boolean;
}

/**
 * The view of one provider's parameters for the given values. `effective` is
 * what the backend evaluates (a cascade step merges the task's parameters under
 * its own); `own` is what this editor writes.
 */
export function buildGroupViews(
  catalog: ParameterCatalog,
  type: string | undefined,
  own: Readonly<Record<string, string>>,
  effective: Readonly<Record<string, string>>,
  excludeKeys: ReadonlySet<string>,
): GroupView[] {
  const provider = findProvider(catalog, type);
  if (!provider) return [];
  const byGroup = new Map<ParamGroup, ParamView[]>();
  for (const param of provider.params) {
    if (excludeKeys.has(param.key)) continue;
    const value = own[param.key];
    const verdict = evaluateParameter(catalog, type, param.key, effective);
    const view: ParamView = {
      param,
      value,
      configured: isSet(value),
      verdict,
      // A value the backend moves to another key is still used: never lock it.
      locked:
        verdict.state === "rejected" &&
        !verdict.migratesTo &&
        rejectedForEveryValue(catalog, type, param.key, effective),
    };
    const list = byGroup.get(param.group) ?? [];
    list.push(view);
    byGroup.set(param.group, list);
  }
  return PARAM_GROUPS.filter((g) => byGroup.has(g)).map((group) => {
    const params = byGroup.get(group)!;
    return {
      group,
      params,
      configured: params.filter((p) => p.configured).length,
      rejectedConfigured: params.filter((p) => p.configured && isDropped(p.verdict)).length,
      hasCommon: params.some((p) => COMMON_PARAM_KEYS.has(p.param.key)),
    };
  });
}

/** Rejected and thrown away — as opposed to rejected and sent under another key. */
export function isDropped(verdict: ParameterVerdict): boolean {
  return verdict.state === "rejected" && !verdict.migratesTo;
}

/**
 * Unset settings the model rejects or ignores: shown behind one quiet line per
 * group, so a reasoning model's long list of refusals does not read as errors.
 */
export function isUnavailable(view: ParamView): boolean {
  return !view.configured && view.verdict.state !== "ok" && !view.verdict.migratesTo;
}

/** A model given as a `${…}` reference: the server checks the resolved value, the Manager cannot. */
export function isVariableReference(model: string | null): boolean {
  return model !== null && model.includes("${");
}

/** A DOM-safe id fragment for a parameter key. */
export function paramDomId(prefix: string, key: string): string {
  return `${prefix}-${key.replace(/[^A-Za-z0-9_-]/g, "_")}`;
}
