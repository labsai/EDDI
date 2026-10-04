import { useEffect } from "react";
import { useQuery, useQueries, useInfiniteQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { ENVIRONMENTS, type Environment } from "@/lib/constants";
import { withAnyDeployedVersion } from "@/lib/deployment-environments";
import { updateDescriptor } from "@/lib/api/descriptors";
import { agentKeys } from "@/lib/query-keys";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import {
  getAgentDescriptors,
  getAgentDescriptorsWithVersions,
  getAgent,
  createAgent,
  updateAgent,
  deleteAgent,
  duplicateAgent,
  deployAgent,
  undeployAgent,
  getDeploymentStatus,
  getDeploymentStatuses,
  getDeploymentImpact,
  listDeploymentStatuses,
  type AgentDeploymentSummary,
  type Agent,
  type AgentDescriptor,
  type EnvironmentStatus,
  type Ownership,
  parseResourceUri,
} from "@/lib/api/agents";

const PAGE_SIZE = 50;

export function useAgentDescriptors(
  limit = 20,
  index = 0,
  filter = ""
) {
  return useQuery({
    queryKey: agentKeys.descriptors(limit, index, filter),
    queryFn: () => getAgentDescriptors(limit, index, filter),
  });
}

/**
 * Infinite-scroll agent list.
 *
 * The page param is a PAGE INDEX, not a row offset: the backend skips
 * `index * limit` rows (`DescriptorStore.readDescriptors`). Sending
 * `allPages.length * PAGE_SIZE` made page 2 skip 2,500 rows, so the list stopped
 * at 50 — and the sync page, which matches against these pages, created
 * duplicates of every local agent past the first 50.
 */
export function useInfiniteAgentDescriptors(filter = "", space = "", ownership: Ownership = "", keySuffix?: string) {
  return useInfiniteQuery({
    // The space and ownership are part of the key: switching either must
    // refetch rather than re-render a cached page belonging to the previous one.
    queryKey: keySuffix
      ? [...agentKeys.descriptorsInfinite(filter), space, ownership, keySuffix]
      : [...agentKeys.descriptorsInfinite(filter), space, ownership],
    queryFn: ({ pageParam = 0 }) => getAgentDescriptors(PAGE_SIZE, pageParam, filter, space, ownership),
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      // If we got a full page, there are probably more
      if (lastPage.length === PAGE_SIZE) {
        return allPages.length;
      }
      return undefined; // no more pages
    },
  });
}

/**
 * Every local agent, for pickers and matchers that must see the whole list —
 * the sync page's auto-match by name, the import dialog's target pickers.
 *
 * Those used `useInfiniteAgentDescriptors` and never asked for a second page,
 * so an agent past the first 50 could not be picked and was never matched: the
 * sync then created a duplicate of it. This keeps fetching until the backend
 * returns a short page (or a page fails). `isComplete` says whether the list is
 * whole yet.
 */
export function useAllAgentDescriptors() {
  // Its own cache entry: sharing the Agents list's key would leave that list
  // holding every page after a visit to Sync, and each later invalidation
  // (deploy, save) would re-fetch all of them one page after another.
  const query = useInfiniteAgentDescriptors("", "", "", "all-pages");
  const { hasNextPage, isFetchingNextPage, isError, fetchNextPage } = query;
  // The page count is a dependency on purpose: the in-flight render can be
  // batched away, so after a page lands every other dependency may read exactly
  // as before (hasNextPage true, not fetching) and the effect would never fire
  // again — the list stopped after two pages.
  const pageCount = query.data?.pages.length ?? 0;
  useEffect(() => {
    // cancelRefetch: false — a repeat call while a page is in flight joins it
    // instead of cancelling and restarting it.
    if (hasNextPage && !isFetchingNextPage && !isError) void fetchNextPage({ cancelRefetch: false });
  }, [hasNextPage, isFetchingNextPage, isError, fetchNextPage, pageCount]);
  return { ...query, isComplete: query.isSuccess && !hasNextPage };
}

export function useAgent(id: string, version?: number) {
  return useQuery({
    queryKey: [...agentKeys.all, id, version],
    queryFn: () => getAgent(id, version),
    enabled: !!id,
    placeholderData: keepPreviousData,
  });
}

export function useDeploymentStatus(agentId: string, version: number, environment = "production") {
  return useQuery({
    queryKey: [...agentKeys.all, "deployment", environment, agentId, version],
    queryFn: () => getDeploymentStatus(environment, agentId, version),
    enabled: !!agentId && version > 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      // Poll every 3s while deploying
      return query.state.data?.status === "IN_PROGRESS" ? 3000 : false;
    },
  });
}

/**
 * What deploying `version` in `environment` would do to the conversations on
 * the agent's other deployed versions — FOLLOW (they move to it on their next
 * turn) or STAY. Informational only: nothing should wait on it, so it does not
 * retry and callers render nothing while it loads or when it fails.
 */
