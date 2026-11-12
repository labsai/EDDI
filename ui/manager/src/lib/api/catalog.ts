import { api } from "../api-client";
import type { RatingProfile } from "./ratings";
import type { AgentGroupConfiguration, DiscussionStyle, GroupMember, MemberType } from "./groups";

/**
 * The end-user catalog (`IRestCatalog`): the agents and groups the caller may
 * USE that their owners chose to list. Readable by `eddi-user`, unlike the
 * authoring stores (`agentstore`, `groupstore`, `deploymentstore`), which answer
 * only `eddi-admin` / `eddi-editor` — so this is what Workforce reads for a
 * signed-in end user.
 *
 * Listing is opt-in (`listed: true` on the agent or group configuration, or,
 * with workspaces enforced, `published` / `internal` visibility). An empty
 * catalog is therefore the normal state of a fresh deployment, not an error.
 */

/** What the caller holds on an entry; always at least `USE`. */
export type CatalogCallerLevel = "USE" | "VIEW" | "EDIT" | "OWN";

/** One entry of `GET /catalog/agents` — the same fields as `GET /agents/{id}/profile`. */
export interface CatalogAgent {
  agentId: string;
  name?: string | null;
  description?: string | null;
  reviewNotice?: string | null;
  rating?: RatingProfile | null;
  callerLevel: CatalogCallerLevel;
}

export interface CatalogGroupMember {
  agentId: string;
  displayName?: string | null;
  role?: string | null;
  memberType?: MemberType | null;
  speakingOrder?: number | null;
}

/** One entry of `GET /catalog/groups`. */
export interface CatalogGroup {
  groupId: string;
  version: number;
  name?: string | null;
  description?: string | null;
  style?: DiscussionStyle | null;
  members: CatalogGroupMember[];
  callerLevel: CatalogCallerLevel;
}

export function getCatalogAgents(): Promise<CatalogAgent[]> {
  return api.get<CatalogAgent[]>("/catalog/agents");
}

export function getCatalogGroups(): Promise<CatalogGroup[]> {
  return api.get<CatalogGroup[]>("/catalog/groups");
}

/** The catalog members, in the shape the Workforce components take. */
export function catalogMembers(group: Pick<CatalogGroup, "members">): GroupMember[] {
  return (group.members ?? []).map((m, i) => ({
    agentId: m.agentId,
    displayName: m.displayName || m.agentId,
    speakingOrder: m.speakingOrder ?? i + 1,
    role: m.role ?? null,
    memberType: m.memberType ?? undefined,
  }));
}

/**
 * A catalog group as the subset of a group configuration the Workforce board,
 * thread and history read. Phases, tasks and the HITL settings are not part of
 * the catalog — they describe how the group is built — so the board falls back
 * to the style's preset phases, exactly as it does for a group that sets none.
 */
export function catalogGroupAsConfig(group: CatalogGroup): AgentGroupConfiguration {
  return {
    name: group.name ?? "",
    description: group.description ?? "",
    members: catalogMembers(group),
    moderatorAgentId: null,
    style: group.style ?? "ROUND_TABLE",
    maxRounds: 2,
    phases: null,
    protocol: null,
  };
}
