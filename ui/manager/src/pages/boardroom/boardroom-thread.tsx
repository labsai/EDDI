import {
  useState,
  useEffect,
  useRef,
  useCallback,
  type KeyboardEvent,
} from "react";
import { useParams, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Paperclip, X } from "lucide-react";
import { useGroup } from "@/hooks/use-groups";
import {
  startConversation,
  sendMessage,
  sendMessageWithContext,
  readConversation,
} from "@/lib/api/chat";
import { useBoardroomThreads } from "@/hooks/use-boardroom-threads";
import type { SimpleConversationStep } from "@/lib/api/conversations";
import { ContextCard } from "@/components/boardroom/context-card";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────

interface ThreadMessage {
  role: "user" | "agent";
  content: string;
  timestamp: number;
  attachment?: { fileName: string };
}

interface GroupContext {
  fromGroup: boolean;
  question: string;
  response: string;
}

interface AttachmentInfo {
  fileName: string;
  file: File;
}

// ─── Helpers ─────────────────────────────────────────────────────

/**
 * Parse a SimpleConversationStep[] into an array of ThreadMessages.
 *
 * Each step contains a `conversationStep` array of key/value entries:
 * - `input:initial` → user message (value is a string)
 * - `output:text:*`  → agent output (value can be string or object with text/input)
 */
function parseConversationSteps(
  steps: SimpleConversationStep[],
): ThreadMessage[] {
  const messages: ThreadMessage[] = [];

  for (const step of steps) {
    const entries = step.conversationStep ?? [];
    const timestamp = step.timestamp
      ? new Date(step.timestamp).getTime()
      : Date.now();

    // Collect user input
    const inputEntry = entries.find((e) => e.key === "input:initial");
    if (inputEntry && inputEntry.value) {
      const content =
        typeof inputEntry.value === "string"
          ? inputEntry.value
          : String(inputEntry.value);
      if (content.trim()) {
        messages.push({ role: "user", content, timestamp });
      }
    }

    // Collect agent outputs (keys starting with "output:text:")
    const outputTexts: string[] = [];
    for (const entry of entries) {
      if (!entry.key.startsWith("output:text:")) continue;

      if (typeof entry.value === "string") {
        outputTexts.push(entry.value);
      } else if (entry.value && typeof entry.value === "object") {
        const obj = entry.value as Record<string, unknown>;
        // Could be { input: "...", actions?: [...] } or { text: "..." }
        const text = (obj.text ?? obj.input ?? "") as string;
        if (text) outputTexts.push(text);
      }
    }

    if (outputTexts.length > 0) {
      messages.push({
        role: "agent",
        content: outputTexts.join("\n"),
        timestamp,
      });
    }
  }

  return messages;
}

// ─── Typing Indicator ────────────────────────────────────────────

function TypingDots() {
  return (
    <div className="flex items-center gap-1 px-3 py-2">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className={cn(
            "inline-block h-2 w-2 rounded-full bg-indigo-400 dark:bg-indigo-500",
            "animate-bounce",
          )}
          style={{ animationDelay: `${i * 150}ms` }}
        />
      ))}
    </div>
  );
}

// ─── Send Icon ───────────────────────────────────────────────────

function SendIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
    >
      <path d="M22 2 11 13" />
      <path d="M22 2 15 22 11 13 2 9z" />
    </svg>
  );
}

// ─── Thread Input (with file attachment) ─────────────────────────

interface ThreadInputProps {
  onSend: (message: string, attachment?: AttachmentInfo) => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
}

