import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listVariables,
  upsertVariable,
  deleteVariable,
} from "@/lib/api/variables";
import type { GlobalVariable } from "@/lib/api/variables";

/* ─── Query Keys ─── */

// TODO: When multi-tenant support is added, include tenantId in queryKey
// and scope invalidation per tenant to prevent cross-tenant cache collisions.
const variableKeys = {
  all: ["variables"] as const,
  list: ["variables", "list"] as const,
};

/* ─── Hooks ─── */

/** List all global variables. */
export function useVariables() {
  return useQuery({
    queryKey: variableKeys.list,
    queryFn: () => listVariables(),
  });
}

/** Raised by a `createOnly` upsert whose key is already taken. */
export class VariableExistsError extends Error {
  constructor(readonly key: string) {
    super(`A variable named "${key}" already exists`);
    this.name = "VariableExistsError";
  }
}

/**
 * Create or update a variable. Invalidates list on success.
 *
 * `createOnly` is for the "Add Variable" form. The endpoint is an upsert, so
 * without it an existing key was silently replaced — value, description and
 * export flag — from a dialog that says "Add". The key is checked against a
 * fresh read rather than the cached list; the backend has no create-only
 * precondition, so a concurrent writer can still win.
 */
export function useUpsertVariable() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (args: { key: string; variable: GlobalVariable; createOnly?: boolean }) => {
      if (args.createOnly) {
        const existing = await listVariables();
        if (existing.some((v) => v.key === args.key)) {
          throw new VariableExistsError(args.key);
        }
      }
      return upsertVariable(args.key, args.variable);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: variableKeys.list });
    },
  });
}

/** Delete a variable. Invalidates list on success. */
export function useDeleteVariable() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (key: string) => deleteVariable(key),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: variableKeys.list });
    },
  });
}
