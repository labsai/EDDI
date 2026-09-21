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
import type { EnvironmentStatus } from "@/lib/api/agents";

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
