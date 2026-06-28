import { useCallback, useRef, useState } from "react";
import {
  streamGroupDiscussion,
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
} from "@/lib/api/groups";

// ─── Streaming State ────────────────────────────────────────────

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
  /** Error message if the discussion failed */
  error: string | null;
  /** Timestamp when the stream was started (stable, not recalculated per render) */
  startedAt: string | null;
  /** Task plan received from task_plan_created SSE event */
  taskPlan: { id: string; subject: string; assignedTo: string; assignedAgentId?: string; priority: number }[] | null;
  /** Task verification results from task_verified SSE events */
  taskVerifications: Map<string, { passed: boolean; feedback: string }>;
  /** Set of task IDs currently being executed (inferred from speaker events during EXECUTE phase) */
  tasksInProgress: Set<string>;
  /** Set of task IDs completed (inferred from speaker events during EXECUTE phase) */
  tasksCompleted: Set<string>;
}

const initialState: GroupStreamState = {
  isStreaming: false,
  conversationId: null,
  state: "CREATED",
  transcript: [],
  currentPhase: null,
  activeSpeakers: new Set(),
  synthesizedAnswer: null,
  error: null,
  startedAt: null,
  taskPlan: null,
  taskVerifications: new Map(),
  tasksInProgress: new Set(),
  tasksCompleted: new Set(),
};

// ─── Hook ───────────────────────────────────────────────────────

/**
 * Hook for SSE-streamed group discussions.
 *
 * Usage:
 *   const { streamState, startStream, abortStream } = useGroupDiscussionStream();
 *   startStream(groupId, question);  // starts SSE
 *   // streamState updates in real-time as events arrive
 */
export function useGroupDiscussionStream() {
  const [streamState, setStreamState] = useState<GroupStreamState>(initialState);
  const abortRef = useRef<AbortController | null>(null);

  const startStream = useCallback(async (groupId: string, question: string) => {
    // Abort any existing stream
    abortRef.current?.abort();
    const abort = new AbortController();
    abortRef.current = abort;

    // Reset state with fresh collection instances (don't reuse shared refs from initialState)
    setStreamState({
      ...initialState,
      isStreaming: true,
      state: "IN_PROGRESS",
      startedAt: new Date().toISOString(),
      activeSpeakers: new Set(),
      tasksInProgress: new Set(),
      tasksCompleted: new Set(),
      taskVerifications: new Map(),
    });

    try {
      const events = streamGroupDiscussion(groupId, question, undefined, abort.signal);

      for await (const event of events) {
        const isDone = handleSSEEvent(event, setStreamState);
        if (isDone) {
          abort.abort();
          break;
        }
      }
    } catch (e) {
      // AbortError is expected when we abort after "group_complete"
      if (e instanceof DOMException && e.name === "AbortError") {
        // expected — swallow
      } else {
        const errorMsg = e instanceof Error ? e.message : String(e);
        setStreamState((s) => ({
          ...s,
          isStreaming: false,
          state: "FAILED",
          error: errorMsg,
        }));
      }
    }

    // Safety-net: if the stream ended without a done event
    setStreamState((s) => {
      if (s.isStreaming) {
        return { ...s, isStreaming: false };
      }
      return s;
    });
  }, []);

  const abortStream = useCallback(() => {
    abortRef.current?.abort();
    setStreamState((s) => ({
      ...s,
      isStreaming: false,
    }));
  }, []);

  return { streamState, startStream, abortStream };
}

// ─── Event Handler ──────────────────────────────────────────────

/**
 * Process a single SSE event from the group discussion stream.
 * Returns `true` when the stream is logically complete.
 */
function handleSSEEvent(
  event: GroupSSEEvent,
  setState: React.Dispatch<React.SetStateAction<GroupStreamState>>
): boolean {
  switch (event.type) {
    case "group_start": {
      try {
        const payload: GroupStartPayload = JSON.parse(event.data);
        setState((s) => ({
          ...s,
          conversationId: payload.groupConversationId ?? payload.conversationId,
          state: "IN_PROGRESS",
          // Add the original question as the first transcript entry
          transcript: [
            {
              speakerAgentId: "user",
              speakerDisplayName: "User",
              content: payload.question,
              phaseIndex: -1,
              phaseName: null,
              type: "QUESTION" as TranscriptEntryType,
              timestamp: new Date().toISOString(),
              errorReason: null,
              targetAgentId: null,
            },
          ],
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
            // Find the next pending task for this agent — prefer agentId, fall back to displayName
            const agentTask = s.taskPlan.find(
              (t) =>
                (t.assignedAgentId
                  ? t.assignedAgentId === payload.agentId
                  : t.assignedTo === payload.displayName) &&
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
            // Prefer agentId matching, fall back to displayName
            const agentTask = s.taskPlan.find(
              (t) =>
                (t.assignedAgentId
                  ? t.assignedAgentId === payload.agentId
                  : t.assignedTo === payload.displayName) &&
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

    case "group_error": {
      let errorMsg = "Unknown error";
      try {
        const payload = JSON.parse(event.data);
        errorMsg = payload.error || payload.message || errorMsg;
      } catch (e) {
        console.warn('[SSE] Failed to parse group_error event:', e);
        errorMsg = event.data || errorMsg;
      }
      setState((s) => ({
        ...s,
        isStreaming: false,
        state: "FAILED",
        error: errorMsg,
        activeSpeakers: new Set(),
      }));
      return true;
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
