import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  type ApprovalStatusSummary,
  listPendingApprovals,
  listAllGroupPendingApprovals,
  getApprovalStatus,
  resumeConversation,
  cancelConversation,
  cancelGroupDiscussion,
  approveGroupPhase,
  getGroupApprovalStatus,
  type HitlDecision,
  type GroupApprovalRequest,
} from "@/lib/api/hitl";
import {
  bindDecisionToPause,
  currentGroupPauseOf,
  currentPauseOf,
  describesPause,
  isPauseChanged,
  type ShownPause,
} from "@/lib/hitl-pause-binding";
import { submitHumanInput } from "@/lib/api/groups";
import { useChatStore } from "@/hooks/use-chat";

/** Clear a stale pause banner in the (persistent, app-wide) chat store when the
 *  conversation it belongs to is resumed/cancelled from elsewhere (e.g. the
 *  conversation-detail page or the approvals inbox) — the chat panel/drawer
 *  doesn't reload on its own and would otherwise keep showing "awaiting
 *  approval" for an already-resolved conversation. */
function clearChatPauseIfCurrent(conversationId: string) {
  const chatState = useChatStore.getState();
  if (chatState.conversationId === conversationId) {
    chatState.setPaused(false, null);
  }
}

// ── Queries ──────────────────────────────────────────────────────

/**
 * How many 1:1 pending approvals the inbox asks for. The backend endpoint has
 * no offset, so anything past this is not reachable from one request; a result
 * of exactly this many is reported as "more pending" rather than presented as
 * the whole queue (see {@link isPendingApprovalsCapped}).
 */
export const PENDING_APPROVALS_LIMIT = 200;

/**
 * Whether a pending-approvals list hit the request cap. Like the group inbox's
 * `truncated`, a heuristic: it also fires when exactly `limit` are pending.
 */
export function isPendingApprovalsCapped(
  list: readonly unknown[] | undefined,
  limit = PENDING_APPROVALS_LIMIT,
): boolean {
  return (list?.length ?? 0) >= limit;
}

/** Pending approvals for regular (1:1) conversations. */
export function usePendingApprovals(limit = PENDING_APPROVALS_LIMIT) {
  return useQuery({
    queryKey: ["pending-approvals", { limit }],
    queryFn: () => listPendingApprovals(limit),
    refetchInterval: 10_000,
  });
}

/**
 * Cross-group HITL inbox — every group's pending approvals in ONE request via
 * the backend's `GET /groups/pending-approvals` (no per-group fan-out). The
 * backend returns a plain, `limit`-capped list with no truncation flag of its
 * own; `truncated` is a client-side heuristic (result count reached `limit`)
 * and can false-positive when the true count is exactly `limit`.
 */
export function useAllGroupPendingApprovals(limit = 200) {
  const query = useQuery({
    queryKey: ["all-group-pending-approvals", { limit }],
    queryFn: () => listAllGroupPendingApprovals(limit),
    refetchInterval: 10_000,
  });
  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    truncated: (query.data?.length ?? 0) >= limit,
  };
}

/** How long a paused conversation's approval-status may fail to describe the pause before it is an error. */
const APPROVAL_DETAILS_WINDOW_MS = 10_000;
/** Poll delays while the stored pause has not caught up with the stream; the last repeats. */
const APPROVAL_POLL_BACKOFF_MS = [500, 1000, 2000];

export interface ApprovalStatusOptions {
  /**
   * The caller KNOWS the conversation is paused (it saw the stream's `done`),
   * so a response that does not describe that pause is "not stored yet", not an
   * answer. Turns on fast re-polling and the `detailsPending`/`detailsError`
   * window, and withholds a non-matching response from `data`. See
   * `describesPause`.
   */
  verifyPause?: boolean;
  /**
   * For a READ-ONLY surface that only shows the pause (a chat notice): called
   * once, when the pause that was seen stored is no longer AWAITING_HUMAN —
   * decided elsewhere, or auto-decided by its timeout policy. Also keeps polling
   * (every 5 s) after the pause is described, which a deciding card has no use
   * for. A not-yet-stored read can never trigger it: only a pause that was
   * actually seen counts. Requires `verifyPause`.
   */
  onResolved?: () => void;
}

/** How often a watching surface re-reads a pause it has already seen stored. */
const APPROVAL_WATCH_MS = 5000;

