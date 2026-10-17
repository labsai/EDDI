import type { Expectation, PropertyExpectation } from "@/lib/api/agent-tests";

export type PropertyKind = "equals" | "matches" | "exists" | "absent";

/** Which of the four mutually exclusive property checks a row currently holds. */
export function propertyKind(p: PropertyExpectation): PropertyKind {
  if (p.absent) return "absent";
  if (p.exists) return "exists";
  if (p.matches !== undefined) return "matches";
  return "equals";
}

function isSet(v: unknown): boolean {
  if (v === undefined || v === null || v === "") return false;
  if (Array.isArray(v)) return v.length > 0;
  if (typeof v === "object") return Object.keys(v as object).length > 0;
  return true;
}

/** How many expectation fields a turn sets — shown on the collapsed turn. */
export function countExpectations(e: Expectation | undefined): number {
  return e ? Object.values(e).filter(isSet).length : 0;
}

/** Drops unset fields so an emptied expectation disappears from the JSON. */
export function cleanExpectation(e: Expectation): Expectation | undefined {
  const entries = Object.entries(e).filter(([, v]) => isSet(v));
  return entries.length > 0 ? (Object.fromEntries(entries) as Expectation) : undefined;
}

/** Pretty-print any expected/actual value; strings stay as they are. */
export function renderValue(v: unknown): string {
  if (v === undefined) return "—";
  if (typeof v === "string") return v;
  return JSON.stringify(v, null, 2);
}
