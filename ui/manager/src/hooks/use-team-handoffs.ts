import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  acceptTeamHandoff,
  declineTeamHandoff,
  deleteWorkspaceBacklogTask,
  type AcceptHandoffRequest,
  type DeclineHandoffRequest,
} from "@eddi/app-kit/lib/api/team-handoffs";
import { WORKSPACE_KEY } from "@/hooks/use-group-workspace";

/**
 * Intake decisions for a group's workspace. The inbox lives on the workspace
 * document itself (`workspace.handoffs`), which `useGroupWorkspace` already
 * fetches and polls, so each mutation only has to invalidate that one query.
 */
export function useAcceptTeamHandoff(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ handoffId, ...request }: AcceptHandoffRequest & { handoffId: string }) =>
      acceptTeamHandoff(groupId!, handoffId, request),
    onSuccess: () => void qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}

export function useDeclineTeamHandoff(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ handoffId, ...request }: DeclineHandoffRequest & { handoffId: string }) =>
      declineTeamHandoff(groupId!, handoffId, request),
    onSuccess: () => void qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}

export function useDeleteWorkspaceBacklogTask(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => deleteWorkspaceBacklogTask(groupId!, taskId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: [...WORKSPACE_KEY, groupId] }),
  });
}
