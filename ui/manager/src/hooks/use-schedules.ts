import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getSchedules,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  enableSchedule,
  disableSchedule,
  fireNow,
  retryDeadLetter,
  dismissDeadLetter,
  getFireLogs,
  getFailedFires,
} from "@/lib/api/schedules";
import type { ScheduleConfiguration } from "@/lib/api/schedules";
import { patchQueries } from "@/lib/optimistic";

// ==================== Query Keys ====================

const KEYS = {
  all: ["schedules"] as const,
  list: (agentId?: string) => ["schedules", "list", agentId] as const,
  detail: (id: string) => ["schedules", "detail", id] as const,
  fireLogs: (id: string) => ["schedules", "fire-logs", id] as const,
  failed: ["schedules", "failed"] as const,
};

// ==================== Queries ====================

export function useSchedules(agentId?: string) {
  return useQuery({
    queryKey: KEYS.list(agentId),
    queryFn: () => getSchedules(agentId),
    refetchInterval: 10_000,
  });
}

export function useFireLogs(scheduleId: string, enabled = true) {
  return useQuery({
    queryKey: KEYS.fireLogs(scheduleId),
    queryFn: () => getFireLogs(scheduleId, 20),
    enabled: enabled && !!scheduleId,
    refetchInterval: 15_000,
  });
}

export function useFailedFires() {
  return useQuery({
    queryKey: KEYS.failed,
    queryFn: () => getFailedFires(50),
    refetchInterval: 15_000,
  });
}

// ==================== Mutations ====================

export function useCreateSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: createSchedule,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
    },
  });
}

export function useUpdateSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, config }: { id: string; config: Parameters<typeof updateSchedule>[1] }) =>
      updateSchedule(id, config),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
    },
  });
}

export function useDeleteSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: deleteSchedule,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
    },
  });
}

/** Key of the toggle mutation, so a page can see every toggle still in flight. */
export const TOGGLE_SCHEDULE_KEY = ["schedules", "toggle"] as const;

export function useToggleSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationKey: TOGGLE_SCHEDULE_KEY,
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      enable ? enableSchedule(id) : disableSchedule(id),
    // Flip the row at once; the request and the refetch follow behind it.
    onMutate: async ({ id, enable }) => {
      await patchQueries<ScheduleConfiguration[]>(qc, ["schedules", "list"], (list) =>
        list.map((s) => (s.id === id ? { ...s, enabled: enable } : s)),
      );
    },
    // Undo only this row, and only if it still shows this toggle. Restoring the
    // whole list snapshot would also undo another row's toggle that started
    // after this one.
    onError: (_err, { id, enable }) => {
      qc.setQueriesData<ScheduleConfiguration[]>({ queryKey: ["schedules", "list"] }, (list) =>
        list?.map((s) => (s.id === id && s.enabled === enable ? { ...s, enabled: !enable } : s)),
      );
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: KEYS.all });
    },
  });
}

export function useFireNow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fireNow,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
    },
  });
}

export function useRetryDeadLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: retryDeadLetter,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
      qc.invalidateQueries({ queryKey: KEYS.failed });
    },
  });
}

export function useDismissDeadLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: dismissDeadLetter,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
      qc.invalidateQueries({ queryKey: KEYS.failed });
    },
  });
}
