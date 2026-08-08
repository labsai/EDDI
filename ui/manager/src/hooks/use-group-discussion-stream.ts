import { useCallback, useMemo, useState } from "react";
import { create } from "zustand";
import {
  streamGroupDiscussion,
  streamGroupContinue,
  streamGroupApproval,
  type TranscriptEntry,
  type TranscriptEntryType,
  type GroupConversationState,
  type GroupSSEEvent,
  type GroupStartPayload,
  type PhaseStartPayload,
  type SpeakerStartPayload,
  type SpeakerCompletePayload,
  type GroupCompletePayload,
  type TaskPlanCreatedPayload,
  type TaskVerifiedPayload,
  type ConvergenceCheckedPayload,
  type ConvergenceReachedPayload,
  type DecisionReachedPayload,
  type DecisionRecord,
  type GroupAttachmentRef,
} from "@/lib/api/groups";
import type { GroupApprovalRequest } from "@/lib/api/hitl";

// ─── Streaming State ────────────────────────────────────────────

/**
 * What the convergence events (I2) told us about one phase. `convergence_checked`
 * fires on every check whether or not it converged, so a phase approaching
 * agreement is visible before it stops rather than only afterwards;
 * `convergence_reached` then adds how many repeats were skipped.
 */
export interface ConvergenceProgress {
  phaseIndex: number;
  phaseName: string;
  /** 0-based index of the most recently checked repeat. */
  repeat: number;
  /** The judge's 0..1 agreement score, or `null` when no judge ran (-1 on the wire). */
  agreementScore: number | null;
  converged: boolean;
  /** Repeats the phase was configured for but will not run. Only known once converged. */
  repeatsSkipped: number | null;
  reason: string;
}

export interface GroupStreamState {
  /** Whether the SSE stream is actively connected */
  isStreaming: boolean;
  /** The conversation ID assigned by the backend */
  conversationId: string | null;
  /** Overall conversation state */
  state: GroupConversationState;
  /** Progressive transcript built from SSE events */
  transcript: TranscriptEntry[];
  /** Currently active phase */
  currentPhase: { index: number; name: string; type: string } | null;
  /** Agent IDs that are currently "speaking" (between speaker_start and speaker_complete) */
  activeSpeakers: Set<string>;
  /** Final synthesized answer (set on group_complete) */
  synthesizedAnswer: string | null;
  /**
   * Structured conclusion from `decision_reached` (EDDI F3) — a debate verdict
   * today. Null until a decision-producing phase runs; a discussion that ends in
   * prose alone never sets it.
   */
  decision: DecisionRecord | null;
  /**
   * The most recent convergence check per phase index (I2). Keyed by phase so a
   * later phase's check does not overwrite the record of an earlier one, and so
   * the display can show "this phase stopped after 2 of 4 repeats" next to the
   * phase it belongs to.
   */
  convergence: Map<number, ConvergenceProgress>;
  /** Error message if the discussion failed */
  error: string | null;
  /** Classifies a failure so the UI can offer recovery guidance.
   *  "config_drift" = the group's phases changed while paused, so the resume was
   *  aborted and the discussion is still awaiting approval (recoverable). */
  errorKind: "config_drift" | "generic" | null;
  /** Timestamp when the stream was started (stable, not recalculated per render) */
  startedAt: string | null;
  /** Task plan received from task_plan_created SSE event */
  taskPlan: { id: string; subject: string; assignedTo: string; priority: number }[] | null;
  /** Task verification results from task_verified SSE events */
  taskVerifications: Map<string, { passed: boolean; feedback: string }>;
  /** Set of task IDs currently being executed (inferred from speaker events during EXECUTE phase) */
  tasksInProgress: Set<string>;
  /** Set of task IDs completed (inferred from speaker events during EXECUTE phase) */
  tasksCompleted: Set<string>;
  /** HITL pause info when the group is awaiting approval */
  hitlPause: {
    phaseIndex: number;
    phaseName: string;
    reason: string;
    granularity: string;
  } | null;
  /** HITL resume info after approval decision */
  hitlResume: {
    verdict: string;
    note?: string;
    decidedBy?: string;
  } | null;
  /** Cancellation info when the group discussion is cancelled */
  cancelInfo: {
    reason?: string;
    cancelledBy?: string;
  } | null;
}

