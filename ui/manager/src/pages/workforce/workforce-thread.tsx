import {
  useState,
  useEffect,
  useRef,
  useCallback,
  type KeyboardEvent,
} from "react";
import { useParams, useLocation, useSearchParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useQuery } from "@tanstack/react-query";
import {
  Paperclip,
  X,
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
  Pencil,
  MessageSquare,
} from "lucide-react";
import { useGroup } from "@/hooks/use-groups";
import {
  startConversation,
  sendMessage,
  sendMessageWithContext,
  readConversation,
} from "@/lib/api/chat";
import { getAgent } from "@/lib/api/agents";
import { useWorkforceThreads } from "@/hooks/use-workforce-threads";
import type { SimpleConversationStep } from "@/lib/api/conversations";
import { ContextCard } from "@/components/workforce/context-card";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { Badge } from "@/components/ui/badge";
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

const ALLOWED_FILE_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
  "image/svg+xml",
  "application/pdf",
  "text/plain",
  "text/csv",
  "text/markdown",
  "application/json",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
]);
const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

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
      } else if (
        typeof entry.value === "object" &&
        entry.value !== null &&
        !Array.isArray(entry.value)
      ) {
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
    <div className="flex items-center gap-1 ps-3 pe-3 py-2">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className={cn(
            "inline-block h-2 w-2 rounded-full bg-muted-foreground",
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
        if (file.size > MAX_FILE_SIZE) {
          toast.error(
            t("Workforce.thread.fileTooLarge", "File must be under 10MB"),
          );
        } else if (!ALLOWED_FILE_TYPES.has(file.type)) {
          toast.error(
            t(
              "Workforce.thread.fileTypeNotAllowed",
              "This file type is not supported",
            ),
          );
        } else {
          setAttachment({ fileName: file.name, file });
        }
      }
      // Reset input so the same file can be re-selected
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    },
    [t],
  );

  const removeAttachment = useCallback(() => {
    setAttachment(null);
  }, []);

  return (
    <div
      className={cn(
        "sticky bottom-0 ps-4 pe-4 py-3",
        "border-t bg-card border-border",
        className,
      )}
    >
      {/* Attachment chip */}
      {attachment && (
        <div className="mb-2 flex items-center gap-1">
          <span
            className={cn(
              "inline-flex items-center gap-1.5 rounded-full ps-3 pe-3 py-1 text-xs font-medium",
              "bg-muted text-muted-foreground",
            )}
          >
            <Paperclip className="h-3 w-3" />
            <span className="max-w-48 truncate">{attachment.fileName}</span>
            <button
              type="button"
              onClick={removeAttachment}
              className={cn(
                "ms-0.5 rounded-full p-0.5",
                "hover:bg-muted-foreground/20",
                "transition-colors",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-muted"
              )}
              aria-label={t(
                "Workforce.thread.removeAttachment",
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
          accept="image/*,.pdf,.txt,.csv,.md,.json,.doc,.docx,.xls,.xlsx"
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
            "text-muted-foreground hover:text-foreground",
          )}
          aria-label={t("Workforce.thread.attachFile", "Attach file")}
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
            t("Workforce.thread.placeholder", "Type a message...")
          }
          disabled={disabled}
          rows={1}
          className={cn(
            "flex-1 min-h-10 max-h-32 resize-none rounded-xl ps-4 pe-4 py-2.5",
            "bg-muted",
            "text-sm text-foreground",
            "placeholder:text-muted-foreground",
            "border-none outline-none",
            "focus:ring-2 focus:ring-ring/30",
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
            "bg-primary text-primary-foreground hover:bg-primary/90",
            "disabled:bg-primary/50 disabled:text-primary-foreground/60",
          )}
          aria-label={t("Workforce.thread.send", "Send")}
        >
          <SendIcon />
        </Button>
      </div>
    </div>
  );
}

// ─── Type Guard ──────────────────────────────────────────────────

