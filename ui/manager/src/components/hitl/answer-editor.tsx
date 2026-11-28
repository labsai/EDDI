import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Pencil } from "lucide-react";
import { cn } from "@eddi/app-kit/lib/utils";
import type { EditableAnswer, ReasonCodeOption } from "@eddi/app-kit/lib/api/hitl";
import { codePointLength } from "@/lib/hitl-edit";

/**
 * "Edit & approve" of a RULE pause: the agent's answer in a textarea, a
 * character counter against the agent's cap, and the original one click away.
 * The edited text replaces the answer verbatim — it is data, never a template.
 */
export function AnswerEditor({
  editable,
  editing,
  value,
  onToggle,
  onChange,
}: {
  editable: EditableAnswer;
  editing: boolean;
  value: string;
  onToggle: () => void;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  const [showOriginal, setShowOriginal] = useState(false);
  const length = codePointLength(value);
  const over = length > editable.maxChars;
  return (
    <div className="mb-3 space-y-2" data-testid="answer-editor">
      <button
        type="button"
        onClick={onToggle}
        aria-pressed={editing}
        className="inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
        data-testid="edit-answer-toggle"
      >
        <Pencil className="h-3 w-3" aria-hidden="true" />
        {editing
          ? t("hitlReview.discardEdit", "Discard my edits")
          : t("hitlReview.editAnswer", "Edit the answer before approving")}
      </button>
      {editing && (
        <div className="space-y-1">
          <textarea
            value={value}
            onChange={(e) => onChange(e.target.value)}
            aria-label={t("hitlReview.editedAnswerLabel", "Final answer")}
            className={cn(
              "w-full resize-y rounded-lg border bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring",
              editable.format === "json" && "font-mono text-xs",
              over ? "border-destructive" : "border-border",
            )}
            rows={6}
            data-testid="edited-answer"
          />
          <div className="flex items-center justify-between text-[11px] text-muted-foreground">
            <button
              type="button"
              onClick={() => setShowOriginal((v) => !v)}
              aria-pressed={showOriginal}
              className="hover:text-foreground"
              data-testid="show-original"
            >
              {showOriginal
                ? t("hitlReview.hideOriginal", "Hide the original")
                : t("hitlReview.showOriginal", "Show the original")}
            </button>
            <span className={cn(over && "text-destructive")} data-testid="edited-answer-count">
              {length} / {editable.maxChars}
            </span>
          </div>
          {showOriginal && (
            <pre
              className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded bg-muted/60 p-2 text-[11px] text-foreground"
              data-testid="original-answer"
            >
              {editable.draft}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

/** The reason-code select: optional, sent with a rejection or an edit. */
export function ReasonCodeSelect({
  options,
  value,
  onChange,
}: {
  options: ReasonCodeOption[];
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <label className="mb-3 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      {t("hitlReview.reasonLabel", "Reason (for a rejection or an edit)")}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-md border border-border bg-background px-2 py-1 text-xs text-foreground"
        data-testid="reason-code"
      >
        <option value="">{t("hitlReview.noReason", "No reason")}</option>
        {options.map((option) => (
          <option key={option.code} value={option.code}>
            {option.code} — {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}