export function useDeploymentImpact(agentId: string, version: number, environment = "production") {
  return useQuery({
    queryKey: agentKeys.deploymentImpact(environment, agentId, version),
    queryFn: () => getDeploymentImpact(environment, agentId, version),
    enabled: !!agentId && version > 0,
    retry: false,
    staleTime: 10_000,
  });
}

export function useAgentVersions(agentId: string) {
  return useQuery({
    queryKey: [...agentKeys.all, "versions", agentId],
    queryFn: () => getAgentDescriptorsWithVersions(agentId),
    enabled: !!agentId,
    // `getAgentDescriptorsWithVersions` issues one descriptor query per version
    // and flattens the results, and the backend's `filter=` is a TEXT match
    // rather than an id lookup. Two consequences the four consumers of this hook
    // all inherited:
    //
    //  - the same version can come back from more than one of those queries, so
    //    the list held duplicates. Rendered as `key={v.version}` in the version
    //    picker, React logged "Encountered two children with the same key" and
    //    reserves the right to drop or duplicate those options.
    //  - a different agent whose id merely CONTAINS this one as a substring
    //    matches the filter, so its versions were offered in this agent's picker
    //    and selecting one navigated to a version that does not exist here.
    //
    // Both are fixed by resolving the id alongside the version and keeping only
    // this agent's, one entry per version.
    select: (descriptors) => {
      // `resource` and `callerLevel` ride along so the detail page can decide
      // which controls to offer (see `accessForDetail` in `@/lib/access`).
      const byVersion = new Map<
        number,
        {
          version: number;
          lastModifiedOn: number;
          name: string;
          description?: string;
          resource: string;
          callerLevel?: string;
        }
      >();
      for (const d of descriptors) {
        const { id, version } = parseResourceUri(d.resource);
        if (id !== agentId) continue;
        if (!byVersion.has(version)) {
          byVersion.set(version, {
            version,
            lastModifiedOn: d.lastModifiedOn,
            name: d.name,
            description: d.description,
            resource: d.resource,
            callerLevel: d.callerLevel,
          });
        }
      }
      return [...byVersion.values()].sort((a, b) => b.version - a.version);
    },
  });
}

export function useUpdateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      agent,
      compatible,
    }: {
      id: string;
      version: number;
      agent: Agent;
      /** See `UpdateAgentOptions.compatible` — omitted means breaking. */
      compatible?: boolean;
    }) => updateAgent(id, version, agent, compatible ? { compatible } : undefined),
    onSuccess: (result, { id, agent }) => {
      // Seed the version the save created with the document just written, so
      // a page that moves onto it renders the edit at once rather than the
      // previous version's placeholder.
      const newVersion = parseVersionFromLocation(result?.location);
      if (newVersion !== null) {
        queryClient.setQueryData([...agentKeys.all, id, newVersion], agent);
      }
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

/**
 * Per-environment deployment status of an agent — at `version`, except that an
 * environment with nothing at `version` (NOT_FOUND) reports whichever older
 * version is still live there (flagged with `deployedVersion`; see
 * `withAnyDeployedVersion`). An ERROR at `version` is never covered up.
 *
 * The environment-wide listing is only fetched when some environment has
 * nothing at `version`, and is shared by key across every card on the page, so
 * a list of 50 agents costs one listing per environment, not 50.
 */
export function useDeploymentStatuses(agentId: string, version: number) {
  const exact = useQuery({
    queryKey: [...agentKeys.all, "deploymentStatuses", agentId, version],
    queryFn: () => getDeploymentStatuses(agentId, version),
    enabled: !!agentId && version > 0,
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data?.some((d) => d.status === "IN_PROGRESS")) return 3000;
      return false;
    },
  });
  // Only NOT_FOUND is ever replaced (see withAnyDeployedVersion), so only it
  // needs the listing.
  const needsListing = !!exact.data?.some((s) => s.status === "NOT_FOUND");
  const listings = useQueries({
    queries: ENVIRONMENTS.map((environment) => ({
      queryKey: [...agentKeys.all, "deploymentListing", environment],
      queryFn: () => listDeploymentStatuses(environment),
      enabled: needsListing,
      staleTime: 10_000,
    })),
  });
  const deployed: Partial<Record<Environment, AgentDeploymentSummary[] | undefined>> = {};
  ENVIRONMENTS.forEach((environment, i) => {
    deployed[environment] = listings[i]?.data;
  });
  return { ...exact, data: withAnyDeployedVersion(exact.data, deployed, agentId, version) };
}

/**
 * What `useCreateAgent` resolves with. `namingFailed` is set when the agent
 * WAS created but the follow-up that stores its name and description failed:
 * the caller must treat it as created — retrying creates a duplicate.
 */
export interface CreateAgentResult {
  location: string;
  namingFailed?: boolean;
}

