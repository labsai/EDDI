import { useCallback, useMemo, useState } from "react";
import { create } from "zustand";
import {
  GroupDiscussion,
  initialGroupStreamState,
  type CancelOutcome,
  type GroupDiscussionStore,
  type GroupStreamState,
  type GroupTransport,
} from "@eddi/conversation/team";
import {
  streamGroupDiscussion,
  streamGroupContinue,
  streamGroupApproval,
  type GroupConversation,
  type GroupAttachmentRef,
} from "../lib/api/groups";
import { cancelGroupDiscussion, type GroupApprovalRequest } from "../lib/api/hitl";
import { isApiError } from "../lib/api-client";

/*
 * The signed-in apps' binding of the team kind of conversation. The turn rules —
 * how a discussion is started, resumed, continued, stopped and followed, and how
 * each SSE event changes what is on screen — live in @eddi/conversation's
 * `GroupDiscussion` controller. This module gives it the apps' authenticated
 * client (`groupTransport`) and keeps every group's state in one Zustand store,
 * so a discussion keeps streaming while the user is on another screen.
 */

export {
  isOpenPlaceholder,
  deliveredRowCount,
  persistedHasCaughtUp,
  type CancelOutcome,
  type ConvergenceProgress,
  type GroupStreamState,
} from "@eddi/conversation/team";

/**
 * Group discussion streams live in a module-level store rather than in
 * component state, keyed by group id. A discussion keeps streaming (and keeps
 * accumulating its transcript) while the user navigates elsewhere in the app,
 * so coming back to the board shows the live discussion instead of a blank
 * slate. The connections outlive React, so they are never aborted on unmount —
 * only explicitly, via abortStream/resetStream or a terminal SSE event.
 *
 * Each action hands over to that group's {@link GroupDiscussion}.
 */
interface GroupStreamStore {
  /** Live stream state per group id. */
  streams: Record<string, GroupStreamState>;
  update: (groupId: string, updater: (s: GroupStreamState) => GroupStreamState) => void;
  startStream: (groupId: string, question: string, attachments?: GroupAttachmentRef[]) => Promise<void>;
  continueStream: (
    groupId: string,
    gcId: string,
    question: string,
    seed?: GroupConversation | null,
  ) => Promise<void>;
  approveAndStream: (
    groupId: string,
    gcId: string,
    request: GroupApprovalRequest,
    seed?: GroupConversation | null,
  ) => Promise<void>;
  /** See {@link GroupDiscussion.abort}: detach, the discussion keeps running. */
  abortStream: (groupId: string) => void;
  /** See {@link GroupDiscussion.cancel}: stop the discussion on the server, then close the stream. */
  cancelStream: (groupId: string, gcId?: string) => Promise<CancelOutcome>;
  /** Acknowledge a reported {@link GroupStreamState.cancelError}. */
  clearCancelError: (groupId: string) => void;
  resetStream: (groupId: string) => void;
}

/** The apps' API binding as a transport (their token refresh, their errors, their mocks in tests). */
const groupTransport: GroupTransport<GroupApprovalRequest> = {
  discuss: (groupId, question, signal, attachments) =>
    streamGroupDiscussion(groupId, question, undefined, signal, attachments),
  continueRound: (groupId, gcId, question, signal) => streamGroupContinue(groupId, gcId, question, undefined, signal),
  approve: (groupId, gcId, request, signal) => streamGroupApproval(groupId, gcId, request, signal),
  cancel: (groupId, gcId) => cancelGroupDiscussion(groupId, gcId),
  isAlreadyEnded: (e) => isApiError(e) && e.status === 409,
};

/** One controller per group, for the life of the page, like the streams they drive. */
const discussions = new Map<string, GroupDiscussion<GroupApprovalRequest>>();

/** The group's slot in {@link useGroupStreamStore}, as the controller's store. */
function storeSlot(groupId: string): GroupDiscussionStore {
  return {
    get: () => useGroupStreamStore.getState().streams[groupId],
    set: (next) => useGroupStreamStore.setState((store) => ({ streams: { ...store.streams, [groupId]: next } })),
    // Drop the entry rather than storing a blank one: consumers fall back to
    // the shared initial state, and finished transcripts don't accumulate for
    // every board visited this session.
    remove: () =>
      useGroupStreamStore.setState((store) => {
        if (!(groupId in store.streams)) return store;
        const rest = { ...store.streams };
        delete rest[groupId];
        return { streams: rest };
      }),
    subscribe: (listener) => useGroupStreamStore.subscribe(() => listener()),
  };
}

