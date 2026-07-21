/* ──────────────────────────────────────────────
   ChatWidget — Main chat orchestrator
   Manages conversation lifecycle, message flow,
   and composes all sub-components.
   ────────────────────────────────────────────── */

import { useEffect, useRef, useState, useCallback } from "react";
import { useParams, useSearchParams } from "react-router-dom";

import { useChatState, useChatDispatch } from "@/store/chat-store";
import { MessageBubble } from "./MessageBubble";
import { ChatInput } from "./ChatInput";
import { SecretInput } from "./SecretInput";
import { QuickReplies } from "./QuickReplies";
import { TypingIndicator, ThinkingIndicator } from "./Indicators";
import { ScrollToBottom } from "./ScrollToBottom";
import { ChatHeader } from "./ChatHeader";

import {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageStreaming,
  sendManagedAgentMessage,
  undoConversation,
  redoConversation,
  fetchAgentDescriptor,
  rerunLastStep,
  setBaseUrl,
} from "@/api/chat-api";
import { setAuthToken } from "@/api/http";
import {
  isDemoMode,
  demoStartConversation,
  demoSendMessageStreaming,
  demoGetQuickReplies,
} from "@/api/demo-api";
import { buildAttachmentContext } from "@/api/attachments-api";
import { cancelConversation, type ApprovalStatus } from "@/api/hitl-api";
import { useHitlPolling } from "@/hooks/useHitlPolling";
import { PausedCard } from "./PausedCard";
import { ApiError } from "@/api/http";
import {
  parseDoneSnapshot,
  parseErrorMessage,
  isSkippedTurn,
  skippedTurnMessage,
  isPausedState,
  extractOutputTexts,
  isTurnPaused,
} from "@/api/sse-events";
import type {
  ChatMessage,
  SSEEvent,
  ChatConfig,
  OutputItem,
  ConversationState,
} from "@/types";

/**
 * Per-turn mutable bookkeeping for the SSE loop. `tokenCount` is what
 * distinguishes a genuinely empty answer from a turn the server dropped.
 */
interface TurnContext {
  tokenCount: number;
  /**
   * The conversation state as it was when this turn was sent. Required to tell
   * a turn that PAUSED (accepted, then gated) from one the server DROPPED
   * (rejected because the conversation was already paused/busy/ended) — both
   * end with zero tokens and the same conversationState.
   */
  stateBeforeSend: ConversationState | null;
}

function newTurn(stateBeforeSend: ConversationState | null): TurnContext {
  return { tokenCount: 0, stateBeforeSend };
}

function makeAgentMessage(content: string): ChatMessage {
  return {
    id: `agent-${Date.now()}-${Math.random()}`,
    role: "agent",
    content,
    timestamp: Date.now(),
  };
}

/**
 * Color query params → CSS variable mappings.
 * Usage: ?accentColor=%2300ff88&bgColor=%23111111
 * (URL-encode # as %23)
 */
const COLOR_PARAM_MAP: Record<string, string[]> = {
  accentColor:  ["--chat-accent", "--chat-user-bg", "--chat-input-focus"],
  accentHover:  ["--chat-accent-hover"],
  bgColor:      ["--chat-bg"],
  surfaceColor: ["--chat-surface"],
  textColor:    ["--chat-text"],
  textMuted:    ["--chat-text-muted"],
  agentBg:        ["--chat-agent-bg"],
  agentBorder:    ["--chat-agent-border"],
  agentText:      ["--chat-agent-text"],
  userBg:       ["--chat-user-bg"],
  userText:     ["--chat-user-text"],
  inputBg:      ["--chat-input-bg"],
  inputBorder:  ["--chat-input-border"],
  borderColor:  ["--chat-border"],
  headerBg:     ["--chat-surface"],
  fontFamily:   ["--chat-font"],
};

/** Apply color overrides from query params as CSS custom properties */
function applyColorOverrides(params: URLSearchParams): void {
  const root = document.documentElement;
  for (const [param, vars] of Object.entries(COLOR_PARAM_MAP)) {
    const value = params.get(param);
    if (value) {
      for (const cssVar of vars) {
        root.style.setProperty(cssVar, value);
      }
    }
  }
  // accentColor also derives soft/hover variants automatically
  const accent = params.get("accentColor");
  if (accent) {
    root.style.setProperty("--chat-accent-soft", accent + "22");
    root.style.setProperty("--chat-qr-border", accent + "66");
    root.style.setProperty("--chat-qr-bg", accent + "2e");
    root.style.setProperty("--chat-qr-text", accent);
    root.style.setProperty("--chat-qr-hover", accent + "55");
  }
}

