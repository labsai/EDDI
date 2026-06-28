import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Send, Loader2, Expand } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

interface DiscussionInputProps {
  onSubmit: (question: string) => void;
  isLoading?: boolean;
  disabled?: boolean;
}

/** Min/max heights for auto-growing textarea */
const MIN_HEIGHT = 40;
const MAX_HEIGHT = 120;

export function DiscussionInput({ onSubmit, isLoading, disabled }: DiscussionInputProps) {
  const { t } = useTranslation();
  const [question, setQuestion] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const dialogTextareaRef = useRef<HTMLTextAreaElement>(null);

  // Auto-grow inline textarea
  const autoGrow = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(Math.max(el.scrollHeight, MIN_HEIGHT), MAX_HEIGHT)}px`;
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

  function handleSubmit(e?: React.FormEvent) {
    e?.preventDefault();
    if (question.trim() && !isLoading) {
      onSubmit(question.trim());
      setQuestion("");
      setDialogOpen(false);
    }
  }

  const charCount = question.length;

  return (
    <>
      <form onSubmit={handleSubmit} className="relative flex items-end gap-2 p-3 pb-5 border-t border-border bg-card/80 backdrop-blur-sm shrink-0">
        <div className="relative flex-1 min-w-0">
          <textarea
            ref={textareaRef}
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            placeholder={t("groups.askQuestion", "Ask a question for the group to discuss…")}
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
            className="absolute end-2 inset-y-0 my-auto h-fit rounded p-0.5 text-muted-foreground hover:text-foreground hover:bg-secondary/50 transition-colors"
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
          ) : (
            <Send className="h-4 w-4" />
          )}
          <span className="hidden sm:inline ms-1">
            {t("groups.startDiscussion", "Discuss")}
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
            placeholder={t("groups.askQuestion", "Ask a question for the group to discuss…")}
            className="w-full resize-y rounded-lg border border-input bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow min-h-[200px]"
            rows={8}
            disabled={isLoading}
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
              disabled={!question.trim() || isLoading}
            >
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin me-1" />
              ) : (
                <Send className="h-4 w-4 me-1" />
              )}
              {t("groups.startDiscussion", "Discuss")}
            </Button>
          </div>
        </div>
      </AccessibleDialog>
    </>
  );
}