/** Shared empty state handed to consumers that have no stream yet. Never mutated. */
const initialState: GroupStreamState = {
  isStreaming: false,
  conversationId: null,
  state: "CREATED",
  transcript: [],
  currentPhase: null,
  activeSpeakers: new Set(),
  synthesizedAnswer: null,
  decision: null,
  convergence: new Map(),
  error: null,
  errorKind: null,
  startedAt: null,
  taskPlan: null,
  taskVerifications: new Map(),
  tasksInProgress: new Set(),
  tasksCompleted: new Set(),
  hitlPause: null,
  hitlResume: null,
  cancelInfo: null,
};

/** A clean state with its own collection instances (the shared `initialState`
 *  ones must never be written to). */
function freshState(): GroupStreamState {
  return {
    ...initialState,
    activeSpeakers: new Set(),
    tasksInProgress: new Set(),
    tasksCompleted: new Set(),
    taskVerifications: new Map(),
    convergence: new Map(),
  };
}

// ─── Store ──────────────────────────────────────────────────────

/**
 * Group discussion streams live in a module-level store rather than in
 * component state, keyed by group id. A discussion keeps streaming (and keeps
 * accumulating its transcript) while the user navigates elsewhere in the app,
 * so coming back to the board shows the live discussion instead of a blank
 * slate. The connections outlive React, so they are never aborted on unmount —
 * only explicitly, via abortStream/resetStream or a terminal SSE event.
 */
interface GroupStreamStore {
  /** Live stream state per group id. */
  streams: Record<string, GroupStreamState>;
  update: (groupId: string, updater: (s: GroupStreamState) => GroupStreamState) => void;
  startStream: (groupId: string, question: string, attachments?: GroupAttachmentRef[]) => Promise<void>;
  continueStream: (groupId: string, gcId: string, question: string) => Promise<void>;
  approveAndStream: (groupId: string, gcId: string, request: GroupApprovalRequest) => Promise<void>;
  abortStream: (groupId: string) => void;
  resetStream: (groupId: string) => void;
}

/** In-flight abort controllers, one per group. Kept outside the store because
 *  they are not render state. */
const abortControllers = new Map<string, AbortController>();

function swapController(groupId: string): AbortController {
  abortControllers.get(groupId)?.abort();
  const abort = new AbortController();
  abortControllers.set(groupId, abort);
  return abort;
}

export const useGroupStreamStore = create<GroupStreamStore>((set, get) => ({
  streams: {},

  update: (groupId, updater) =>
    set((store) => ({
      streams: {
        ...store.streams,
        [groupId]: updater(store.streams[groupId] ?? freshState()),
      },
    })),

  startStream: async (groupId, question, attachments) => {
    const abort = swapController(groupId);
    const update = get().update;

    update(groupId, () => ({
      ...freshState(),
      isStreaming: true,
      state: "IN_PROGRESS",
      startedAt: new Date().toISOString(),
    }));

    await consumeStream(
      groupId,
      streamGroupDiscussion(groupId, question, undefined, abort.signal, attachments),
      abort,
      update,
    );
  },

  /**
   * Submit an approve/reject decision for a paused group discussion AND stream
   * the resumed progress over the same connection. Preserves the existing
   * transcript so a live pause→resume appends rather than restarts.
   */
  approveAndStream: async (groupId, gcId, request) => {
    const abort = swapController(groupId);
    const update = get().update;

    update(groupId, (s) => ({
      ...s,
      isStreaming: true,
      state: "IN_PROGRESS",
      conversationId: gcId,
      hitlPause: null,
      hitlResume: null,
      error: null,
      startedAt: s.startedAt ?? new Date().toISOString(),
      activeSpeakers: new Set(),
    }));

    await consumeStream(
      groupId,
      streamGroupApproval(groupId, gcId, request, abort.signal),
      abort,
      update,
    );
  },

  /**
   * Continue a COMPLETED discussion as a new round via SSE streaming.
   * Preserves the existing transcript so the new round appends rather than
   * replaces — same pattern as approveAndStream.
   */
  continueStream: async (groupId, gcId, question) => {
    const abort = swapController(groupId);
    const update = get().update;

    update(groupId, (s) => ({
      ...s,
      isStreaming: true,
      state: "IN_PROGRESS",
      conversationId: gcId,
      error: null,
      errorKind: null,
      startedAt: s.startedAt ?? new Date().toISOString(),
      // Keep transcript (appended by group_start handler), but reset
      // per-round derived fields so stale data doesn't leak into the UI.
      synthesizedAnswer: null,
      // `continueDiscussion` clears both of these server-side — a round that
      // produces no verdict of its own must not display the previous round's.
      decision: null,
      convergence: new Map(),
      currentPhase: null,
      taskPlan: null,
      taskVerifications: new Map(),
      tasksInProgress: new Set(),
      tasksCompleted: new Set(),
      activeSpeakers: new Set(),
      hitlPause: null,
      hitlResume: null,
      cancelInfo: null,
    }));

    await consumeStream(
      groupId,
      streamGroupContinue(groupId, gcId, question, undefined, abort.signal),
      abort,
      update,
    );
  },

  abortStream: (groupId) => {
    abortControllers.get(groupId)?.abort();
    abortControllers.delete(groupId);
    get().update(groupId, (s) => ({ ...s, isStreaming: false }));
  },

  /** Abort any in-flight stream AND fully reset to the initial clean state.
   *  Use this when the user explicitly starts a new discussion (clears stale
   *  transcript, synthesized answer, etc). */
  resetStream: (groupId) => {
    abortControllers.get(groupId)?.abort();
    abortControllers.delete(groupId);
    // Drop the entry rather than storing a blank one: consumers fall back to
    // the shared initial state, and finished transcripts don't accumulate for
    // every board visited this session.
    set((store) => {
      if (!(groupId in store.streams)) return store;
      const rest = { ...store.streams };
      delete rest[groupId];
      return { streams: rest };
    });
  },
}));

