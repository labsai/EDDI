import {
  useState,
  useEffect,
  useRef,
  useCallback,
  type KeyboardEvent,
} from "react";
import { useParams, useLocation, useSearchParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { agentKeys } from "@/lib/query-keys";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  Paperclip,
  X,
  ChevronLeft,
  PanelRightClose,
  PanelRightOpen,
  FileText,
  AlertTriangle,
  Loader2,
  CheckCircle2,
  ArrowDown,
  Copy,
  Check,
  Square,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { parseTranscriptContent, formatMarkdownText } from "@/components/groups/group-utils";
import { useSmartAutoScroll } from "@/hooks/use-smart-auto-scroll";
import { useGroup } from "@/hooks/use-groups";
import {
  startConversation,
  sendMessageStreaming,
  readConversation,
  type InputData,
} from "@/lib/api/chat";
import { translateStreamError } from "@/hooks/use-chat";
import { getErrorMessage, isApiError } from "@/lib/api-client";
import {
  uploadAttachment,
  deleteAttachment,
  buildAttachmentContext,
  isImageMime,
  formatBytes,
  MAX_ATTACHMENTS_PER_TURN,
  MAX_ATTACHMENT_BYTES,
  type AttachmentResult,
  type AttachmentRef,
} from "@/lib/api/attachments";
import { getAgentDescriptors } from "@/lib/api/agents";
import { useWorkforceThreads } from "@/hooks/use-workforce-threads";
import type { SimpleConversationStep } from "@/lib/api/conversations";
import { ContextCard } from "@/components/workforce/context-card";
import { AgentDetailsPanel } from "@/components/workforce/agent-details-panel";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────

/** Sent attachment metadata stored in message history for rendering. */
interface SentAttachment {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes?: number;
  forwardableInline?: boolean;
  previewUrl?: string;
}

interface ThreadMessage {
  role: "user" | "agent";
  content: string;
  timestamp: number;
  attachments?: SentAttachment[];
  /** True while tokens are still arriving for this message. */
  streaming?: boolean;
}

/**
 * A turn that did not produce an answer.
 *
 * This used to be appended to the transcript as a message with
 * `role: "agent"` reading "Sorry, I encountered an error." — so an expired
 * token, an exceeded quota, an undeployed agent and a discussion paused for
 * approval all rendered identically, in the agent's own voice, while the
 * backend's actual sentence went to `console.error` and nowhere else. It is
 * not a message; it is the turn failing, and it says what happened and offers
 * the send again.
 */
interface ThreadSendError {
  message: string;
  /** The text to re-send on retry. */
  retryText: string;
  retryAttachments?: SentAttachment[];
  /**
   * The optimistic user message this failed turn added, when it is still in the
   * transcript.
   *
   * A retry re-sends through `handleSend`, which appends its own optimistic
   * message — so without withdrawing this one first, a successful retry leaves
   * the same text on screen twice. Kept by identity rather than by text so two
   * genuinely identical messages are never confused for each other.
   *
   * Null when the turn was already withdrawn, which is what the 409 path does:
   * there the backend never received the message at all, so leaving it on
   * screen would show it as sent.
   */
  pendingUserMessage: ThreadMessage | null;
  /**
   * The backend rejected the send because the conversation is paused awaiting
   * a human decision (409). Nothing is wrong and retrying will not help — the
   * pause has to be decided first.
   */
  paused: boolean;
}

interface GroupContext {
  fromGroup: boolean;
  question: string;
  response: string;
}

/** A file being uploaded or ready to send. */
interface PendingAttachment {
  id: string;
  file: File;
  previewUrl?: string;
  status: "uploading" | "ready" | "error";
  result?: AttachmentResult;
  error?: string;
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

function formatShortTime(ts: number): string {
  return new Date(ts).toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function CopyMessageButton({ content }: { content: string }) {
  const [copied, setCopied] = useState(false);
  const { t } = useTranslation();

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard API unavailable (non-secure context or permission denied)
    }
  }, [content]);

  return (
    <button
      type="button"
      onClick={handleCopy}
      className={cn(
        "inline-flex items-center gap-0.5 rounded px-1 py-0.5 text-[10px] opacity-0 group-hover/msg:opacity-100 focus-visible:opacity-100 transition-opacity duration-150",
        copied
          ? "text-emerald-500 font-medium"
          : "text-muted-foreground/60 hover:text-foreground hover:bg-muted/50",
      )}
      title={t("chat.copyMessage", "Copy message")}
      aria-label={t("chat.copyMessage", "Copy message")}
    >
      {copied ? (
        <>
          <Check className="h-3 w-3" />
          <span>{t("chat.copied", "Copied")}</span>
        </>
      ) : (
        <>
          <Copy className="h-3 w-3" />
          <span>{t("chat.copy", "Copy")}</span>
        </>
      )}
    </button>
  );
}

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

