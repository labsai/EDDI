import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { ValidationMessage } from "@/components/connections/validation-message";
import { splitTemplate } from "@/lib/secret-reference";
import type { ValidationCode } from "@/lib/connection-validation";

interface HeaderValueFieldProps {
  value: string;
  onChange: (value: string) => void;
  error?: ValidationCode;
  readOnly?: boolean;
  /** Id of the element naming this composite field, for `aria-labelledby`. */
  labelledBy?: string;
  /** Kept for callers that still label the raw input directly. */
  id?: string;
  testIdPrefix?: string;
}

/**
 * The STATIC header value — a template, not a secret.
 *
 * Presented as "what goes in front" plus "which stored secret", because that is
 * what an author is actually deciding, and because the alternative is a free
 * text field whose rules (`${vault:…}` only, and at least one of them) are
 * invisible until the save fails.
 *
 * ## The mode is state, not a derivation
 *
 * It used to be `raw || !splittable`, recomputed from the live value — which
 * meant the field changed shape *while being typed into*. From an empty guided
 * field, one keystroke in the prefix box produced `"B"`, which is not a
 * splittable template, so the guided grid unmounted and was replaced by the raw
 * input: focus and caret lost after every first character. The same flip fired
 * in reverse when a closing brace completed a reference, and clearing the
 * secret chip while a prefix remained stranded the user in raw mode with the
 * toggle hidden.
 *
 * A guided field is guided until somebody says otherwise. It keeps its own
 * prefix and reference and emits their concatenation; the value is only
 * re-split when it changes from *outside* (a version switch, a form reset),
 * which is the one case where the component's idea of the value is stale.
 */
export function HeaderValueField({
  value,
  onChange,
  error,
  readOnly,
  labelledBy,
  id,
  testIdPrefix = "header-value",
}: HeaderValueFieldProps) {
  const { t } = useTranslation();

  const [mode, setMode] = useState<"guided" | "raw">(() =>
    canBeGuided(value) ? "guided" : "raw",
  );
  const [parts, setParts] = useState(() => splitOrEmpty(value));

  /**
   * The last value this component emitted.
   *
   * Anything else arriving in `value` came from the outside, and only then is
   * re-splitting correct — re-splitting our own emission is what re-introduces
   * the flip.
   */
  const emitted = useRef(value);

  useEffect(() => {
    if (value === emitted.current) return;
    emitted.current = value;
    setParts(splitOrEmpty(value));
    setMode(canBeGuided(value) ? "guided" : "raw");
  }, [value]);

  const emit = (next: string) => {
    emitted.current = next;
    onChange(next);
  };

  const updateParts = (next: { prefix: string; reference: string }) => {
    setParts(next);
    emit(`${next.prefix}${next.reference}`);
  };

  /**
   * Whether the raw value could be shown as two fields.
   *
   * Gates the toggle: switching a template this cannot take apart into the
   * guided view would have to throw part of it away, and silently rewriting an
   * author's config is worse than leaving them in the editor that can express
   * it.
   */
  const guidedAvailable = canBeGuided(value);

  /**
   * Re-split on the way back into the guided view.
   *
   * `parts` only tracks the value while the guided fields own it: raw-mode
   * edits go out through `emit`, which marks them as ours, so the re-split
   * effect deliberately ignores them. Returning to guided without this line
   * therefore showed whatever the fields held *before* the raw edit, and the
   * next guided keystroke emitted the concatenation of that stale pair —
   * silently reverting the edit the user had just made in the raw box.
   */
  const toggleMode = () => {
    if (mode === "raw") {
      setParts(splitOrEmpty(value));
      setMode("guided");
      return;
    }
    setMode("raw");
  };

  return (
    <div
      className="space-y-2"
      role="group"
      aria-labelledby={labelledBy}
      data-testid={testIdPrefix}
    >
      {mode === "raw" ? (
        <Input
          id={id}
          className="font-mono text-xs"
          dir="ltr"
          value={value}
          onChange={(e) => emit(e.target.value)}
          readOnly={readOnly}
          placeholder="Bearer ${vault:jira-token}"
          aria-labelledby={labelledBy}
          aria-invalid={error !== undefined || undefined}
          data-testid={`${testIdPrefix}-raw`}
        />
      ) : (
        <div className="grid gap-2 sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)]">
          <div className="space-y-1">
            <Input
              className="h-7 font-mono text-xs"
              dir="ltr"
              value={parts.prefix}
              onChange={(e) => updateParts({ ...parts, prefix: e.target.value })}
              readOnly={readOnly}
              placeholder="Bearer "
              aria-label={t("connections.headerPrefix", "Prefix")}
              data-testid={`${testIdPrefix}-prefix`}
            />
            <p className="text-[10px] text-muted-foreground">
              {t("connections.headerPrefixHint", "Optional, e.g. “Bearer ”")}
            </p>
          </div>
          <div className="space-y-1">
            <SecretKeyPicker
              value={parts.reference}
              onChange={(reference) => updateParts({ ...parts, reference })}
              readOnly={readOnly}
              referenceOnly
              ariaLabel={t("connections.headerSecret", "Stored secret")}
              testId={`${testIdPrefix}-secret`}
            />
            <p className="text-[10px] text-muted-foreground">
              {t("connections.headerSecretHint", "The stored secret to send")}
            </p>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <code
          className="truncate rounded bg-muted/50 px-2 py-1 font-mono text-[11px] text-muted-foreground"
          dir="ltr"
          data-testid={`${testIdPrefix}-preview`}
        >
          {value || t("connections.headerEmptyPreview", "(nothing yet)")}
        </code>
        {!readOnly && (mode === "raw" ? guidedAvailable : true) && (
          <button
            type="button"
            onClick={toggleMode}
            className="text-[11px] text-primary hover:underline"
            data-testid={`${testIdPrefix}-toggle`}
          >
            {mode === "raw"
              ? t("connections.headerUseFields", "Use the guided fields")
              : t("connections.headerUseRaw", "Edit as a template")}
          </button>
        )}
      </div>

      <ValidationMessage code={error} testId={`${testIdPrefix}-error`} />
    </div>
  );
}

/** An empty field starts guided; anything splittable can be shown guided. */
function canBeGuided(value: string): boolean {
  return value.trim() === "" || splitTemplate(value) !== null;
}

function splitOrEmpty(value: string): { prefix: string; reference: string } {
  return splitTemplate(value) ?? { prefix: "", reference: "" };
}
