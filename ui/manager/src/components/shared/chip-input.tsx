import { useTranslation } from "react-i18next";
import { Plus, X, type LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { splitChipEntries } from "@/lib/chip-values";

/**
 * A list of short values built one at a time — scopes, allowed origins,
 * trigger keywords.
 *
 * ## The pending text belongs to the caller
 *
 * `pending` / `onPendingChange` are props, not internal state, and that is the
 * whole point of this component. When the half-typed value lived inside the
 * widget, a form could not see it: the user typed `offline_access`, pressed
 * Save without pressing Enter, and the parent serialised a list that did not
 * contain it — with a success toast on top. Blur-committing alone does not fix
 * that (blur and click are separate events, so the parent may still read the
 * pre-commit state), and an imperative ref makes the parent's own state a lie
 * in between. Lifting the text means the parent always holds everything that is
 * about to be saved, and `commitPending` gives it one way to fold that text in.
 *
 * Committing also happens on blur and on Enter, so in practice the flush is a
 * backstop rather than the normal path.
 */

interface ChipInputProps {
  /** The committed values. */
  values: string[];
  onChange: (values: string[]) => void;
  /** The uncommitted text in the box. Owned by the caller — see above. */
  pending: string;
  onPendingChange: (pending: string) => void;
  /** Rejects a value before it is committed; the caller renders the code. */
  validate?: (candidate: string) => string | null;
  /** Reported when `validate` refuses the pending text. */
  onInvalid?: (code: string | null) => void;
  /**
   * Splits one entry into several. Providers document OAuth scopes
   * space-delimited and that is how people paste them, so re-typing them one at
   * a time is transcription nobody should have to do.
   */
  splitOn?: RegExp;
  placeholder?: string;
  /** Names the input for assistive tech; there is no visible label inside. */
  inputLabel: string;
  itemIcon?: LucideIcon;
  readOnly?: boolean;
  invalid?: boolean;
  /** Wired to the input's `aria-describedby` so the error is announced with it. */
  errorId?: string;
  testId: string;
  /** Renders values left-to-right even in an RTL locale (URLs, scopes). */
  ltr?: boolean;
}

export function ChipInput({
  values,
  onChange,
  pending,
  onPendingChange,
  validate,
  onInvalid,
  splitOn,
  placeholder,
  inputLabel,
  itemIcon: ItemIcon,
  readOnly,
  invalid,
  errorId,
  testId,
  ltr,
}: ChipInputProps) {
  const { t } = useTranslation();

  const add = () => {
    const parts = splitChipEntries(pending, splitOn);
    if (parts.length === 0) return;

    if (validate) {
      for (const part of parts) {
        const problem = validate(part);
        if (problem) {
          onInvalid?.(problem);
          return;
        }
      }
    }
    const fresh = parts.filter((part) => !values.includes(part));
    if (fresh.length > 0) onChange([...values, ...fresh]);
    onPendingChange("");
    onInvalid?.(null);
  };

  const remove = (value: string) =>
    onChange(values.filter((existing) => existing !== value));

  return (
    <div className="space-y-2" data-testid={testId}>
      {values.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {values.map((value) => (
            <Badge
              key={value}
              variant="secondary"
              className="gap-1 font-mono text-[11px]"
              data-testid={`${testId}-item-${value}`}
            >
              {ItemIcon && <ItemIcon className="h-3 w-3" aria-hidden="true" />}
              <span dir={ltr ? "ltr" : undefined}>{value}</span>
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => remove(value)}
                  className="rounded hover:text-destructive"
                  aria-label={t("common.removeItem", {
                    item: value,
                    defaultValue: "Remove {{item}}",
                  })}
                  data-testid={`${testId}-remove-${value}`}
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </Badge>
          ))}
        </div>
      )}

      {!readOnly && (
        <div className="flex gap-2">
          <Input
            className="h-8 font-mono text-xs"
            dir={ltr ? "ltr" : undefined}
            value={pending}
            onChange={(e) => {
              onPendingChange(e.target.value);
              onInvalid?.(null);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                add();
              } else if (e.key === "Backspace" && pending === "" && values.length > 0) {
                // The standard token-field affordance: backspace at the start of
                // an empty box removes the last chip.
                e.preventDefault();
                remove(values[values.length - 1]!);
              }
            }}
            // Committing on the way out means the common case — type, then click
            // Save — never silently drops the text, even before the caller's
            // flush runs.
            onBlur={add}
            placeholder={placeholder}
            aria-label={inputLabel}
            aria-invalid={invalid || undefined}
            aria-describedby={errorId}
            data-testid={`${testId}-input`}
          />
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={add}
            data-testid={`${testId}-add`}
          >
            <Plus className="h-3.5 w-3.5" aria-hidden="true" />
            {t("common.add", "Add")}
          </Button>
        </div>
      )}
    </div>
  );
}
