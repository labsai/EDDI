import { modelParamSpec } from "../model-params";

/** Parse a numeric input into a value, or `undefined` when blank/invalid. */
export function parseNum(v: string): number | undefined {
  if (v === "") return undefined;
  const n = parseFloat(v);
  return isNaN(n) ? undefined : n;
}

/**
 * First free `param{N}` key that does not collide with an existing parameter,
 * so adding a parameter after removing an earlier one never overwrites a value.
 */
export function nextParamKey(params: Record<string, string>): string {
  let n = 0;
  while (Object.prototype.hasOwnProperty.call(params, `param${n}`)) n++;
  return `param${n}`;
}

/**
 * The legacy key every step's model used to be written to, whatever the
 * provider. Only Ollama's builder reads it; on every other provider the step
 * silently ran the task's model instead.
 */
export const LEGACY_MODEL_KEY = "model";

/**
 * Where a cascade step's or judge's model lives and what the model field shows. The key is the
 * step provider's own (`modelName`, `model`, `modelId` or `deploymentName`).
 * A step or judge saved before that fix carries `model`; while the own key is empty the
 * legacy value is shown, and the first edit moves it — an untouched one is
 * never rewritten.
 */
export function modelField(params: Record<string, string>, type: string | undefined) {
  const key = modelParamSpec(type).modelKey;
  const own = params[key];
  const legacy =
    key !== LEGACY_MODEL_KEY && (own === undefined || String(own).trim() === "") && params[LEGACY_MODEL_KEY] !== undefined;
  return { key, legacy, value: String((legacy ? params[LEGACY_MODEL_KEY] : own) ?? "") };
}

/** `params` with the model written to `key`, the legacy `model` folded away. */
export function withModel(params: Record<string, string>, key: string, value: string, legacy: boolean): Record<string, string> {
  const next = { ...params, [key]: value };
  if (legacy) delete next[LEGACY_MODEL_KEY];
  return next;
}