/** Read feature toggles from query parameters */
function parseConfigFromQuery(params: URLSearchParams): Partial<ChatConfig> {
  const cfg: Partial<ChatConfig> = {};
  if (params.get("hideUndo") === "true") cfg.enableUndo = false;
  if (params.get("hideRedo") === "true") cfg.enableRedo = false;
  if (params.get("hideNewConversation") === "true") cfg.enableNewConversation = false;
  if (params.get("hideQuickReplies") === "true") cfg.enableQuickReplies = false;
  if (params.get("hideStreaming") === "true") cfg.enableStreaming = false;
  if (params.get("hideLogo") === "true") cfg.showLogo = false;
  if (params.get("hideAgentName") === "true") cfg.showAgentName = false;
  if (params.get("theme")) cfg.theme = params.get("theme") as ChatConfig["theme"];
  if (params.get("title")) cfg.title = params.get("title")!;
  if (params.get("accentColor")) cfg.accentColor = params.get("accentColor")!;
  return cfg;
}

export function ChatWidget() {
  const state = useChatState();
  const dispatch = useChatDispatch();

  const { environment, agentId, userId: userIdParam, intent } = useParams();
  const [searchParams] = useSearchParams();

  const userId =
    userIdParam ?? searchParams.get("userId") ?? undefined;
  const apiServer = searchParams.get("apiServer");
  const isManagedAgent = !!intent;
  const isDemo = isDemoMode(environment, agentId);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const [showScrollBtn, setShowScrollBtn] = useState(false);
  const initializedRef = useRef(false);
  /** Controller for the in-flight SSE read, so it can be stopped on demand. */
  const abortRef = useRef<AbortController | null>(null);
  /**
   * Live conversation state. handleSend must read this at CLICK time; taking it
   * from the callback closure made it stale by however long the memo lived, so
   * skipped-turn detection compared against an out-of-date state.
   */
  const conversationStateRef = useRef(state.conversationState);
  conversationStateRef.current = state.conversationState;
  /** The raw input of the in-flight turn, for restoring it if the server refuses. */
  const pendingTurnRef = useRef<{
    text: string;
    attachments: typeof state.pendingAttachments;
  }>({ text: "", attachments: [] });

  /* ─── Apply query param config + colors on mount ── */
  useEffect(() => {
    const queryCfg = parseConfigFromQuery(searchParams);
    if (Object.keys(queryCfg).length > 0) {
      dispatch({ type: "SET_CONFIG", config: queryCfg });
    }
    // Apply CSS custom property color overrides
    applyColorOverrides(searchParams);
  }, [searchParams, dispatch]);


  /* ─── Set base URL + auth on mount ──────────── */
  useEffect(() => {
    setBaseUrl(apiServer ?? state.config.apiBaseUrl ?? "");
  }, [apiServer, state.config.apiBaseUrl]);

  useEffect(() => {
    // Query param is a convenience for embedding; config is the real channel.
    setAuthToken(searchParams.get("token") ?? state.config.authToken ?? null);
  }, [searchParams, state.config.authToken]);

  /* ─── SSE event handler (declared early to avoid reference issues) ──
     Returns `true` when the stream is logically complete (done / error),
     so the caller can break out of the for-await loop.                  */
  const handleSSEEvent = useCallback(
    (event: SSEEvent, turn: TurnContext): boolean => {
      switch (event.type) {
        case "token":
          turn.tokenCount += 1;
          dispatch({ type: "SET_THINKING", value: false });
          dispatch({ type: "APPEND_TO_LAST_AGENT", token: event.data });
          return false;

        // Pipeline progress. The backend emits no "thinking" event, so these
        // are what actually tell us the agent is working before any text.
        case "task_start":
          if (turn.tokenCount === 0) {
            dispatch({ type: "SET_THINKING", value: true });
          }
          return false;

        case "task_complete":
          return false;

        case "task_failed":
          // Structured per-task failure (#593). The turn may still recover
          // (cascade escalation, retry), so `done`/`error` decides the final
          // outcome — but stop implying the agent is still composing.
          dispatch({ type: "SET_THINKING", value: false });
          return false;

        // Cascade progress is observability, not chat content.
        case "cascade_step_start":
        case "cascade_escalation":
          return false;

        case "done": {
          const snapshot = parseDoneSnapshot(event.data);
          const lastOutput = snapshot?.conversationOutputs?.length
            ? snapshot.conversationOutputs[snapshot.conversationOutputs.length - 1]
            : undefined;
          const outputText = extractOutputTexts(lastOutput?.output).join("\n\n");

          if (isSkippedTurn(snapshot, turn.tokenCount, turn.stateBeforeSend)) {
            // The server dropped this turn without consuming it. The payload
            // carries the PREVIOUS step's outputs, so its quick replies must
            // not be applied — doing so re-offered stale buttons as if new.
            dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(
                skippedTurnMessage(snapshot?.conversationState),
              ),
            });
          } else if (turn.tokenCount === 0) {
            // No tokens, but the turn WAS accepted. Any text lives only in the
            // done payload — including HITL's pending-approval placeholder,
            // which arrives as a bare string. Dropping it left a paused turn
            // rendering as "No response".
            dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            if (outputText) {
              dispatch({
                type: "ADD_MESSAGE",
                message: makeAgentMessage(outputText),
              });
            }
            if (!isTurnPaused(snapshot, turn.stateBeforeSend)) {
              dispatch({
                type: "SET_QUICK_REPLIES",
                replies: lastOutput?.quickReplies ?? [],
              });
            }
          } else if (snapshot?.conversationOutputs?.length) {
            // Tokens already rendered the answer, but responseValidation can
            // SUPERSEDE them (fallback text) after the fact. The snapshot is
            // authoritative.
            if (outputText) {
              dispatch({ type: "RECONCILE_LAST_AGENT", content: outputText });
            }
            dispatch({
              type: "SET_QUICK_REPLIES",
              replies: lastOutput?.quickReplies ?? [],
            });
          }

          if (snapshot?.conversationState) {
            dispatch({
              type: "SET_CONVERSATION_STATE",
              state: snapshot.conversationState,
            });
          }

          dispatch({ type: "FINISH_STREAMING" });
          dispatch({ type: "SET_PROCESSING", value: false });
          return true;
        }

        case "error": {
          // Payload is {"message":"…"}, not a bare string.
          const message = parseErrorMessage(event.data);
          if (turn.tokenCount === 0) {
            dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(`⚠️ ${message}`),
            });
          } else {
            dispatch({
              type: "APPEND_TO_LAST_AGENT",
              token: `\n\n⚠️ ${message}`,
            });
          }
          dispatch({ type: "FINISH_STREAMING" });
          dispatch({ type: "SET_PROCESSING", value: false });
          return true;
        }

        default:
          // Unknown event: ignore rather than render. New backend events must
          // never leak into the transcript as visible text.
          return false;
      }
    },
    [dispatch],
  );

  /* ─── Process conversation snapshot ─────────── */
  const processSnapshot = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (snapshot: any) => {
      // Managed-agent mode never calls startConversation, so without this the
      // widget has no conversationId — disabling HITL polling, cancel, retry
      // and attachments for the entire managed route.
      if (snapshot.conversationId) {
        dispatch({ type: "SET_CONVERSATION_ID", id: snapshot.conversationId });
      }
      if (snapshot.conversationState) {
        dispatch({
          type: "SET_CONVERSATION_STATE",
          state: snapshot.conversationState,
        });
      }

      // Track undo/redo availability
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? false,
        redoAvailable: snapshot.redoAvailable ?? false,
      });

      // Handle the "conversationOutputs" format (from POST /agents responses)
      if (snapshot.conversationOutputs?.length) {
        for (const output of snapshot.conversationOutputs) {
          // Extract agent replies and detect input field requests
          const agentReplies: unknown[] = output.output ?? [];

          // inputField items configure the composer rather than the transcript.
          for (const reply of agentReplies) {
            if (
              reply &&
              typeof reply === "object" &&
              (reply as OutputItem).type === "inputField"
            ) {
              const field = reply as OutputItem;
              dispatch({
                type: "SET_INPUT_FIELD",
                field: {
                  subType: field.subType || "password",
                  placeholder: field.placeholder,
                  label: field.label,
                  defaultValue: field.defaultValue,
                },
              });
            }
          }

          // Handles bare-string entries too — HITL's pending-approval
          // placeholder and reviewer-rejection message arrive as raw strings.
          for (const text of extractOutputTexts(agentReplies)) {
            dispatch({
              type: "ADD_MESSAGE",
              message: makeAgentMessage(text),
            });
          }
        }

        // Quick replies from the last output (most recent step)
        const lastOutput =
          snapshot.conversationOutputs[snapshot.conversationOutputs.length - 1];
        dispatch({
          type: "SET_QUICK_REPLIES",
          replies: lastOutput.quickReplies ?? [],
        });
      }

      // Handle the "conversationSteps" format (from GET responses / welcome messages)
      if (snapshot.conversationSteps?.length) {
        for (const step of snapshot.conversationSteps) {
          if (step.input) {
            dispatch({
              type: "ADD_MESSAGE",
              message: {
                id: `user-${Date.now()}-${Math.random()}`,
                role: "user",
                content: step.input,
                timestamp: Date.now(),
              },
            });
          }
          if (step.output) {
            dispatch({
              type: "ADD_MESSAGE",
              message: {
                id: `agent-${Date.now()}-${Math.random()}`,
                role: "agent",
                content: step.output,
                timestamp: Date.now(),
              },
            });
          }
        }
      }
    },
    [dispatch],
  );

  /* ─── Auto-start conversation ───────────────── */
  useEffect(() => {
    if (initializedRef.current) return;
    initializedRef.current = true;

    const init = async () => {
      try {
        if (isDemo) {
          // Demo mode: use mock data
          const result = await demoStartConversation();
          dispatch({ type: "SET_CONVERSATION_ID", id: result.conversationId });
          dispatch({ type: "ADD_MESSAGE", message: result.welcomeMessage });
          dispatch({ type: "SET_QUICK_REPLIES", replies: result.quickReplies });
          dispatch({ type: "SET_CONVERSATION_STATE", state: "READY" });
        } else if (isManagedAgent && intent && userId) {
          // Managed agent: GET to load existing or start new
          const snapshot = await sendManagedAgentMessage(intent, userId);
          processSnapshot(snapshot);
        } else if (environment && agentId) {
          // Direct agent: POST to create conversation
          const convId = await startConversation(
            environment,
            agentId,
            userId,
          );
          dispatch({ type: "SET_CONVERSATION_ID", id: convId });

          // GET to pick up welcome message
          const snapshot = await readConversation(
            environment,
            agentId,
            convId,
          );
          processSnapshot(snapshot);
        }
      } catch (err) {
        console.error("Failed to start conversation:", err);
      }
    };

    init();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ─── Fetch agent name ────────────────────────── */
  useEffect(() => {
    if (isDemo || !agentId || state.config.showAgentName === false) return;
    fetchAgentDescriptor(agentId).then((desc) => {
      if (desc.name) {
        dispatch({ type: "SET_AGENT_NAME", name: desc.name });
      }
    }).catch(() => { /* swallow */ });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, isDemo]);

  /* ─── Send message ──────────────────────────── */
  const handleSend = useCallback(
    async (text: string, isSecret?: boolean) => {
      // Attachments staged in the composer travel with THIS turn as
      // attachment_N context entries — the only path the backend reads.
      const attachments = state.pendingAttachments;
      const attachmentContext = buildAttachmentContext(attachments);

      const secretContext = isSecret
        ? { secretInput: { type: "string" as const, value: "true" } }
        : undefined;

      const context =
        secretContext || Object.keys(attachmentContext).length > 0
          ? { ...secretContext, ...attachmentContext }
          : undefined;

      // Add user message (display masked if secret). Attachments are named in
      // the transcript so the user can see what was actually sent.
      const attachmentLine = attachments.length
        ? attachments.map((a) => `📎 ${a.fileName}`).join("\n")
        : "";
      const displayed = isSecret ? "●●●●●●●●" : text;
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}-${Math.random()}`,
        role: "user",
        content: [attachmentLine, displayed].filter(Boolean).join("\n\n"),
        timestamp: Date.now(),
      };
      // Remember the REAL input: the bubble content is display text (masked
      // secrets, "📎 name" lines) and must never be what we hand back.
      pendingTurnRef.current = { text, attachments };

      dispatch({ type: "ADD_MESSAGE", message: userMsg });
      dispatch({ type: "CLEAR_ATTACHMENTS" });
      dispatch({ type: "SET_QUICK_REPLIES", replies: [] });
      dispatch({ type: "SET_PROCESSING", value: true });
      dispatch({ type: "SET_THINKING", value: true });

      try {
        if (isDemo) {
          // Demo mode: simulated streaming response
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `agent-${Date.now()}-${Math.random()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          const events = demoSendMessageStreaming(text);
          const demoTurn = newTurn(conversationStateRef.current);
          let demoDone = false;
          for await (const event of events) {
            if (handleSSEEvent(event, demoTurn)) demoDone = true;
          }
          // Safety net: finish streaming if the stream closed without a done event
          if (!demoDone) dispatch({ type: "FINISH_STREAMING" });

          // Set quick replies after streaming completes
          const qrs = demoGetQuickReplies(text);
          dispatch({ type: "SET_QUICK_REPLIES", replies: qrs });
        } else if (isManagedAgent && intent && userId) {
          // Managed agent (non-streaming only)
          const snapshot = await sendManagedAgentMessage(intent, userId, text);
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        } else if (
          state.config.enableStreaming &&
          environment &&
          agentId &&
          state.conversationId
        ) {
          // Streaming path
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `agent-${Date.now()}-${Math.random()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          // AbortController: break out of the for-await when "done" fires.
          // Proxies (Vite dev, nginx) may not forward the SSE close signal,
          // so reader.read() would hang forever without this.
          const abort = new AbortController();
          abortRef.current = abort;
          const turn = newTurn(conversationStateRef.current);
          let streamDone = false;

          const events = sendMessageStreaming(
            environment,
            agentId,
            state.conversationId,
            text,
            context,
            abort.signal,
          );

          try {
            for await (const event of events) {
              const isDone = handleSSEEvent(event, turn);
              if (isDone) {
                streamDone = true;
                abort.abort();
                break;
              }
            }
          } catch (e) {
            if (e instanceof DOMException && e.name === "AbortError") {
              // expected after abort — swallow
            } else {
              throw e;
            }
          }
          // Safety net: finish streaming if the stream closed without a done event
          if (!streamDone) dispatch({ type: "FINISH_STREAMING" });

          // The `done` payload is a trimmed snapshot carrying only
          // conversationState and conversationOutputs — undoAvailable and
          // redoAvailable are absent, so without this re-read the undo/redo
          // buttons stay permanently greyed out on the streaming path.
          try {
            const after = await readConversation(
              environment,
              agentId,
              state.conversationId,
              true,
            );
            dispatch({
              type: "SET_UNDO_REDO",
              undoAvailable: after.undoAvailable ?? false,
              redoAvailable: after.redoAvailable ?? false,
            });
          } catch {
            // Availability refresh is best-effort; a failure here must not
            // sink an otherwise successful turn.
          }
        } else if (environment && agentId && state.conversationId) {
          // Non-streaming path — pass context for secret input
          const snapshot = await sendMessage(
            environment,
            agentId,
            state.conversationId,
            text,
            userId,
            context,
          );
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        }
      } catch (err) {
        dispatch({ type: "SET_PROCESSING", value: false });
        dispatch({ type: "SET_THINKING", value: false });

        if (err instanceof ApiError && err.status === 409) {
          // The turn was refused and NEVER consumed — most often because the
          // conversation is awaiting a human decision. Withdraw the optimistic
          // bubble, hand the text back to the composer, and say why.
          dispatch({
            type: "WITHDRAW_LAST_USER_MESSAGE",
            draft: pendingTurnRef.current.text,
            attachments: pendingTurnRef.current.attachments,
          });
          dispatch({
            type: "ADD_MESSAGE",
            message: makeAgentMessage(
              err.body?.trim()
                ? `⚠️ ${err.body.trim()}`
                : "⚠️ Your message was not sent — this conversation is waiting on a decision.",
            ),
          });
          // Re-read so the paused state (and its card) appears immediately.
          if (environment && agentId && state.conversationId) {
            try {
              const snap = await readConversation(
                environment,
                agentId,
                state.conversationId,
                true,
              );
              if (snap.conversationState) {
                dispatch({
                  type: "SET_CONVERSATION_STATE",
                  state: snap.conversationState,
                });
              }
            } catch {
              // best effort
            }
          }
          return;
        }

        if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
          dispatch({
            type: "WITHDRAW_LAST_USER_MESSAGE",
            draft: pendingTurnRef.current.text,
            attachments: pendingTurnRef.current.attachments,
          });
          dispatch({
            type: "ADD_MESSAGE",
            message: makeAgentMessage(
              "⚠️ You are not allowed to continue this conversation. It may belong to a different user.",
            ),
          });
          return;
        }

        console.error("Failed to send message:", err);
        dispatch({
          type: "ADD_MESSAGE",
          message: makeAgentMessage(
            "⚠️ Your message could not be sent. Please try again.",
          ),
        });
      }
    },
    [
      dispatch,
      processSnapshot,
      handleSSEEvent,
      isDemo,
      isManagedAgent,
      intent,
      userId,
      environment,
      agentId,
      state.conversationId,
      state.config.enableStreaming,
      state.pendingAttachments,
    ],
  );

  /* ─── Undo ──────────────────────────────────── */
  const handleUndo = useCallback(async () => {
    if (!environment || !agentId || !state.conversationId) return;
    if (isDemo) return; // Demo mode doesn't support undo

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      // undo returns 200 with an EMPTY body; re-read the snapshot to rebuild.
      await undoConversation(environment, agentId, state.conversationId);
      const snapshot = await readConversation(
        environment,
        agentId,
        state.conversationId,
      );

      // Rebuild messages from the full snapshot
      const msgs: ChatMessage[] = [];
      for (const step of snapshot.conversationSteps ?? []) {
        if (step.input) {
          msgs.push({
            id: `user-${msgs.length}-${Date.now()}`,
            role: "user",
            content: step.input,
            timestamp: Date.now(),
          });
        }
        if (step.output) {
          msgs.push({
            id: `agent-${msgs.length}-${Date.now()}`,
            role: "agent",
            content: step.output,
            timestamp: Date.now(),
          });
        }
      }
      dispatch({ type: "REPLACE_MESSAGES", messages: msgs });
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? false,
        redoAvailable: snapshot.redoAvailable ?? true,
      });
    } catch (err) {
      // 409 is expected while the conversation is paused or a turn is running —
      // undo/redo availability is deliberately NOT pause-aware server-side, so
      // the button can be enabled while the operation is refused.
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ Undo is not possible right now."
            : "⚠️ Undo failed.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, environment, agentId, state.conversationId, isDemo]);

  /* ─── Redo ──────────────────────────────────── */
  const handleRedo = useCallback(async () => {
    if (!environment || !agentId || !state.conversationId) return;
    if (isDemo) return;

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      // redo returns 200 with an EMPTY body; re-read the snapshot to rebuild.
      await redoConversation(environment, agentId, state.conversationId);
      const snapshot = await readConversation(
        environment,
        agentId,
        state.conversationId,
      );

      const msgs: ChatMessage[] = [];
      for (const step of snapshot.conversationSteps ?? []) {
        if (step.input) {
          msgs.push({
            id: `user-${msgs.length}-${Date.now()}`,
            role: "user",
            content: step.input,
            timestamp: Date.now(),
          });
        }
        if (step.output) {
          msgs.push({
            id: `agent-${msgs.length}-${Date.now()}`,
            role: "agent",
            content: step.output,
            timestamp: Date.now(),
          });
        }
      }
      dispatch({ type: "REPLACE_MESSAGES", messages: msgs });
      dispatch({
        type: "SET_UNDO_REDO",
        undoAvailable: snapshot.undoAvailable ?? true,
        redoAvailable: snapshot.redoAvailable ?? false,
      });
    } catch (err) {
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ Redo is not possible right now."
            : "⚠️ Redo failed.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, environment, agentId, state.conversationId, isDemo]);

  /* ─── Quick reply handler ───────────────────── */
  const handleQuickReply = useCallback(
    (value: string) => {
      handleSend(value);
    },
    [handleSend],
  );

  /* ─── Restart conversation ──────────────────── */
  const handleRestart = useCallback(async () => {
    dispatch({ type: "CLEAR_MESSAGES" });
    // Re-init conversation
    try {
      if (isDemo) {
        const result = await demoStartConversation();
        dispatch({ type: "SET_CONVERSATION_ID", id: result.conversationId });
        dispatch({ type: "ADD_MESSAGE", message: result.welcomeMessage });
        dispatch({ type: "SET_QUICK_REPLIES", replies: result.quickReplies });
        dispatch({ type: "SET_CONVERSATION_STATE", state: "READY" });
      } else if (isManagedAgent && intent && userId) {
        // Managed agent: GET to load or re-initialize conversation
        const snapshot = await sendManagedAgentMessage(intent, userId);
        processSnapshot(snapshot);
      } else if (environment && agentId) {
        const convId = await startConversation(environment, agentId, userId);
        dispatch({ type: "SET_CONVERSATION_ID", id: convId });
        const snapshot = await readConversation(environment, agentId, convId);
        processSnapshot(snapshot);
      }
    } catch (err) {
      console.error("Failed to restart conversation:", err);
    }
  }, [dispatch, isDemo, isManagedAgent, intent, environment, agentId, userId, processSnapshot]);

  /* ─── HITL: watch a paused conversation ─────── */
  const isPaused = isPausedState(state.conversationState);

  const handleApprovalStatus = useCallback(
    (status: ApprovalStatus | null) => {
      dispatch({ type: "SET_APPROVAL_STATUS", status });
    },
    [dispatch],
  );

  const handlePauseResolved = useCallback(async (): Promise<boolean> => {
    // The pause ended — by a reviewer, or automatically by timeout policy.
    // Nothing was pushed to us, so re-read to pick up the resumed turn.
    // Returning false keeps the watch alive: a dropped refresh must not strand
    // the widget in a paused state it can never leave.
    if (!state.conversationId) return false;
    try {
      const snapshot = await readConversation(
        environment ?? "",
        agentId ?? "",
        state.conversationId,
        true,
      );
      processSnapshot(snapshot);
      return true;
    } catch (err) {
      console.error("Failed to refresh after approval:", err);
      return false;
    }
  }, [environment, agentId, state.conversationId, processSnapshot]);

  useHitlPolling({
    conversationId: state.conversationId,
    paused: isPaused && !isDemo,
    onStatus: handleApprovalStatus,
    onResolved: handlePauseResolved,
  });

  const handleCancel = useCallback(async () => {
    if (!state.conversationId) return;
    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      await cancelConversation(state.conversationId);
      dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage("This request was cancelled."),
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // Nothing to cancel — it resolved between render and click.
        dispatch({ type: "SET_APPROVAL_STATUS", status: null });
      } else {
        console.error("Cancel failed:", err);
      }
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, state.conversationId]);

  /* ─── Recovery from a stuck conversation ────── */
  const isStuck =
    state.conversationState === "ERROR" ||
    state.conversationState === "EXECUTION_INTERRUPTED";

  const handleRetry = useCallback(async () => {
    if (!environment || !agentId || !state.conversationId) return;
    dispatch({ type: "SET_PROCESSING", value: true });
    try {
      await rerunLastStep(state.conversationId);
      const snapshot = await readConversation(
        environment,
        agentId,
        state.conversationId,
        true,
      );
      if (snapshot.conversationState) {
        dispatch({ type: "SET_CONVERSATION_STATE", state: snapshot.conversationState });
      }
      processSnapshot(snapshot);
    } catch (err) {
      dispatch({
        type: "ADD_MESSAGE",
        message: makeAgentMessage(
          err instanceof ApiError && err.status === 409
            ? "⚠️ There is nothing to retry right now."
            : "⚠️ Retrying failed. You can start a new conversation instead.",
        ),
      });
    } finally {
      dispatch({ type: "SET_PROCESSING", value: false });
    }
  }, [dispatch, environment, agentId, state.conversationId, processSnapshot]);

  /* ─── Stop generating ───────────────────────── */
  const handleStop = useCallback(async () => {
    // Abort the local read first so tokens stop arriving immediately, then ask
    // the server to stop producing them.
    abortRef.current?.abort();
    abortRef.current = null;
    dispatch({ type: "FINISH_STREAMING" });
    dispatch({ type: "SET_PROCESSING", value: false });
    dispatch({ type: "SET_THINKING", value: false });

    if (!state.conversationId || isDemo) return;
    try {
      await cancelConversation(state.conversationId);
      dispatch({ type: "SET_CONVERSATION_STATE", state: "EXECUTION_INTERRUPTED" });
    } catch (err) {
      // 409 = nothing to cancel; the turn finished as we clicked.
      if (!(err instanceof ApiError && err.status === 409)) {
        console.error("Stop failed:", err);
      }
    }
  }, [dispatch, state.conversationId, isDemo]);

  /* ─── Auto-scroll ───────────────────────────── */
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [state.messages, state.isProcessing]);

  const handleScroll = useCallback(() => {
    const el = messagesContainerRef.current;
    if (!el) return;
    const atBottom =
      el.scrollHeight - el.scrollTop - el.clientHeight <= 20;
    setShowScrollBtn(!atBottom);
  }, []);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  /* ─── Derived state ─────────────────────────── */
  const isEnded = state.conversationState === "ENDED";
  const showInput =
    !isEnded && (state.quickReplies.length === 0 || state.messages.length > 0);

  return (
    <div className="chat-root">
      <ChatHeader />

      <div
        className="chat-messages"
        ref={messagesContainerRef}
        onScroll={handleScroll}
      >
        {state.messages.length === 0 && !state.isProcessing && !isPaused ? (
          <div className="chat-empty">
            <div className="chat-empty__icon">💬</div>
            <p className="chat-empty__text">
              Starting conversation…
            </p>
          </div>
        ) : (
          <>
            {state.messages.map((msg) => (
              <MessageBubble key={msg.id} message={msg} />
            ))}

            {isPaused && state.approvalStatus ? (
              <PausedCard
                status={state.approvalStatus}
                onCancel={handleCancel}
                cancelDisabled={state.isProcessing}
              />
            ) : (
              <>
                {state.isThinking && <ThinkingIndicator />}
                {state.isProcessing && !state.isThinking && <TypingIndicator />}
              </>
            )}

            <div ref={messagesEndRef} />
          </>
        )}
      </div>

      <div style={{ position: "relative" }}>
        <ScrollToBottom visible={showScrollBtn} onClick={scrollToBottom} />
      </div>

      {!isEnded && !isPaused &&
        state.config.enableQuickReplies !== false && (
          <QuickReplies
            replies={state.quickReplies}
            onSelect={handleQuickReply}
          />
        )}

      {isStuck && (
        <div className="recovery-banner" role="status" data-testid="recovery-banner">
          <span className="recovery-banner__text">
            {state.conversationState === "EXECUTION_INTERRUPTED"
              ? "This request was interrupted before it finished."
              : "Something went wrong on the last step."}
          </span>
          <div className="recovery-banner__actions">
            <button
              className="recovery-banner__btn"
              onClick={handleRetry}
              disabled={state.isProcessing}
              data-testid="recovery-retry"
            >
              Try again
            </button>
            <button
              className="recovery-banner__btn"
              onClick={handleRestart}
              disabled={state.isProcessing}
              data-testid="recovery-restart"
            >
              Start over
            </button>
          </div>
        </div>
      )}

      {isEnded ? (
        <div className="chat-ended">
          <span className="chat-ended__label">Conversation Ended</span>
          <button className="chat-ended__restart" onClick={handleRestart}>
            Start New Conversation
          </button>
        </div>
      ) : (
        showInput && (
          <div className="chat-input-area">
            {/* Action bar — undo / redo / restart near the input */}
            {state.conversationId && (
              <div className="chat-actions">
                <div className="chat-actions__left">
                  {state.config.enableUndo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleUndo}
                      disabled={!state.undoAvailable || state.isProcessing}
                      title="Undo last message"
                      data-testid="undo-btn"
                      style={{ opacity: state.undoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      ↩
                    </button>
                  )}
                  {state.config.enableRedo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleRedo}
                      disabled={!state.redoAvailable || state.isProcessing}
                      title="Redo message"
                      data-testid="redo-btn"
                      style={{ opacity: state.redoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      ↪
                    </button>
                  )}
                </div>
                <div className="chat-actions__right">
                  {state.isProcessing && !isPaused && (
                    <button
                      className="chat-actions__btn chat-actions__btn--stop"
                      onClick={handleStop}
                      title="Stop generating"
                      aria-label="Stop generating"
                      data-testid="chat-stop"
                    >
                      ■
                    </button>
                  )}
                  {state.config.enableNewConversation !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={handleRestart}
                      title="New conversation"
                      data-testid="restart-btn"
                    >
                      ↻
                    </button>
                  )}
                </div>
              </div>
            )}

            {state.activeInputField ? (
              <SecretInput
                label={state.activeInputField.label}
                placeholder={state.activeInputField.placeholder}
                defaultValue={state.activeInputField.defaultValue}
                subType={state.activeInputField.subType}
                onSend={handleSend}
                disabled={state.isProcessing}
              />
            ) : (
              <ChatInput
                onSend={handleSend}
                disabled={(!state.conversationId && !isManagedAgent) || isPaused}
                conversationId={state.conversationId}
              />
            )}
          </div>
        )
      )}
    </div>
  );
}
