import { useLocation, matchPath } from "react-router-dom";

/** What screen the admin is currently on, threaded into the operator's system
 *  prompt as `context.currentScreen`/`context.currentAgentId`/etc. — see
 *  `src/lib/operator/system-prompt.ts`. */
export interface CurrentScreenContext {
  screen: string;
  agentId?: string;
  workflowId?: string;
  groupId?: string;
  channelId?: string;
  conversationId?: string;
  resourceType?: string;
  resourceId?: string;
  boardId?: string;
  memberId?: string;
}

type ContextParamKey = Exclude<keyof CurrentScreenContext, "screen">;

interface RouteEntry {
  pattern: string;
  screen: string;
  /** Maps a matched URL param name to the context field it becomes. */
  params?: Record<string, ContextParamKey>;
}

/**
 * Ordered by specificity, most specific first.
 *
 * `matchPath` tests one pattern at a time with no cross-pattern ranking —
 * unlike the real <Routes> tree in App.tsx, which resolves ambiguity like this
 * automatically. So a literal segment must be listed before a param pattern
 * that would also match it (`/manage/groups/wizard` before
 * `/manage/groups/:id`, or `wizard` becomes a "group id"), and a deeper path
 * before its own prefix (`/workforce/:boardId/thread/:memberId` before
 * `/workforce/:boardId`). Kept in sync with App.tsx by hand — there is no
 * single source both this and the router can share without inverting one of
 * them.
 */
const ROUTE_TABLE: readonly RouteEntry[] = [
  { pattern: "/manage/studio/:agentId", screen: "agent-studio", params: { agentId: "agentId" } },

  { pattern: "/manage/resources/:type/:id", screen: "resource-detail", params: { type: "resourceType", id: "resourceId" } },
  { pattern: "/manage/resources/:type", screen: "resource-list", params: { type: "resourceType" } },
  { pattern: "/manage/resources", screen: "resources" },

  { pattern: "/manage/groups/wizard", screen: "group-wizard" },
  { pattern: "/manage/groups/:id", screen: "group-detail", params: { id: "groupId" } },
  { pattern: "/manage/groups", screen: "groups" },

  { pattern: "/manage/agents/wizard", screen: "agent-wizard" },
  { pattern: "/manage/agentview/:id", screen: "agent-detail", params: { id: "agentId" } },
  { pattern: "/manage/agents", screen: "agents" },

  { pattern: "/manage/workflowview/:id", screen: "workflow-detail", params: { id: "workflowId" } },
  { pattern: "/manage/workflows", screen: "workflows" },

  { pattern: "/manage/conversations/monitoring", screen: "conversation-monitoring" },
  { pattern: "/manage/conversationview/:id", screen: "conversation-detail", params: { id: "conversationId" } },
  { pattern: "/manage/conversations", screen: "conversations" },

  { pattern: "/manage/channels/:id", screen: "channel-detail", params: { id: "channelId" } },
  { pattern: "/manage/channels", screen: "channels" },

  { pattern: "/manage/operator", screen: "operator" },
  { pattern: "/manage/coordinator", screen: "coordinator" },
  { pattern: "/manage/schedules", screen: "schedules" },
  { pattern: "/manage/logs", screen: "logs" },
  { pattern: "/manage/orphans", screen: "orphans" },
  { pattern: "/manage/secrets", screen: "secrets" },
  { pattern: "/manage/variables", screen: "variables" },
  { pattern: "/manage/audit", screen: "audit" },
  { pattern: "/manage/quotas", screen: "quotas" },
  { pattern: "/manage/gdpr", screen: "gdpr" },
  { pattern: "/manage/userdata", screen: "user-data" },
  { pattern: "/manage/triggers", screen: "triggers" },
  { pattern: "/manage/capabilities", screen: "capabilities" },
  { pattern: "/manage/sync", screen: "sync" },
  { pattern: "/manage/approvals", screen: "approvals" },
  { pattern: "/manage/chat", screen: "chat" },
  { pattern: "/manage", screen: "dashboard" },

  { pattern: "/workforce/new", screen: "workforce-wizard" },
  { pattern: "/workforce/analytics", screen: "workforce-analytics" },
  { pattern: "/workforce/chat", screen: "workforce-chat" },
  {
    pattern: "/workforce/:boardId/thread/:memberId",
    screen: "workforce-thread",
    params: { boardId: "boardId", memberId: "memberId" },
  },
  { pattern: "/workforce/:boardId/settings", screen: "workforce-settings", params: { boardId: "boardId" } },
  { pattern: "/workforce/:boardId/history", screen: "workforce-history", params: { boardId: "boardId" } },
  { pattern: "/workforce/:boardId", screen: "workforce-board", params: { boardId: "boardId" } },
  { pattern: "/workforce", screen: "workforce-dashboard" },
];

/**
 * What the admin is currently looking at, derived from the URL alone.
 *
 * Meant to be called from a component mounted at the LAYOUT level (the
 * operator drawer, in both `AppLayout` and `WorkforceLayout`) — i.e. above the
 * routed `<Outlet/>`, where `useParams()` cannot see the matched child
 * route's params. `matchPath`, used imperatively against the current
 * location rather than through the route tree, works regardless of where it
 * is called from — at the cost of needing its own ordered table above.
 */
export function useCurrentScreenContext(): CurrentScreenContext {
  const location = useLocation();
  for (const entry of ROUTE_TABLE) {
    const match = matchPath(entry.pattern, location.pathname);
    if (!match) continue;
    const context: CurrentScreenContext = { screen: entry.screen };
    if (entry.params) {
      for (const [urlParam, contextKey] of Object.entries(entry.params)) {
        const value = match.params[urlParam];
        if (value) context[contextKey] = value;
      }
    }
    return context;
  }
  return { screen: "other" };
}
