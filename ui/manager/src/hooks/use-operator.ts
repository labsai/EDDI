import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  readOperatorConfig,
  assertProvisioned,
  runOperatorCanary,
  reactivateOperator,
  type CanaryResult,
  writeOperatorConfig,
  readOperatorStatus,
  deactivateOperator,
  resetOperator,
  provisionOperator,
  resolveAgentVersion,
  fetchOpenApiSpec,
  findMissingEndpoints,
  defaultOperatorConfig,
  verifyGateInstalled,
  type GateVerificationResult,
  type OperatorConfig,
} from "@/lib/api/operator";
import { undeployAgent, deleteAgent } from "@/lib/api/agents";
import { endpointsForScope } from "@/lib/operator/tool-scopes";

/* ─── Query Keys ─── */

export const operatorKeys = {
  all: ["operator"] as const,
  config: ["operator", "config"] as const,
  status: (agentId: string, version: number) =>
    ["operator", "status", agentId, version] as const,
  gate: (agentId: string) => ["operator", "gate", agentId] as const,
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
  | "verifying-gate"
  | "canary"
  | "done";

/** What activation returns: the saved config plus the probe outcomes. */
export interface ActivationOutcome {
  config: OperatorConfig;
  canary: CanaryResult;
  gate: GateVerificationResult;
}

export interface ActivateParams {
  agentName: string;
  config: OperatorConfig;
  apiKey: string;
  baseUrl?: string;
  onStage?: (stage: ActivationStage) => void;
}

/**
 * Provision the operator from scratch and persist the config.
 *
 * `setup-api` always builds a new agent, so a re-provision retires the one it
 * replaced instead of leaving it deployed. Merely re-enabling a disabled
 * operator should go through `useReactivateOperator`, which redeploys the
 * existing agent rather than rebuilding it.
 *
 * Ordering matters: the config is written only once the agent is confirmed
 * deployed and its version resolved, so a half-provisioned operator is never
 * recorded as enabled.
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
      const result = await provisionOperator({ agentName, config, apiKey, baseUrl, spec });
      // 201 does not mean deployed, and the id can come back as "unknown".
      assertProvisioned(result);

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

      // Read the gate back from the document we just created — never trust that
      // sending hitlConfig means it landed. This is what actually proves
      // isWriteScopeAvailable's "backend accepts hitlConfig" fact; a caught
      // exception here still resolves (not throws) so a read_only activation
      // that failed only its verification step is reported, not treated as a
      // failed provision.
      onStage?.("verifying-gate");
      const gate = await verifyGateInstalled(result.agentId);

      // A READY badge only proves the config loaded. Run one real read so a
      // deployed-but-unreachable operator is reported as such, not as success.
      onStage?.("canary");
      const canary = await runOperatorCanary(next);

      onStage?.("done");
      return { config: next, canary, gate };
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

/** Re-enable a configured-but-disabled operator by redeploying its agent. */
export function useReactivateOperator() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (config: OperatorConfig) => reactivateOperator(config),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: operatorKeys.all });
    },
  });
}

/** Re-run the probe read on demand, from the operator screen. */
export function useOperatorCanary() {
  return useMutation({
    mutationFn: (config: OperatorConfig) => runOperatorCanary(config),
  });
}

/**
 * Continuously re-verifies the tool-approval gate on the live agent document.
 *
 * Verification is continuous, not one-time: `eddi.hitl.tool.enabled=false` (or
 * any other deployment-level change) can be flipped after activation and
 * nothing else would report it. `staleTime: 0` makes every mount — i.e. every
 * time the operator page is opened — re-check rather than serve a cached
 * "verified" from a stale mount. A failed or inconclusive result must drop any
 * write-scope offer; this hook only reports the fact, callers are responsible
 * for failing closed on it (see `isWriteScopeAvailable`).
 */
export function useVerifyOperatorGate(config: OperatorConfig | null | undefined) {
  const agentId = config?.agentId ?? "";
  const ready = Boolean(config?.enabled && agentId);
  return useQuery({
    queryKey: operatorKeys.gate(agentId),
    queryFn: () => verifyGateInstalled(agentId),
    enabled: ready,
    staleTime: 0,
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
  return existing ?? defaultOperatorConfig();
}