type UpdateFn = GroupStreamStore["update"];

/** Drain an SSE event source into the group's state, then settle isStreaming.
 *  Shared by the start / continue / approve-resume flows. */
async function consumeStream(
  groupId: string,
  events: AsyncGenerator<GroupSSEEvent>,
  abort: AbortController,
  update: UpdateFn,
) {
  // A superseded loop (new discussion started, or the user hit Stop) may still
  // run for a tick or fail afterwards. Its writes must not land on the stream
  // that replaced it — otherwise a fresh discussion flips back to "not
  // streaming", or inherits the old one's error.
  const setState = (updater: (s: GroupStreamState) => GroupStreamState) => {
    if (abortControllers.get(groupId) !== abort) return;
    update(groupId, updater);
  };

  try {
    for await (const event of events) {
      const isDone = handleSSEEvent(event, setState);
      if (isDone) {
        abort.abort();
        break;
      }
    }
  } catch (e) {
    // AbortError is expected when we abort after a terminal event
    if (e instanceof DOMException && e.name === "AbortError") {
      // expected — swallow
    } else {
      const errorMsg = e instanceof Error ? e.message : String(e);
      setState((s) => ({
        ...s,
        isStreaming: false,
        state: "FAILED",
        error: errorMsg,
        errorKind: "generic",
      }));
    }
  }

  // Safety-net: if the stream ended without a done event
  setState((s) => (s.isStreaming ? { ...s, isStreaming: false } : s));

  // Unregister once finished. The map is the supersession source of truth, so
  // leaving a dead controller behind would have swapController re-abort an
  // already-aborted one on the next start. Only clear our own entry — a stream
  // that superseded us owns the slot now.
  if (abortControllers.get(groupId) === abort) {
    abortControllers.delete(groupId);
  }
}

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
    useGroupStreamStore((store) => (key ? store.streams[key] : undefined)) ?? initialState;

  const startStream = useCallback(
    async (gid: string, question: string, attachments?: GroupAttachmentRef[]) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().startStream(gid, question, attachments);
    },
    [],
  );

  const continueStream = useCallback(
    async (gid: string, gcId: string, question: string) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().continueStream(gid, gcId, question);
    },
    [],
  );

  const approveAndStream = useCallback(
    async (gid: string, gcId: string, request: GroupApprovalRequest) => {
      setStartedGroupId(gid);
      await useGroupStreamStore.getState().approveAndStream(gid, gcId, request);
    },
    [],
  );

  const abortStream = useCallback(() => {
    if (key) useGroupStreamStore.getState().abortStream(key);
  }, [key]);

  const resetStream = useCallback(() => {
    if (key) useGroupStreamStore.getState().resetStream(key);
  }, [key]);

  return { streamState, startStream, continueStream, approveAndStream, abortStream, resetStream };
}

// ─── Event Handler ──────────────────────────────────────────────

/**
 * Process a single SSE event from the group discussion stream.
 * Returns `true` when the stream is logically complete.
 */