/** The controller for `groupId`, created on first use. */
export function groupDiscussion(groupId: string): GroupDiscussion<GroupApprovalRequest> {
  let discussion = discussions.get(groupId);
  if (!discussion) {
    discussion = new GroupDiscussion({ groupId, transport: groupTransport, store: storeSlot(groupId) });
    discussions.set(groupId, discussion);
  }
  return discussion;
}

export const useGroupStreamStore = create<GroupStreamStore>(() => ({
  streams: {},
  update: (groupId, updater) => groupDiscussion(groupId).update(updater),
  startStream: (groupId, question, attachments) => groupDiscussion(groupId).start(question, attachments),
  approveAndStream: (groupId, gcId, request, seed) => groupDiscussion(groupId).approve(gcId, request, seed),
  continueStream: (groupId, gcId, question, seed) => groupDiscussion(groupId).continueRound(gcId, question, seed),
  abortStream: (groupId) => groupDiscussion(groupId).abort(),
  cancelStream: (groupId, gcId) => groupDiscussion(groupId).cancel(gcId),
  clearCancelError: (groupId) => groupDiscussion(groupId).clearCancelError(),
  resetStream: (groupId) => groupDiscussion(groupId).reset(),
}));

// ─── Selectors ──────────────────────────────────────────────────

/**
 * Ids of the groups that currently have a live stream — for "this task force is
 * still talking" affordances outside the board itself (sidebar, cards).
 * The selector projects to a joined string so its result is referentially
 * stable between unrelated store updates.
 */
export function useStreamingGroupIds(): string[] {
  const joined = useGroupStreamStore((store) =>
    Object.keys(store.streams)
      .filter((id) => store.streams[id]?.isStreaming)
      .sort()
      .join(","),
  );
  return useMemo(() => (joined ? joined.split(",") : []), [joined]);
}

/** See {@link GroupDiscussion.whenAccepted}. */
export function whenStreamAccepted(groupId: string): Promise<boolean> {
  return groupDiscussion(groupId).whenAccepted();
}

/** The message of the failure the group's stream last reported, if any. */
export function streamErrorOf(groupId: string): string | null {
  return useGroupStreamStore.getState().streams[groupId]?.error ?? null;
}

// ─── Hook ───────────────────────────────────────────────────────

/**
 * Hook for SSE-streamed group discussions.
 *
 * Usage:
 *   const { streamState, startStream, abortStream } = useGroupDiscussionStream(groupId);
 *   startStream(groupId, question);  // starts SSE
 *   // streamState updates in real-time as events arrive
 *
 * Passing `groupId` binds the hook to that group's stream, so a discussion
 * started earlier (even from another screen) is picked up on mount. Without it
 * the hook only observes the stream it started itself.
 */
export function useGroupDiscussionStream(groupId?: string) {
  // Group of the stream this hook instance started, used when no explicit
  // groupId is bound.
  const [startedGroupId, setStartedGroupId] = useState<string | null>(null);
  const key = groupId ?? startedGroupId;

  const streamState =
    useGroupStreamStore((store) => (key ? store.streams[key] : undefined)) ?? initialGroupStreamState;

  const startStream = useCallback(
    async (gid: string, question: string, attachments?: GroupAttachmentRef[]) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().startStream(gid, question, attachments);
    },
    [],
  );

  const continueStream = useCallback(
    async (gid: string, gcId: string, question: string, seed?: GroupConversation | null) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().continueStream(gid, gcId, question, seed);
    },
    [],
  );

  const approveAndStream = useCallback(
    async (gid: string, gcId: string, request: GroupApprovalRequest, seed?: GroupConversation | null) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().approveAndStream(gid, gcId, request, seed);
    },
    [],
  );

  const abortStream = useCallback(() => {
    if (key) useGroupStreamStore.getState().abortStream(key);
  }, [key]);

  const cancelStream = useCallback(async (gcId?: string): Promise<CancelOutcome> => {
    if (!key) return "nothingToCancel";
    return useGroupStreamStore.getState().cancelStream(key, gcId);
  }, [key]);

  const clearCancelError = useCallback(() => {
    if (key) useGroupStreamStore.getState().clearCancelError(key);
  }, [key]);

  const resetStream = useCallback(() => {
    if (key) useGroupStreamStore.getState().resetStream(key);
  }, [key]);

  return {
    streamState,
    startStream,
    continueStream,
    approveAndStream,
    abortStream,
    cancelStream,
    clearCancelError,
    resetStream,
  };
}