function isGroupContext(state: unknown): state is GroupContext {
  if (typeof state !== "object" || state === null) return false;
  const s = state as Record<string, unknown>;
  return (
    s.fromGroup === true &&
    typeof s.question === "string" &&
    typeof s.response === "string"
  );
}

// ─── Component ───────────────────────────────────────────────────

function WorkforceThread() {
  const { t } = useTranslation();
  const { boardId = "", memberId = "" } = useParams<{
    boardId: string;
    memberId: string;
  }>();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const version = Number(searchParams.get("version")) || 1;

  // Group context from router state (passed by advisor-response-card)
  const groupContext = isGroupContext(location.state) ? location.state : null;
  const hasGroupContext = groupContext?.fromGroup === true;

  // Fetch board config to resolve member display name & role
  const { data: groupConfig } = useGroup(boardId, version);
  const member = groupConfig?.members.find((m) => m.agentId === memberId);
  const memberName = member?.displayName ?? memberId;
  const memberRole = member?.role ?? null;

  // Thread persistence
  const { getThread, registerThread, updateActivity } =
    useWorkforceThreads();

  // Stable refs for init effect (avoid stale closures)
  const getThreadRef = useRef(getThread);
  getThreadRef.current = getThread;
  const registerThreadRef = useRef(registerThread);
  registerThreadRef.current = registerThread;
  const updateActivityRef = useRef(updateActivity);
  updateActivityRef.current = updateActivity;
  const memberNameRef = useRef(memberName);
  memberNameRef.current = memberName;

  // Local state
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ThreadMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isStarting, setIsStarting] = useState(true);
  const [inputPrefill, setInputPrefill] = useState("");
  const [showDetails, setShowDetails] = useState(false);
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);

  // Fetch full agent data for the details panel
  const { data: agentData, isLoading: agentLoading } = useQuery({
    queryKey: ["agent-detail", memberId],
    queryFn: () => getAgent(memberId),
    enabled: !!memberId && showDetails,
  });

  // Refs
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const initRef = useRef(false);
  const sendingRef = useRef(false);

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
        const existingThread = getThreadRef.current(boardId, memberId);

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
          updateActivityRef.current(boardId, memberId);
        } else {
          // Start a new conversation
          const newConvId = await startConversation("production", memberId);
          setConversationId(newConvId);
          registerThreadRef.current({
            memberId,
            memberName: memberNameRef.current,
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
      } catch (err) {
        console.error("Failed to initialize thread:", err);
      } finally {
        setIsStarting(false);
      }
    }

    init();
  }, [boardId, memberId]);

  // ─── Prefill input when group context is available ────────────
  useEffect(() => {
    if (hasGroupContext && !isStarting) {
      setInputPrefill(
        t(
          "Workforce.thread.followUp",
          "Following up on your response...",
        ),
      );
    }
  }, [hasGroupContext, isStarting, t]);

  // ─── Send a message ──────────────────────────────────────────
  const handleSend = useCallback(
    async (text: string, attachment?: AttachmentInfo) => {
      if (!conversationId || (!text.trim() && !attachment) || isLoading) return;
      if (sendingRef.current) return;
      sendingRef.current = true;

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
                  note: "metadata-only: file content is not uploaded",
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

        updateActivityRef.current(boardId, memberId);
      } catch (err) {
        console.error("Failed to send message:", err);
        // Add an error message from the agent
        setMessages((prev) => [
          ...prev,
          {
            role: "agent",
            content: t(
              "Workforce.thread.sendError",
              "Sorry, I encountered an error. Please try again.",
            ),
            timestamp: Date.now(),
          },
        ]);
      } finally {
        sendingRef.current = false;
        setIsLoading(false);
      }
    },
    [conversationId, isLoading, inputPrefill, memberId, boardId, t],
  );

  // ─── Starting state ──────────────────────────────────────────
  if (isStarting) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4">
        <Skeleton className="h-12 w-12 rounded-full" />
        <Skeleton className="h-4 w-48" />
        <p className="text-sm text-muted-foreground">
          {t("Workforce.thread.startingConversation", "Starting conversation...")}
        </p>
      </div>
    );
  }

  // ─── Render ──────────────────────────────────────────────────
  return (
    <div className="flex flex-1 min-h-0 overflow-hidden">
      <div className="flex flex-1 min-w-0 flex-col">
        {/* Back header */}
        <div className="sticky top-0 z-10 flex h-12 shrink-0 items-center gap-2 border-b border-border bg-card/80 backdrop-blur-sm ps-2 pe-4">
          <Link
            to={`/workforce/${boardId}?version=${version}`}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            aria-label={t("Workforce.back", "Back")}
          >
            <ChevronLeft className="h-5 w-5" />
          </Link>
          <div className="flex flex-1 items-center gap-2 min-w-0">
            <AdvisorAvatar name={memberName} agentId={memberId} size="sm" />
            <span className="text-sm font-medium text-foreground truncate">
              {memberName}
            </span>
            {memberRole && (
              <span className="text-xs text-muted-foreground truncate hidden sm:inline">
                — {memberRole}
              </span>
            )}
          </div>
          {/* Details panel toggle */}
          <button
            type="button"
            onClick={() => setShowDetails((v) => !v)}
            className={cn(
              "flex h-8 w-8 shrink-0 items-center justify-center rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring max-lg:hidden",
              showDetails
                ? "bg-primary/10 text-primary"
                : "text-muted-foreground hover:bg-muted hover:text-foreground"
            )}
            aria-label={t(
              "Workforce.chat.toggleDetails",
              showDetails ? "Hide agent details" : "Show agent details"
            )}
            aria-expanded={showDetails}
          >
            {showDetails ? (
              <PanelRightClose className="h-4 w-4" />
            ) : (
              <PanelRightOpen className="h-4 w-4" />
            )}
          </button>
        </div>
      {/* Context card — shown when navigating from a group discussion */}
      {hasGroupContext && groupContext && (
        <div className="shrink-0 ps-4 pe-4 pt-4">
          <ContextCard
            boardName={groupConfig?.name ?? boardId}
            question={groupContext.question}
            response={groupContext.response}
          />
        </div>
      )}

      {/* Messages area */}
      <div className="flex-1 overflow-y-auto ps-4 pe-4 pt-4 pb-4">
        <div className="ms-auto me-auto max-w-3xl space-y-4">
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
                {t("Workforce.thread.emptyTitle", "Chat with {{name}}", {
                  name: memberName,
                })}
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {t(
                  "Workforce.thread.emptySubtitle",
                  "Send a message to begin chatting with {{name}}.",
                  { name: memberName },
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
                  "max-w-lg rounded-2xl ps-4 pe-4 py-2.5 text-sm",
                  msg.role === "user"
                    ? "rounded-ee-md bg-primary text-primary-foreground"
                    : cn(
                        "rounded-es-md border",
                        "bg-card dark:bg-card",
                        "border-border",
                        "text-foreground",
                      ),
                )}
              >
                {msg.role === "agent" && (
                  <p className="mb-1 text-xs font-medium text-muted-foreground">
                    {memberName}
                  </p>
                )}

                {/* Attachment chip on user messages */}
                {msg.attachment && (
                  <span
                    className={cn(
                      "mb-1 inline-flex items-center gap-1 rounded-full ps-2 pe-2 py-0.5 text-xs",
                      msg.role === "user"
                        ? "bg-white/20 text-primary-foreground"
                        : "bg-muted text-muted-foreground",
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
                  "bg-card",
                  "border-border",
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
            t("Workforce.thread.placeholder", "Message {{name}}...", {
              name: memberName,
            })
          }
          className="shrink-0"
        />
      </div>

      {/* Right details panel */}
      {showDetails && (
        <div className="w-72 shrink-0 border-s border-border bg-card overflow-y-auto flex flex-col max-lg:hidden">
          <div className="p-3 border-b border-border flex items-center justify-between shrink-0">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("Workforce.chat.agentDetails", "Agent Details")}
            </h3>
            <button
              type="button"
              onClick={() => setShowDetails(false)}
              className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label={t("Workforce.chat.hideDetails", "Hide details panel")}
            >
              <PanelRightClose className="h-3.5 w-3.5" />
            </button>
          </div>
          {agentLoading ? (
            <div className="p-4 space-y-4">
              <div className="flex flex-col items-center gap-2">
                <Skeleton className="h-14 w-14 rounded-full" />
                <Skeleton className="h-4 w-24" />
              </div>
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : agentData ? (
            <div className="p-4 space-y-5">
              <div className="flex flex-col items-center text-center gap-2">
                <AdvisorAvatar
                  name={memberName ?? agentData.name ?? "Agent"}
                  agentId={memberId}
                  size="lg"
                />
                <div>
                  <p className="text-sm font-semibold text-foreground">
                    {memberName ?? agentData.name}
                  </p>
                  {agentData.description && (
                    <p className="text-xs text-muted-foreground mt-0.5 line-clamp-3">
                      {agentData.description}
                    </p>
                  )}
                </div>
              </div>

              {agentData.capabilities && agentData.capabilities.length > 0 && (
                <div>
                  <h4 className="text-xs font-medium text-muted-foreground mb-2">
                    {t("Workforce.agentEditor.capabilities", "Capabilities")}
                  </h4>
                  <div className="flex flex-wrap gap-1.5">
                    {agentData.capabilities.map((cap, idx) => (
                      <Badge
                        key={`${cap.skill}-${idx}`}
                        variant="secondary"
                        className="text-[10px]"
                      >
                        {cap.skill}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}

              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    {t("Workforce.agentEditor.a2aEnabled", "Agent-to-Agent")}
                  </span>
                  <Badge
                    variant={agentData.a2aEnabled ? "success" : "secondary"}
                    className="text-[10px]"
                  >
                    {agentData.a2aEnabled
                      ? t("common.on", "On")
                      : t("common.off", "Off")}
                  </Badge>
                </div>
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">
                    {t("Workforce.agentEditor.memoryTools", "Memory Tools")}
                  </span>
                  <Badge
                    variant={
                      agentData.enableMemoryTools ? "success" : "secondary"
                    }
                    className="text-[10px]"
                  >
                    {agentData.enableMemoryTools
                      ? t("common.on", "On")
                      : t("common.off", "Off")}
                  </Badge>
                </div>
              </div>

              <div>
                <h4 className="text-xs font-medium text-muted-foreground mb-2 mt-4">
                  {t("Workforce.thread.history", "Conversation History")}
                </h4>
                {conversationId ? (
                  <div className="flex flex-col gap-2 rounded-lg border border-border p-3 text-xs bg-muted/30">
                    <div className="flex justify-between items-center">
                      <span className="text-muted-foreground flex items-center gap-1.5">
                         <MessageSquare className="h-3.5 w-3.5" />
                         {t("Workforce.thread.currentSession", "Current Session")}
                      </span>
                      <span className="font-medium text-foreground">{messages.length} {t("Workforce.thread.msgs", "msgs")}</span>
                    </div>
                  </div>
                ) : (
                  <p className="text-xs text-muted-foreground">{t("Workforce.thread.noHistory", "No history available.")}</p>
                )}
              </div>

              <Button
                variant="outline"
                size="sm"
                className="w-full mt-2"
                onClick={() => setEditingAgentId(memberId)}
              >
                <Pencil className="h-3.5 w-3.5" />
                {t("Workforce.chat.editAgent", "Edit Agent")}
              </Button>
            </div>
          ) : (
             <div className="flex flex-1 items-center justify-center p-4">
                <div className="text-center text-muted-foreground">
                  <p className="text-xs">
                    {t(
                      "Workforce.chat.selectToView",
                      "Select an agent to view details",
                    )}
                  </p>
                </div>
              </div>
          )}
        </div>
      )}

      {/* Agent editor sheet (slide-over) */}
      <AgentEditorSheet
        agentId={editingAgentId}
        onClose={() => setEditingAgentId(null)}
      />
    </div>
  );
}

export { WorkforceThread };
