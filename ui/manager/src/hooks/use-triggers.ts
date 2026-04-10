import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getAllTriggers,
  createTrigger,
  updateTrigger,
  deleteTrigger,
  type AgentTriggerConfiguration,
} from "@/lib/api/triggers";

export function useTriggers() {
  return useQuery<AgentTriggerConfiguration[], Error>({
    queryKey: ["triggers"],
    queryFn: getAllTriggers,
  });
}

export function useCreateTrigger() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, AgentTriggerConfiguration>({
    mutationFn: (config) => createTrigger(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["triggers"] });
    },
  });
}

export function useUpdateTrigger() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, { intent: string; config: AgentTriggerConfiguration }>({
    mutationFn: ({ intent, config }) => updateTrigger(intent, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["triggers"] });
    },
  });
}

export function useDeleteTrigger() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (intent) => deleteTrigger(intent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["triggers"] });
    },
  });
}
