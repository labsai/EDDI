import { parseConversationControls, type ConversationControls } from "@eddi/core";
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
  /**
   * The deployed version's `appearance`, as the profile serves it; null (or
   * absent on an older backend) when the agent sets none.
   */
  appearance?: AgentAppearanceProfile | null;
  /** The deployed version's resolved `conversationControls`; absent on an older backend. */
  conversationControls?: ConversationControlsProfile | null;
}

/** `AppearanceProfile` of the profile and the catalog: validated when the agent was saved. */
export interface AgentAppearanceProfile {
  /** The name to show, or null for the agent's name. */
  displayName?: string | null;
  /** An https URL or a data:image URI, or null. */
  avatar?: string | null;
  /** `#rgb` or `#rrggbb`, or null for the theme's. */
  accentColor?: string | null;
  /** Conversation starters, in order. */
  starters?: string[];
}

/**
 * `ControlsProfile`: which chat controls to offer, every default applied —
 * true unless the agent switched it off, except `rawHtml` (false unless on).
 * The rule lives in @eddi/core (`parseConversationControls`), shared with the
 * Chat UI.
 */
export type ConversationControlsProfile = ConversationControls;

/** The controls a profile or catalog entry reports, with the defaults for anything it leaves out. */
export function resolveConversationControls(controls: unknown): ConversationControlsProfile {
  return parseConversationControls(controls);
}

/** The name an agent shows: its `appearance.displayName`, else its name. */
export function catalogDisplayName(agent: Pick<CatalogAgent, "agentId" | "name" | "appearance">): string {
  return agent.appearance?.displayName?.trim() || agent.name?.trim() || agent.agentId;
}

/**
 * An accent colour safe to put in a style attribute: only `#rgb` / `#rrggbb`,
 * which the backend already enforces on save — checked again because the
 * value ends up in CSS.
 */
export function safeAccentColor(value: string | null | undefined): string | null {
  return value && /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value) ? value : null;
}

/**
 * An avatar safe to put in an `<img src>`: an https URL or a raster
 * `data:image` URI (no SVG), as the backend validates on save.
 */
export function safeAvatarUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  if (/^data:image\/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$/.test(value)) return value;
  try {
    return new URL(value).protocol === "https:" ? value : null;
  } catch {
    return null;
  }
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
