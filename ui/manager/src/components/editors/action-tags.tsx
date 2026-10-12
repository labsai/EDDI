import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, X } from "lucide-react";
import { isImeComposing } from "@/lib/ime";

/**
 * Splits typed text into actions: a comma separates, and blanks and repeats (of
 * the actions already present, or of each other) are dropped.
 */
function parseActions(raw: string, existing: readonly string[]): string[] {
  const out: string[] = [];
  for (const part of raw.split(",")) {
    const a = part.trim();
    if (a && !existing.includes(a) && !out.includes(a)) out.push(a);
  }
  return out;
}

/**
 * The action-tag input shared by the rules, LLM, API-call and property-setter
 * editors (each had its own copy, and none of them committed text left in the
 * box when the user clicked away — the edit was lost, and the dirty check never
 * saw it).
 *
 * Text is committed on Enter, on a comma, on blur and on the Add button. A
 * comma list is split into separate actions, which is what a user pasting
 * `greet, farewell` means; the backend matches each action string exactly.
 *
 * `suggestions` feeds a datalist (actions emitted elsewhere in the same config),
 * so a typo does not silently produce an action nothing listens for.
 */
export function ActionTags({
  actions,
  onChange,
  readOnly,
  placeholder,
  emptyLabel,
  suggestions,
  ariaLabel,
  testId,
}: {
  actions: string[];
  onChange: (a: string[]) => void;
  readOnly?: boolean;
  placeholder?: string;
  /** Shown when there are no actions. */
  emptyLabel?: string;
  suggestions?: readonly string[];
  ariaLabel?: string;
  testId?: string;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const listId = useId();

  const commit = (raw: string) => {
    const added = parseActions(raw, actions);
    setInput("");
    if (added.length > 0) onChange([...actions, ...added]);
  };

  const available = (suggestions ?? []).filter((s) => !actions.includes(s));

  return (
    <div className="space-y-1.5" data-testid={testId}>
      <div className="flex flex-wrap gap-1.5">
        {actions.map((a, i) => (
          <span
            key={`${a}-${i}`}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
          >
            {a}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(actions.filter((_, j) => j !== i))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                aria-label={t("actionTags.remove", "Remove {{action}}", { action: a })}
              >
                <X className="h-3 w-3" aria-hidden="true" />
              </button>
            )}
          </span>
        ))}
        {actions.length === 0 && (
          <span className="text-xs text-muted-foreground italic">
            {emptyLabel ?? t("actionTags.none", "No actions")}
          </span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1.5">
          <input
            type="text"
            value={input}
            list={available.length > 0 ? listId : undefined}
            aria-label={ariaLabel ?? t("actionTags.inputLabel", "New action")}
            onChange={(e) => {
              const v = e.target.value;
              // A comma (typed or pasted) ends an action.
              if (v.includes(",")) commit(v);
              else setInput(v);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !isImeComposing(e)) {
                e.preventDefault();
                commit(input);
              }
            }}
            onBlur={() => commit(input)}
            placeholder={placeholder}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          {available.length > 0 && (
            <datalist id={listId}>
              {available.map((s) => (
                <option key={s} value={s} />
              ))}
            </datalist>
          )}
          <button
            type="button"
            onClick={() => commit(input)}
            aria-label={t("actionTags.add", "Add action")}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
          >
            <Plus className="h-3 w-3" aria-hidden="true" />
          </button>
        </div>
      )}
    </div>
  );
}
