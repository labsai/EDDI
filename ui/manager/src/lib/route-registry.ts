import type { TFunction } from "i18next";
import {
  LayoutDashboard,
  Bot,
  Workflow,
  MessagesSquare,
  MessageCircle,
  FileCode,
  CalendarClock,
  Link2,
  Link2Off,
  ScrollText,
  KeyRound,
  ShieldCheck,
  SlidersHorizontal,
  Building2,
  Zap,
  Users,
  Cable,
  Variable,
  Sparkles,
  ArrowUpCircle,
  Plug,
  Blocks,
  Radio,
  Network,
  Hand,
  UserRoundSearch,
  ArrowRightLeft,
  ShieldUser,
  Briefcase,
  type LucideIcon,
} from "lucide-react";

/**
 * The single source of truth for what a Manager route is CALLED.
 *
 * The breadcrumb, the document title, the command palette's page list and the
 * sidebar used to keep four separate copies of this table, and they drifted:
 * `/manage/groups/wizard` crumbed as "Agent Wizard", and nine sections showed
 * their raw lowercase path segment in the breadcrumb while the document title
 * (which had been fixed) said the right thing. Add a page here, once.
 */

// ── Navigation entries (sidebar + command palette) ───────────────────

export interface PageEntry {
  path: string;
  icon: LucideIcon;
  labelKey: string;
  /** English fallback, used when the key is missing from a locale bundle. */
  fallback: string;
}

export interface NavSection {
  /** Stable id — persisted collapse state is keyed by this, never by position. */
  id: string;
  labelKey: string;
  items: readonly PageEntry[];
}

export const NAV_SECTIONS: readonly NavSection[] = [
  {
    id: "core",
    labelKey: "nav.sectionCore",
    items: [
      { path: "/manage", icon: LayoutDashboard, labelKey: "nav.dashboard", fallback: "Dashboard" },
      { path: "/manage/operator", icon: Sparkles, labelKey: "nav.operator", fallback: "Platform Operator" },
      { path: "/manage/agents", icon: Bot, labelKey: "nav.agents", fallback: "Agents" },
      { path: "/manage/workflows", icon: Workflow, labelKey: "nav.packages", fallback: "Workflows" },
      { path: "/manage/groups", icon: Users, labelKey: "nav.groups", fallback: "Groups" },
      { path: "/manage/channels", icon: Cable, labelKey: "nav.channels", fallback: "Channels" },
      { path: "/manage/capabilities", icon: Blocks, labelKey: "nav.capabilities", fallback: "Capabilities" },
    ],
  },
  {
    id: "build",
    labelKey: "nav.sectionBuild",
    items: [
      { path: "/manage/resources", icon: FileCode, labelKey: "nav.resources", fallback: "Resources" },
      { path: "/manage/chat", icon: MessageCircle, labelKey: "nav.chat", fallback: "Chat" },
      { path: "/manage/triggers", icon: Zap, labelKey: "nav.triggers", fallback: "Triggers" },
    ],
  },
  {
    id: "monitor",
    labelKey: "nav.sectionMonitor",
    items: [
      { path: "/manage/logs", icon: ScrollText, labelKey: "nav.logs", fallback: "Logs" },
      { path: "/manage/conversations", icon: MessagesSquare, labelKey: "nav.conversations", fallback: "Conversations" },
      { path: "/manage/conversations/monitoring", icon: Radio, labelKey: "nav.activeConversations", fallback: "Active Conversations" },
      { path: "/manage/coordinator", icon: Network, labelKey: "nav.coordinator", fallback: "Coordinator" },
      { path: "/manage/approvals", icon: Hand, labelKey: "nav.approvals", fallback: "Approvals" },
      { path: "/manage/audit", icon: ShieldCheck, labelKey: "nav.audit", fallback: "Audit Trail" },
    ],
  },
  {
    id: "admin",
    labelKey: "nav.sectionAdmin",
    items: [
      { path: "/manage/workspaces", icon: Building2, labelKey: "nav.workspaces", fallback: "Workspaces" },
      { path: "/manage/secrets", icon: KeyRound, labelKey: "nav.secrets", fallback: "Secrets" },
      // Shown to everybody, like the nine other admin-only entries around it.
      // Nothing here is role-gated, so hiding this one alone would be
      // inconsistent — and it would also hide it from an admin whose roles have
      // not arrived yet. The page explains a 403 and degrades to the viewer's own
      // linked accounts, which is a better answer than a nav entry that silently
      // is not there.
      { path: "/manage/connections", icon: Plug, labelKey: "nav.connections", fallback: "Connections" },
      { path: "/manage/variables", icon: Variable, labelKey: "nav.variables", fallback: "Variables" },
      { path: "/manage/quotas", icon: SlidersHorizontal, labelKey: "nav.quotas", fallback: "Quotas" },
      { path: "/manage/schedules", icon: CalendarClock, labelKey: "nav.schedules", fallback: "Schedules" },
      { path: "/manage/userdata", icon: UserRoundSearch, labelKey: "nav.userData", fallback: "User Data" },
      { path: "/manage/orphans", icon: Link2Off, labelKey: "nav.orphans", fallback: "Orphans" },
      { path: "/manage/sync", icon: ArrowRightLeft, labelKey: "nav.sync", fallback: "Sync" },
      { path: "/manage/gdpr", icon: ShieldUser, labelKey: "nav.gdpr", fallback: "Privacy" },
      { path: "/manage/updates", icon: ArrowUpCircle, labelKey: "nav.updates", fallback: "Updates" },
    ],
  },
];

