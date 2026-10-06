import { useCallback, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { create } from "zustand";
import {
  deployAgentAndWait,
  preflightDeploy,
  VAULT_GRANT_MISSING,
  type DeployResult,
  type DeploymentFailure,
  type DeploymentPreflight,
  type GrantEnforcement,
  type PreflightGrantIssue,
} from "@/lib/api/agents";
import { getErrorMessage } from "@/lib/api-client";
import { agentKeys } from "@/lib/query-keys";

/* ─── The grant decision, shared with the one mounted dialog ─── */

/**
 * One secret the agent uses and is not granted, whichever way it was learned —
 * from the preflight (before deploying) or from a refused deploy's `failure`.
 * The dialog renders either the same way.
 */
export interface GrantIssue {
  /** Absent for a reference that is not a plain vault reference — nothing to grant. */
  tenantId?: string;
  keyName?: string;
  reference: string;
  /** Agent ids on the grant — known for an admin's preflight only. */
  allowedAgents?: string[];
  /** How many agents the grant lists; absent when unknown. */
  allowedAgentCount?: number;
  /**
   * The agents of a batch request that need THIS key — absent for a
   * single-agent request, where it is the request's own agent.
   */
  agentIds?: string[];
}

/** One agent a (batch) grant request covers. */
export interface GrantAgent {
  agentId: string;
  agentName?: string;
}

/** What the person decided in the dialog. The dialog performs the grant itself before answering `granted`. */
export type GrantDecision = "granted" | "deploy-anyway" | "cancel";

/** What opens the grant dialog. */
export interface GrantRequest {
  agentId: string;
  agentName?: string;
  version: number;
  environment: string;
  /** `UNKNOWN` when the issues came from a refused deploy rather than a preflight. */
  enforcement: GrantEnforcement | "UNKNOWN";
  issues: GrantIssue[];
  /** Where the issues came from: the dialog words a refusal that already happened differently. */
  source: "preflight" | "failure";
  /** The refusal, when `source` is `failure` — its message is shown verbatim. */
  failure?: DeploymentFailure;
  /**
   * Set only for a batch request that spans several agents (the wizards that
   * create a team): every agent the request covers, so the dialog asks ONCE and
   * a grant adds all of them. `agentId` is then the first of them. Absent for
   * the ordinary one-agent request, which renders exactly as before.
   */
  agents?: GrantAgent[];
}

interface PendingGrantRequest extends GrantRequest {
  resolve: (decision: GrantDecision) => void;
}

interface GrantDialogState {
  pending: PendingGrantRequest | null;
  /** How many dialog hosts are mounted. With none, asking would wait forever. */
  hosts: number;
  registerHost(): () => void;
  answer(decision: GrantDecision): void;
}

/**
 * The open grant request. A store rather than component state because the
 * flows that need it — save-and-deploy, the card, the detail page, the wizard,
 * operator activation — are hooks and mutations that render nothing, and the
 * question must be asked in ONE dialog mounted once (`GrantRequiredDialogHost`).
 */
export const useGrantDialogStore = create<GrantDialogState>((set, get) => ({
  pending: null,
  hosts: 0,
  registerHost: () => {
    set((s) => ({ hosts: s.hosts + 1 }));
    return () => {
      const hosts = Math.max(0, get().hosts - 1);
      // The last dialog went away with a question still open (a page left
      // mid-question, a test torn down): nobody can answer it any more, so it
      // is answered "cancel" rather than left to hang its deploy forever.
      const orphaned = hosts === 0 ? get().pending : null;
      set({ hosts, ...(orphaned ? { pending: null } : {}) });
      orphaned?.resolve("cancel");
    };
  },
  answer: (decision) => {
    const pending = get().pending;
    if (!pending) return;
    set({ pending: null });
    pending.resolve(decision);
  },
}));

/**
 * Ask the person what to do about the issues, and wait for the answer.
 *
 * `null` when no dialog host is mounted — nobody could answer. A second request
 * while one is open cancels the first rather than queueing behind it: the newer
 * one is what the person just asked for.
 */
export function requestGrantDecision(request: GrantRequest): Promise<GrantDecision | null> {
  const store = useGrantDialogStore.getState();
  if (store.hosts === 0) return Promise.resolve(null);
  store.pending?.resolve("cancel");
  return new Promise<GrantDecision>((resolve) => {
    useGrantDialogStore.setState({ pending: { ...request, resolve } });
  });
}

/* ─── Normalising the two sources ─── */

/** The preflight's issues, as the dialog takes them. */
export function issuesFromPreflight(preflight: DeploymentPreflight): GrantIssue[] {
  return preflight.grantIssues.map((issue: PreflightGrantIssue) => ({
    tenantId: issue.tenantId ?? undefined,
    keyName: issue.keyName ?? undefined,
    reference: issue.reference,
    allowedAgents: issue.allowedAgents ?? undefined,
    allowedAgentCount: issue.allowedAgentCount ?? undefined,
  }));
}

/** A refused deploy's secrets, as the dialog takes them. */
export function issuesFromFailure(failure: DeploymentFailure): GrantIssue[] {
  return (failure.secrets ?? []).map((secret) => ({
    tenantId: secret.tenantId,
    keyName: secret.keyName,
    reference: secret.reference,
  }));
}

/** Whether a failure is the grant refusal the dialog can fix. */
export function isGrantFailure(failure: DeploymentFailure | undefined | null): failure is DeploymentFailure {
  return failure?.code === VAULT_GRANT_MISSING;
}

/* ─── The flow ─── */

export interface DeployWithGrantsOptions {
  agentId: string;
  version: number;
  environment?: string;
  /** Shown in the dialog; the id is used when absent. */
  agentName?: string;
  signal?: AbortSignal;
  /**
   * A refusal already in hand (a "Fix" button on a failed deployment): skip the
   * preflight and open the dialog from it straight away.
   */
  failure?: DeploymentFailure;
}

export type DeployWithGrantsOutcome =
  | { kind: "deployed"; result: DeployResult }
  /** The person cancelled the grant dialog. Nothing was deployed by this call. */
  | { kind: "cancelled"; failure?: DeploymentFailure }
  /** The deploy ran and did not end READY. `message` is the backend's reason when it gave one. */
  | { kind: "failed"; result: DeployResult; message: string | null; failure?: DeploymentFailure };

/**
 * Grant, then deploy: preflight → (dialog) → deploy and wait → (dialog again
 * on a grant refusal the preflight did not predict) → outcome.
 *
 * Granting is never automatic. The dialog asks, an admin confirms, and only
 * then does it append THIS agent to each secret's grant; a non-admin gets an
 * explanation and a request to copy, and nothing is written. Cancel deploys
 * nothing.
 *
 * The second chance exists for the cases the preflight cannot see: a race (the
 * grant changed between the check and the deploy), a preflight that could not
 * run (`checked: false`, or an older backend), or a fix button that starts from
 * a failure. It is offered once per call, so a grant that keeps being refused
 * ends as a failure the caller shows instead of a loop.
 */
export async function runDeployWithGrants(options: DeployWithGrantsOptions): Promise<DeployWithGrantsOutcome> {
  const environment = options.environment ?? "production";
  const base = {
    agentId: options.agentId,
    agentName: options.agentName,
    version: options.version,
    environment,
  };

  if (options.failure && isGrantFailure(options.failure)) {
    const decision = await requestGrantDecision({
      ...base,
      enforcement: "UNKNOWN",
      issues: issuesFromFailure(options.failure),
      source: "failure",
      failure: options.failure,
    });
    if (decision === "cancel") return { kind: "cancelled", failure: options.failure };
  } else {
    // An older backend (404), or a caller who may not edit the agent (403):
    // deploy anyway and let a refusal, if any, come back with its reason.
    const preflight: DeploymentPreflight | null = await preflightDeploy(
      environment,
      options.agentId,
      options.version,
    ).catch(() => null);
    if (preflight && preflight.checked && preflight.enforcement !== "OFF" && preflight.grantIssues.length > 0) {
      const decision = await requestGrantDecision({
        ...base,
        enforcement: preflight.enforcement,
        issues: issuesFromPreflight(preflight),
        source: "preflight",
      });
      if (decision === "cancel") return { kind: "cancelled" };
    }
  }

  return deployAndSettle(base, options.signal);
}

type DeployBase = Pick<GrantRequest, "agentId" | "agentName" | "version" | "environment">;

/**
 * Deploy and wait; on a grant refusal the preflight did not predict, ask once
 * and redeploy. The tail both the single and the batch flow end in.
 */
async function deployAndSettle(base: DeployBase, signal?: AbortSignal): Promise<DeployWithGrantsOutcome> {
  let result = await deployAgentAndWait(base.environment, base.agentId, base.version, signal);
  if (result.status !== "READY" && isGrantFailure(result.failure)) {
    const failure = result.failure;
    const decision = await requestGrantDecision({
      ...base,
      enforcement: "UNKNOWN",
      issues: issuesFromFailure(failure),
      source: "failure",
      failure,
    });
    if (decision === "cancel") return { kind: "cancelled", failure };
    if (decision === "granted") {
      result = await deployAgentAndWait(base.environment, base.agentId, base.version, signal);
    }
  }

  if (result.status === "READY") return { kind: "deployed", result };
  return {
    kind: "failed",
    result,
    message: result.failure?.message ?? result.error ?? null,
    failure: result.failure,
  };
}

/* ─── Many agents, one question ─── */

/** An agent to deploy as part of a batch. */
export interface DeployTarget {
  agentId: string;
  version: number;
  /** Shown in the dialog; the id is used when absent. */
  agentName?: string;
}

export interface DeployManyOptions {
  targets: DeployTarget[];
  environment?: string;
  signal?: AbortSignal;
}

/** What happened to one target of a batch. */
export interface DeployManyResult {
  target: DeployTarget;
  outcome: DeployWithGrantsOutcome;
}

/**
 * Merge the preflight issues of several agents by the secret they name — the
 * `(tenantId, keyName)` pair, or the raw reference when it is not a plain vault
 * reference — so the person is asked about each key once, with every agent that
 * needs it listed on it.
 */
export function mergeGrantIssues(perAgent: { agentId: string; issues: GrantIssue[] }[]): GrantIssue[] {
  const merged = new Map<string, GrantIssue>();
  for (const { agentId, issues } of perAgent) {
    for (const issue of issues) {
      const id = issue.tenantId && issue.keyName ? `${issue.tenantId}/${issue.keyName}` : `ref:${issue.reference}`;
      const existing = merged.get(id);
      if (!existing) {
        merged.set(id, { ...issue, agentIds: [agentId] });
      } else if (!existing.agentIds!.includes(agentId)) {
        existing.agentIds!.push(agentId);
      }
    }
  }
  return [...merged.values()];
}

/**
 * {@link runDeployWithGrants} for several agents created together: preflight
 * them all, ask about the keys ONCE (each key listed once, with every agent that
 * needs it, and one confirmation adds all of them), then deploy each and wait.
 *
 * - **Cancel deploys nothing** — every target comes back `cancelled`, including
 *   the ones that needed no grant, so a team is never left half-live.
 * - The unexpected refusal (a race, a preflight that could not run) still gets
 *   its one second chance, per agent, through the ordinary single-agent dialog.
 * - Targets are deployed one after another: a second dialog would cancel the
 *   first, and a deploy that waits is not something to fan out.
 * - A transport error on one target is that target's `failed` outcome, not a
 *   throw that hides the others.
 */
export async function runDeployManyWithGrants(options: DeployManyOptions): Promise<DeployManyResult[]> {
  const environment = options.environment ?? "production";
  const targets = options.targets;
  const baseOf = (target: DeployTarget): DeployBase => ({
    agentId: target.agentId,
    agentName: target.agentName,
    version: target.version,
    environment,
  });

  const preflights = await Promise.all(
    targets.map((target) =>
      preflightDeploy(environment, target.agentId, target.version).catch((): DeploymentPreflight | null => null),
    ),
  );
  const needing: { target: DeployTarget; preflight: DeploymentPreflight }[] = [];
  targets.forEach((target, i) => {
    const preflight = preflights[i];
    if (preflight && preflight.checked && preflight.enforcement !== "OFF" && preflight.grantIssues.length > 0) {
      needing.push({ target, preflight });
    }
  });

  if (needing.length > 0) {
    const issues = mergeGrantIssues(
      needing.map(({ target, preflight }) => ({ agentId: target.agentId, issues: issuesFromPreflight(preflight) })),
    );
    const first = needing[0]!.target;
    const isBatch = needing.length > 1;
    const decision = await requestGrantDecision({
      ...baseOf(first),
      enforcement: needing.some(({ preflight }) => preflight.enforcement === "ENFORCE") ? "ENFORCE" : "WARN",
      // One agent after all: the dialog's single-agent wording, without agent lists.
      issues: isBatch ? issues : issues.map((issue) => ({ ...issue, agentIds: undefined })),
      source: "preflight",
      ...(isBatch
        ? { agents: needing.map(({ target }) => ({ agentId: target.agentId, agentName: target.agentName })) }
        : {}),
    });
    if (decision === "cancel") {
      return targets.map((target) => ({ target, outcome: { kind: "cancelled" as const } }));
    }
  }

  const results: DeployManyResult[] = [];
  for (const target of targets) {
    // Left the page (or the caller gave up): deploy nothing more.
    if (options.signal?.aborted) {
      results.push({ target, outcome: { kind: "cancelled" } });
      continue;
    }
    let outcome: DeployWithGrantsOutcome;
    try {
      outcome = await deployAndSettle(baseOf(target), options.signal);
    } catch (err) {
      const message = getErrorMessage(err);
      outcome = {
        kind: "failed",
        result: { status: "ERROR", agentId: target.agentId, version: target.version, environment, error: message },
        message,
      };
    }
    results.push({ target, outcome });
  }
  return results;
}

/**
 * {@link runDeployWithGrants} for a component: tracks whether a deploy is
 * running and refreshes every agent query once it ends, whatever the outcome —
 * a refused deploy changes the deployment status as surely as a good one.
 */
export function useDeployWithGrants() {
  const queryClient = useQueryClient();
  const [isRunning, setIsRunning] = useState(false);
  const runningRef = useRef(0);

  const deploy = useCallback(
    async (options: DeployWithGrantsOptions): Promise<DeployWithGrantsOutcome> => {
      runningRef.current += 1;
      setIsRunning(true);
      try {
        return await runDeployWithGrants(options);
      } finally {
        runningRef.current -= 1;
        if (runningRef.current === 0) setIsRunning(false);
        void queryClient.invalidateQueries({ queryKey: agentKeys.all });
        void queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
      }
    },
    [queryClient],
  );

  const deployMany = useCallback(
    async (options: DeployManyOptions): Promise<DeployManyResult[]> => {
      runningRef.current += 1;
      setIsRunning(true);
      try {
        return await runDeployManyWithGrants(options);
      } finally {
        runningRef.current -= 1;
        if (runningRef.current === 0) setIsRunning(false);
        void queryClient.invalidateQueries({ queryKey: agentKeys.all });
        void queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
      }
    },
    [queryClient],
  );

  return { deploy, deployMany, isRunning };
}
