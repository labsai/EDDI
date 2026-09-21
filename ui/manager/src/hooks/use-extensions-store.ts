import { useQuery } from "@tanstack/react-query";
import { getExtensionTypes } from "@/lib/api/extensions";
import type { ExtensionDescriptor } from "@/lib/api/extensions";

const EXTENSIONS_STORE_KEY = ["extensionStore"] as const;

/**
 * Well-known extension types that should always appear in the Add Task dialog
 * even if the backend doesn't list them in /extensionstore/extensions.
 */
const WELL_KNOWN_TYPES: ExtensionDescriptor[] = [
  {
    type: "eddi://ai.labs.mcpcalls",
    displayName: "MCP Calls",
    configs: {
      uri: {
        displayName: "Resource URI",
        fieldType: "URI",
        isOptional: false,
        defaultValue: null,
      },
    },
    extensions: {},
  },
];

/**
 * Fetch available extension types from /extensionstore/extensions.
 * Merges in well-known types that the backend may not list.
 * Data is fairly static so we can cache aggressively.
 */
export function useExtensionTypes(filter = "") {
  return useQuery({
    queryKey: [...EXTENSIONS_STORE_KEY, { filter }],
    queryFn: async () => {
      const types = await getExtensionTypes(filter);
      // Merge in well-known types that the backend didn't return
      const existing = new Set(types.map((t) => t.type));
      for (const wk of WELL_KNOWN_TYPES) {
        if (!existing.has(wk.type)) {
          types.push(wk);
        }
      }
      return types;
    },
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}
