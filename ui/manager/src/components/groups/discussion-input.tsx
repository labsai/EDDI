import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Send, Loader2, Expand, RotateCw, Paperclip, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { cn } from "@/lib/utils";
import { filesFromClipboard, useFileDrop } from "@/hooks/use-attachment-staging";
import { FileDropOverlay } from "@/components/chat/attachment-chip";
import {
  formatAttachmentBytes,
  useGroupAttachmentStaging,
} from "@/hooks/use-group-attachment-staging";
import { MAX_GROUP_QUESTION_CHARS, type GroupAttachmentRef } from "@/lib/api/groups";

interface DiscussionInputProps {
  /**
   * `attachments` is only ever non-empty in `mode: "new"` — the backend rejects a
   * continuation that carries any, because files are shared with member agents
   * when the discussion first starts.
   */
  onSubmit: (question: string, attachments?: GroupAttachmentRef[]) => void;
  isLoading?: boolean;
  disabled?: boolean;
  /** Controls placeholder text, button label, and icon.
   *  "new" = start a new discussion (default).
   *  "continue" = continue the selected discussion. */
  mode?: "new" | "continue";
  /** Shown as placeholder when disabled (e.g. "Discussion is closed"). */
  disabledMessage?: string;
}

/** Min/max heights for auto-growing textarea */
const MIN_HEIGHT = 40;
const MAX_HEIGHT = 120;

