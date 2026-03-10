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
import { QuickReplies } from "./QuickReplies";
import { TypingIndicator, ThinkingIndicator } from "./Indicators";
import { ScrollToBottom } from "./ScrollToBottom";
import { ChatHeader } from "./ChatHeader";

import {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageStreaming,
  sendManagedBotMessage,
  undoConversation,
  redoConversation,
  fetchBotDescriptor,
  setBaseUrl,
} from "@/api/chat-api";
import {
  isDemoMode,
  demoStartConversation,
  demoSendMessageStreaming,
  demoGetQuickReplies,
} from "@/api/demo-api";
import type { ChatMessage, SSEEvent, ChatConfig } from "@/types";

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
  botBg:        ["--chat-bot-bg"],
  botBorder:    ["--chat-bot-border"],
  botText:      ["--chat-bot-text"],
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
  if (params.get("hideBotName") === "true") cfg.showBotName = false;
  if (params.get("theme")) cfg.theme = params.get("theme") as ChatConfig["theme"];
  if (params.get("title")) cfg.title = params.get("title")!;
  if (params.get("accentColor")) cfg.accentColor = params.get("accentColor")!;
  return cfg;
}

export function ChatWidget() {
  const state = useChatState();
  const dispatch = useChatDispatch();

  const { environment, botId, userId: userIdParam, intent } = useParams();
  const [searchParams] = useSearchParams();

  const userId =
    userIdParam ?? searchParams.get("userId") ?? undefined;
  const apiServer = searchParams.get("apiServer");
  const isManagedBot = !!intent;
  const isDemo = isDemoMode(environment, botId);

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
          dispatch({ type: "APPEND_TO_LAST_BOT", token: event.data });
          break;
        case "thinking":
          dispatch({ type: "SET_THINKING", value: true });
          break;
        case "done":
          dispatch({ type: "FINISH_STREAMING" });
          break;
        case "error":
          dispatch({
            type: "APPEND_TO_LAST_BOT",
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

      // Handle the "conversationOutputs" format (from POST /bots responses)
      if (snapshot.conversationOutputs?.length) {
        const output = snapshot.conversationOutputs[0];

        // Extract bot replies
        const botReplies: { text: string }[] = output.output ?? [];
        for (const reply of botReplies) {
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `bot-${Date.now()}-${Math.random()}`,
              role: "bot",
              content: reply.text,
              timestamp: Date.now(),
            },
          });
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
                id: `bot-${Date.now()}-${Math.random()}`,
                role: "bot",
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
        } else if (isManagedBot && intent && userId) {
          // Managed bot: GET to load existing or start new
          const snapshot = await sendManagedBotMessage(intent, userId);
          processSnapshot(snapshot);
        } else if (environment && botId) {
          // Direct bot: POST to create conversation
          const convId = await startConversation(
            environment,
            botId,
            userId,
          );
          dispatch({ type: "SET_CONVERSATION_ID", id: convId });

          // GET to pick up welcome message
          const snapshot = await readConversation(
            environment,
            botId,
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

  /* ─── Fetch bot name ────────────────────────── */
  useEffect(() => {
    if (isDemo || !botId || state.config.showBotName === false) return;
    fetchBotDescriptor(botId).then((desc) => {
      if (desc.name) {
        dispatch({ type: "SET_BOT_NAME", name: desc.name });
      }
    }).catch(() => { /* swallow */ });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [botId, isDemo]);

  /* ─── Send message ──────────────────────────── */
  const handleSend = useCallback(
    async (text: string) => {
      // Add user message
      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: "user",
        content: text,
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
              id: `bot-${Date.now()}`,
              role: "bot",
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
        } else if (isManagedBot && intent && userId) {
          // Managed bot (non-streaming only)
          const snapshot = await sendManagedBotMessage(intent, userId, text);
          dispatch({ type: "SET_THINKING", value: false });
          processSnapshot(snapshot);
          dispatch({ type: "SET_PROCESSING", value: false });
        } else if (
          state.config.enableStreaming &&
          environment &&
          botId &&
          state.conversationId
        ) {
          // Streaming path
          dispatch({
            type: "ADD_MESSAGE",
            message: {
              id: `bot-${Date.now()}`,
              role: "bot",
              content: "",
              timestamp: Date.now(),
              isStreaming: true,
            },
          });

          const events = sendMessageStreaming(
            environment,
            botId,
            state.conversationId,
            text,
          );

          for await (const event of events) {
            handleSSEEvent(event);
          }
          dispatch({ type: "FINISH_STREAMING" });
        } else if (environment && botId && state.conversationId) {
          // Non-streaming path
          const snapshot = await sendMessage(
            environment,
            botId,
            state.conversationId,
            text,
            userId,
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
      isManagedBot,
      intent,
      userId,
      environment,
      botId,
      state.conversationId,
      state.config.enableStreaming,
    ],
  );

  /* ─── Undo ──────────────────────────────────── */
  const handleUndo = useCallback(async () => {
    if (!environment || !botId || !state.conversationId) return;
    if (isDemo) return; // Demo mode doesn't support undo

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      const snapshot = await undoConversation(
        environment,
        botId,
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
            id: `bot-${msgs.length}-${Date.now()}`,
            role: "bot",
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
  }, [dispatch, environment, botId, state.conversationId, isDemo]);

  /* ─── Redo ──────────────────────────────────── */
  const handleRedo = useCallback(async () => {
    if (!environment || !botId || !state.conversationId) return;
    if (isDemo) return;

    try {
      dispatch({ type: "SET_PROCESSING", value: true });
      const snapshot = await redoConversation(
        environment,
        botId,
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
            id: `bot-${msgs.length}-${Date.now()}`,
            role: "bot",
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
  }, [dispatch, environment, botId, state.conversationId, isDemo]);

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
      } else if (environment && botId) {
        const convId = await startConversation(environment, botId, userId);
        dispatch({ type: "SET_CONVERSATION_ID", id: convId });
        const snapshot = await readConversation(environment, botId, convId);
        processSnapshot(snapshot);
      }
    } catch (err) {
      console.error("Failed to restart conversation:", err);
    }
  }, [dispatch, isDemo, environment, botId, userId, processSnapshot]);

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

            <ChatInput
              onSend={handleSend}
              disabled={!state.conversationId && !isManagedBot}
            />
          </div>
        )
      )}
    </div>
  );
}