/**
 * Pages worth jumping to that the sidebar does not list: the per-user
 * linked-accounts page (reached from the user menu), the wizards and the
 * Workforce app.
 */
const EXTRA_PAGES: readonly PageEntry[] = [
  { path: "/manage/linked-accounts", icon: Link2, labelKey: "pages.linkedAccounts.title", fallback: "Linked accounts" },
  { path: "/manage/agents/wizard", icon: Bot, labelKey: "wizard.title", fallback: "Agent Wizard" },
  { path: "/manage/groups/wizard", icon: Users, labelKey: "groupWizard.title", fallback: "Group Setup Wizard" },
  { path: "/manage/groups/templates", icon: Users, labelKey: "groupTemplates.title", fallback: "Group Templates" },
  { path: "/workforce", icon: Briefcase, labelKey: "nav.workforce", fallback: "Workforce" },
];

/** Every navigable page, nav order first. The command palette lists these. */
export const ALL_PAGES: readonly PageEntry[] = [
  ...NAV_SECTIONS.flatMap((s) => s.items),
  ...EXTRA_PAGES,
];

export function pageLabel(t: TFunction, entry: Pick<PageEntry, "labelKey" | "fallback">): string {
  return t(entry.labelKey, { defaultValue: entry.fallback });
}

// ── Segment labels (breadcrumb + document title) ─────────────────────

type LabelRef = readonly [key: string, fallback: string];

/** Label for a path segment on its own — `/manage/<segment>`. */
const SEGMENT_LABELS: Record<string, LabelRef> = {
  agents: ["nav.agents", "Agents"],
  workflows: ["nav.packages", "Workflows"],
  conversations: ["nav.conversations", "Conversations"],
  chat: ["nav.chat", "Chat"],
  resources: ["nav.resources", "Resources"],
  groups: ["nav.groups", "Groups"],
  coordinator: ["nav.coordinator", "Coordinator"],
  schedules: ["nav.schedules", "Schedules"],
  logs: ["nav.logs", "Logs"],
  orphans: ["nav.orphans", "Orphans"],
  secrets: ["nav.secrets", "Secrets"],
  audit: ["nav.audit", "Audit Trail"],
  quotas: ["nav.quotas", "Quotas"],
  userdata: ["nav.userData", "User Data"],
  triggers: ["nav.triggers", "Triggers"],
  capabilities: ["nav.capabilities", "Capabilities"],
  sync: ["nav.sync", "Sync"],
  gdpr: ["nav.gdpr", "Privacy"],
  connections: ["nav.connections", "Connections"],
  "linked-accounts": ["pages.linkedAccounts.title", "Linked accounts"],
  updates: ["nav.updates", "Updates"],
  operator: ["nav.operator", "Platform Operator"],
  channels: ["nav.channels", "Channels"],
  approvals: ["nav.approvals", "Approvals"],
  variables: ["nav.variables", "Variables"],
  workspaces: ["nav.workspaces", "Workspaces"],
  memories: ["nav.memories", "User Memory"],
  properties: ["nav.properties", "Properties"],
  "user-conversations": ["nav.userConversations", "User Conversations"],
  studio: ["nav.studio", "Agent Studio"],
  // Not under /manage, so it arrives as the first segment verbatim.
  workforce: ["nav.workforce", "Workforce"],
};

/**
 * Labels that depend on the section they sit in: "wizard" is the Agent Wizard
 * under /agents and the Group Setup Wizard under /groups. Keyed `section/segment`.
 */
const CONTEXT_LABELS: Record<string, LabelRef> = {
  "agents/wizard": ["wizard.title", "Agent Wizard"],
  "groups/wizard": ["groupWizard.title", "Group Setup Wizard"],
  "groups/templates": ["groupTemplates.title", "Group Templates"],
  "groups/workspace": ["groupWorkspace.title", "Standing Team Workspace"],
  "conversations/monitoring": ["nav.monitoring", "Conversation Monitoring"],
  "workforce/new": ["workforceWizard.title", "New Team"],
  "workforce/analytics": ["nav.analytics", "Analytics"],
  "workforce/chat": ["nav.chat", "Chat"],
  "workforce/settings": ["common.settings", "Settings"],
  "workforce/history": ["common.history", "History"],
};

