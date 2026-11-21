import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AGENT_MUTATION_INVALIDATIONS, invalidateAll } from "../lib/query-keys";
import {
  setupAgent,
  createApiAgent,
  type SetupAgentRequest,
  type CreateApiAgentRequest,
  type SetupResult,
} from "../lib/api/agent-setup";

export function useSetupAgent() {
  const queryClient = useQueryClient();
  return useMutation<SetupResult, Error, SetupAgentRequest>({
    mutationFn: setupAgent,
    onSuccess: () => {
      invalidateAll(queryClient, AGENT_MUTATION_INVALIDATIONS);
    },
  });
}

export function useCreateApiAgent() {
  const queryClient = useQueryClient();
  return useMutation<SetupResult, Error, CreateApiAgentRequest>({
    mutationFn: createApiAgent,
    onSuccess: () => {
      invalidateAll(queryClient, AGENT_MUTATION_INVALIDATIONS);
    },
  });
}