function handleSSEEvent(
  event: GroupSSEEvent,
  setState: (updater: (s: GroupStreamState) => GroupStreamState) => void
): boolean {
  switch (event.type) {
    case "group_start": {
      try {
        const payload: GroupStartPayload = JSON.parse(event.data);
        const questionEntry: TranscriptEntry = {
          speakerAgentId: "user",
          speakerDisplayName: "User",
          content: payload.question,
          phaseIndex: -1,
          phaseName: null,
          type: "QUESTION" as TranscriptEntryType,
          timestamp: new Date().toISOString(),
          errorReason: null,
          targetAgentId: null,
        };
        setState((s) => ({
          ...s,
          conversationId: payload.groupConversationId ?? payload.conversationId,
          state: "IN_PROGRESS",
          // Continuation (conversationId already set by continueStream) →
          // append the new question to the existing transcript.
          // New discussion → replace with just the question.
          transcript: s.conversationId
            ? [...s.transcript, questionEntry]
            : [questionEntry],
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse group_start event:', e);
      }
      return false;
    }

    case "phase_start": {
      try {
        const payload: PhaseStartPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          currentPhase: {
            index: payload.phaseIndex,
            name: payload.phaseName,
            type: payload.phaseType,
          },
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse phase_start event:', e);
      }
      return false;
    }

    case "speaker_start": {
      try {
        const payload: SpeakerStartPayload = JSON.parse(event.data);
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.add(payload.agentId);

          // Track task execution during EXECUTE phase
          let newTasksInProgress = s.tasksInProgress;
          if (s.currentPhase?.type === "EXECUTE" && s.taskPlan) {
            newTasksInProgress = new Set(s.tasksInProgress);
            // Match the next pending task for this speaker by display name
            // (the backend task plan carries assignedTo = display name only).
            const agentTask = s.taskPlan.find(
              (t) =>
                t.assignedTo === payload.displayName &&
                !s.tasksCompleted.has(t.id) &&
                !s.tasksInProgress.has(t.id)
            );
            if (agentTask) {
              newTasksInProgress.add(agentTask.id);
            }
          }

          return {
            ...s,
            activeSpeakers: newSpeakers,
            tasksInProgress: newTasksInProgress,
            // Add a placeholder entry for the active speaker (typing indicator)
            transcript: [
              ...s.transcript,
              {
                speakerAgentId: payload.agentId,
                speakerDisplayName: payload.displayName,
                content: null,
                phaseIndex: payload.phaseIndex,
                phaseName: payload.phaseName,
                type: mapPhaseToEntryType(s.currentPhase?.type),
                timestamp: new Date().toISOString(),
                errorReason: null,
                targetAgentId: null,
              },
            ],
          };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse speaker_start event:', e);
      }
      return false;
    }

    case "speaker_complete": {
      try {
        const payload: SpeakerCompletePayload = JSON.parse(event.data);
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.delete(payload.agentId);

          // Replace the placeholder entry with the real content
          const transcript = [...s.transcript];
          const placeholderIdx = transcript.findIndex(
            (e) =>
              e.speakerAgentId === payload.agentId &&
              e.content === null &&
              e.phaseIndex === payload.phaseIndex
          );

          if (placeholderIdx >= 0) {
            const prev = transcript[placeholderIdx]!;
            transcript[placeholderIdx] = {
              speakerAgentId: prev.speakerAgentId,
              speakerDisplayName: prev.speakerDisplayName,
              content: payload.response ?? payload.content ?? null,
              phaseIndex: prev.phaseIndex,
              phaseName: prev.phaseName,
              type: prev.type,
              timestamp: new Date().toISOString(),
              errorReason: prev.errorReason,
              targetAgentId: prev.targetAgentId,
            };
          } else {
            // No placeholder found — append directly
            transcript.push({
              speakerAgentId: payload.agentId,
              speakerDisplayName: payload.displayName,
              content: payload.response ?? payload.content ?? null,
              phaseIndex: payload.phaseIndex,
              phaseName: payload.phaseName,
              type: mapPhaseToEntryType(s.currentPhase?.type),
              timestamp: new Date().toISOString(),
              errorReason: null,
              targetAgentId: null,
            });
          }

          // Track task completion during EXECUTE phase
          let newTasksInProgress2 = s.tasksInProgress;
          let newTasksCompleted = s.tasksCompleted;
          if (s.currentPhase?.type === "EXECUTE" && s.taskPlan) {
            // Match by display name (see speaker_start above).
            const agentTask = s.taskPlan.find(
              (t) =>
                t.assignedTo === payload.displayName &&
                s.tasksInProgress.has(t.id)
            );
            if (agentTask) {
              newTasksInProgress2 = new Set(s.tasksInProgress);
              newTasksInProgress2.delete(agentTask.id);
              newTasksCompleted = new Set(s.tasksCompleted);
              newTasksCompleted.add(agentTask.id);
            }
          }

          return {
            ...s,
            activeSpeakers: newSpeakers,
            transcript,
            tasksInProgress: newTasksInProgress2,
            tasksCompleted: newTasksCompleted,
          };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse speaker_complete event:', e);
      }
      return false;
    }

    case "task_plan_created": {
      try {
        const payload: TaskPlanCreatedPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          taskPlan: payload.tasks,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse task_plan_created event:', e);
      }
      return false;
    }

    case "task_verified": {
      try {
        const payload: TaskVerifiedPayload = JSON.parse(event.data);
        setState((s) => {
          const newVerifications = new Map(s.taskVerifications);
          newVerifications.set(payload.taskId, {
            passed: payload.passed,
            feedback: payload.feedback,
          });
          return { ...s, taskVerifications: newVerifications };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse task_verified event:', e);
      }
      return false;
    }

    case "convergence_checked": {
      try {
        const payload: ConvergenceCheckedPayload = JSON.parse(event.data);
        setState((s) => {
          const next = new Map(s.convergence);
          const prev = next.get(payload.phaseIndex);
          next.set(payload.phaseIndex, {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            repeat: payload.repeat,
            // -1 is the backend's "no judge ran" sentinel (everyone abstained, or
            // the judge's answer could not be parsed). Surfacing it as a score
            // would render "-1.00 agreement". A non-number is treated the same
            // way rather than stored, so the field's `number | null` holds.
            agreementScore:
              typeof payload.agreementScore === "number" && payload.agreementScore >= 0
                ? payload.agreementScore
                : null,
            converged: payload.converged,
            // Only convergence_reached knows the skipped count, and it is
            // terminal for the phase — so anything already here belongs to this
            // phase and is worth carrying rather than resetting.
            repeatsSkipped: prev?.repeatsSkipped ?? null,
            reason: payload.reason,
          });
          return { ...s, convergence: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse convergence_checked event:', e);
      }
      return false;
    }

    case "convergence_reached": {
      try {
        const payload: ConvergenceReachedPayload = JSON.parse(event.data);
        setState((s) => {
          const next = new Map(s.convergence);
          const prev = next.get(payload.phaseIndex);
          next.set(payload.phaseIndex, {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            repeat: payload.repeat,
            agreementScore: prev?.agreementScore ?? null,
            converged: true,
            repeatsSkipped: payload.repeatsSkipped,
            reason: payload.reason,
          });
          return { ...s, convergence: next };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse convergence_reached event:', e);
      }
      return false;
    }

    case "decision_reached": {
      try {
        const payload: DecisionReachedPayload = JSON.parse(event.data);
        // Guard the shape: this drives a card that reads `.dissents.length` and
        // `.tally`, and a malformed payload should cost the card, not the stream.
        const decision = payload?.decision;
        if (!decision || typeof decision !== "object") {
          console.warn('[SSE] decision_reached carried no decision');
          return false;
        }
        const normalized: DecisionRecord = {
          ...decision,
          dissents: Array.isArray(decision.dissents) ? decision.dissents : [],
        };
        setState((s) => ({ ...s, decision: normalized }));
      } catch (e) {
        console.warn('[SSE] Failed to parse decision_reached event:', e);
      }
      return false;
    }

    case "phase_complete": {
      try {
        JSON.parse(event.data); // validate payload
        setState((s) => ({
          ...s,
          activeSpeakers: new Set(),
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse phase_complete event:', e);
      }
      return false;
    }

    case "synthesis_start": {
      setState((s) => ({
        ...s,
        state: "SYNTHESIZING",
      }));
      return false;
    }

    case "group_complete": {
      try {
        const payload: GroupCompletePayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          isStreaming: false,
          state: "COMPLETED",
          synthesizedAnswer: payload.synthesizedAnswer,
          activeSpeakers: new Set(),
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse group_complete event:', e);
        setState((s) => ({
          ...s,
          isStreaming: false,
          state: "COMPLETED",
          activeSpeakers: new Set(),
        }));
      }
      return true;
    }

    // "group_error" is the generic terminal failure event. It also carries the
    // approve/stream endpoint's expected resume rejections (409 concurrent
    // decision, 400 invalid taskApprovals/note); the backend emits those as
    // "group_error", never a bare "error" (EDDI issue #36).
    case "group_error": {
      let errorMsg = "Unknown error";
      try {
        const payload = JSON.parse(event.data);
        errorMsg = payload.error || payload.message || errorMsg;
      } catch (e) {
        console.warn('[SSE] Failed to parse error event:', e);
        errorMsg = event.data || errorMsg;
      }
      // Config-drift aborts leave the discussion AWAITING_APPROVAL on the
      // backend (the pause is restored) — classify it so the UI can guide the
      // user to fix the config and re-approve rather than treat it as terminal.
      const configDrift = /config changed while paused|fix the config and retry/i.test(errorMsg);
      setState((s) => ({
        ...s,
        isStreaming: false,
        state: "FAILED",
        error: errorMsg,
        errorKind: configDrift ? "config_drift" : "generic",
        activeSpeakers: new Set(),
      }));
      return true;
    }

    case "awaiting_approval": {
      try {
        const payload = JSON.parse(event.data) as {
          phaseIndex: number;
          phaseName: string;
          reason: string;
          granularity: string;
        };
        setState((s) => ({
          ...s,
          state: "AWAITING_APPROVAL" as GroupConversationState,
          hitlPause: {
            phaseIndex: payload.phaseIndex,
            phaseName: payload.phaseName,
            reason: payload.reason,
            granularity: payload.granularity,
          },
          isStreaming: false,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse awaiting_approval event:', e);
      }
      return true;
    }

    case "hitl_resume": {
      try {
        const payload = JSON.parse(event.data) as {
          verdict: string;
          note?: string;
          decidedBy?: string;
        };
        setState((s) => ({
          ...s,
          state: "IN_PROGRESS" as GroupConversationState,
          hitlResume: {
            verdict: payload.verdict,
            note: payload.note,
            decidedBy: payload.decidedBy,
          },
          hitlPause: null,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse hitl_resume event:', e);
      }
      return false;
    }

    case "cancelled": {
      try {
        const payload = JSON.parse(event.data) as {
          reason?: string;
          cancelledBy?: string;
        };
        setState((s) => ({
          ...s,
          state: "CANCELLED" as GroupConversationState,
          cancelInfo: {
            reason: payload.reason,
            cancelledBy: payload.cancelledBy,
          },
          isStreaming: false,
        }));
      } catch (e) {
        console.warn('[SSE] Failed to parse cancelled event:', e);
      }
      return true;
    }

    case "member_pause_skipped": {
      // A member agent's own turn paused for approval (unsupported in a group);
      // the backend records it SKIPPED. Promote the live placeholder to a
      // SKIPPED-with-reason card so the live view matches the reloaded transcript
      // (otherwise the null-content placeholder renders as a bare "No response").
      try {
        const payload = JSON.parse(event.data) as {
          agentId: string;
          displayName: string;
          phaseIndex: number;
          phaseName: string;
          reason: string;
        };
        setState((s) => {
          const newSpeakers = new Set(s.activeSpeakers);
          newSpeakers.delete(payload.agentId);
          const transcript = [...s.transcript];
          const idx = transcript.findIndex(
            (e) =>
              e.speakerAgentId === payload.agentId &&
              e.content === null &&
              e.phaseIndex === payload.phaseIndex,
          );
          if (idx >= 0) {
            const prev = transcript[idx]!;
            transcript[idx] = {
              ...prev,
              type: "SKIPPED" as TranscriptEntryType,
              errorReason: payload.reason,
            };
          }
          return { ...s, activeSpeakers: newSpeakers, transcript };
        });
      } catch (e) {
        console.warn('[SSE] Failed to parse member_pause_skipped event:', e);
      }
      return false;
    }

    default:
      return false;
  }
}

// ─── Helpers ────────────────────────────────────────────────────

/** Map phase type to the TranscriptEntryType used in entries */
function mapPhaseToEntryType(phaseType?: string): TranscriptEntryType {
  switch (phaseType) {
    case "OPINION":
      return "OPINION";
    case "CRITIQUE":
      return "CRITIQUE";
    case "REVISION":
      return "REVISION";
    case "CHALLENGE":
      return "CHALLENGE";
    case "DEFENSE":
      return "DEFENSE";
    case "ARGUE":
      return "ARGUMENT";
    case "REBUTTAL":
      return "REBUTTAL";
    case "SYNTHESIS":
      return "SYNTHESIS";
    case "PLAN":
      return "PLAN";
    case "EXECUTE":
      return "TASK_RESULT";
    case "VERIFY":
      return "VERIFICATION";
    default:
      return "OPINION";
  }
}
