import { useCallback, useRef, useState } from "react";
import {
  startConversation,
  sendMessageStreaming,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import type { OperatorConfig } from "@/lib/api/operator";
import { getErrorMessage } from "@/lib/api-client";

/**
 * Chat state for the Platform Operator.
 *
 * Deliberately a local hook rather than the global chat/debug stores: the
 * operator conversation is a separate thing from whatever the user is testing on
 * the Chat page, and mixing the two would show operator tool calls in the
 * agent-debug drawer (and vice versa).
 */

export interface OperatorChatState {
  messages: ChatMessage[];
  /** Pipeline events for the turn in flight, fed straight to `ChatActivity`. */
  events: PipelineEvent[];
  isStreaming: boolean;
  error: string | null;
  conversationId: string | null;
}

let messageSeq = 0;
function nextId(prefix: string): string {
  messageSeq += 1;
  return `${prefix}-${messageSeq}`;
}

/**
 * Parse an SSE payload into a pipeline event.
 *
 * The backend sends JSON for structured task events but falls back to plain text
 * in some paths, so both are handled — same tolerance as the main chat hook.
 */
function toPipelineEvent(event: SSEEvent): PipelineEvent | null {
  if (event.type !== "task_start" && event.type !== "task_complete" && event.type !== "task_failed") {
    return null;
  }
  let taskId = "unknown";
  let taskType = "unknown";
  let index = 0;
  let durationMs: number | undefined;
  let toolTrace: PipelineEvent["toolTrace"];
  let actions: string[] | undefined;
  let errorType: string | undefined;
  let errorSummary: string | undefined;

  try {
    const parsed = JSON.parse(event.data);
    taskId = parsed.taskId ?? parsed.id ?? "unknown";
    taskType = parsed.taskType ?? parsed.type ?? "unknown";
    index = parsed.index ?? 0;
    durationMs = parsed.durationMs ?? parsed.duration;
    toolTrace = parsed.toolTrace;
    actions = parsed.actions;
    errorType = parsed.errorType;
    errorSummary = parsed.errorSummary;
  } catch {
    taskType = event.data || "unknown";
  }

  return {
    type: event.type,
    taskId,
    taskType,
    index,
    durationMs,
    toolTrace,
    actions,
    errorType,
    errorSummary,
    timestamp: Date.now(),
  };
}

export function useOperatorChat(config: OperatorConfig | null | undefined) {
  const [state, setState] = useState<OperatorChatState>({
    messages: [],
    events: [],
    isStreaming: false,
    error: null,
    conversationId: null,
  });
  const abortRef = useRef<AbortController | null>(null);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState({
      messages: [],
      events: [],
      isStreaming: false,
      error: null,
      conversationId: null,
    });
  }, []);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState((s) => ({ ...s, isStreaming: false }));
  }, []);

  const send = useCallback(
    async (input: string) => {
      if (!config?.agentId || !input.trim()) return;

      const userMessage: ChatMessage = {
        id: nextId("user"),
        role: "user",
        content: input,
        timestamp: Date.now(),
      };
      const agentId = nextId("agent");

      setState((s) => ({
        ...s,
        messages: [
          ...s.messages,
          userMessage,
          {
            id: agentId,
            role: "agent",
            content: "",
            timestamp: Date.now(),
            isStreaming: true,
          },
        ],
        // Activity is per-turn; the previous turn's trace is replaced.
        events: [],
        isStreaming: true,
        error: null,
      }));

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        let conversationId = state.conversationId;
        if (!conversationId) {
          conversationId = await startConversation(config.environment, config.agentId);
          setState((s) => ({ ...s, conversationId }));
        }

        const stream = sendMessageStreaming(
          config.environment,
          config.agentId,
          conversationId,
          { input },
          controller.signal,
        );

        for await (const event of stream) {
          if (event.type === "token") {
            setState((s) => ({
              ...s,
              messages: s.messages.map((m) =>
                m.id === agentId ? { ...m, content: m.content + event.data } : m,
              ),
            }));
            continue;
          }
          if (event.type === "error") {
            setState((s) => ({ ...s, error: event.data || "Stream error" }));
            continue;
          }
          if (event.type === "done") break;

          const pipelineEvent = toPipelineEvent(event);
          if (pipelineEvent) {
            setState((s) => ({ ...s, events: [...s.events, pipelineEvent] }));
          }
        }
      } catch (error) {
        if (!controller.signal.aborted) {
          setState((s) => ({ ...s, error: getErrorMessage(error) }));
        }
      } finally {
        abortRef.current = null;
        setState((s) => ({
          ...s,
          isStreaming: false,
          messages: s.messages.map((m) =>
            m.id === agentId ? { ...m, isStreaming: false } : m,
          ),
        }));
      }
    },
    [config, state.conversationId],
  );

  return { ...state, send, stop, reset };
}
