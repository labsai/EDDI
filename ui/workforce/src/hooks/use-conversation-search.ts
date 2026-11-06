import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  MAX_CONVERSATION_SEARCH_LENGTH,
  parseConversationUri,
  searchConversations,
  type ConversationDescriptor,
} from "@eddi/app-kit/lib/api/conversations";
import { useDeployedAgents } from "@eddi/app-kit/hooks/use-chat";
import { useMyTeamSessions } from "@/hooks/use-workforce-sessions";
import { chatSession, memberConversationIds, type WorkforceSession } from "@/lib/workforce-sessions";

/**
 * "Search my conversations" (T1.3) — the person's own conversations whose
 * TITLE contains the text (`GET /conversationstore/conversations/search`, B3).
 * Titles only: what was said is not searched (see the backend's changelog for
 * why), and the UI says so wherever it offers the search.
 *
 * Debounced, and every new text aborts the request still out for the old one,
 * so a slow answer to "rom" can never land over the answer to "rome". Paged
 * with "more": a full page may have a next one; a short page ends the list.
 */

/** How long typing must pause before a request goes out. */
export const SEARCH_DEBOUNCE_MS = 300;
/** Results per request. */
export const SEARCH_PAGE_SIZE = 20;

export type SearchStatus = "idle" | "loading" | "error" | "done";

export interface ConversationSearch {
  /** The normalized text the results are for; empty when idle. */
  query: string;
  status: SearchStatus;
  results: ConversationDescriptor[];
  /** The last page was full, so there may be another. */
  hasMore: boolean;
  /** A next page is being read; the results so far stay. */
  loadingMore: boolean;
  /** Reading the next page failed; the results so far stay. */
  moreFailed: boolean;
  loadMore: () => void;
  retry: () => void;
}

/**
 * What the backend searches for: whitespace collapsed, trimmed, at most
 * {@link MAX_CONVERSATION_SEARCH_LENGTH} characters (it answers a longer text
 * with a 400). Empty means "no search".
 */
export function normalizeSearchQuery(raw: string): string {
  return raw.replace(/\s+/g, " ").trim().slice(0, MAX_CONVERSATION_SEARCH_LENGTH).trim();
}

type Search = typeof searchConversations;

interface State {
  query: string;
  status: SearchStatus;
  results: ConversationDescriptor[];
  nextIndex: number;
  hasMore: boolean;
  loadingMore: boolean;
  moreFailed: boolean;
}

const IDLE: State = {
  query: "",
  status: "idle",
  results: [],
  nextIndex: 0,
  hasMore: false,
  loadingMore: false,
  moreFailed: false,
};

function isAbort(err: unknown): boolean {
  return err instanceof Error && err.name === "AbortError";
}

export function useConversationSearch(
  text: string,
  options: { debounceMs?: number; pageSize?: number; search?: Search } = {},
): ConversationSearch {
  const { debounceMs = SEARCH_DEBOUNCE_MS, pageSize = SEARCH_PAGE_SIZE, search = searchConversations } = options;
  const query = normalizeSearchQuery(text);
  const [debounced, setDebounced] = useState("");
  const [state, setState] = useState<State>(IDLE);
  const inFlight = useRef<AbortController | null>(null);

  // Clearing the box is immediate; a new text waits for the typing to pause.
  useEffect(() => {
    if (!query) {
      setDebounced("");
      return;
    }
    const timer = setTimeout(() => setDebounced(query), debounceMs);
    return () => clearTimeout(timer);
  }, [query, debounceMs]);

  const run = useCallback(
    (q: string, index: number, append: boolean) => {
      inFlight.current?.abort();
      const controller = new AbortController();
      inFlight.current = controller;
      setState((s) =>
        append
          ? { ...s, loadingMore: true, moreFailed: false }
          : { ...IDLE, query: q, status: "loading" },
      );
      search(q, index, pageSize, controller.signal).then(
        (page) => {
          if (controller.signal.aborted) return;
          setState((s) => {
            const seen = new Set(append ? s.results.map((d) => d.resource) : []);
            const fresh = page.filter((d) => !seen.has(d.resource));
            return {
              query: q,
              status: "done",
              results: append ? [...s.results, ...fresh] : page,
              nextIndex: index + 1,
              hasMore: page.length >= pageSize,
              loadingMore: false,
              moreFailed: false,
            };
          });
        },
        (err: unknown) => {
          if (controller.signal.aborted || isAbort(err)) return;
          setState((s) => (append ? { ...s, loadingMore: false, moreFailed: true } : { ...IDLE, query: q, status: "error" }));
        },
      );
    },
    [search, pageSize],
  );

  useEffect(() => {
    if (!debounced) {
      inFlight.current?.abort();
      inFlight.current = null;
      setState(IDLE);
      return;
    }
    run(debounced, 0, false);
  }, [debounced, run]);

  // Leaving abandons the request.
  useEffect(() => () => inFlight.current?.abort(), []);

  const loadMore = useCallback(() => {
    if (state.status !== "done" || !state.hasMore || state.loadingMore) return;
    run(state.query, state.nextIndex, true);
  }, [state, run]);

  const retry = useCallback(() => {
    if (!state.query) return;
    if (state.status === "error") run(state.query, 0, false);
    else if (state.moreFailed) run(state.query, state.nextIndex, true);
  }, [state, run]);

  // Only the answer for the text in the box is shown: while the typing has not
  // paused yet, or the request is out, it is "loading", never the old results.
  const current: State =
    query && query === debounced && state.query === query
      ? state
      : { ...IDLE, query, status: query ? "loading" : "idle" };
  return {
    query: current.query,
    status: current.status,
    results: current.results,
    hasMore: current.hasMore,
    loadingMore: current.loadingMore,
    moreFailed: current.moreFailed,
    loadMore,
    retry,
  };
}

/**
 * The search results as sidebar chats: the agent's name for an untitled one,
 * and without the member conversations of a team session, which the sidebar
 * does not list either.
 */
export function useSearchedSessions(search: ConversationSearch): WorkforceSession[] {
  const { data: agents } = useDeployedAgents();
  const teams = useMyTeamSessions();
  return useMemo(() => {
    const names = new Map<string, string>((agents ?? []).map((a) => [a.id, a.name ?? ""]));
    const hidden = memberConversationIds(teams.data?.sessions ?? []);
    return search.results
      .filter((d) => !hidden.has(parseConversationUri(d.resource)))
      .map((d) => chatSession(d, names))
      .filter((s): s is WorkforceSession => s !== null);
  }, [search.results, agents, teams.data]);
}