// ─── Thread Input (with multi-file attachment upload) ────────────

interface ThreadInputProps {
  onSend: (message: string, attachments?: SentAttachment[]) => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  conversationId: string | null;
}

function ThreadInput({
  onSend,
  disabled = false,
  placeholder,
  className,
  conversationId,
}: ThreadInputProps) {
  const { t } = useTranslation();
  const [message, setMessage] = useState("");
  const [pending, setPending] = useState<PendingAttachment[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Ref tracks latest pending so the unmount cleanup sees current state
  const pendingRef = useRef(pending);
  pendingRef.current = pending;

  // Ref tracks conversationId for stale-upload detection
  const convIdRef = useRef(conversationId);
  convIdRef.current = conversationId;

  const trimmed = message.trim();
  const readyCount = pending.filter((a) => a.status === "ready").length;
  const canSend = (trimmed.length > 0 || readyCount > 0) && !disabled;
  const hasUploading = pending.some((a) => a.status === "uploading");

  // Cleanup preview URLs on unmount (uses ref to avoid stale closure)
  useEffect(() => {
    return () => {
      pendingRef.current.forEach((a) => {
        if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
      });
    };
  }, []);

  // Reset pending when conversation changes
  useEffect(() => {
    setPending((prev) => {
      prev.forEach((a) => {
        if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
      });
      return [];
    });
  }, [conversationId]);

  const handleSend = useCallback(() => {
    if (!canSend || hasUploading) return;
    const sent: SentAttachment[] = pending
      .filter((a): a is PendingAttachment & { result: AttachmentResult } =>
        a.status === "ready" && !!a.result,
      )
      .map((a) => ({
        storageRef: a.result.storageRef,
        fileName: a.result.fileName || a.file.name,
        mimeType: a.result.mimeType || a.file.type || "application/octet-stream",
        sizeBytes: a.result.sizeBytes ?? a.file.size,
        forwardableInline: a.result.forwardableInline,
        previewUrl: a.previewUrl,
      }));
    onSend(trimmed, sent.length ? sent : undefined);
    setMessage("");
    // Don't revoke preview URLs — they're now owned by the message bubble
    setPending([]);
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [canSend, hasUploading, onSend, trimmed, pending]);

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
    const nextHeight = Math.min(Math.max(el.scrollHeight, 40), 128);
    el.style.height = `${nextHeight}px`;
    el.style.overflowY = el.scrollHeight > 128 ? "auto" : "hidden";
  }, []);

  const handleFileSelect = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (fileInputRef.current) fileInputRef.current.value = "";
      if (!file || !conversationId) return;

      // Validate
      if (file.size > MAX_ATTACHMENT_BYTES) {
        toast.error(
          t("Workforce.thread.fileTooLarge", "File must be under 20MB"),
        );
        return;
      }
      if (!ALLOWED_FILE_TYPES.has(file.type)) {
        toast.error(
          t("Workforce.thread.fileTypeNotAllowed", "This file type is not supported"),
        );
        return;
      }

      // Enforce per-turn cap
      const activeCount = pending.filter((a) => a.status !== "error").length;
      if (activeCount >= MAX_ATTACHMENTS_PER_TURN) {
        toast.error(
          t("Workforce.thread.attachmentLimit", "Maximum {{max}} attachments per message", {
            max: MAX_ATTACHMENTS_PER_TURN,
          }),
        );
        return;
      }

      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      const previewUrl = isImageMime(file.type) ? URL.createObjectURL(file) : undefined;

      const entry: PendingAttachment = { id, file, previewUrl, status: "uploading" };
      setPending((prev) => [...prev, entry]);

      const capturedConvId = conversationId;
      try {
        const result = await uploadAttachment(conversationId, file);
        // Guard: discard result if conversation changed during upload
        if (convIdRef.current !== capturedConvId) return;
        setPending((prev) =>
          prev.map((a) => (a.id === id ? { ...a, status: "ready" as const, result } : a)),
        );
        if (result.forwardableInline === false) {
          toast.warning(
            t("Workforce.thread.notForwarded", "File stored but too large to send to model"),
          );
        }
      } catch (err) {
        // Guard: suppress error toast if conversation changed during upload
        if (convIdRef.current !== capturedConvId) return;
        const msg = err instanceof Error ? err.message : "Upload failed";
        setPending((prev) =>
          prev.map((a) => (a.id === id ? { ...a, status: "error" as const, error: msg } : a)),
        );
        toast.error(
          t("Workforce.thread.uploadFailed", "Upload failed: {{error}}", { error: msg }),
        );
      }
    },
    [conversationId, pending, t],
  );

  const removeAttachment = useCallback(
    async (id: string) => {
      const att = pending.find((a) => a.id === id);
      if (!att) return;
      if (att.previewUrl) URL.revokeObjectURL(att.previewUrl);
      // Delete server-side if already uploaded
      if (att.result?.storageRef && conversationId) {
        try {
          await deleteAttachment(conversationId, att.result.storageRef);
        } catch {
          // Best effort — file may already be gone
        }
      }
      setPending((prev) => prev.filter((a) => a.id !== id));
    },
    [pending, conversationId],
  );

  const atLimit = pending.filter((a) => a.status !== "error").length >= MAX_ATTACHMENTS_PER_TURN;

  return (
    <div
      className={cn(
        "sticky bottom-0 ps-4 pe-4 py-3",
        "border-t bg-card border-border",
        className,
      )}
    >
      {/* Pending attachment chips */}
      {pending.length > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-1.5">
          {pending.map((att) => (
            <span
              key={att.id}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full ps-2 pe-1 py-1 text-xs font-medium",
                att.status === "error"
                  ? "bg-destructive/10 text-destructive"
                  : att.status === "uploading"
                    ? "bg-muted text-muted-foreground animate-pulse"
                    : "bg-muted text-muted-foreground",
              )}
            >
              {att.status === "uploading" && <Loader2 className="h-3 w-3 animate-spin" />}
              {att.status === "ready" && <CheckCircle2 className="h-3 w-3 text-emerald-500" />}
              {att.status === "error" && <AlertTriangle className="h-3 w-3" />}

              {/* Image preview thumbnail */}
              {att.previewUrl && att.status !== "error" ? (
                <img
                  src={att.previewUrl}
                  alt=""
                  className="h-6 w-6 rounded object-cover shrink-0"
                  onError={(e) => {
                    (e.target as HTMLElement).style.display = "none";
                  }}
                />
              ) : null}

              <span className="max-w-32 truncate">{att.file.name}</span>
              <span className="text-[10px] opacity-60">{formatBytes(att.file.size)}</span>

              <button
                type="button"
                onClick={() => removeAttachment(att.id)}
                className={cn(
                  "ms-0.5 rounded-full p-0.5",
                  "hover:bg-muted-foreground/20",
                  "transition-colors",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-muted",
                )}
                aria-label={t(
                  "Workforce.thread.removeAttachment",
                  "Remove attachment",
                )}
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
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
          disabled={disabled || atLimit}
          className={cn(
            "h-10 w-10 shrink-0 rounded-full",
            "text-muted-foreground hover:text-foreground",
          )}
          aria-label={t("Workforce.thread.attachFile", "Attach file")}
          title={atLimit ? t("Workforce.thread.attachmentLimit", "Maximum {{max}} attachments per message", { max: MAX_ATTACHMENTS_PER_TURN }) : undefined}
        >
          <Paperclip className="h-5 w-5" />
        </Button>

        <textarea
          ref={textareaRef}
          autoFocus
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
          aria-label={
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
          disabled={!canSend || hasUploading}
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
  const memberRole = member?.role ?? null;

  // Fallback: if the member isn't in the group config, fetch the agent descriptor
  // to resolve the display name (avoids showing raw ObjectIds).
  const { data: descriptorResults } = useQuery({
    queryKey: agentKeys.descriptor(memberId),
    queryFn: () => getAgentDescriptors(1, 0, memberId),
    enabled: !!memberId && !member,
    staleTime: 60_000,
  });
  const memberName = member?.displayName || descriptorResults?.[0]?.name || memberId;

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
  const [sendError, setSendError] = useState<ThreadSendError | null>(null);
  const [isStarting, setIsStarting] = useState(true);
  const [inputPrefill, setInputPrefill] = useState("");
  const [showDetails, setShowDetails] = useState(false);
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);

  // Refs
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const initRef = useRef(false);
  const sendingRef = useRef(false);
  /** Aborts the in-flight stream — for the Stop button and for unmount. */
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  // Track latest messages for unmount cleanup of sent-message preview URLs
  const messagesRef = useRef(messages);
  messagesRef.current = messages;

  // Revoke all sent-message preview URLs on unmount to prevent SPA memory leaks
  useEffect(() => {
    return () => {
      messagesRef.current.forEach((msg) => {
        msg.attachments?.forEach((a) => {
          if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
        });
      });
    };
  }, []);

  // Smart auto-scroll: auto scrolls when at bottom, pauses when user scrolls up
  const {
    scrollRef: scrollContainerRef,
    showScrollFab,
    hasNewContent,
    scrollToBottom,
    handleScroll,
  } = useSmartAutoScroll<HTMLDivElement>({
    deps: [messages, isLoading],
    bottomThreshold: 80,
  });

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
    async (text: string, attachments?: SentAttachment[]) => {
      if (!conversationId || (!text.trim() && !attachments?.length) || isLoading) return;
      if (sendingRef.current) return;
      sendingRef.current = true;

      // Clear prefill after first send
      if (inputPrefill) setInputPrefill("");

      const messageText = text.trim();

      // Optimistically add user message
      const userMsg: ThreadMessage = {
        role: "user",
        content: messageText || (attachments?.length ? `📎 ${attachments.map((a) => a.fileName).join(", ")}` : ""),
        timestamp: Date.now(),
        attachments: attachments?.length ? attachments : undefined,
      };
      // The placeholder the tokens stream into. Present from the start so the
      // reply appears where it will finally sit, rather than jumping in at the
      // end from under a separate typing indicator.
      const placeholder: ThreadMessage = {
        role: "agent",
        content: "",
        timestamp: Date.now(),
        streaming: true,
      };
      setMessages((prev) => [...prev, userMsg, placeholder]);
      setIsLoading(true);
      setSendError(null);

      const abort = new AbortController();
      abortRef.current = abort;

      /** Append streamed text to the placeholder, which is always last. */
      const appendToken = (chunk: string) => {
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last?.role === "agent" && last.streaming) {
            next[next.length - 1] = { ...last, content: last.content + chunk };
          }
          return next;
        });
      };

      /** Settle the placeholder: keep it if it has content, drop it if not. */
      const settle = (finalContent?: string) => {
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last?.role !== "agent" || !last.streaming) return prev;
          const content = finalContent ?? last.content;
          if (!content.trim()) {
            next.pop();
            return next;
          }
          next[next.length - 1] = { ...last, content, streaming: false };
          return next;
        });
      };

      /** Drop the placeholder AND the optimistic user message. */
      const dropTurn = () => {
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last?.role === "agent" && last.streaming) next.pop();
          if (next[next.length - 1] === userMsg) next.pop();
          return next;
        });
      };

      /**
       * The failure state for a turn whose user message is still on screen.
       *
       * It stays there deliberately: the reader should be able to see what they
       * sent while the error is in front of them. `handleRetry` withdraws it
       * just before re-sending, so exactly one copy survives a retry.
       *
       * Not withdrawn once tokens have arrived. `settle` keeps a partial reply,
       * and a retry that took the question away would leave that half-answer
       * stranded above the NEXT question rather than below the one it belongs
       * to. Decided here rather than at each call site: four sites were spelling
       * out the same rule, and the one that did not — the pause after `done` --
       * was the one that got it wrong.
       */
      const failed = (message: string, paused: boolean): ThreadSendError => ({
        message,
        retryText: messageText,
        retryAttachments: attachments,
        paused,
        pendingUserMessage: streamedAnything ? null : userMsg,
      });

      let streamedAnything = false;

      const inputData: InputData = attachments?.length
        ? {
            // Send ALL attachments (including non-forwardable ones) so the
            // backend can log the storageRef; it decides what to inline from
            // `forwardableInline`.
            input: messageText || attachments[0]?.fileName || "attachment",
            context: buildAttachmentContext(
              attachments.map(
                (a): AttachmentRef => ({
                  storageRef: a.storageRef,
                  fileName: a.fileName,
                  mimeType: a.mimeType,
                  sizeBytes: a.sizeBytes,
                  forwardableInline: a.forwardableInline,
                }),
              ),
            ),
          }
        : { input: messageText };

      try {
        // Which terminal event actually arrived. A stream can also just stop —
        // a dropped connection, a proxy timing out — and that is neither.
        let sawDone = false;
        let sawError = false;

        for await (const event of sendMessageStreaming(
          "production",
          memberId,
          conversationId,
          inputData,
          abort.signal,
        )) {
          if (event.type === "token") {
            streamedAnything = true;
            appendToken(event.data);
            continue;
          }
          if (event.type === "error") {
            sawError = true;
            // The backend sends `{"message","code"}`; a known code has a
            // sentence in the reader's language, and the backend's own text is
            // the fallback rather than something discarded.
            let message = event.data;
            let code: string | undefined;
            try {
              const parsed = JSON.parse(event.data);
              if (typeof parsed?.message === "string") message = parsed.message;
              if (typeof parsed?.code === "string") code = parsed.code;
            } catch {
              /* non-JSON payload — the raw text is what there is */
            }
            settle();
            setSendError(failed(translateStreamError(code, t) ?? message, false));
            break;
          }
          if (event.type === "done") {
            sawDone = true;
            // A turn that produced no tokens still has its answer in the
            // snapshot, and a turn that paused for approval says so there.
            let finalContent: string | undefined;
            let paused = false;
            if (event.data) {
              try {
                const snapshot = JSON.parse(event.data);
                paused = snapshot?.conversationState === "AWAITING_HUMAN";
                if (!streamedAnything && Array.isArray(snapshot?.conversationSteps)) {
                  // The LAST agent message, never a join across steps.
                  // `sendMessageStreaming` posts to `/agents/{id}/stream` with no
                  // query params, so unlike the `returnCurrentStepOnly=true`
                  // calls this replaced, the `done` snapshot carries the whole
                  // conversation. Joining it filled the new bubble with every
                  // reply in the thread — the entire history rendered a second
                  // time, and on a paused turn directly above the pause banner.
                  // `use-chat` and `use-operator-chat` both take only the last
                  // output for the same reason.
                  const agentSteps = parseConversationSteps(snapshot.conversationSteps).filter(
                    (m) => m.role === "agent",
                  );
                  finalContent = agentSteps[agentSteps.length - 1]?.content;
                }
              } catch {
                /* an unreadable snapshot costs the fallback text, not the turn */
              }
            }
            settle(finalContent);
            if (paused) {
              setSendError(
                failed(
                  t(
                    "Workforce.thread.pausedForApproval",
                    "This turn is paused waiting for a human decision. It resumes once the request is approved.",
                  ),
                  true,
                ),
              );
            }
            // The stream is logically over; abort so the reader resolves now
            // instead of waiting for the server to close.
            abort.abort();
            break;
          }
        }

        // A stream that ended without `done` still has to settle, or the
        // placeholder streams forever — but settling silently was wrong: it
        // presented a truncated reply as a finished one, with no way to tell
        // and nothing to do about it.
        settle();
        if (!sawDone && !sawError && !abort.signal.aborted) {
          setSendError(
            failed(
              t(
                "Workforce.thread.streamEnded",
                "The connection ended before the reply finished. What arrived is above — send again to retry.",
              ),
              false,
            ),
          );
        }
        updateActivityRef.current(boardId, memberId);
      } catch (err) {
        // Expected once `done` has already aborted the reader.
        if (err instanceof DOMException && err.name === "AbortError") {
          settle();
        } else if (isApiError(err) && err.status === 409) {
          // The send was rejected WITHOUT being consumed because the
          // conversation is paused. Both the placeholder and the optimistic
          // user message have to go: the backend never received it, and
          // leaving it in the transcript shows a message as sent that was not.
          // `dropTurn` already removed the message: the backend never received
          // it, so whatever `failed` records here has nothing left to withdraw.
          // A paused error offers a link to the approvals queue rather than a
          // Retry, so it is never read either way.
          dropTurn();
          setSendError(
            failed(
              t(
                "Workforce.thread.pausedRejected",
                "This conversation is paused waiting for a human decision, so the message was not sent.",
              ),
              true,
            ),
          );
        } else {
          settle();
          setSendError(failed(getErrorMessage(err), false));
        }
      } finally {
        abortRef.current = null;
        sendingRef.current = false;
        setIsLoading(false);
      }
    },
    [conversationId, isLoading, inputPrefill, memberId, boardId, t],
  );

  /** Stop generating — the stream is abandoned, what arrived is kept. */
  const handleStop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  /**
   * Re-send the turn that failed.
   *
   * The failed turn's user message is withdrawn first: `handleSend` appends its
   * own, so without this a successful retry would show the same text twice.
   */
  const handleRetry = useCallback(() => {
    const previous = sendError;
    if (!previous) return;
    setSendError(null);
    if (previous.pendingUserMessage) {
      setMessages((prev) => prev.filter((msg) => msg !== previous.pendingUserMessage));
    }
    void handleSend(previous.retryText, previous.retryAttachments);
  }, [sendError, handleSend]);

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
            aria-label={
              showDetails
                ? t("Workforce.chat.hideAgentDetails", "Hide agent details")
                : t("Workforce.chat.showAgentDetails", "Show agent details")
            }
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
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="relative flex-1 overflow-y-auto ps-4 pe-4 pt-4 pb-4"
      >
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
                "group/msg flex",
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

              <div className="flex flex-col min-w-0 max-w-lg">
                <div
                  className={cn(
                    "rounded-2xl ps-4 pe-4 py-2.5 text-sm",
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

                  {/* Attachment rendering on messages */}
                  {msg.attachments && msg.attachments.length > 0 && (
                    <div className="mb-1.5 space-y-1.5">
                      {msg.attachments.map((att) => (
                        <div key={att.storageRef}>
                          {/* Image preview */}
                          {isImageMime(att.mimeType) && att.previewUrl ? (
                            <img
                              src={att.previewUrl}
                              alt=""
                              className="max-h-40 max-w-[220px] rounded-lg object-cover"
                              onError={(e) => {
                                (e.target as HTMLElement).style.display = "none";
                              }}
                            />
                          ) : (
                            /* Non-image file chip */
                            <span
                              className={cn(
                                "inline-flex items-center gap-1.5 rounded-full ps-2.5 pe-2.5 py-1 text-xs",
                                msg.role === "user"
                                  ? "bg-white/20 text-primary-foreground"
                                  : "bg-muted text-muted-foreground",
                              )}
                            >
                              <FileText className="h-3 w-3" />
                              <span className="max-w-32 truncate">{att.fileName}</span>
                              {att.sizeBytes != null && (
                                <span className="opacity-60">{formatBytes(att.sizeBytes)}</span>
                              )}
                            </span>
                          )}
                          {/* Warning when file not forwarded to model */}
                          {att.forwardableInline === false && (
                            <span
                              data-testid="attachment-not-forwarded"
                              className={cn(
                                "mt-0.5 inline-flex items-center gap-1 rounded-full ps-2 pe-2 py-0.5 text-[10px]",
                                msg.role === "user"
                                  ? "bg-amber-500/20 text-amber-100"
                                  : "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400",
                              )}
                            >
                              <AlertTriangle className="h-2.5 w-2.5" />
                              {t("Workforce.thread.notForwarded", "File stored but too large to send to model")}
                            </span>
                          )}
                        </div>
                      ))}
                    </div>
                  )}

                  {msg.role === "agent" ? (() => {
                    // A placeholder with nothing in it yet is the reply on its
                    // way, not an empty answer.
                    if (msg.streaming && !msg.content) return <TypingDots />;
                    const parsed = parseTranscriptContent(msg.content);
                    if (!parsed.trim()) return <p className="italic text-muted-foreground">{t("Workforce.thread.noResponse", "No response")}</p>;
                    return (
                      <div className="prose prose-sm dark:prose-invert max-w-none text-foreground [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {formatMarkdownText(parsed)}
                        </ReactMarkdown>
                      </div>
                    );
                  })() : (
                    <p className="whitespace-pre-wrap">{msg.content}</p>
                  )}
                </div>

                {/* Timestamp & copy button row — fixed h-5 prevents hover layout shift */}
                <div
                  className={cn(
                    "mt-0.5 flex h-5 items-center gap-1.5 px-1 text-[10px] text-muted-foreground/60 select-none",
                    msg.role === "user" ? "justify-end" : "justify-start",
                  )}
                >
                  <span>{formatShortTime(msg.timestamp)}</span>
                  {msg.content && (
                    <CopyMessageButton content={parseTranscriptContent(msg.content)} />
                  )}
                </div>
              </div>
            </div>
          ))}

          {/* A failed turn.
              This used to be appended to the transcript as a message from the
              agent reading "Sorry, I encountered an error." — so a paused
              conversation, an expired token and a quota rejection all looked
              like the agent apologising, and the backend's own sentence went
              only to the console. It is not something the agent said. */}
          {sendError && (
            <div
              className={cn(
                "flex items-start gap-2 rounded-xl border p-3 text-sm",
                sendError.paused
                  ? "border-warning/30 bg-warning/5"
                  : "border-destructive/30 bg-destructive/5",
              )}
              role="alert"
              data-testid="thread-send-error"
            >
              <AlertTriangle
                className={cn(
                  "mt-0.5 h-4 w-4 shrink-0",
                  sendError.paused ? "text-warning" : "text-destructive",
                )}
                aria-hidden="true"
              />
              <div className="min-w-0 flex-1">
                <p className="text-foreground">{sendError.message}</p>
                <div className="mt-1.5 flex flex-wrap items-center gap-3">
                  {sendError.paused ? (
                    // Retrying will not help until the pause is decided, and
                    // the queue that decides it is a screen away.
                    <Link
                      to="/manage/approvals"
                      className="text-xs font-medium text-warning underline-offset-2 hover:underline"
                      data-testid="thread-review-approvals"
                    >
                      {t("Workforce.thread.reviewApprovals", "Review pending approvals")}
                    </Link>
                  ) : (
                    <button
                      type="button"
                      onClick={handleRetry}
                      className="text-xs font-medium text-primary underline-offset-2 hover:underline"
                      data-testid="thread-retry"
                    >
                      {t("common.retry", "Retry")}
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => setSendError(null)}
                    className="text-xs text-muted-foreground underline-offset-2 hover:underline"
                    data-testid="thread-dismiss-error"
                  >
                    {t("common.dismiss", "Dismiss")}
                  </button>
                </div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Scroll-to-bottom FAB with new content pulse */}
        {showScrollFab && (
          <button
            type="button"
            onClick={() => scrollToBottom("smooth")}
            className="absolute bottom-4 end-4 z-10 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card shadow-lg text-muted-foreground hover:text-foreground hover:bg-muted transition-all animate-in fade-in slide-in-from-bottom-2"
            title={t("chat.scrollToBottom", "Scroll to bottom")}
            data-testid="scroll-to-bottom"
          >
            <ArrowDown className="h-4 w-4" />
            {hasNewContent && (
              <span className="absolute -top-1 -end-1 flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75" />
                <span className="relative inline-flex rounded-full h-3 w-3 bg-primary" />
              </span>
            )}
          </button>
        )}
      </div>

        {/* Stop generating — a streamed turn can be long, and the only way to
            end one used to be to wait it out. */}
        {isLoading && (
          <div className="flex shrink-0 justify-center pb-1">
            <Button
              variant="outline"
              size="sm"
              onClick={handleStop}
              data-testid="thread-stop"
            >
              <Square className="h-3 w-3" />
              {t("Workforce.thread.stop", "Stop generating")}
            </Button>
          </div>
        )}

        {/* Thread input with file attachment support */}
        <ThreadInput
          onSend={handleSend}
          disabled={isLoading || !conversationId}
          conversationId={conversationId}
          placeholder={
            inputPrefill ||
            // Distinct key from the generic `Workforce.thread.placeholder`
            // below: one key cannot hold two different English strings, and
            // whichever landed in en.json first would silently win for the
            // other call site.
            t("Workforce.thread.placeholderNamed", "Message {{name}}...", {
              name: memberName,
            })
          }
          className="shrink-0"
        />
      </div>

      {/* Right details panel */}
      {showDetails && (
        <AgentDetailsPanel
          agentId={memberId}
          agentName={memberName}
          onClose={() => setShowDetails(false)}
        />
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
