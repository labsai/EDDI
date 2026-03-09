import { useQuery } from "@tanstack/react-query";
import { getJsonSchema, getBotJsonSchema, getPackageJsonSchema } from "@/lib/api/schemas";
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
 * Fetch the JSON Schema for bot configurations.
 */
export function useBotJsonSchema() {
  return useQuery({
    queryKey: ["jsonSchema", "bot"],
    queryFn: getBotJsonSchema,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}

/**
 * Fetch the JSON Schema for package configurations.
 */
export function usePackageJsonSchema() {
  return useQuery({
    queryKey: ["jsonSchema", "package"],
    queryFn: getPackageJsonSchema,
    staleTime: Infinity,
    gcTime: Infinity,
    retry: 1,
  });
}
