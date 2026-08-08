import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Send, Loader2, Expand, RotateCw, Paperclip, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { MAX_ATTACHMENT_BYTES } from "@/lib/api/attachments";
import {
  MAX_GROUP_ATTACHMENTS,
  MAX_GROUP_ATTACHMENTS_TOTAL_BYTES,
  type GroupAttachmentRef,
} from "@/lib/api/groups";

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

/** A picked file plus the base64 payload the group endpoint takes. */
interface PendingAttachment extends GroupAttachmentRef {
  id: string;
  sizeBytes: number;
}

/**
 * Read a File as bare base64 — no `data:` prefix, which is what the backend's
 * `AttachmentRef.data` expects. FileReader yields a data URI, so the header is
 * stripped here rather than in every caller.
 */
function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("read failed"));
    reader.onload = () => {
      const result = String(reader.result ?? "");
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.readAsDataURL(file);
  });
}

/** Min/max heights for auto-growing textarea */
const MIN_HEIGHT = 40;
const MAX_HEIGHT = 120;

export function DiscussionInput({ onSubmit, isLoading, disabled, mode = "new", disabledMessage }: DiscussionInputProps) {
  const { t } = useTranslation();
  const [question, setQuestion] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const dialogTextareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  // Attachments are shared with member agents only when a discussion STARTS, so
  // a continuation that carries them is a 400. Hide the affordance rather than
  // letting the user assemble a request the backend will refuse.
  const canAttach = mode === "new";

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

  // Switching to a continuation drops anything staged. The chips render
  // independently of `canAttach`, so leaving them would show files that
  // `handleSubmit` then silently declines to send — the backend rejects
  // attachments on a continuation outright.
  useEffect(() => {
    if (!canAttach) setAttachments([]);
  }, [canAttach]);

  // Focus the dialog textarea when dialog opens
  useEffect(() => {
    if (dialogOpen && dialogTextareaRef.current) {
      // Small delay to let the dialog render
      const timer = setTimeout(() => dialogTextareaRef.current?.focus(), 50);
      return () => clearTimeout(timer);
    }
  }, [dialogOpen]);

  const addFiles = useCallback(
    async (files: FileList | null) => {
      if (!files?.length) return;
      const room = MAX_GROUP_ATTACHMENTS - attachments.length;
      if (room <= 0) {
        toast.error(
          t("groups.attachmentLimit", "At most {{max}} attachments per discussion", {
            max: MAX_GROUP_ATTACHMENTS,
          }),
        );
        return;
      }
      const picked = Array.from(files).slice(0, room);
      if (picked.length < files.length) {
        toast.warning(
          t("groups.attachmentLimit", "At most {{max}} attachments per discussion", {
            max: MAX_GROUP_ATTACHMENTS,
          }),
        );
      }
      const accepted: PendingAttachment[] = [];
      // The per-file cap alone is not enough: this endpoint takes the bytes
      // inline, so fifty legal files still become one enormous base64 body.
      let stagedBytes = attachments.reduce((sum, a) => sum + a.sizeBytes, 0);
      for (const file of picked) {
        if (file.size > MAX_ATTACHMENT_BYTES) {
          toast.error(
            t("groups.attachmentTooLarge", "{{name}} is too large to attach", { name: file.name }),
          );
          continue;
        }
        if (stagedBytes + file.size > MAX_GROUP_ATTACHMENTS_TOTAL_BYTES) {
          // `break`, not `continue`: once the budget is gone, every remaining
          // file would produce the same toast.
          toast.error(
            t("groups.attachmentTotalTooLarge", "Attachments exceed the total size limit"),
          );
          break;
        }
        try {
          accepted.push({
            id: `${file.name}-${file.size}-${file.lastModified}`,
            fileName: file.name,
            // Browsers leave this empty for types they cannot identify; the
            // backend treats an absent mimeType as "work it out from the bytes".
            mimeType: file.type || null,
            data: await readAsBase64(file),
            sizeBytes: file.size,
          });
          stagedBytes += file.size;
        } catch {
          toast.error(t("groups.attachmentReadFailed", "Could not read {{name}}", { name: file.name }));
        }
      }
      if (accepted.length) {
        setAttachments((prev) => [
          ...prev,
          ...accepted.filter((a) => !prev.some((p) => p.id === a.id)),
        ]);
      }
    },
    // `attachments`, not `attachments.length`: the running-total check reads the
    // entries themselves now, and a remove-then-add pair leaves the length equal
    // while the sizes differ.
    [attachments, t],
  );

  function handleSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    if (question.trim() && !isLoading && !disabled) {
      const files =
        canAttach && attachments.length
          ? attachments.map(({ fileName, mimeType, data }) => ({ fileName, mimeType, data }))
          : null;
      // Called with one argument when there is nothing to attach, rather than
      // with an explicit `undefined` — the question-only call is the overwhelming
      // case and its shape should not change just because the signature grew.
      if (files) onSubmit(question.trim(), files);
      else onSubmit(question.trim());
      setQuestion("");
      setAttachments([]);
      setDialogOpen(false);
    }
  }

  const charCount = question.length;

  return (
    <>
      <form onSubmit={handleSubmit} className="relative flex flex-wrap items-end gap-2 p-3 pb-5 border-t border-border bg-card/80 backdrop-blur-sm shrink-0">
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
                <button
                  type="button"
                  onClick={() => setAttachments((prev) => prev.filter((p) => p.id !== a.id))}
                  aria-label={t("groups.removeAttachment", "Remove {{name}}", { name: a.fileName })}
                  className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
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
                void addFiles(e.target.files);
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
              disabled={disabled || isLoading}
              onClick={() => fileInputRef.current?.click()}
              aria-label={t("groups.attachFiles", "Attach files")}
              title={t("groups.attachFiles", "Attach files")}
              data-testid="discussion-attach-btn"
            >
              <Paperclip className="h-4 w-4" />
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
          disabled={!question.trim() || isLoading || disabled}
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
            data-testid="discussion-input-expanded"
          />
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              {t("groups.submitShortcut", "Ctrl+Enter to submit")}
            </p>
            {charCount > 0 && (
              <p className="text-xs text-muted-foreground tabular-nums">
                {charCount.toLocaleString()} {t("groups.characters", "characters")}
              </p>
            )}
          </div>
          <div className="flex justify-end gap-2 pt-2 border-t border-border">
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              {t("common.cancel", "Cancel")}
            </Button>
            <Button
              onClick={() => handleSubmit()}
              disabled={!question.trim() || isLoading || disabled}
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
