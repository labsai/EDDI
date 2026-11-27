import { HttpClient } from "@eddi/core";
import { getUserEventStream, type UserEvent, type UserEventStream } from "@eddi/core/user-events";
import { api } from "./api-client";

/*
 * The signed-in apps' binding of `@eddi/core/user-events` (B12, docs/user-events.md).
 *
 * One stream per tab: every caller gets the same instance, because the server
 * caps streams per user (8 per node) and a browser on HTTP/1.1 has six
 * connections per origin for everything. `UserEventBridge` starts it once the
 * user is signed in and stops it on sign-out; components only subscribe.
 *
 * The transport is `ApiClient.authorizedFetch` (the token, its refresh, one
 * retry on a 401). The error stays the package's own `ApiError` — not the
 * Manager's `ApiClientError` — because the stream tells a refusal it must not
 * retry (401, 403, 404) from a failure it should by `instanceof ApiError`.
 */
let http: HttpClient | null = null;

function userEventHttp(): HttpClient {
  http ??= new HttpClient({ baseUrl: api.getBaseUrl(), fetch: (url, init) => api.authorizedFetch(url, init) });
  return http;
}

/** The tab's one user event stream. */
export function appUserEventStream(): UserEventStream {
  return getUserEventStream({ http: userEventHttp() });
}

/**
 * The query keys an event makes stale. Events carry ids only, so the way to
 * show what happened is to refetch what is on screen through the endpoints that
 * check access again (docs/user-events.md, "Never content").
 *
 * Keys are prefixes: `["pending-approvals"]` covers every limit the inbox, the
 * Manager's badge and Workforce's indicator ask with.
 */
export function staleQueryKeys(event: UserEvent): unknown[][] {
  switch (event.kind) {
    case "approval.pending":
    case "approval.decided": {
      const keys: unknown[][] = [["pending-approvals"], ["all-group-pending-approvals"]];
      if (event.surface === "group") {
        if (event.groupId) keys.push(["groupConversations", event.groupId]);
      } else if (event.conversationId) {
        // The paused card and the read-only pause notice watch this: the
        // decision shows the moment it is made, not at the next 5 s poll.
        keys.push(["approval-status", event.conversationId]);
        keys.push(["conversations"]);
      }
      return keys;
    }
    case "team.started":
    case "team.waiting_for_human":
    case "team.ended":
      return [
        event.groupId ? ["groupConversations", event.groupId] : ["groupConversations"],
        ["chat", "workforce-sessions", "teams"],
        ["all-group-pending-approvals"],
      ];
    case "job.finished":
    case "job.run_completed":
    case "job.failed":
      return [["chat", "workforce-sessions", "chats"], ["conversations"]];
    case "notification.created":
      return [["workspaces", "notifications"]];
    default:
      return [];
  }
}

/** What a (re)connect refetches: there is no replay, so anything shown may be stale. */
export const REFETCH_ON_OPEN: readonly unknown[][] = [
  ["pending-approvals"],
  ["all-group-pending-approvals"],
  ["approval-status"],
  ["groupConversations"],
  ["chat", "workforce-sessions"],
  ["conversations"],
  ["workspaces", "notifications"],
];