export function useCreateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      agent,
      name,
      description,
    }: {
      agent: Agent;
      name?: string;
      description?: string;
    }): Promise<CreateAgentResult> => {
      const response = await createAgent(agent);
      if ((name || description) && response.location) {
        // Location header is a URL path (e.g. /agentstore/agents/id?version=1),
        // not an eddi:// resource URI, so we parse it with a dummy base.
        const url = new URL(response.location, "http://dummy");
        const parts = url.pathname.split("/").filter(Boolean);
        const id = parts[parts.length - 1]!;
        const version = parseInt(url.searchParams.get("version") || "1", 10);
        try {
          await updateDescriptor(id, version, { name, description });
        } catch {
          // The agent exists. Failing here made the dialog show an error, and
          // the user's retry created a second agent.
          return { ...response, namingFailed: true };
        }
      }
      return response;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

/**
 * Rename an agent (name and description live on its descriptor, not in the
 * agent document, so this does not create a new agent version).
 */
export function useUpdateAgentDescriptor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      name,
      description,
    }: {
      id: string;
      version: number;
      name: string;
      description: string;
    }) => updateDescriptor(id, version, { name, description }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
      queryClient.invalidateQueries({ queryKey: ["studio", "descriptors"] });
    },
  });
}

export function useDeleteAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      deleteAgent(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDuplicateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      deepCopy,
    }: {
      id: string;
      version: number;
      deepCopy?: boolean;
    }) => duplicateAgent(id, version, deepCopy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useDeployAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "production",
      agentId,
      version,
    }: {
      environment?: string;
      agentId: string;
      version: number;
    }) => deployAgent(environment, agentId, version),
    onMutate: async ({ environment = "production", agentId, version }) => {
      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];
      await queryClient.cancelQueries({ queryKey: depKey });
      await queryClient.cancelQueries({ queryKey: depsKey });

      const prevDep = queryClient.getQueryData(depKey);
      const prevDeps = queryClient.getQueryData(depsKey);

      queryClient.setQueryData(depKey, (old: Record<string, unknown> | undefined) => ({ ...old, status: "IN_PROGRESS" }));
      queryClient.setQueryData(depsKey, (old: EnvironmentStatus[] | undefined) =>
        old ? old.map((s) => (s.environment === environment ? { ...s, status: "IN_PROGRESS" as const } : s)) : undefined
      );

      return { prevDep, prevDeps, depKey, depsKey };
    },
    onError: (_err, _vars, context) => {
      if (context) {
        queryClient.setQueryData(context.depKey, context.prevDep);
        queryClient.setQueryData(context.depsKey, context.prevDeps);
      }
    },
    onSuccess: (_, { environment = "production", agentId, version }) => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
      queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];

      [1000, 2500, 4500, 7000].forEach((delay) => {
        setTimeout(() => {
          queryClient.invalidateQueries({ queryKey: depKey });
          queryClient.invalidateQueries({ queryKey: depsKey });
        }, delay);
      });
    },
  });
}

export function useUndeployAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "production",
      agentId,
      version,
      endAllActiveConversations,
      undeployAllPreviousVersions,
    }: {
      environment?: string;
      agentId: string;
      version: number;
      endAllActiveConversations?: boolean;
      undeployAllPreviousVersions?: boolean;
    }) =>
      undeployAgent(environment, agentId, version, {
        endAllActiveConversations,
        undeployAllPreviousVersions,
      }),
    onMutate: async ({ environment = "production", agentId, version }) => {
      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];
      await queryClient.cancelQueries({ queryKey: depKey });
      await queryClient.cancelQueries({ queryKey: depsKey });

      const prevDep = queryClient.getQueryData(depKey);
      const prevDeps = queryClient.getQueryData(depsKey);

      queryClient.setQueryData(depKey, (old: Record<string, unknown> | undefined) => ({ ...old, status: "NOT_FOUND" }));
      queryClient.setQueryData(depsKey, (old: EnvironmentStatus[] | undefined) =>
        old ? old.map((s) => (s.environment === environment ? { ...s, status: "NOT_FOUND" as const } : s)) : undefined
      );

      return { prevDep, prevDeps, depKey, depsKey };
    },
    onError: (_err, _vars, context) => {
      if (context) {
        queryClient.setQueryData(context.depKey, context.prevDep);
        queryClient.setQueryData(context.depsKey, context.prevDeps);
      }
    },
    onSuccess: (_, { environment = "production", agentId, version }) => {
      queryClient.invalidateQueries({ queryKey: agentKeys.all });
      queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });

      const depKey = [...agentKeys.all, "deployment", environment, agentId, version];
      const depsKey = [...agentKeys.all, "deploymentStatuses", agentId, version];

      [1000, 2500].forEach((delay) => {
        setTimeout(() => {
          queryClient.invalidateQueries({ queryKey: depKey });
          queryClient.invalidateQueries({ queryKey: depsKey });
        }, delay);
      });
    },
  });
}

/** Group agent descriptors by resource ID, keeping the latest version per agent */
export function groupAgentsByName(
  agents: AgentDescriptor[]
): (AgentDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    AgentDescriptor & { id: string; version: number }
  >();

  for (const agent of agents) {
    const { id, version } = parseResourceUri(agent.resource);
    const existing = grouped.get(id);
    if (!existing || version > existing.version) {
      grouped.set(id, { ...agent, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => b.lastModifiedOn - a.lastModifiedOn
  );
}