/**
 * `agentview`/`workflowview`/`conversationview` are detail routes whose segment is
 * the singular of their section, and `<section>` is where their list lives.
 */
const DETAIL_VIEW_SECTIONS: Record<string, string> = {
  agentview: "agents",
  workflowview: "workflows",
  conversationview: "conversations",
  agentstore: "agents",
};

/** Section → the list route a detail crumb links back to. */
export const LIST_ROUTE_FOR_SEGMENT: Record<string, string> = {
  agentview: "/manage/agents",
  workflowview: "/manage/workflows",
  conversationview: "/manage/conversations",
};

/** The detail-view segment whose id an entity name can be looked up for. */
export type EntityKind = "agent";

const ID_LIKE = /^[a-f0-9]{24}$/;

function resolve(t: TFunction, ref: LabelRef): string {
  return t(ref[0], { defaultValue: ref[1] });
}

/** Map a raw first segment onto the section whose label it takes, or itself. */
function resolveSection(raw: string): string {
  const mapped = DETAIL_VIEW_SECTIONS[raw];
  if (mapped) return mapped;
  if (SEGMENT_LABELS[raw]) return raw;
  const stripped = raw.replace(/view$/, "");
  return SEGMENT_LABELS[stripped] ? stripped : raw;
}

/** Whether the first path segment names a section this registry knows. */
export function isKnownSection(rawFirstSegment: string): boolean {
  return SEGMENT_LABELS[resolveSection(rawFirstSegment)] !== undefined;
}

/** The path split into segments with a leading `/manage` removed. */
export function routeSegments(pathname: string): string[] {
  return pathname
    .replace(/^\/manage\/?/, "")
    .split("/")
    .filter(Boolean);
}

/** The entity a `/manage/agentview/:id` style path points at, when it does. */
export function entityFromPath(pathname: string): { kind: EntityKind; id: string } | null {
  const [first, id] = routeSegments(pathname);
  if (first === "agentview" && id) return { kind: "agent", id: decodeURIComponent(id) };
  return null;
}

function labelAt(t: TFunction, segments: string[], idx: number): string {
  const segment = segments[idx]!;
  const parent = idx > 0 ? resolveSection(segments[idx - 1]!) : undefined;
  const contextual = parent ? CONTEXT_LABELS[`${parent}/${segment}`] : undefined;
  if (contextual) return resolve(t, contextual);
  const own = SEGMENT_LABELS[resolveSection(segment)];
  if (own) return resolve(t, own);
  return ID_LIKE.test(segment) ? `${segment.substring(0, 8)}…` : segment;
}

export interface Crumb {
  label: string;
  to: string;
}

/**
 * Breadcrumb trail for a `/manage/...` path. `entityName` replaces the id crumb
 * of a detail route (the agent's name instead of its raw id) when known.
 */
export function buildCrumbs(t: TFunction, pathname: string, entityName?: string): Crumb[] {
  const segments = routeSegments(pathname);
  const crumbs: Crumb[] = [{ label: t("nav.dashboard", { defaultValue: "Dashboard" }), to: "/manage" }];
  let currentPath = "/manage";
  segments.forEach((segment, idx) => {
    currentPath += `/${segment}`;
    const isEntityId = idx === 1 && DETAIL_VIEW_SECTIONS[segments[0]!] !== undefined;
    const label = isEntityId && entityName ? entityName : labelAt(t, segments, idx);
    crumbs.push({ label, to: LIST_ROUTE_FOR_SEGMENT[segment] ?? currentPath });
  });
  return crumbs;
}

/**
 * Label for the page at `pathname`, for the document title. A trailing id keeps
 * the section's label; a trailing literal sub-page (wizard, templates, …) gets
 * its own. `entityName` wins for a detail route.
 */
export function pageTitleLabel(t: TFunction, pathname: string, entityName?: string): string {
  const segments = routeSegments(pathname);
  if (segments.length === 0) return t("nav.dashboard", { defaultValue: "Dashboard" });
  const raw = segments[0]!;
  const section = resolveSection(raw);
  // A path that matches no route renders the NotFound page; say so.
  if (!isKnownSection(raw)) return t("notFound.title", { defaultValue: "Page not found" });
  const last = segments[segments.length - 1]!;
  if (entityName && DETAIL_VIEW_SECTIONS[raw] && segments.length === 2) return entityName;
  const contextual = last === raw ? undefined : CONTEXT_LABELS[`${section}/${last}`];
  if (contextual) return resolve(t, contextual);
  return resolve(t, SEGMENT_LABELS[section]!);
}
