import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  readOperatorConfig,
  writeOperatorConfig,
  readOperatorStatus,
  deactivateOperator,
  resetOperator,
  provisionOperator,
  resolveAgentVersion,
  fetchOpenApiSpec,
  findMissingEndpoints,
  defaultOperatorConfig,
  type OperatorConfig,
} from "@/lib/api/operator";
import { undeployAgent, deleteAgent } from "@/lib/api/agents";
import { endpointsForScope } from "@/lib/operator/tool-scopes";
import { OPERATOR_PROMPT_BODY } from "@/lib/operator/system-prompt";

/* ─── Query Keys ─── */

export const operatorKeys = {
  all: ["operator"] as const,
  config: ["operator", "config"] as const,
  status: (agentId: string, version: number) =>
    ["operator", "status", agentId, version] as const,
};

/* ─── Config ─── */

/** The operator config blob. `null` means "never activated". */
export function useOperatorConfig() {
  return useQuery({
    queryKey: operatorKeys.config,
    queryFn: readOperatorConfig,
    staleTime: 30_000,
  });
}

/**
 * Deployment status of the operator agent.
 *
 * Only polls while the operator is enabled and fully provisioned — an agent id
 * without a version cannot be queried, since the endpoint requires one.
 */
export function useOperatorStatus(config: OperatorConfig | null | undefined) {
  const agentId = config?.agentId ?? "";
  const version = config?.version ?? 0;
  const ready = Boolean(config?.enabled && agentId && version > 0);
  return useQuery({
    queryKey: operatorKeys.status(agentId, version),
    queryFn: () => readOperatorStatus(config!),
    enabled: ready,
    refetchInterval: (query) =>
      query.state.data?.status === "IN_PROGRESS" ? 2_000 : 15_000,
  });
}

/* ─── Activation ─── */

/** Progress stages surfaced while activation runs. */
export type ActivationStage =
  | "idle"
  | "validating"
  | "provisioning"
  | "resolving-version"
  | "saving"
  | "done";

export interface ActivateParams {
  agentName: string;
  config: OperatorConfig;
  apiKey: string;
  baseUrl?: string;
  onStage?: (stage: ActivationStage) => void;
}

/**
 * Provision (or re-provision) the operator and persist the config.
 *
 * Idempotency: when a previous agent exists, `setup-api` creates a fresh one, so
 * the old one is removed afterwards rather than left orphaned. The config is
 * written only after the version resolves, so a half-provisioned operator never
 * ends up marked enabled.
 */
export function useActivateOperator() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (params: ActivateParams) => {
      const { agentName, config, apiKey, baseUrl, onStage } = params;

      // Fail before creating anything if the deployment's spec doesn't actually
      // expose the endpoints we intend to bind — otherwise the operator would
      // come up "READY" with silently missing tools.
      onStage?.("validating");
      const spec = await fetchOpenApiSpec();
      const missing = findMissingEndpoints(spec, endpointsForScope(config.scope));
      if (missing.length > 0) {
        throw new Error(
          `This EDDI deployment does not expose ${missing.length} endpoint(s) the operator needs: ${missing.join(", ")}`,
        );
      }

      onStage?.("provisioning");
      const result = await provisionOperator({ agentName, config, apiKey, baseUrl });

      onStage?.("resolving-version");
      const version = await resolveAgentVersion(result);

      onStage?.("saving");
      const next: OperatorConfig = {
        ...config,
        enabled: true,
        agentId: result.agentId,
        version,
      };
      await writeOperatorConfig(next);

      // Retire the agent this activation replaced, so repeated reconfiguration
      // doesn't accumulate deployed operators.
      if (config.agentId && config.agentId !== result.agentId) {
        try {
          await removeSupersededAgent(config);
        } catch {
          // Best-effort cleanup; the new operator is already live and saved.
        }
      }

      onStage?.("done");
      return next;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: operatorKeys.all });
    },
  });
}

/**
 * Remove a superseded operator agent without touching the config variable — by
 * this point the variable already points at the replacement.
 */
async function removeSupersededAgent(config: OperatorConfig): Promise<void> {
  if (!config.agentId || config.version == null) return;
  try {
    await undeployAgent(config.environment, config.agentId, config.version);
  } catch {
    // Already undeployed.
  }
  await deleteAgent(config.agentId, config.version, {
    cascade: true,
    permanent: true,
  });
}

/* ─── Kill switch ─── */

/** Undeploy the operator and mark it disabled. Reversible by re-activating. */
export function useDeactivateOperator() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: OperatorConfig) => deactivateOperator(config),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: operatorKeys.all });
    },
  });
}

/** Delete the operator agent and its config entirely. */
export function useResetOperator() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: OperatorConfig) => resetOperator(config),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: operatorKeys.all });
    },
  });
}

/* ─── Helpers ─── */

/** The config to seed the activation form with. */
export function seedConfig(existing: OperatorConfig | null | undefined): OperatorConfig {
  return existing ?? defaultOperatorConfig(OPERATOR_PROMPT_BODY);
}
