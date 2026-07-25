import { useState, useRef, useCallback, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Paperclip, X } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

// ─── Constants ───────────────────────────────────────────────────

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

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

export interface AttachmentInfo {
  fileName: string;
  file: File;
}

interface BoardInputProps {
  onSend: (message: string, attachment?: AttachmentInfo) => void;
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
    // Reset textarea height
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
    const nextHeight = Math.min(Math.max(el.scrollHeight, 40), 128);
    el.style.height = `${nextHeight}px`;
    el.style.overflowY = el.scrollHeight > 128 ? "auto" : "hidden";
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
            t("Workforce.board.fileTooLarge", "File must be under 10MB"),
          );
        } else if (!ALLOWED_FILE_TYPES.has(file.type)) {
          toast.error(
            t("Workforce.board.fileTypeNotAllowed", "This file type is not supported"),
          );
        } else {
          setAttachment({ fileName: file.name, file });
        }
      }
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
            className="inline-flex items-center gap-1.5 rounded-full ps-3 pe-3 py-1 text-xs font-medium bg-muted text-muted-foreground"
          >
            <Paperclip className="h-3 w-3" />
            <span className="max-w-48 truncate">{attachment.fileName}</span>
            <button
              type="button"
              onClick={removeAttachment}
              className="ms-0.5 rounded-full p-0.5 hover:bg-muted-foreground/20 transition-colors"
              aria-label={t("Workforce.board.removeAttachment", "Remove attachment")}
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

        {/* Attachment button — hidden in continue mode (backend rejects attachments on continuation) */}
        {mode !== "continue" && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={handleFileSelect}
            disabled={disabled}
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
        >
          <SendIcon />
        </Button>
      </div>
    </div>
  );
}

export { BoardInput };
export type { BoardInputProps };