/**
 * Structured approval status of a paused 1:1 conversation — the only source of
 * `pauseDetails` (per-call tool name + redacted arguments for a TOOL_CALL pause,
 * or the rule reason/actions for a RULE pause). Enable only while the
 * conversation is actually AWAITING_HUMAN.
 *
 * `pauseKey` identifies WHICH pause the caller is showing — pass the pause's
 * `hitlPausedAt` when available. A turn may pause up to `maxPausesPerTurn`
 * times (backend default 3), and with the key on the conversation id alone a
 * re-pause is the SAME query, so the second pause rendered the first pause's
 * cached calls — decided buttons and all. (Verified live: the previous remedy,
 * `removeQueries` after deciding, produces no refetch on an actively-observed
 * query — the observer keeps rendering its last data. Keying by pause identity
 * makes the fresh fetch a cache-model guarantee instead of a removal
 * side-effect.) Callers without a pause timestamp omit it and keep the old
 * behaviour.
 *
 * Pass `{ verifyPause: true }` when the pause was announced by a stream: the
 * `done` event is emitted before the paused memory is persisted, so the first
 * read can say "not paused". The hook then re-polls (500 ms, backing off to 2 s)
 * for up to 10 s until the response describes this pause, and exposes
 * `detailsPending` / `detailsError` / `retry` for the approval card. Never an
 * endless spinner: past the window it is `detailsError`.
 */
export function useApprovalStatus(
  conversationId: string | undefined,
  enabled = true,
  pauseKey?: string | null,
  options?: ApprovalStatusOptions,
) {
  const verifyPause = options?.verifyPause === true;
  const active = enabled && !!conversationId;
  const expectedPausedAt = pauseKey ?? null;
  // One window per (conversation, pause, retry). It restarts when any of them
  // changes, so a Retry gets a full window rather than the remainder of the last.
  const [attempt, setAttempt] = useState(0);
  const watching = verifyPause && !!options?.onResolved;
  // The pause was seen stored (so any later non-AWAITING read is a genuine
  // resolution), and whether that resolution was already reported — for ONE
  // watched pause. A mounted notice can move on to another conversation or
  // pause; carried over, "resolved" would silence the new one and "seen" would
  // read its not-yet-stored READY as a decision. Read and written only from the
  // async query function and the interval callback, never during render.
  const watchKey = `${conversationId ?? ""}|${expectedPausedAt ?? ""}`;
  const watchRef = useRef({ key: watchKey, seen: false, resolved: false });
  const watchFor = (key: string) => {
    if (watchRef.current.key !== key) watchRef.current = { key, seen: false, resolved: false };
    return watchRef.current;
  };
  const onResolvedRef = useRef(options?.onResolved);
  useEffect(() => {
    onResolvedRef.current = options?.onResolved;
  });
  const windowId = `${conversationId ?? ""}|${expectedPausedAt ?? ""}|${attempt}`;
  const [timedOutWindow, setTimedOutWindow] = useState<string | null>(null);
  const timedOut = verifyPause && timedOutWindow === windowId;

  useEffect(() => {
    if (!verifyPause || !active) return;
    const id = setTimeout(() => setTimedOutWindow(windowId), APPROVAL_DETAILS_WINDOW_MS);
    return () => clearTimeout(id);
  }, [verifyPause, active, windowId]);

  const query = useQuery({
    queryKey: ["approval-status", conversationId, pauseKey ?? null],
    queryFn: async () => {
      const status = await getApprovalStatus(conversationId!);
      if (watching) {
        const watch = watchFor(watchKey);
        if (describesPause(status, expectedPausedAt)) {
          watch.seen = true;
        } else if (watch.seen && !watch.resolved && status.state !== "AWAITING_HUMAN") {
          watch.resolved = true;
          onResolvedRef.current?.();
        }
      }
      return status;
    },
    // Verifying callers want a fresh read on mount: a cached response from an
    // earlier pause of this conversation must not stand in for this one.
    staleTime: verifyPause ? 0 : undefined,
    enabled: active,
    // Poll fast until the response describes the pause, then stop. A function,
    // so the answer is re-derived from each response rather than frozen at the
    // render that created the query. `timedOut` is read from the closure: it is
    // state, so flipping it re-renders and the observer picks up the new options.
    refetchInterval: verifyPause
      ? (q) => {
          if (watchFor(watchKey).resolved) return false;
          if (describesPause(q.state.data, expectedPausedAt)) return watching ? APPROVAL_WATCH_MS : false;
          if (timedOut) return false;
          const polls = q.state.dataUpdateCount + q.state.errorUpdateCount;
          // The interval is re-derived AFTER each response, so the first
          // response (polls = 1) must already map to the first delay.
          const step = Math.min(Math.max(polls - 1, 0), APPROVAL_POLL_BACKOFF_MS.length - 1);
          return APPROVAL_POLL_BACKOFF_MS[step];
        }
      : undefined,
  });

  const { refetch } = query;
  const retry = useCallback(() => {
    setAttempt((n) => n + 1);
    return refetch();
  }, [refetch]);

  if (!verifyPause) {
    return { ...query, latest: query.data, detailsPending: active && query.isLoading, detailsError: active && query.isError, retry };
  }

  const describes = describesPause(query.data, expectedPausedAt);
  // A failed read is an error only once it has stopped trying: while a Retry is
  // in flight the card says "loading", not "failed" again.
  const failed = query.isError && !query.isFetching;
  return {
    ...query,
    // A response that does not describe THIS pause is not shown at all — its
    // `pauseDetails` is null or belongs to an earlier pause, and passing it on
    // would render a card with nothing on it to object to.
    data: describes ? query.data : (undefined as ApprovalStatusSummary | undefined),
    /** The last response as received, described or not — for a surface that must tell "not stored yet" from "decided". */
    latest: query.data,
    detailsPending: active && !describes && !timedOut && !failed,
    detailsError: active && !describes && (timedOut || failed),
    retry,
  };
}

