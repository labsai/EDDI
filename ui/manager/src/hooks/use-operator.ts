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
  reportOperatorGateStatus,
  type GateVerificationResult,
  type OperatorConfig,
} from "@/lib/api/operator";
import { undeployAgent, deleteAgent } from "@/lib/api/agents";
import { endpointsForScope } from "@/lib/operator/tool-scopes";
import { enforceWriteCanaryGate, type WriteCanaryResult } from "@/lib/operator/write-canary";

/* ─── Query Keys ─── */

export const operatorKeys = {
  all: ["operator"] as const,
  config: ["operator", "config"] as const,
  status: (agentId: string, version: number) =>
    ["operator", "status", agentId, version] as const,
  gate: (agentId: string) => ["operator", "gate", agentId] as const,
};

/* ─── Config ─── */

/**
 * The operator config blob. `null` means "never activated".
 *
 * `enabled` exists for callers that mount somewhere this read is not
 * guaranteed to be permitted. The config lives in the global variable store,
 * which is `@RolesAllowed({"eddi-admin", "eddi-editor"})` on the backend — so
 * for any other role (`eddi-approver`, most notably) this 403s rather than
 * 404s, and `readOperatorConfig` rethrows anything that is not a 404. A caller
 * mounted on every page would turn that into a failed request on every
 * navigation for every non-admin. Defaults to `true`: the operator screen and
 * the dashboard card are admin surfaces already, and an admin who genuinely
 * cannot read it needs the error, not silence.
 */
export function useOperatorConfig(enabled = true) {
  return useQuery({
    queryKey: operatorKeys.config,
    queryFn: readOperatorConfig,
    staleTime: 30_000,
    enabled,
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
  | "write-canary"
  | "done";

/** What activation returns: the saved config plus the probe outcomes. */
export interface ActivationOutcome {
  config: OperatorConfig;
  canary: CanaryResult;
  gate: GateVerificationResult;
  /** Only run for scope "read_write" — null for a read_only activation. */
  writeCanary: WriteCanaryResult | null;
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

      // Everything from here to the config write is rolled back on failure.
      // provisionOperator DEPLOYS the agent, so a throw in any of these steps
      // used to leave a live agent bound to the whole admin-API surface and
      // running as the caller's identity — while the operator screen still said
      // "off", because the config variable it reads was never written. It was
      // invisible, unmanaged, and a retry made a second one:
      // removeSupersededAgent only ever cleans up the agent recorded in the
      // config. The write-canary path below already rolls back for exactly this
      // reason; these three steps simply never got the same treatment.
      let version: number;
      let next: OperatorConfig;
      try {
        onStage?.("resolving-version");
        version = await resolveAgentVersion(result);

        onStage?.("saving");
        next = {
          ...config,
          enabled: true,
          agentId: result.agentId,
          version,
        };
        await writeOperatorConfig(next);
      } catch (provisioningError) {
        // Best-effort: the original failure is what the admin needs to see, and
        // a cleanup that itself fails must not replace it. `version` may be
        // unresolved, so fall back to 1 — the version provisionOperator creates.
        try {
          await removeSupersededAgent({ ...config, agentId: result.agentId, version: 1 });
        } catch {
          // Left deployed; the rethrown error below is still the honest report.
        }
        throw provisioningError;
      }

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
      await reportOperatorGateStatus(gate.verified);

      // A READY badge only proves the config loaded. Run one real read so a
      // deployed-but-unreachable operator is reported as such, not as success.
      onStage?.("canary");
      const canary = await runOperatorCanary(next);

      // The empirical proof, not just configuration: does a real gated write
      // actually pause? A non-pass result rolls the whole activation back
      // (undeploy, delete, clear the config variable) rather than merely
      // reporting the failure — see enforceWriteCanaryGate's own doc comment
      // for why a failed write canary can't be treated like a failed read
      // canary or gate check.
      //
      // The scope check stays here (enforceWriteCanaryGate also no-ops for
      // read_only on its own) so the "write-canary" stage is never announced
      // for an activation that has no write tool to probe.
      // `next`, NOT `config`: the probe has to run against the agent that was
      // just provisioned. `config` still carries the PREVIOUS agentId, which on
      // a reconfigure `removeSupersededAgent` deleted a few lines above — so
      // probing it returned "unknown", rolled back an already-deleted agent, and
      // left the new write-capable operator deployed with its config pointer
      // cleared: the exact outcome this rollback exists to prevent. The read
      // canary above already uses `next`; these must agree.
      if (next.scope === "read_write") onStage?.("write-canary");
      const writeCanary = await enforceWriteCanaryGate(next, spec);

      onStage?.("done");
      return { config: next, canary, gate, writeCanary };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: operatorKeys.all });
    },
    // A failed activation mutates server state as surely as a successful one:
    // the provisioning rollback above and `enforceWriteCanaryGate`'s
    // `resetOperator` both DELETE agents and clear the config variable before
    // throwing. Without this the cache still holds the pre-activation config,
    // so cancelling out of the form lands on a page reporting an active
    // operator, with Check-connection / Deactivate / Delete all pointed at an
    // agent id that no longer exists — every one of them a 404. Invalidating on
    // both outcomes is the only version of this that matches what the server
    // actually did.
    onError: () => {
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
    queryFn: async () => {
      const result = await verifyGateInstalled(agentId);
      // Piggybacks on traffic that already has to happen for the status
      // panel, rather than opening a separate poll: every time an admin looks
      // at this page, the gauge that "the alert is on it dropping to 0"
      // refers to (docs/hitl.md, EDDI backend repo) gets refreshed too.
      await reportOperatorGateStatus(result.verified);
      return result;
    },
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
