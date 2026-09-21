import { useQuery } from "@tanstack/react-query";
import { getJsonSchema, getAgentJsonSchema, getWorkflowJsonSchema } from "@/lib/api/schemas";
import { getResourceType } from "@/lib/api/resources";

/**
 * Fetch the JSON Schema for a resource type slug.
 * Returns undefined while loading or on error.
 * Schemas are static, so staleTime is Infinity.
 */
export function useJsonSchema(typeSlug: string | undefined) {
  return useQuery({
    queryKey: ["jsonSchema", typeSlug],
    queryFn: () => {
      if (!typeSlug) throw new Error("No type slug");
      const rt = getResourceType(typeSlug);
      if (!rt) throw new Error(`Unknown resource type: ${typeSlug}`);
      return getJsonSchema(rt);
    },
    enabled: !!typeSlug,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}

/**
 * Fetch the JSON Schema for agent configurations.
 */
export function useAgentJsonSchema() {
  return useQuery({
    queryKey: ["jsonSchema", "agent"],
    queryFn: getAgentJsonSchema,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}

/**
 * Fetch the JSON Schema for package configurations.
 */
export function useWorkflowJsonSchema() {
  return useQuery({
    queryKey: ["jsonSchema", "package"],
    queryFn: getWorkflowJsonSchema,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}
