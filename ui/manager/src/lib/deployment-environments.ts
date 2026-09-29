/**
 * Which environments an agent is actually live in.
 *
 * The agents list used to read `useDeploymentStatus(id, version)` — whose
 * `environment` parameter DEFAULTS to production — so an agent deployed only to
 * `test` was labelled "Not deployed" on the card that is most people's only view
 * of it. Combined with every chat entry point also targeting production, a
 * perfectly healthy test agent looked broken AND unreachable.
 *
 * Pure helpers live here rather than beside the component so the component file
 * exports only components (fast refresh).
 */
import { ENVIRONMENTS, type Environment } from "@/lib/constants";
import type { AgentDeploymentSummary, EnvironmentStatus } from "@/lib/api/agents";

/** The environments where `statuses` says the agent is live, in ENVIRONMENTS order. */
export function deployedEnvironments(
  statuses: EnvironmentStatus[] | undefined,
): Environment[] {
  if (!statuses) return [];
  const live = new Set(
    statuses.filter((s) => s.status === "READY").map((s) => s.environment),
  );
  // Ordered by ENVIRONMENTS rather than by the response, so the chips do not
  // reorder themselves between renders when the settled promises race.
  return ENVIRONMENTS.filter((env) => live.has(env));
}

/** True while any environment is mid-deploy — the card's "busy" state. */
export function isAnyEnvironmentBusy(statuses: EnvironmentStatus[] | undefined): boolean {
  return (statuses ?? []).some((s) => s.status === "IN_PROGRESS");
}

/**
 * Preferred environment to open a conversation in: production when the agent is
 * live there (what a reader assumes), otherwise wherever it actually runs.
 */
export function preferredChatEnvironment(live: readonly Environment[]): Environment {
  return live.includes("production") ? "production" : (live[0] ?? "production");
}

/**
 * Fill in environments where the asked-for version is not deployed but an older
 * version of the same agent is live.
 *
 * The per-version status endpoint answers only for the exact version asked, and
 * every save bumps the version — so an agent still serving v3 in production
 * showed "Not deployed" the moment someone saved v4, on the card, the detail
 * page and the chat picker alike. `deployed` is the per-environment result of
 * `listDeploymentStatuses` (same order as `statuses`' environments, entries may
 * be missing when that call failed). The adopted entry carries `deployedVersion`
 * so callers can tell "live at this version" from "live at another one".
 *
 * Only a NOT_FOUND status is replaced. READY and IN_PROGRESS are version-exact
 * answers and win outright; ERROR is kept too, because a failed deploy of v4 is
 * the thing the reader must see — overwriting it with a green chip for the v3
 * that still serves would hide it (and the card's `env-chip-error-*`).
 *
 * Only a READY row is adopted, and never one at `version` itself. An IN_PROGRESS
 * row from the listing would come from a cached, unpolled read and keep the
 * card's toggle disabled on a state that has long moved on; the version-exact
 * query is the one that polls. A READY row AT `version` contradicts the
 * version-exact NOT_FOUND it would replace, so it can only be stale: an undeploy
 * sets the exact status to NOT_FOUND optimistically while the shared listing is
 * still cached from before, and adopting that row reported the version just
 * undeployed as live again until the listing refetched.
 *
 * Known gap: the listing holds the HIGHEST deployed version per agent whatever
 * its status (`AgentFactory.getAllLatestAgents`). If that version failed (ERROR)
 * while an older one is still READY, the older one is invisible here and the
 * environment reads "Not deployed". The backend has no "latest READY" listing,
 * and probing every older version per card would cost N requests.
 */
export function withAnyDeployedVersion(
  statuses: EnvironmentStatus[] | undefined,
  deployed: Partial<Record<Environment, AgentDeploymentSummary[] | undefined>>,
  agentId: string,
  version: number,
): EnvironmentStatus[] | undefined {
  if (!statuses) return statuses;
  return statuses.map((s) => {
    if (s.status !== "NOT_FOUND") return s;
    const other = deployed[s.environment]?.find(
      (d) => d.agentId === agentId && d.status === "READY" && d.agentVersion !== version,
    );
    return other
      ? { environment: s.environment, status: other.status, deployedVersion: other.agentVersion }
      : s;
  });
}

/** True when `status` is live at exactly the version that was asked about. */
export function isLiveAtRequestedVersion(status: EnvironmentStatus | undefined): boolean {
  return status?.status === "READY" && status.deployedVersion === undefined;
}
