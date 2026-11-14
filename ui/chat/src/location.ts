/* ──────────────────────────────────────────────
   The widget's three URL shapes, without a router

   react-router was 92 KB of the widget's first load for three path patterns
   and one "drop ?token= from the address bar" call (frontend unification
   plan, §3.3). This is the part of it the widget used:

     /chat/managed/:intent/:userId        managed agent (resumes per user)
     /chat/:environment/:agentId/:userId  direct agent, userId in the path
     /chat/:environment/:agentId          direct agent (?userId= optional)

   anything else is replaced with /chat/production/default, as the router's
   catch-all did. The URLs are a public contract for embedders
   (docs/chat-ui-embedding.md) — location.test.ts pins every shape.

   Matching follows react-router's rules, so no URL changes meaning:
   segments match case-insensitively for the literals ("chat", "managed"),
   `managed` beats `:environment` (a static segment ranks higher), trailing
   slashes are ignored, an empty segment matches nothing, and params are
   percent-decoded (a malformed escape is kept as written).
   ────────────────────────────────────────────── */

import { createContext, useContext, useMemo } from "react";

/** What the route names. Absent keys are not part of the matched shape. */
export interface ChatRouteParams {
  environment?: string;
  agentId?: string;
  userId?: string;
  intent?: string;
}

/** Where the router's catch-all sent an unknown path. */
export const DEFAULT_CHAT_PATH = "/chat/production/default";

function decodeSegment(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/** The params of a widget path, or null when it is none of the three shapes. */
export function parseChatPath(pathname: string): ChatRouteParams | null {
  const trimmed = pathname.replace(/\/+$/, "");
  const segments = trimmed.split("/").slice(1);
  if (trimmed[0] !== "/" || segments.some((s) => s === "")) return null;
  if (segments[0]?.toLowerCase() !== "chat") return null;
  const rest = segments.slice(1).map(decodeSegment);
  const raw = segments.slice(1);

  if (rest.length === 3 && raw[0]!.toLowerCase() === "managed") {
    return { intent: rest[1], userId: rest[2] };
  }
  if (rest.length === 3) {
    return { environment: rest[0], agentId: rest[1], userId: rest[2] };
  }
  if (rest.length === 2) {
    return { environment: rest[0], agentId: rest[1] };
  }
  return null;
}

/** The part of `window.location` the widget reads. */
export interface ChatLocation {
  pathname: string;
  search: string;
}

export interface ChatLocationApi {
  location: ChatLocation;
  /** Replace the query string in place (no history entry), keeping the path. */
  replaceSearch: (next: URLSearchParams) => void;
  /** Replace the whole path (no history entry); the query string is dropped. */
  replacePath: (pathname: string) => void;
}

/** Set by `ChatLocationProvider` — the browser's address bar, or a test's. */
export const ChatLocationContext = createContext<ChatLocationApi | null>(null);

export function useChatLocation(): ChatLocationApi {
  const api = useContext(ChatLocationContext);
  if (!api) throw new Error("useChatLocation() needs a <ChatLocationProvider>");
  return api;
}

/**
 * The route params, the query parameters and a way to rewrite the latter.
 * `searchParams` keeps its identity while the query string is unchanged, as
 * react-router's `useSearchParams` did — effects depend on it.
 */
export function useChatRoute(): {
  params: ChatRouteParams;
  matched: boolean;
  searchParams: URLSearchParams;
  replaceSearch: (next: URLSearchParams) => void;
} {
  const { location, replaceSearch } = useChatLocation();
  const parsed = useMemo(() => parseChatPath(location.pathname), [location.pathname]);
  const searchParams = useMemo(() => new URLSearchParams(location.search), [location.search]);
  return { params: parsed ?? {}, matched: parsed !== null, searchParams, replaceSearch };
}
