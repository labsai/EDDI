import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getWorkspaceSettings,
  updateWorkspaceSettings,
  type WorkspaceSettings,
} from "@/lib/api/workspaces";
import {
  deleteSpaceSecret,
  deleteSpaceVariable,
  listSpaceSecrets,
  listSpaceVariables,
  storeSpaceSecret,
  storeSpaceVariable,
} from "@/lib/api/space-resources";

const keys = {
  settings: ["workspaces", "settings"] as const,
  secrets: (space: string) => ["workspaces", "space-secrets", space] as const,
  variables: (space: string) => ["workspaces", "space-variables", space] as const,
};

/** The runtime workspace settings. Administrators only — others get a 403. */
export function useWorkspaceSettings(enabled: boolean) {
  return useQuery<WorkspaceSettings>({
    queryKey: keys.settings,
    queryFn: getWorkspaceSettings,
    enabled,
    retry: false,
  });
}

export function useUpdateWorkspaceSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: updateWorkspaceSettings,
    onSuccess: (settings) => {
      queryClient.setQueryData(keys.settings, settings);
      // The default space shapes what GET /workspaces reports as defaultSpace.
      void queryClient.invalidateQueries({ queryKey: ["workspaces", "info"] });
    },
  });
}

export function useSpaceSecrets(space: string | null) {
  return useQuery({
    queryKey: keys.secrets(space ?? ""),
    queryFn: () => listSpaceSecrets(space!),
    enabled: !!space,
    retry: false,
  });
}

export function useStoreSpaceSecret(space: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (args: { keyName: string; value: string; description?: string }) =>
      storeSpaceSecret(space, args.keyName, args.value, args.description),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.secrets(space) }),
  });
}

export function useDeleteSpaceSecret(space: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (keyName: string) => deleteSpaceSecret(space, keyName),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.secrets(space) }),
  });
}

export function useSpaceVariables(space: string | null) {
  return useQuery({
    queryKey: keys.variables(space ?? ""),
    queryFn: () => listSpaceVariables(space!),
    enabled: !!space,
    retry: false,
  });
}

export function useStoreSpaceVariable(space: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (args: { key: string; value: string; description?: string }) =>
      storeSpaceVariable(space, args.key, args.value, args.description),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.variables(space) }),
  });
}

export function useDeleteSpaceVariable(space: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (key: string) => deleteSpaceVariable(space, key),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.variables(space) }),
  });
}
