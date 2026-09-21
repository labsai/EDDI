import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listGroupTemplates,
  readGroupTemplate,
  instantiateGroupTemplate,
  type InstantiateTemplateRequest,
} from "@/lib/api/group-templates";

const TEMPLATES_KEY = ["group-templates"] as const;
/** Matches `use-groups.ts`'s own GROUPS_KEY — instantiation creates a real group. */
const GROUPS_KEY = ["groups"] as const;

/** List every packaged template's manifest, in the backend's curated order. */
export function useGroupTemplates() {
  return useQuery({
    queryKey: TEMPLATES_KEY,
    queryFn: () => listGroupTemplates(),
    staleTime: Infinity, // packaged templates are static per deploy
  });
}

/** One template's manifest + packaged (still-placeholder) config, for the instantiate flow. */
export function useGroupTemplate(templateId: string | undefined) {
  return useQuery({
    queryKey: [...TEMPLATES_KEY, templateId],
    queryFn: () => readGroupTemplate(templateId!),
    enabled: !!templateId,
    staleTime: Infinity,
  });
}

export function useInstantiateGroupTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, request }: { templateId: string; request: InstantiateTemplateRequest }) =>
      instantiateGroupTemplate(templateId, request),
    // Instantiation saves through the ordinary group store, so the groups list
    // is now stale. Without this a user who creates a group from a template and
    // navigates back to the list does not see it until the query goes stale on
    // its own — the same invalidation `useCreateGroup` does.
    onSuccess: () => qc.invalidateQueries({ queryKey: GROUPS_KEY }),
  });
}