function ThreadInput({
  onSend,
  disabled = false,
  placeholder,
  className,
}: ThreadInputProps) {
  const { t } = useTranslation();
  const [message, setMessage] = useState("");
  const [attachment, setAttachment] = useState<AttachmentInfo | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const trimmed = message.trim();
  const canSend = (trimmed.length > 0 || !!attachment) && !disabled;

  const handleSend = useCallback(() => {
    if (!canSend) return;
    onSend(trimmed, attachment ?? undefined);
    setMessage("");
    setAttachment(null);
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [canSend, onSend, trimmed, attachment]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  const handleInput = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 128)}px`;
  }, []);

  const handleFileSelect = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) {
        setAttachment({ fileName: file.name, file });
      }
      // Reset input so the same file can be re-selected
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    },
    [],
  );

  const removeAttachment = useCallback(() => {
    setAttachment(null);
  }, []);

  return (
    <div
      className={cn(
        "sticky bottom-0 px-4 py-3",
        "border-t bg-white border-slate-200",
        "dark:bg-slate-900 dark:border-slate-800",
        className,
      )}
    >
      {/* Attachment chip */}
      {attachment && (
        <div className="mb-2 flex items-center gap-1">
          <span
            className={cn(
              "inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium",
              "bg-indigo-100 text-indigo-700",
              "dark:bg-indigo-500/20 dark:text-indigo-300",
            )}
          >
            <Paperclip className="h-3 w-3" />
            <span className="max-w-48 truncate">{attachment.fileName}</span>
            <button
              type="button"
              onClick={removeAttachment}
              className={cn(
                "ms-0.5 rounded-full p-0.5",
                "hover:bg-indigo-200 dark:hover:bg-indigo-500/30",
                "transition-colors",
              )}
              aria-label={t(
                "boardroom.thread.removeAttachment",
                "Remove attachment",
              )}
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        </div>
      )}

      <div className="flex items-end gap-2">
        {/* Hidden file input */}
        <input
          ref={fileInputRef}
          type="file"
          onChange={handleFileChange}
          className="hidden"
          aria-hidden="true"
        />

        {/* Attachment button */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={handleFileSelect}
          disabled={disabled}
          className={cn(
            "h-10 w-10 shrink-0 rounded-full",
            "text-slate-500 hover:text-slate-700",
            "dark:text-slate-400 dark:hover:text-slate-200",
          )}
          aria-label={t("boardroom.thread.attachFile", "Attach file")}
        >
          <Paperclip className="h-5 w-5" />
        </Button>

        <textarea
          ref={textareaRef}
          value={message}
          onChange={(e) => {
            setMessage(e.target.value);
            handleInput();
          }}
          onKeyDown={handleKeyDown}
          placeholder={
            placeholder ??
            t("boardroom.thread.placeholder", "Type a message...")
          }
          disabled={disabled}
          rows={1}
          className={cn(
            "flex-1 min-h-10 max-h-32 resize-none rounded-xl px-4 py-2.5",
            "bg-slate-100 dark:bg-slate-800",
            "text-sm text-slate-900 dark:text-slate-100",
            "placeholder:text-slate-400 dark:placeholder:text-slate-500",
            "border-none outline-none",
            "focus:ring-2 ring-indigo-500/30",
            "transition-shadow",
          )}
        />

        <Button
          type="button"
          size="icon"
          onClick={handleSend}
          disabled={!canSend}
          className={cn(
            "h-10 w-10 shrink-0 rounded-full",
            "bg-indigo-500 text-white hover:bg-indigo-600",
            "disabled:bg-indigo-500/50 disabled:text-white/60",
          )}
          aria-label={t("boardroom.thread.send", "Send")}
        >
          <SendIcon />
        </Button>
      </div>
    </div>
  );
}

// ─── Component ───────────────────────────────────────────────────

function BoardroomThread() {
  const { t } = useTranslation();
  const { boardId = "", memberId = "" } = useParams<{
    boardId: string;
    memberId: string;
  }>();
  const location = useLocation();

  // Group context from router state (passed by advisor-response-card)
  const groupContext = (location.state as GroupContext | null) ?? null;
  const hasGroupContext = groupContext?.fromGroup === true;

  // Fetch board config to resolve member display name & role
  const { data: groupConfig } = useGroup(boardId);
  const member = groupConfig?.members.find((m) => m.agentId === memberId);
  const memberName = member?.displayName ?? memberId;
  const memberRole = member?.role ?? null;

  // Thread persistence
  const { getThread, registerThread, updateActivity } =
    useBoardroomThreads();

  // Local state
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ThreadMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isStarting, setIsStarting] = useState(true);
  const [inputPrefill, setInputPrefill] = useState("");

  // Auto-scroll ref
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const initRef = useRef(false);

  // Scroll to bottom whenever messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isLoading]);

  // ─── Initialize conversation ─────────────────────────────────
  useEffect(() => {
    if (initRef.current || !boardId || !memberId) return;
    initRef.current = true;

    async function init() {
      try {
        const existingThread = getThread(boardId, memberId);

        if (existingThread) {
          // Resume existing conversation
          setConversationId(existingThread.conversationId);
          const snapshot = await readConversation(
            "production",
            memberId,
            existingThread.conversationId,
          );
          const parsed = parseConversationSteps(
            snapshot.conversationSteps ?? [],
          );
          setMessages(parsed);
          updateActivity(boardId, memberId);
        } else {
          // Start a new conversation
          const newConvId = await startConversation("production", memberId);
          setConversationId(newConvId);
          registerThread({
            memberId,
            memberName,
            conversationId: newConvId,
            boardId,
          });

          // Read any welcome message the agent might have
          try {
            const snapshot = await readConversation(
              "production",
              memberId,
              newConvId,
            );
            const parsed = parseConversationSteps(
              snapshot.conversationSteps ?? [],
            );
            if (parsed.length > 0) {
              setMessages(parsed);
            }
          } catch {
            // No welcome message — that's fine
          }
        }

        // Pre-fill input if arriving from group context
        if (hasGroupContext) {
          setInputPrefill(
            t(
              "boardroom.thread.followUp",
              "Following up on your response...",
            ),
          );
        }
      } catch (err) {
        console.error("Failed to initialize thread:", err);
      } finally {
        setIsStarting(false);
      }
    }

    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boardId, memberId]);

  // ─── Send a message ──────────────────────────────────────────
  const handleSend = useCallback(
    async (text: string, attachment?: AttachmentInfo) => {
      if (!conversationId || (!text.trim() && !attachment) || isLoading) return;

      // Clear prefill after first send
      if (inputPrefill) setInputPrefill("");

      const messageText = text.trim();

      // Optimistically add user message
      const userMsg: ThreadMessage = {
        role: "user",
        content: messageText || (attachment ? `📎 ${attachment.fileName}` : ""),
        timestamp: Date.now(),
        attachment: attachment
          ? { fileName: attachment.fileName }
          : undefined,
      };
      setMessages((prev) => [...prev, userMsg]);
      setIsLoading(true);

      try {
        let snapshot;

        if (attachment) {
          // Send with context including file reference
          snapshot = await sendMessageWithContext(
            "production",
            memberId,
            conversationId,
            {
              input: messageText || attachment.fileName,
              context: {
                attachment: {
                  fileName: attachment.fileName,
                  mimeType: attachment.file.type || "application/octet-stream",
                  sizeBytes: attachment.file.size,
                },
              },
            },
          );
        } else {
          snapshot = await sendMessage(
            "production",
            memberId,
            conversationId,
            messageText,
          );
        }

        // Parse the agent's reply from the returned step(s)
        const newMessages = parseConversationSteps(
          snapshot.conversationSteps ?? [],
        );

        // Extract only agent messages from the response (user message already added)
        const agentMessages = newMessages.filter((m) => m.role === "agent");
        if (agentMessages.length > 0) {
          setMessages((prev) => [...prev, ...agentMessages]);
        }

        updateActivity(boardId, memberId);
      } catch (err) {
        console.error("Failed to send message:", err);
        // Add an error message from the agent
        setMessages((prev) => [
          ...prev,
          {
            role: "agent",
            content: t(
              "boardroom.thread.sendError",
              "Sorry, I encountered an error. Please try again.",
            ),
            timestamp: Date.now(),
          },
        ]);
      } finally {
        setIsLoading(false);
      }
    },
    [conversationId, isLoading, inputPrefill, memberId, boardId, updateActivity, t],
  );

  // ─── Starting state ──────────────────────────────────────────
  if (isStarting) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4">
        <Skeleton className="h-12 w-12 rounded-full" />
        <Skeleton className="h-4 w-48" />
        <p className="text-sm text-muted-foreground">
          {t("boardroom.thread.starting", "Starting conversation...")}
        </p>
      </div>
    );
  }

  // ─── Render ──────────────────────────────────────────────────
  return (
    <div className="flex h-full flex-col">
      {/* Context card — shown when navigating from a group discussion */}
      {hasGroupContext && groupContext && (
        <div className="shrink-0 px-4 pt-4">
          <ContextCard
            boardName={groupConfig?.name ?? boardId}
            question={groupContext.question}
            response={groupContext.response}
          />
        </div>
      )}

      {/* Messages area */}
      <div className="flex-1 overflow-y-auto p-4">
        <div className="mx-auto max-w-3xl space-y-4">
          {messages.length === 0 && !isLoading && (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <AdvisorAvatar
                name={memberName}
                agentId={memberId}
                size="xl"
                role={memberRole}
                showRole={!!memberRole}
              />
              <p className="mt-4 text-lg font-medium text-slate-900 dark:text-slate-100">
                {t("boardroom.thread.emptyTitle", "Chat with {{name}}", {
                  name: memberName,
                })}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {t(
                  "boardroom.thread.emptySubtitle",
                  "Start a private conversation with this advisor.",
                )}
              </p>
            </div>
          )}

          {messages.map((msg, idx) => (
            <div
              key={`${msg.role}-${msg.timestamp}-${idx}`}
              className={cn(
                "flex",
                msg.role === "user" ? "justify-end" : "justify-start",
              )}
            >
              {msg.role === "agent" && (
                <div className="me-2 mt-1 shrink-0">
                  <AdvisorAvatar
                    name={memberName}
                    agentId={memberId}
                    size="sm"
                  />
                </div>
              )}

              <div
                className={cn(
                  "max-w-lg rounded-2xl px-4 py-2.5 text-sm",
                  msg.role === "user"
                    ? "rounded-ee-md bg-indigo-500 text-white"
                    : cn(
                        "rounded-es-md border",
                        "bg-white dark:bg-slate-900",
                        "border-slate-200 dark:border-slate-700",
                        "text-slate-900 dark:text-slate-100",
                      ),
                )}
              >
                {msg.role === "agent" && (
                  <p className="mb-1 text-xs font-medium text-indigo-600 dark:text-indigo-400">
                    {memberName}
                  </p>
                )}

                {/* Attachment chip on user messages */}
                {msg.attachment && (
                  <span
                    className={cn(
                      "mb-1 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs",
                      msg.role === "user"
                        ? "bg-white/20 text-white"
                        : "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400",
                    )}
                  >
                    <Paperclip className="h-3 w-3" />
                    <span className="max-w-32 truncate">
                      {msg.attachment.fileName}
                    </span>
                  </span>
                )}

                <p className="whitespace-pre-wrap">{msg.content}</p>
              </div>
            </div>
          ))}

          {/* Agent typing indicator */}
          {isLoading && (
            <div className="flex justify-start">
              <div className="me-2 mt-1 shrink-0">
                <AdvisorAvatar
                  name={memberName}
                  agentId={memberId}
                  size="sm"
                />
              </div>
              <div
                className={cn(
                  "rounded-2xl rounded-es-md border",
                  "bg-white dark:bg-slate-900",
                  "border-slate-200 dark:border-slate-700",
                )}
              >
                <TypingDots />
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>
      </div>

      {/* Thread input with file attachment support */}
      <ThreadInput
        onSend={handleSend}
        disabled={isLoading || !conversationId}
        placeholder={
          inputPrefill ||
          t("boardroom.thread.placeholder", "Message {{name}}...", {
            name: memberName,
          })
        }
        className="shrink-0"
      />
    </div>
  );
}

export { BoardroomThread };
