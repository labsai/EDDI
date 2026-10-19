import { api } from "../api-client";
import { nameSeg } from "./path-segment";

/* ─── Types ─── */

export interface GlobalVariable {
  key: string;
  value: string;
  description?: string | null;
  exportable?: boolean;
}

/* ─── API Functions ─── */

const BASE = "/variablestore/variables";
const DEFAULT_TENANT = "default";

/** List all global variables for the default tenant. */
export async function listVariables(
  tenantId = DEFAULT_TENANT,
): Promise<GlobalVariable[]> {
  return api.get<GlobalVariable[]>(
    `${BASE}/${nameSeg(tenantId)}`,
  );
}

/** Get a single variable by key. */
export async function getVariable(
  key: string,
  tenantId = DEFAULT_TENANT,
): Promise<GlobalVariable> {
  return api.get<GlobalVariable>(
    `${BASE}/${nameSeg(tenantId)}/${nameSeg(key)}`,
  );
}

/** Create or update a variable. */
export async function upsertVariable(
  key: string,
  variable: GlobalVariable,
  tenantId = DEFAULT_TENANT,
): Promise<void> {
  await api.put(
    `${BASE}/${nameSeg(tenantId)}/${nameSeg(key)}`,
    variable,
  );
}

/** Delete a variable by key. */
export async function deleteVariable(
  key: string,
  tenantId = DEFAULT_TENANT,
): Promise<void> {
  await api.delete(
    `${BASE}/${nameSeg(tenantId)}/${nameSeg(key)}`,
  );
}

/* ─── Validation ─── */

/** Key must match [a-zA-Z0-9_.-]+ */
const KEY_PATTERN = /^[a-zA-Z0-9_.-]+$/;

export function isValidVariableKey(key: string): boolean {
  return KEY_PATTERN.test(key);
}