export function DiscussionInput({ onSubmit, isLoading, disabled, mode = "new", disabledMessage }: DiscussionInputProps) {
  const { t } = useTranslation();
  const [question, setQuestion] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const dialogTextareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  // Attachments are shared with member agents only when a discussion STARTS, so
  // a continuation that carries them is a 400. Hide the affordance rather than
  // letting the user assemble a request the backend will refuse.
  const canAttach = mode === "new";
  const {
    attachments,
    isStaging,
    addFiles,
    remove: removeAttachment,
    clear: clearAttachments,
    toRefs: attachmentRefs,
  } = useGroupAttachmentStaging(canAttach);

  // Auto-grow inline textarea
  const autoGrow = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const nextHeight = Math.min(Math.max(el.scrollHeight, MIN_HEIGHT), MAX_HEIGHT);
    el.style.height = `${nextHeight}px`;
    el.style.overflowY = el.scrollHeight > MAX_HEIGHT ? "auto" : "hidden";
  }, []);

  useEffect(() => {
    autoGrow();
  }, [question, autoGrow]);

  // Focus the dialog textarea when dialog opens
  useEffect(() => {
    if (dialogOpen && dialogTextareaRef.current) {
      // Small delay to let the dialog render
      const timer = setTimeout(() => dialogTextareaRef.current?.focus(), 50);
      return () => clearTimeout(timer);
    }
  }, [dialogOpen]);

  // Paste (screenshots via Ctrl/Cmd+V) and drag-drop feed the SAME serialized
  // staging queue as the picker — the caps, dedupe and encoding all apply.
  const handlePasteFiles = useCallback(
    (e: React.ClipboardEvent) => {
      const files = filesFromClipboard(e);
      if (!files.length || !canAttach || disabled || isLoading) return;
      e.preventDefault();
      void addFiles(files);
    },
    [canAttach, disabled, isLoading, addFiles],
  );
  const { isDragOver, dropHandlers } = useFileDrop(
    canAttach && !disabled && !isLoading,
    (files) => {
      void addFiles(files);
    },
  );

  const charCount = question.length;
  // The backend caps the question at MAX_QUESTION_CHARS and fans it out to every
  // member in every phase, so this is a real ceiling rather than a tuning knob.
  const questionTooLong = charCount > MAX_GROUP_QUESTION_CHARS;

  function handleSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    if (questionTooLong) {
      // Server-side this is a 400 with a bean-validation message the user never
      // sees, after the whole (potentially large) body has been uploaded.
      toast.error(
        t("groups.questionTooLong", "A question can be at most {{max}} characters", {
          max: MAX_GROUP_QUESTION_CHARS.toLocaleString(),
        }),
      );
      return;
    }
    if (question.trim() && !isLoading && !disabled) {
      const files = attachmentRefs();
      // Called with one argument when there is nothing to attach, rather than
      // with an explicit `undefined` — the question-only call is the overwhelming
      // case and its shape should not change just because the signature grew.
      if (files) onSubmit(question.trim(), files);
      else onSubmit(question.trim());
      setQuestion("");
      clearAttachments();
      setDialogOpen(false);
    }
  }


  return (
    <>
      <form
        onSubmit={handleSubmit}
        className="relative flex flex-wrap items-end gap-2 p-3 pb-5 border-t border-border bg-card/80 backdrop-blur-sm shrink-0"
        {...dropHandlers}
      >
        {isDragOver && <FileDropOverlay />}
        {attachments.length > 0 && (
          <ul className="flex w-full flex-wrap gap-1.5" data-testid="discussion-attachments">
            {attachments.map((a) => (
              <li
                key={a.id}
                className="flex items-center gap-1 rounded-md border border-border bg-secondary/40 px-2 py-0.5 text-[11px] text-foreground"
              >
                <Paperclip className="h-2.5 w-2.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="max-w-[12rem] truncate" title={a.fileName ?? undefined}>
                  {a.fileName}
                </span>
                {/* Without a size, the total-size cap can only be discovered by
                    hitting it. */}
                <span className="tabular-nums text-muted-foreground">{formatAttachmentBytes(a.sizeBytes)}</span>
                <button
                  type="button"
                  onClick={() => removeAttachment(a.id)}
                  aria-label={t("groups.removeAttachment", "Remove {{name}}", { name: a.fileName })}
                  className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <X className="h-2.5 w-2.5" />
                </button>
              </li>
            ))}
          </ul>
        )}
        {canAttach && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="hidden"
              onChange={(e) => {
                // Materialize the list here: clearing `value` below also clears
                // `files`, and `addFiles` awaits between reads.
                const picked = Array.from(e.target.files ?? []);
                void addFiles(picked);
                // Reset so re-picking the same file fires change again.
                e.target.value = "";
              }}
              data-testid="discussion-file-input"
            />
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="shrink-0"
              // Also blocked while reading: the staging queue makes concurrent
              // selections safe, but leaving the button live invites a second
              // pick whose result appears seconds later with no explanation.
              disabled={disabled || isLoading || isStaging}
              onClick={() => fileInputRef.current?.click()}
              aria-label={t("groups.attachFiles", "Attach files")}
              title={t("groups.attachFiles", "Attach files")}
              data-testid="discussion-attach-btn"
            >
              {isStaging ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Paperclip className="h-4 w-4" />
              )}
            </Button>
          </>
        )}
        <div className="relative flex-1 min-w-0">
          <textarea
            ref={textareaRef}
            autoFocus
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder={
              disabled && disabledMessage
                ? disabledMessage
                : mode === "continue"
                  ? t("groups.continuePlaceholder", "Continue this discussion with a follow-up…")
                  : t("groups.askQuestion", "Ask a question for the group to discuss…")
            }
            className="w-full resize-none rounded-lg border border-input bg-background px-3 py-2 pe-8 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            style={{ minHeight: MIN_HEIGHT, maxHeight: MAX_HEIGHT }}
            rows={1}
            disabled={disabled || isLoading}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            onPaste={handlePasteFiles}
            data-testid="discussion-input"
          />
          {/* Expand button */}
          <button
            type="button"
            onClick={() => setDialogOpen(true)}
            disabled={disabled || isLoading}
            aria-label={t("groups.expandInput", "Expand input")}
            aria-expanded={dialogOpen}
            className="absolute end-2 inset-y-0 my-auto h-fit rounded p-0.5 text-muted-foreground hover:text-foreground hover:bg-secondary/50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title={t("groups.expandInput", "Expand input")}
          >
            <Expand className="h-3.5 w-3.5" />
          </button>
        </div>
        <Button
          type="submit"
          disabled={!question.trim() || isLoading || disabled || questionTooLong}
          className="shrink-0"
          data-testid="start-discussion-btn"
        >
          {isLoading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : mode === "continue" ? (
            <RotateCw className="h-4 w-4" />
          ) : (
            <Send className="h-4 w-4" />
          )}
          <span className="hidden sm:inline ms-1">
            {mode === "continue"
              ? t("groups.continueButton", "Continue")
              : t("groups.startDiscussion", "Discuss")}
          </span>
        </Button>
        {question.length > 0 && (
          <p className="absolute -bottom-4 start-0 text-[10px] text-muted-foreground/60">
            ↵ {t("groups.enterToSend", "Enter to send")} · ⇧↵ {t("groups.shiftEnter", "new line")}
          </p>
        )}
      </form>

      {/* Expanded input dialog */}
      <AccessibleDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={t("groups.composeQuestion", "Compose Question")}
        maxWidth="max-w-2xl"
        testId="discussion-input-dialog"
      >
        <div className="p-5 space-y-3">
          <textarea
            ref={dialogTextareaRef}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder={
              mode === "continue"
                ? t("groups.continuePlaceholder", "Continue this discussion with a follow-up…")
                : t("groups.askQuestion", "Ask a question for the group to discuss…")
            }
            className="w-full resize-y rounded-lg border border-input bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow min-h-[200px]"
            rows={8}
            disabled={isLoading || disabled}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            onPaste={handlePasteFiles}
            data-testid="discussion-input-expanded"
          />
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              {t("groups.submitShortcut", "Ctrl+Enter to submit")}
            </p>
            {charCount > 0 && (
              <p
                className={cn(
                  "text-xs tabular-nums",
                  questionTooLong ? "font-medium text-destructive" : "text-muted-foreground",
                )}
                data-testid="discussion-char-count"
              >
                {questionTooLong
                  ? // `current`, not `count` — i18next reserves `count` for
                    // pluralization and its typings require a number.
                    t("groups.charactersOverLimit", "{{current}} / {{max}} characters", {
                      current: charCount.toLocaleString(),
                      max: MAX_GROUP_QUESTION_CHARS.toLocaleString(),
                    })
                  : `${charCount.toLocaleString()} ${t("groups.characters", "characters")}`}
              </p>
            )}
          </div>
          <div className="flex justify-end gap-2 pt-2 border-t border-border">
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              {t("common.cancel", "Cancel")}
            </Button>
            <Button
              onClick={() => handleSubmit()}
              disabled={!question.trim() || isLoading || disabled || questionTooLong}
            >
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin me-1" />
              ) : mode === "continue" ? (
                <RotateCw className="h-4 w-4 me-1" />
              ) : (
                <Send className="h-4 w-4 me-1" />
              )}
              {mode === "continue"
                ? t("groups.continueButton", "Continue")
                : t("groups.startDiscussion", "Discuss")}
            </Button>
          </div>
        </div>
      </AccessibleDialog>
    </>
  );
}
