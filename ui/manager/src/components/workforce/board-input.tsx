import { useState, useRef, useCallback, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Paperclip, X } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  formatAttachmentBytes,
  useGroupAttachmentStaging,
} from "@/hooks/use-group-attachment-staging";
import { MAX_GROUP_QUESTION_CHARS, type GroupAttachmentRef } from "@/lib/api/groups";

// ─── Constants ───────────────────────────────────────────────────

const ALLOWED_FILE_TYPES = new Set([
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/webp",
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

// ─── Types ───────────────────────────────────────────────────────

interface BoardInputProps {
  /**
   * `attachments` is only ever non-empty in `mode: "new"` — the backend rejects
   * a continuation that carries any, because files are shared with member
   * agents when the discussion first starts.
   *
   * This used to be a single `File`, and `workforce-board` never read it: the
   * paperclip staged a file, rendered a chip, and dropped it on send with no
   * error. The shape now matches `DiscussionInput`'s, which the group endpoint
   * actually takes.
   */
  onSend: (message: string, attachments?: GroupAttachmentRef[]) => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  /** Controls placeholder text and attachment visibility.
   *  "new" = start a new discussion (default).
   *  "continue" = continue the selected discussion (hides attachments). */
  mode?: "new" | "continue";
  /** Shown as placeholder when disabled (e.g. "Discussion is closed"). */
  disabledMessage?: string;
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

// ─── Component ───────────────────────────────────────────────────

function BoardInput({ onSend, disabled = false, placeholder, className, mode = "new", disabledMessage }: BoardInputProps) {
  const { t } = useTranslation();
  const [message, setMessage] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // The backend rejects attachments on a continuation outright.
  const canAttach = mode !== "continue";
  const {
    attachments,
    isStaging,
    addFiles,
    remove: removeAttachment,
    clear: clearAttachments,
    toRefs: attachmentRefs,
  } = useGroupAttachmentStaging(canAttach);

  const trimmed = message.trim();
  // The backend caps the question and fans it out to every member in every
  // phase, so this is a real ceiling. Unenforced, a 50k-character question
  // uploaded in full and came back as a 400 the user never saw — the Manager's
  // composer already blocks it, and this one posts to the same endpoint.
  // Measured on the trimmed body, which is what is actually sent — trailing
  // whitespace should not block a question that fits.
  const tooLong = trimmed.length > MAX_GROUP_QUESTION_CHARS;
  const canSend =
    (trimmed.length > 0 || attachments.length > 0) && !disabled && !tooLong && !isStaging;

  const handleSend = useCallback(() => {
    if (!canSend) return;
    const files = attachmentRefs();
    // One argument when there is nothing to attach: the message-only call is
    // the overwhelming case and its shape should not change because the
    // signature grew.
    if (files) onSend(trimmed, files);
    else onSend(trimmed);
    setMessage("");
    clearAttachments();
    // Reset textarea height
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [canSend, onSend, trimmed, attachmentRefs, clearAttachments]);

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
    (e: React.ChangeEvent<HTMLInputElement>) => {
      // Materialize the list here: clearing `value` below also clears `files`,
      // and the staging queue awaits between reads.
      const picked = Array.from(e.target.files ?? []);
      // The type gate stays a board concern — `accept` already narrows the
      // picker and this catches what slips past it. Size and count belong to
      // the shared hook, so they match what the endpoint actually enforces.
      const allowed = picked.filter((file) => {
        if (file.type && !ALLOWED_FILE_TYPES.has(file.type)) {
          toast.error(
            t("Workforce.board.fileTypeNotAllowed", "This file type is not supported"),
          );
          return false;
        }
        return true;
      });
      if (allowed.length) void addFiles(allowed);
      // Reset so re-picking the same file fires change again.
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    },
    [t, addFiles],
  );

  return (
    <div
      className={cn(
        "sticky bottom-0 ps-4 pe-4 py-3",
        "border-t bg-card border-border",
        className,
      )}
    >
      {/* Attachment chips */}
      {attachments.length > 0 && (
        <ul className="mb-2 flex flex-wrap items-center gap-1" data-testid="board-attachments">
          {attachments.map((a) => (
            <li
              key={a.id}
              className="inline-flex items-center gap-1.5 rounded-full ps-3 pe-3 py-1 text-xs font-medium bg-muted text-muted-foreground"
            >
              <Paperclip className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="max-w-48 truncate" title={a.fileName ?? undefined}>
                {a.fileName}
              </span>
              {/* Without a size, the total-size cap can only be found by hitting it. */}
              <span className="tabular-nums">{formatAttachmentBytes(a.sizeBytes)}</span>
              <Button
                type="button"
                variant="ghost"
                size="iconSm"
                onClick={() => removeAttachment(a.id)}
                className="ms-0.5 rounded-full hover:bg-muted-foreground/20"
                aria-label={t("groups.removeAttachment", "Remove {{name}}", { name: a.fileName })}
              >
                <X />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {tooLong && (
        <p
          className="mb-2 text-xs text-destructive"
          role="alert"
          id="board-question-too-long"
          data-testid="board-question-too-long"
        >
          {t("groups.questionTooLong", "A question can be at most {{max}} characters", {
            max: MAX_GROUP_QUESTION_CHARS.toLocaleString(),
          })}
        </p>
      )}

      <div className="flex items-end gap-2">
        {/* Hidden file input */}
        <input
          ref={fileInputRef}
          type="file"
          multiple
          onChange={handleFileChange}
          accept="image/*,.pdf,.txt,.csv,.md,.json,.doc,.docx,.xls,.xlsx"
          className="hidden"
          aria-hidden="true"
        />

        {/* Attachment button — hidden in continue mode (backend rejects attachments on continuation) */}
        {mode !== "continue" && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={handleFileSelect}
            disabled={disabled || isStaging}
            className="h-10 w-10 shrink-0 rounded-full text-muted-foreground hover:text-foreground"
            aria-label={t("Workforce.board.attachFile", "Attach file")}
          >
            <Paperclip className="h-5 w-5" />
          </Button>
        )}

        <textarea
          ref={textareaRef}
          autoFocus
          value={message}
          onChange={(e) => {
            setMessage(e.target.value);
            handleInput();
          }}
          onKeyDown={handleKeyDown}
          aria-invalid={tooLong || undefined}
          aria-describedby={tooLong ? "board-question-too-long" : undefined}
          placeholder={
            disabled && disabledMessage
              ? disabledMessage
              : placeholder ??
                (mode === "continue"
                  ? t("Workforce.board.continuePlaceholder", "Continue this discussion…")
                  : t("Workforce.board.askYourBoard", "Ask your task force..."))
          }
          aria-label={
            disabled && disabledMessage
              ? disabledMessage
              : placeholder ??
                (mode === "continue"
                  ? t("Workforce.board.continuePlaceholder", "Continue this discussion…")
                  : t("Workforce.board.askYourBoard", "Ask your task force..."))
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
            "focus-visible:ring-ring",
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
          aria-label={t("Workforce.board.send", "Send")}
          data-testid="board-send"
        >
          <SendIcon />
        </Button>
      </div>
    </div>
  );
}

export { BoardInput };
export type { BoardInputProps };
