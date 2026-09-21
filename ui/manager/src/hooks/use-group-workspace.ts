import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getGroupWorkspace,
  addWorkspaceBacklogTask,
  addWorkspaceCadence,
  deleteWorkspaceCadence,
  type AddBacklogTaskRequest,
  type AddCadenceRequest,
} from "@/lib/api/group-workspace";

const WORKSPACE_KEY = ["group-workspace"] as const;

/** A group's standing-team workspace — read-repairs on every fetch (see `getGroupWorkspace`'s doc). */
export function useGroupWorkspace(groupId: string | undefined) {
  return useQuery({
    queryKey: [...WORKSPACE_KEY, groupId],
    queryFn: () => getGroupWorkspace(groupId!),
    enabled: !!groupId,
    // The read itself can mutate (writeback), so a background poll keeps a
    // dashboard left open catching up on cadence fires without a manual refresh.
    refetchInterval: 30_000,
  });
}

export function useAddWorkspaceBacklogTask(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: AddBacklogTaskRequest) => addWorkspaceBacklogTask(groupId!, request),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}

export function useAddWorkspaceCadence(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: AddCadenceRequest) => addWorkspaceCadence(groupId!, request),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}

export function useDeleteWorkspaceCadence(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (cadenceId: string) => deleteWorkspaceCadence(groupId!, cadenceId),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}
