import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getConsolidationState,
  getLessonHistory,
  getSharedLessons,
  listGroupLessons,
  runConsolidation,
  scheduleConsolidation,
  unscheduleConsolidation,
} from "@/lib/api/group-lessons";

export const LESSONS_KEY = ["group-lessons"] as const;

export function useGroupLessons(groupId: string | undefined) {
  return useQuery({ queryKey: [...LESSONS_KEY, groupId, "own"], queryFn: () => listGroupLessons(groupId!), enabled: !!groupId });
}

export function useSharedLessons(groupId: string | undefined) {
  return useQuery({ queryKey: [...LESSONS_KEY, groupId, "shared"], queryFn: () => getSharedLessons(groupId!), enabled: !!groupId });
}

export function useLessonHistory(groupId: string | undefined, enabled = true) {
  return useQuery({ queryKey: [...LESSONS_KEY, groupId, "history"], queryFn: () => getLessonHistory(groupId!), enabled: !!groupId && enabled });
}

export function useConsolidationState(groupId: string | undefined) {
  return useQuery({ queryKey: [...LESSONS_KEY, groupId, "consolidation"], queryFn: () => getConsolidationState(groupId!), enabled: !!groupId });
}

export function useScheduleConsolidation(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ cronExpression, timeZone }: { cronExpression: string; timeZone?: string }) => scheduleConsolidation(groupId!, cronExpression, timeZone),
    onSuccess: () => void qc.invalidateQueries({ queryKey: [...LESSONS_KEY, groupId] }),
  });
}

export function useUnscheduleConsolidation(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unscheduleConsolidation(groupId!),
    onSuccess: () => void qc.invalidateQueries({ queryKey: [...LESSONS_KEY, groupId] }),
  });
}

/** Run now; a real run changes the lessons and the history, so both are refetched. */
export function useRunConsolidation(groupId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (dryRun: boolean) => runConsolidation(groupId!, dryRun),
    onSuccess: (result) => {
      if (!result.dryRun) void qc.invalidateQueries({ queryKey: [...LESSONS_KEY, groupId] });
    },
  });
}
