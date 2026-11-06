import { AppLink } from "@eddi/app-kit/components/shared/app-link";
import {
  Fragment,
  useState,
  useEffect,
  useMemo,
  useRef,
  useCallback,
  type KeyboardEvent,
  type MutableRefObject,
} from "react";
import type { TFunction } from "i18next";
import { useParams, useLocation, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { agentKeys } from "@eddi/app-kit/lib/query-keys";
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
  MessageSquarePlus,
} from "lucide-react";
import { parseTranscriptContent, formatMarkdownText } from "@eddi/conversation/team";
import { useSmartAutoScroll } from "@eddi/app-kit/hooks/use-smart-auto-scroll";
import { useWorkforceGroup } from "@/hooks/use-workforce-group";
import { useWorkforceAccess } from "@eddi/app-kit/hooks/use-workforce-access";
import { isImeComposing } from "@eddi/app-kit/lib/ime";
import {
  startConversation,
  sendMessageStreaming,
  readConversation,
  undoConversation,
  redoConversation,
  rerunLastStep,
} from "@eddi/app-kit/lib/api/chat";
import { cancelConversation } from "@eddi/app-kit/lib/api/hitl";
import {
  AgentConversation,
  RECONNECT_DELAYS_MS,
  STOP_VERIFY_DELAYS_MS,
  useAgentConversation,
  type ConversationMessage,
} from "@eddi/conversation";
import { extractOutputTexts, lastStepIndex, stepIndexes, type ContextMap, type ConversationSnapshot } from "@eddi/core";
import { toolResultListener } from "@eddi/core/tool-events";
import {
  GuardrailNote,
  ToolActivityCards,
  ToolActivityLog,
  TurnNotes,
  TurnNotesLog,
  toolCardPositions,
  turnNoteListener,
  useToolTurns,
  useTurnNotes,
} from "@eddi/conversation/tool-activity";
import { translateStreamError } from "@eddi/app-kit/hooks/use-chat";
import { getErrorMessage, isApiError } from "@eddi/app-kit/lib/api-client";
import {
  uploadAttachment,
  deleteAttachment,
  isImageMime,
  formatBytes,
  MAX_ATTACHMENTS_PER_TURN,
  MAX_ATTACHMENT_BYTES,
  type AttachmentResult,
} from "@eddi/app-kit/lib/api/attachments";
import { getAgentDescriptors } from "@eddi/app-kit/lib/api/agents";
import { useWorkforceThreads } from "@/hooks/use-workforce-threads";
import { extractOutputParts, type ConversationOutput, type SimpleConversationStep } from "@eddi/app-kit/lib/api/conversations";
import { ContextCard } from "@/components/workforce/context-card";
import { PauseNotice } from "@eddi/app-kit/components/hitl/pause-notice";
import { AgentDetailsPanel } from "@/components/workforce/agent-details-panel";
import { AdvisorAvatar } from "@eddi/app-kit/components/workforce/advisor-avatar";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { Button } from "@eddi/ui";
import { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
import { cn } from "@eddi/app-kit/lib/utils";
import { ConversationMarkdown } from "@eddi/conversation/i18next";

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

/**
 * One message of the thread: @eddi/conversation's message (the thread runs on
 * its controller). `isStreaming` while tokens arrive; `attachments` on a sent one.
 */
type ThreadMessage = ConversationMessage;

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
   * the same text on screen twice. Kept by id rather than by text so two
   * genuinely identical messages are never confused for each other.
   *
   * Null when the turn was already withdrawn, which is what the 409 path does:
   * there the backend never received the message at all, so leaving it on
   * screen would show it as sent.
   */
  pendingUserMessageId: string | null;
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
        messages.push({ id: `step-user-${messages.length}`, role: "user", content, timestamp });
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
        id: `step-agent-${messages.length}`,
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
        "reveal-on-touch inline-flex items-center gap-0.5 rounded px-1 py-0.5 text-[10px] opacity-0 group-hover/msg:opacity-100 focus-visible:opacity-100 transition-opacity duration-150",
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
      if (isImeComposing(e)) return;
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
          data-testid="thread-input"
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

// ─── The thread's conversation ───────────────────────────────────

/** What the page needs to know about the turn in flight. */
interface ThreadTurn {
  text: string;
  attachments?: SentAttachment[];
  /** Which events the stream delivered. A stream can also just stop. */
  tokens: boolean;
  done: boolean;
  error: boolean;
  /** The reader pressed Stop: what arrived is kept, and nothing is reported. */
  stopped: boolean;
}

/** The page's handlers, read by the controller's hooks at call time. */
interface ThreadHooks {
  t: TFunction;
  /** The turn did not produce an answer; `userMessageId` is the question to withdraw on retry. */
  failed: (message: string, paused: boolean, userMessageId: string | null) => void;
}

/** The first attachment's name, as the input of a turn that sends only files. */
function firstAttachmentName(context: ContextMap | undefined): string | undefined {
  const value = context?.attachment_0?.value as { fileName?: unknown } | undefined;
  return typeof value?.fileName === "string" ? value.fileName : undefined;
}

/**
 * The member thread on @eddi/conversation's controller (phase 3c): the turn —
 * streaming, the reconcile, dropped vs paused turns — is the controller's, the
 * thread keeps its own error banner and Retry (`presentFailure`) and its pause
 * notice (`onTurnSnapshot`). One per advisor. The `tool_result` events of its
 * turns go into `tools`, for the tool cards (T1.9); its cascade and re-ask
 * events into `notes`, for the plain-language turn notes (T2.7).
 */
function createThreadConversation(
  memberId: string,
  hooksRef: MutableRefObject<ThreadHooks | null>,
  turnRef: MutableRefObject<ThreadTurn | null>,
  tools: ToolActivityLog,
  notes: TurnNotesLog,
): AgentConversation {
  // Filed under the conversation and step on screen when each call finishes.
  const recordToolResult = toolResultListener((result) => {
    const { conversationId, messages } = controller.getSnapshot();
    tools.record(conversationId, lastStepIndex(messages), result);
  });
  const recordTurnNote = turnNoteListener((note) => {
    const { conversationId, messages } = controller.getSnapshot();
    notes.record(conversationId, lastStepIndex(messages), note);
  });
  const controller: AgentConversation = new AgentConversation({
    stopVerifyDelaysMs: STOP_VERIFY_DELAYS_MS,
    reconnectDelaysMs: RECONNECT_DELAYS_MS,
    transport: {
      mode: "conversation",
      // A turn of files only names the first file as its input; the backend
      // reads the files from the context.
      stream: (conversationId, text, { context, signal }) =>
        sendMessageStreaming(
          "production",
          memberId,
          conversationId ?? "",
          context ? { input: text || firstAttachmentName(context) || "attachment", context } : { input: text },
          signal,
        ),
      read: (conversationId, currentStepOnly) =>
        readConversation("production", memberId, conversationId, currentStepOnly ?? false) as unknown as Promise<
          ConversationSnapshot
        >,
      undo: (conversationId) => undoConversation("production", memberId, conversationId),
      redo: (conversationId) => redoConversation("production", memberId, conversationId),
      rerun: (conversationId) => rerunLastStep(conversationId),
      cancel: (conversationId) => cancelConversation(conversationId),
    },
    refreshAfterStream: false,
    attachmentDisplay: "chips",
    outputTexts: (output) => {
      const texts = extractOutputTexts(output.output);
      return texts.length > 0 ? texts : extractOutputParts(output as ConversationOutput);
    },
    onTurnStarting: () => {
      const { conversationId, messages } = controller.getSnapshot();
      tools.beginTurn(conversationId, lastStepIndex(messages));
      notes.beginTurn(conversationId, lastStepIndex(messages));
    },
    onStreamEvent: (event) => {
      recordToolResult(event);
      recordTurnNote(event);
      const turn = turnRef.current;
      if (!turn) return;
      if (event.type === "token") turn.tokens = true;
      else if (event.type === "done") turn.done = true;
      else if (event.type === "error") turn.error = true;
    },
    onTurnSnapshot: (snapshot) => {
      const hooks = hooksRef.current;
      if (!hooks || snapshot.conversationState !== "AWAITING_HUMAN") return;
      hooks.failed(
        hooks.t(
          "Workforce.thread.pausedForApproval",
          "This turn is paused waiting for a human decision. It resumes once the request is approved.",
        ),
        true,
        turnRef.current?.tokens ? null : lastUserMessageId(controller),
      );
    },
    presentFailure: (failure, ctx) => {
      const hooks = hooksRef.current;
      if (!hooks) return true;
      if (failure.status === 409) {
        // Refused WITHOUT being consumed because the conversation is paused:
        // the backend never received the message, so it leaves the transcript.
        // A paused error links to the approvals queue rather than a Retry.
        ctx.withdraw();
        hooks.failed(
          hooks.t(
            "Workforce.thread.pausedRejected",
            "This conversation is paused waiting for a human decision, so the message was not sent.",
          ),
          true,
          null,
        );
        return true;
      }
      // The question stays on screen next to the error, so the reader sees
      // what they sent; Retry withdraws it just before re-sending. Not once
      // tokens arrived: the partial reply stays under the question it answers.
      controller.dispatch({ type: "REMOVE_EMPTY_STREAMING_MESSAGE" });
      // A known stream code has a sentence in the reader's language; the
      // backend's own text is the fallback rather than something discarded.
      const message =
        failure.error !== undefined
          ? getErrorMessage(failure.error)
          : (translateStreamError(failure.code ?? undefined, hooks.t) ?? failure.message);
      hooks.failed(message, false, failure.partial ? null : ctx.userMessageId);
      return true;
    },
  });
  return controller;
}

/** The id of the latest question in the transcript. */
function lastUserMessageId(controller: AgentConversation): string | null {
  const messages = controller.getSnapshot().messages;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") return messages[i]!.id;
  }
  return null;
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
  // Resolved, not defaulted to 1: the links into a thread carry no version, and
  // version 1 is the group's FIRST — its original roster and name.
  // The group store for an author, the catalog entry for an end user.
  const { version: resolvedVersion, data: groupConfig } = useWorkforceGroup(boardId, searchParams.get("version"));
  const version = resolvedVersion ?? 1;
  const { canAuthor } = useWorkforceAccess();

  // Group context from router state (passed by advisor-response-card)
  const groupContext = isGroupContext(location.state) ? location.state : null;
  const hasGroupContext = groupContext?.fromGroup === true;

  // Fetch board config to resolve member display name & role
  const member = groupConfig?.members.find((m) => m.agentId === memberId);
  const memberRole = member?.role ?? null;

  // Fallback: if the member isn't in the group config, fetch the agent descriptor
  // to resolve the display name (avoids showing raw ObjectIds).
  const { data: descriptorResults } = useQuery({
    queryKey: agentKeys.descriptor(memberId),
    queryFn: () => getAgentDescriptors(1, 0, memberId),
    // The agent store answers authors only; an end user sees the member's id.
    enabled: canAuthor && !!memberId && !member,
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
  // The thread's conversation runs on the controller; these read it.
  const hooksRef = useRef<ThreadHooks | null>(null);
  const turnRef = useRef<ThreadTurn | null>(null);
  // One for the page: it files every call under its conversation, so another
  // advisor's thread simply reads other entries.
  const [toolLog] = useState(() => new ToolActivityLog({ maxConversations: 5 }));
  const [noteLog] = useState(() => new TurnNotesLog());
  const controller = useMemo(
    () => createThreadConversation(memberId, hooksRef, turnRef, toolLog, noteLog),
    [memberId, toolLog, noteLog],
  );
  const conversation = useAgentConversation(controller);
  const conversationId = conversation.conversationId;
  const messages = conversation.messages;
  const toolTurns = useToolTurns(toolLog, conversationId);
  const toolCardsAt = useMemo(() => toolCardPositions(messages, toolTurns), [messages, toolTurns]);
  // The turn notes go under each turn's last message (T2.7).
  const turnNotes = useTurnNotes(noteLog, conversationId);
  const turnNotesAt = useMemo(() => {
    const at = new Map<number, number>();
    if (turnNotes.size === 0) return at;
    stepIndexes(messages).forEach((step, i) => {
      if (step !== null && turnNotes.has(step)) at.set(step, i);
    });
    return new Map([...at].map(([step, i]) => [i, step]));
  }, [messages, turnNotes]);
  const isLoading = conversation.isProcessing;
  const setConversationId = useCallback(
    (id: string | null) => controller.dispatch({ type: "SET_CONVERSATION_ID", id }),
    [controller],
  );
  const setMessages = useCallback(
    (next: ThreadMessage[]) => controller.dispatch({ type: "REPLACE_MESSAGES", messages: next }),
    [controller],
  );
  const [sendError, setSendError] = useState<ThreadSendError | null>(null);
  const [isStarting, setIsStarting] = useState(true);
  /** Why the thread could not be opened, when it could not. */
  const [initError, setInitError] = useState<string | null>(null);
  /** Bumped by "Try again" to re-run the initialisation. */
  const [initAttempt, setInitAttempt] = useState(0);
  /** A restart is in flight — keeps the button from firing twice. */
  const [isRestarting, setIsRestarting] = useState(false);
  const [inputPrefill, setInputPrefill] = useState("");
  const [showDetails, setShowDetails] = useState(false);
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);

  // Refs
  const messagesEndRef = useRef<HTMLDivElement>(null);
  /** Which (board, member, attempt) the thread was last initialised for. */
  const initKeyRef = useRef<string | null>(null);

  // Leaving the advisor (or the page) abandons the turn in flight.
  useEffect(() => () => controller.reset(), [controller]);

  hooksRef.current = {
    t,
    failed: (message, paused, userMessageId) => {
      const turn = turnRef.current;
      setSendError({
        message,
        retryText: turn?.text ?? "",
        retryAttachments: turn?.attachments,
        paused,
        pendingUserMessageId: userMessageId,
      });
    },
  };

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

  /**
   * Start a brand-new conversation with this advisor and make it the thread's.
   *
   * Registering the thread overwrites the stored entry for (board, member), so
   * the previous conversation stops being the one this page resumes. The old
   * conversation is left on the server — nothing is deleted — which is the same
   * contract the Manager's other chat surfaces have: "New Conversation" starts a
   * fresh one, it does not destroy the last.
   */
  const startFreshConversation = useCallback(async (isCurrent: () => boolean = () => true) => {
    if (!boardId || !memberId) return;
    const newConvId = await startConversation("production", memberId);
    // The thread is still registered for its own (board, member) even when the
    // page has moved on: it IS that advisor's conversation now.
    registerThreadRef.current({
      memberId,
      memberName: memberNameRef.current,
      conversationId: newConvId,
      boardId,
    });
    if (!isCurrent()) return;
    // Abandons anything of the old conversation and empties the transcript —
    // only now that the new one exists: a failed start keeps the old thread.
    controller.reset();
    turnRef.current = null;
    setConversationId(newConvId);

    // Read any welcome message the agent might have
    try {
      const snapshot = await readConversation("production", memberId, newConvId);
      const parsed = parseConversationSteps(snapshot.conversationSteps ?? []);
      if (parsed.length > 0 && isCurrent()) {
        setMessages(parsed);
      }
    } catch {
      // No welcome message — that's fine
    }
  }, [boardId, memberId, setConversationId, setMessages, controller]);

  const startFreshRef = useRef(startFreshConversation);
  startFreshRef.current = startFreshConversation;

  // ─── Initialize conversation ─────────────────────────────────
  //
  // Keyed on (board, member, attempt) rather than run once per mount. Moving
  // from one advisor's thread to another's keeps this page mounted — only the
  // route param changes — and a once-per-mount guard left the second advisor
  // showing the FIRST one's messages and sending into the first one's
  // conversation. The key is re-checked after every await, so a slow init for
  // the advisor just left cannot write into the one now on screen.
  useEffect(() => {
    if (!boardId || !memberId) return;
    const key = `${boardId}\u0000${memberId}\u0000${initAttempt}`;
    if (initKeyRef.current === key) return;
    initKeyRef.current = key;
    const isCurrent = () => initKeyRef.current === key;

    controller.reset();
    turnRef.current = null;
    setSendError(null);
    setInitError(null);
    setIsStarting(true);

    async function init() {
      try {
        const existingThread = getThreadRef.current(boardId, memberId);

        if (existingThread) {
          // Resume existing conversation
          try {
            const snapshot = await readConversation(
              "production",
              memberId,
              existingThread.conversationId,
            );
            if (!isCurrent()) return;
            setConversationId(existingThread.conversationId);
            setMessages(parseConversationSteps(snapshot.conversationSteps ?? []));
            updateActivityRef.current(boardId, memberId);
          } catch (err) {
            // The stored conversation no longer exists — deleted, erased, or
            // cleaned up. Resuming it would leave a thread every send fails in,
            // so start a fresh one instead; that is what the reader came for.
            if (!(isApiError(err) && (err.status === 404 || err.status === 410))) throw err;
            if (!isCurrent()) return;
            await startFreshRef.current(isCurrent);
          }
        } else {
          await startFreshRef.current(isCurrent);
        }
      } catch (err) {
        if (!isCurrent()) return;
        // Used to go to the console only: the page then showed an empty,
        // healthy-looking thread whose composer was dead (no conversation to
        // send to) and said nothing about why.
        console.error("Failed to initialize thread:", err);
        setInitError(getErrorMessage(err));
      } finally {
        if (isCurrent()) setIsStarting(false);
      }
    }

    init();
  }, [boardId, memberId, initAttempt, controller, setConversationId, setMessages]);

  /**
   * "New Conversation" — every other chat surface in the Manager has one
   * (`chat-panel`, `chat-drawer`, `operator-chat`, and the Workforce board's
   * "New"); this page, the 1:1 advisor thread, was the only one without. Its
   * conversation is pinned in localStorage by (board, member), so without a
   * restart the only way out of a derailed thread was to clear site data.
   */
  const handleNewConversation = useCallback(async () => {
    if (isRestarting || isLoading) return;
    setIsRestarting(true);
    setSendError(null);
    setInputPrefill("");
    // Bound to the thread it was pressed on: if the reader moves to another
    // advisor while this is in flight, the new conversation is still registered
    // for THIS advisor, but it must not land in the other one's view.
    const key = initKeyRef.current;
    const isCurrent = () => initKeyRef.current === key;
    try {
      await startFreshConversation(isCurrent);
      if (isCurrent()) setInitError(null);
    } catch (err) {
      console.error("Failed to start a new conversation:", err);
      toast.error(
        t("Workforce.thread.newConversationFailed", "Could not start a new conversation"),
      );
    } finally {
      setIsRestarting(false);
    }
  }, [isRestarting, isLoading, startFreshConversation, t]);

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

      // Clear prefill after first send
      if (inputPrefill) setInputPrefill("");
      setSendError(null);

      const turn: ThreadTurn = {
        text: text.trim(),
        attachments,
        tokens: false,
        done: false,
        error: false,
        stopped: false,
      };
      turnRef.current = turn;
      const generation = controller.currentGeneration;
      // ALL attachments travel, including the ones too large to inline: the
      // backend logs the storageRef and decides what to inline from
      // `forwardableInline`.
      const result = await controller.send(turn.text, false, { attachments: attachments ?? [] });
      if (turnRef.current !== turn || !controller.isCurrent(generation)) return;

      // A stream that ended without `done` or `error` — a dropped connection,
      // a proxy timing out — presented a truncated reply as a finished one.
      // Say so; what arrived stays.
      if (result.outcome === "sent" && !turn.done && !turn.error && !turn.stopped) {
        hooksRef.current?.failed(
          t(
            "Workforce.thread.streamEnded",
            "The connection ended before the reply finished. What arrived is above — send again to retry.",
          ),
          false,
          turn.tokens ? null : lastUserMessageId(controller),
        );
      }
      if (result.outcome !== "ignored") updateActivityRef.current(boardId, memberId);
    },
    [conversationId, isLoading, inputPrefill, memberId, boardId, t, controller],
  );

  /** Stop generating — the stream ends, what arrived is kept, the server stops the turn. */
  const handleStop = useCallback(() => {
    if (turnRef.current) turnRef.current.stopped = true;
    void controller.stop();
  }, [controller]);

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
    if (previous.pendingUserMessageId) {
      setMessages(messages.filter((msg) => msg.id !== previous.pendingUserMessageId));
    }
    void handleSend(previous.retryText, previous.retryAttachments);
  }, [sendError, handleSend, messages, setMessages]);

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
          <AppLink
            to={`/workforce/${boardId}?version=${version}`}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            aria-label={t("Workforce.back", "Back")}
          >
            <ChevronLeft className="h-5 w-5 rtl:rotate-180" />
          </AppLink>
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
          {/* New conversation */}
          <button
            type="button"
            onClick={handleNewConversation}
            disabled={isRestarting || isLoading}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50"
            aria-label={t("Workforce.thread.newConversation", "New conversation")}
            title={t("Workforce.thread.newConversation", "New conversation")}
            data-testid="thread-new-conversation"
          >
            {isRestarting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <MessageSquarePlus className="h-4 w-4" />
            )}
          </button>
          {/* Details panel toggle — the panel reads the agent and deployment
              stores, which answer authors only. */}
          {canAuthor && (
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
          )}
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
          {initError && !conversationId && (
            <div
              className="flex items-start gap-2 rounded-xl border border-destructive/30 bg-destructive/5 p-3 text-sm"
              role="alert"
              data-testid="thread-init-error"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="text-foreground">
                  {t("Workforce.thread.initFailed", "This conversation could not be opened.")}
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">{initError}</p>
                <div className="mt-1.5 flex flex-wrap items-center gap-3">
                  <button
                    type="button"
                    onClick={() => setInitAttempt((n) => n + 1)}
                    className="text-xs font-medium text-primary underline-offset-2 hover:underline"
                    data-testid="thread-init-retry"
                  >
                    {t("common.retry", "Retry")}
                  </button>
                  <button
                    type="button"
                    onClick={() => void handleNewConversation()}
                    disabled={isRestarting}
                    className="text-xs text-muted-foreground underline-offset-2 hover:underline"
                    data-testid="thread-init-new"
                  >
                    {t("Workforce.thread.newConversation", "New conversation")}
                  </button>
                </div>
              </div>
            </div>
          )}

          {messages.length === 0 && !isLoading && !(initError && !conversationId) && (
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

          {messages.map((msg, idx) => {
            const toolStep = toolCardsAt.get(idx);
            const toolActivity = toolStep === undefined ? undefined : toolTurns.get(toolStep);
            const notesStep = turnNotesAt.get(idx);
            const notes = notesStep === undefined ? undefined : turnNotes.get(notesStep);
            const guardrails = msg.role === "agent" ? (msg.guardrails ?? []) : [];
            return (
            <Fragment key={`${msg.role}-${msg.timestamp}-${idx}`}>
            <div
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
                      {msg.attachments.map((att, attIndex) => (
                        <div key={`${att.fileName}-${attIndex}`}>
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
                                  ? "bg-warn-500/20 text-warn-100"
                                  : "bg-warn-100 text-warn-700 dark:bg-warn-900/30 dark:text-warn-400",
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
                    if (msg.isStreaming && !msg.content) return <TypingDots />;
                    // Images the agent designer put in the output (the
                    // controller keeps only safe URIs; a model's image in text
                    // is a link, via the renderer).
                    if (msg.images?.length && !msg.content) {
                      return (
                        <div className="flex flex-wrap gap-2" data-testid="message-images">
                          {msg.images.map((image, i) => (
                            <img
                              key={`${image.uri}-${i}`}
                              src={image.uri}
                              alt={image.alt ?? ""}
                              loading="lazy"
                              referrerPolicy="no-referrer"
                              className="max-h-80 max-w-full rounded-lg"
                            />
                          ))}
                        </div>
                      );
                    }
                    const parsed = parseTranscriptContent(msg.content);
                    if (!parsed.trim()) return <p className="italic text-muted-foreground">{t("Workforce.thread.noResponse", "No response")}</p>;
                    return (
                      <div className="prose prose-sm dark:prose-invert max-w-none text-foreground [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs">
                        <ConversationMarkdown escapeRawHtml>
                          {formatMarkdownText(parsed)}
                        </ConversationMarkdown>
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
            {/* What the tools of this turn did (T1.9), under its question. */}
            {toolActivity && (
              <div className="ps-10">
                <ToolActivityCards activity={toolActivity} />
              </div>
            )}
            {/* Why the answer came the way it did (T2.7): a guardrail that
                acted, a cascade that moved on, a re-ask — only what the stream said. */}
            {guardrails.length > 0 && (
              <ul className="flex flex-col gap-0.5 ps-10" data-testid="guardrail-notices">
                {guardrails.map((n, i) => (
                  <GuardrailNote key={i} notice={n} />
                ))}
              </ul>
            )}
            {notes && (
              <div className="ps-10">
                <TurnNotes notes={notes} />
              </div>
            )}
            </Fragment>
            );
          })}

          {/* A failed turn.
              This used to be appended to the transcript as a message from the
              agent reading "Sorry, I encountered an error." — so a paused
              conversation, an expired token and a quota rejection all looked
              like the agent apologising, and the backend's own sentence went
              only to the console. It is not something the agent said. */}
          {sendError?.paused && conversationId && (
            <div role="alert" data-testid="thread-send-error">
              <PauseNotice
                conversationId={conversationId}
                headline={sendError.message}
                testIdBase="thread-pause"
                reviewTestId="thread-review-approvals"
                onResolved={() => setSendError((current) => (current?.paused ? null : current))}
              />
            </div>
          )}
          {sendError && !(sendError.paused && conversationId) && (
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
                    <AppLink
                      to="/manage/approvals"
                      className="text-xs font-medium text-warning underline-offset-2 hover:underline"
                      data-testid="thread-review-approvals"
                    >
                      {t("Workforce.thread.reviewApprovals", "Review pending approvals")}
                    </AppLink>
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
      {canAuthor && showDetails && (
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
