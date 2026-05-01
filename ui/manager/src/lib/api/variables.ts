import { api } from "../api-client";

/* ─── Types ─── */

export interface GlobalVariable {
  key: string;
  value: string;
  description?: string | null;
  exportable?: boolean;
}

/* ─── API Functions ─── */

const BASE = "/variablestore/variables";

/** List all global variables. */
export async function listVariables(): Promise<GlobalVariable[]> {
  return api.get<GlobalVariable[]>(BASE);
}

/** Get a single variable by key. */
export async function getVariable(key: string): Promise<GlobalVariable> {
  return api.get<GlobalVariable>(`${BASE}/${encodeURIComponent(key)}`);
}

/** Create or update a variable. */
export async function upsertVariable(
  key: string,
  variable: GlobalVariable,
): Promise<void> {
  await api.put(`${BASE}/${encodeURIComponent(key)}`, variable);
}

/** Delete a variable by key. */
export async function deleteVariable(key: string): Promise<void> {
  await api.delete(`${BASE}/${encodeURIComponent(key)}`);
}

/* ─── Validation ─── */

/** Key must match [a-zA-Z0-9_.-]+ */
const KEY_PATTERN = /^[a-zA-Z0-9_.-]+$/;

export function isValidVariableKey(key: string): boolean {
  return KEY_PATTERN.test(key);
}
