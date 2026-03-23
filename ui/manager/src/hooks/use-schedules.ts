import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getSchedules,
  getSchedule,
  createSchedule,
  updateSchedule,
  deleteSchedule,
  enableSchedule,
  disableSchedule,
  fireNow,
  retryDeadLetter,
  getFireLogs,
  getFailedFires,
} from "@/lib/api/schedules";

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

export function useSchedule(id: string) {
  return useQuery({
    queryKey: KEYS.detail(id),
    queryFn: () => getSchedule(id),
    enabled: !!id,
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

export function useToggleSchedule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, enable }: { id: string; enable: boolean }) =>
      enable ? enableSchedule(id) : disableSchedule(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.all });
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
