import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listVariables,
  upsertVariable,
  deleteVariable,
} from "@/lib/api/variables";
import type { GlobalVariable } from "@/lib/api/variables";

/* ─── Query Keys ─── */

const variableKeys = {
  all: ["variables"] as const,
  list: ["variables", "list"] as const,
};

/* ─── Hooks ─── */

/** List all global variables. */
export function useVariables() {
  return useQuery({
    queryKey: variableKeys.list,
    queryFn: listVariables,
  });
}

/** Create or update a variable. Invalidates list on success. */
export function useUpsertVariable() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { key: string; variable: GlobalVariable }) =>
      upsertVariable(args.key, args.variable),
    onSuccess: () => {
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
