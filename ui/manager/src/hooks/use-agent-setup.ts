import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  setupAgent,
  createApiAgent,
  type SetupAgentRequest,
  type CreateApiAgentRequest,
  type SetupResult,
} from "@/lib/api/agent-setup";

export function useSetupAgent() {
  const queryClient = useQueryClient();
  return useMutation<SetupResult, Error, SetupAgentRequest>({
    mutationFn: setupAgent,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["agents"] });
    },
  });
}

export function useCreateApiAgent() {
  const queryClient = useQueryClient();
  return useMutation<SetupResult, Error, CreateApiAgentRequest>({
    mutationFn: createApiAgent,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["agents"] });
    },
  });
}
