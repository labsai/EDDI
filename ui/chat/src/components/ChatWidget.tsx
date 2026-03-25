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
  setBaseUrl,
} from "@/api/chat-api";
import {
  isDemoMode,
  demoStartConversation,
  demoSendMessageStreaming,
  demoGetQuickReplies,
} from "@/api/demo-api";
import type { ChatMessage, SSEEvent, ChatConfig, OutputItem } from "@/types";

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

  /* ─── Apply query param config + colors on mount ── */
  useEffect(() => {
    const queryCfg = parseConfigFromQuery(searchParams);
    if (Object.keys(queryCfg).length > 0) {
      dispatch({ type: "SET_CONFIG", config: queryCfg });
    }
    // Apply CSS custom property color overrides
    applyColorOverrides(searchParams);
  }, [searchParams, dispatch]);


  /* ─── Set base URL on mount ─────────────────── */
  useEffect(() => {
    setBaseUrl(apiServer ?? state.config.apiBaseUrl ?? "");
  }, [apiServer, state.config.apiBaseUrl]);

  /* ─── SSE event handler (declared early to avoid reference issues) ── */
  const handleSSEEvent = useCallback(
    (event: SSEEvent) => {
      switch (event.type) {
        case "token":
          dispatch({ type: "SET_THINKING", value: false });
          dispatch({ type: "APPEND_TO_LAST_AGENT", token: event.data });
          break;
        case "thinking":
          dispatch({ type: "SET_THINKING", value: true });
          break;
        case "done":
          dispatch({ type: "FINISH_STREAMING" });
          break;
        case "error":
          dispatch({
            type: "APPEND_TO_LAST_AGENT",
            token: `\n\n⚠️ Error: ${event.data}`,
          });
          dispatch({ type: "FINISH_STREAMING" });
          break;
        // task_start / task_complete — pipeline progress, ignore for now
      }
    },
    [dispatch],
  );

  /* ─── Process conversation snapshot ─────────── */
  const processSnapshot = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (snapshot: any) => {
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
        const output = snapshot.conversationOutputs[0];

        // Extract agent replies and detect input field requests
        const agentReplies: OutputItem[] = output.output ?? [];
        for (const reply of agentReplies) {
          if (reply.type === "inputField") {
            // Backend is requesting a specific input field (e.g. password)
            dispatch({
              type: "SET_INPUT_FIELD",
              field: {
                subType: reply.subType || "password",
                placeholder: reply.placeholder,
                label: reply.label,
                defaultValue: reply.defaultValue,
              },
            });
          } else if (reply.text) {
            dispatch({
              type: "ADD_MESSAGE",
              message: {
                id: `agent-${Date.now()}-${Math.random()}`,
                role: "agent",
                content: reply.text,
                timestamp: Date.now(),
              },
            });
          }
        }

        // Quick replies
        dispatch({
          type: "SET_QUICK_REPLIES",
          replies: output.quickReplies ?? [],
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
      // Build context for secret input
      const secretContext = isSecret
        ? { secretInput: { type: "string" as const, value: "true" } }
        : undefined;

      // Add user message (display masked if secret)
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: "user",
        content: isSecret ? "●●●●●●●●" : text,
        timestamp: Date.now(),
      };
      dispatch({ type: "ADD_MESSAGE", message: userMsg });
      dispatch({ type: "SET_QUICK_REPLIES", replies: [] });
      dispatch({ type: "SET_PROCESSING", value: true });
      dispatch({ type: "SET_THINKING", value: true });

      try {
        if (isDemo) {
          // Demo mode: simulated streaming response
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `agent-${Date.now()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          const events = demoSendMessageStreaming(text);
          for await (const event of events) {
            handleSSEEvent(event);
          }
          dispatch({ type: "FINISH_STREAMING" });

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
              id: `agent-${Date.now()}`,
              role: "agent",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          const events = sendMessageStreaming(
            environment,
            agentId,
            state.conversationId,
            text,
            secretContext,
          );

          for await (const event of events) {
            handleSSEEvent(event);
          }
          dispatch({ type: "FINISH_STREAMING" });
        } else if (environment && agentId && state.conversationId) {
          // Non-streaming path — pass context for secret input
          const snapshot = await sendMessage(
            environment,
            agentId,
            state.conversationId,
            text,
            userId,
            secretContext,
          );
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        }
      } catch (err) {
        console.error("Failed to send message:", err);
        dispatch({ type: "SET_PROCESSING", value: false });
        dispatch({ type: "SET_THINKING", value: false });
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
    ],
  );

  /* ─── Undo ──────────────────────────────────── */
  const handleUndo = useCallback(async () => {
    if (!environment || !agentId || !state.conversationId) return;
    if (isDemo) return; // Demo mode doesn't support undo

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      const snapshot = await undoConversation(
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
      console.error("Undo failed:", err);
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
      const snapshot = await redoConversation(
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
      console.error("Redo failed:", err);
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
      } else if (environment && agentId) {
        const convId = await startConversation(environment, agentId, userId);
        dispatch({ type: "SET_CONVERSATION_ID", id: convId });
        const snapshot = await readConversation(environment, agentId, convId);
        processSnapshot(snapshot);
      }
    } catch (err) {
      console.error("Failed to restart conversation:", err);
    }
  }, [dispatch, isDemo, environment, agentId, userId, processSnapshot]);

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
        {state.messages.length === 0 && !state.isProcessing ? (
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

            {state.isThinking && <ThinkingIndicator />}
            {state.isProcessing && !state.isThinking && <TypingIndicator />}

            <div ref={messagesEndRef} />
          </>
        )}
      </div>

      <div style={{ position: "relative" }}>
        <ScrollToBottom visible={showScrollBtn} onClick={scrollToBottom} />
      </div>

      {!isEnded &&
        state.config.enableQuickReplies !== false && (
          <QuickReplies
            replies={state.quickReplies}
            onSelect={handleQuickReply}
          />
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
                disabled={!state.conversationId && !isManagedAgent}
              />
            )}
          </div>
        )
      )}
    </div>
  );
}