// ── Mutations ────────────────────────────────────────────────────

/**
 * Resume a paused 1:1 conversation with an APPROVED/REJECTED decision.
 *
 * Pass `shown` — the pause the reviewer was looking at — and the decision is
 * bound to it (see `lib/hitl-pause-binding.ts`): if the conversation has since
 * been resumed and paused on something else, the mutation fails with a
 * pause-changed error instead of approving what nobody reviewed. Every approval
 * surface should pass it; it is optional only for callers that decide a pause
 * they have just read themselves.
 */
export function useResumeConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      conversationId,
      decision,
      shown,
    }: {
      conversationId: string;
      decision: HitlDecision;
      shown?: ShownPause;
    }) => {
      const bound = shown
        ? await bindDecisionToPause(decision, shown, async () =>
            currentPauseOf(await getApprovalStatus(conversationId)),
          )
        : decision;
      return resumeConversation(conversationId, bound);
    },
    onSuccess: (_data, { conversationId }) => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
      qc.invalidateQueries({ queryKey: ["approval-status", conversationId] });
      clearChatPauseIfCurrent(conversationId);
    },
    onError: (err, { conversationId }) => {
      // The reviewer is looking at a pause that no longer exists: show them the
      // current one rather than leaving the stale one up to be decided again.
      if (isPauseChanged(err)) {
        qc.invalidateQueries({ queryKey: ["pending-approvals"] });
        qc.invalidateQueries({ queryKey: ["approval-status", conversationId] });
      }
    },
  });
}

/** Cancel a paused or in-progress 1:1 conversation. */
export function useCancelConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: string) => cancelConversation(conversationId),
    onSuccess: (_data, conversationId) => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
      qc.invalidateQueries({ queryKey: ["approval-status", conversationId] });
      clearChatPauseIfCurrent(conversationId);
    },
  });
}

/**
 * Approve or reject a paused group discussion, without streaming the resume.
 *
 * The group page uses the streaming variant, because it has a transcript to
 * play the resumed discussion into. The cross-group approvals inbox does not:
 * it is a queue of decisions, and its rows previously offered no decision at
 * all for a group pause — only a link out, on a screen whose whole purpose is
 * answering "what is waiting for me?" in one place. A 1:1 pause could always be
 * decided inline; this is the group equivalent.
 */
export function useApproveGroupPhase() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      groupId,
      gcId,
      request,
      shown,
    }: {
      groupId: string;
      gcId: string;
      request: GroupApprovalRequest;
      /** The pause the reviewer saw — see `useResumeConversation`. */
      shown?: ShownPause;
    }) => {
      const decision = shown
        ? await bindDecisionToPause(request.decision, shown, async () =>
            currentGroupPauseOf(await getGroupApprovalStatus(groupId, gcId)),
          )
        : request.decision;
      return approveGroupPhase(groupId, gcId, { ...request, decision });
    },
    onSuccess: (_data, { groupId }) => {
      qc.invalidateQueries({ queryKey: ["groupConversations", groupId] });
      qc.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
    },
    onError: (err) => {
      if (isPauseChanged(err)) {
        qc.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
      }
    },
  });
}

/** Cancel a group discussion. */
export function useCancelGroupDiscussion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, gcId }: { groupId: string; gcId: string }) =>
      cancelGroupDiscussion(groupId, gcId),
    onSuccess: () => {
      // Real list key is camelCase ["groupConversations", …] (see use-groups.ts).
      qc.invalidateQueries({ queryKey: ["groupConversations"] });
      qc.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
    },
  });
}

/**
 * Submit a HUMAN group member's turn (I6). Unlike the approve/stream resume,
 * this is a plain REST call — the backend has no `/human-input/stream` variant,
 * so there is no live token-by-token progress to attach to. The call returns
 * once the turn is recorded; the discussion resumes on the server from there, so
 * callers follow it by polling the persisted conversation and should say it is
 * resuming rather than imply it has settled.
 */
export function useSubmitHumanInput() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      groupId,
      gcId,
      memberId,
      content,
    }: {
      groupId: string;
      gcId: string;
      memberId: string;
      content: string;
    }) => submitHumanInput(groupId, gcId, memberId, content),
    // Awaited, so the mutation stays pending until the refetched conversation
    // is in: the backend resumes the discussion asynchronously, and without
    // this the banner re-enabled for an instant on the stale AWAITING_HUMAN_INPUT
    // document, with the typed text still in it.
    onSuccess: async (_data, { groupId }) => {
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["groupConversations", groupId] }),
        qc.invalidateQueries({ queryKey: ["all-group-pending-approvals"] }),
      ]);
    },
  });
}
