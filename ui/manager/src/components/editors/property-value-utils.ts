/**
 * The typed value slots of a property instruction — `property.json` rows and
 * every httpcall / MCP / LLM `preRequest` / `postResponse` instruction. Since
 * EDDI 6.6 both run through one engine (`PropertyInstructionExecutor`) and
 * accept the same slots, including `valueLong` and `valueDouble`.
 *
 * The editors used to show `valueString` only. A row carrying a typed value
 * rendered an empty value field, and typing into it wrote `valueString` next to
 * the typed slot — which the engine then let win, so what the field showed was
 * never what ran.
 */
export interface TypedPropertyValue {
  valueString?: string;
  valueObject?: Record<string, unknown>;
  valueList?: unknown[];
  valueInt?: number;
  /** A whole number beyond the `int` range (EDDI 6.6+). */
  valueLong?: number;
  valueFloat?: number;
  /** A decimal at full precision (EDDI 6.6+); `valueFloat` keeps about 7 digits. */
  valueDouble?: number;
  valueBoolean?: boolean;
}

export type PropertyValueKind = "string" | "int" | "long" | "float" | "double" | "boolean" | "object" | "list";

export const VALUE_FIELD: Record<PropertyValueKind, keyof TypedPropertyValue> = {
  string: "valueString",
  object: "valueObject",
  list: "valueList",
  int: "valueInt",
  long: "valueLong",
  float: "valueFloat",
  double: "valueDouble",
  boolean: "valueBoolean",
};

/**
 * The order the engine applies the typed slots in: each one set overwrites the
 * value of the one before (`PropertyInstructionExecutor.valueOf`), so the LAST
 * slot set in this order is the one that runs. `valueString` comes first and
 * loses to every typed slot.
 */
const ENGINE_ORDER: PropertyValueKind[] = ["string", "object", "list", "int", "long", "float", "double", "boolean"];

/** Kinds an author can pick in the form. Objects and lists are edited in the JSON tab. */
export const EDITABLE_KINDS: PropertyValueKind[] = ["string", "int", "long", "double", "float", "boolean"];

/** Kinds the form shows but does not edit. */
export const JSON_ONLY_KINDS: PropertyValueKind[] = ["object", "list"];

export const INT_MIN = -2147483648;
export const INT_MAX = 2147483647;

function isSet(p: TypedPropertyValue, kind: PropertyValueKind): boolean {
  const v = p[VALUE_FIELD[kind]];
  if (kind === "string") return typeof v === "string" && v !== "";
  return v !== undefined && v !== null;
}

/** Every slot that carries a value, in engine order. */
export function setValueKinds(p: TypedPropertyValue): PropertyValueKind[] {
  return ENGINE_ORDER.filter((k) => isSet(p, k));
}

/** The slot whose value the engine stores — the last set in engine order, else `string`. */
export function effectiveValueKind(p: TypedPropertyValue): PropertyValueKind {
  const set = setValueKinds(p);
  return set.length > 0 ? set[set.length - 1]! : "string";
}

/**
 * The instruction with exactly one value slot: `kind`, holding `value`. Every
 * other slot is REMOVED (not set to null), so switching back to text on an
 * older backend never sends a field its strict parser does not know.
 */
export function withValue<T extends TypedPropertyValue>(p: T, kind: PropertyValueKind, value: unknown): T {
  const next = { ...p } as Record<string, unknown>;
  for (const field of Object.values(VALUE_FIELD)) delete next[field];
  if (value !== undefined) {
    next[VALUE_FIELD[kind]] = value;
  } else if (kind === "string") {
    next.valueString = "";
  }
  return next as T;
}

/**
 * The value carried over when the author switches the type, so "42" typed as
 * text becomes 42 as an integer rather than an empty field. `undefined` when it
 * does not convert.
 */
export function convertValue(p: TypedPropertyValue, to: PropertyValueKind): unknown {
  const from = effectiveValueKind(p);
  const current = p[VALUE_FIELD[from]];
  if (current === undefined || current === null) return undefined;
  const text = typeof current === "string" ? current.trim() : JSON.stringify(current);
  switch (to) {
    case "string":
      return typeof current === "string" ? current : text;
    case "int":
    case "long": {
      const n = Number(text);
      return text !== "" && Number.isInteger(n) ? n : undefined;
    }
    case "float":
    case "double": {
      const n = Number(text);
      return text !== "" && Number.isFinite(n) ? n : undefined;
    }
    case "boolean":
      return text === "true" ? true : text === "false" ? false : undefined;
    default:
      return undefined;
  }
}

/** A problem with a numeric slot's value the server would refuse or round. */
export type NumericProblem = "intRange" | "unsafeLong" | null;

export function numericProblem(kind: PropertyValueKind, value: unknown): NumericProblem {
  if (typeof value !== "number") return null;
  if (kind === "int" && (value < INT_MIN || value > INT_MAX)) return "intRange";
  // JSON numbers are doubles in the browser: past 2^53 the value the field
  // shows and the value saved are no longer the same whole number.
  if (kind === "long" && !Number.isSafeInteger(value)) return "unsafeLong";
  return null;
}
