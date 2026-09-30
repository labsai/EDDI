import { useId } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

export interface CompatibleVersionCheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  /**
   * The compatibility generation of the agent version the save replaces:
   *  - a number — it is on a chain, nothing more to say;
   *  - `null` — a version stored before version following, which has no chain
   *    to continue: say that conversations already running on it stay;
   *  - `undefined` — not known here (e.g. several agents at once): say the same
   *    thing conditionally.
   * Only shown while the box is ticked — it qualifies what ticking it does.
   */
  previousGeneration?: number | null;
  /** Tone of the surrounding panel; `amber` sits inside the cascade dialog. */
  tone?: "default" | "amber";
  className?: string;
  "data-testid"?: string;
}

/**
 * "This new agent version is compatible with the one it replaces" — the choice
 * every save that creates an agent version from an existing one can offer.
 *
 * Unticked by default, always: a compatible version lets running conversations
 * switch to it on their next turn, which is only safe for changes that cannot
 * break a conversation in progress. The caller owns the state (and resets it),
 * and passes it to `updateAgent(..., { compatible })`.
 */
export function CompatibleVersionCheckbox({
  checked,
  onChange,
  disabled,
  previousGeneration,
  tone = "default",
  className,
  "data-testid": testId = "compatible-version-checkbox",
}: CompatibleVersionCheckboxProps) {
  const { t } = useTranslation();
  const hintId = useId();
  const amber = tone === "amber";

  const note =
    !checked || typeof previousGeneration === "number"
      ? null
      : previousGeneration === null
        ? t(
            "agentVersioning.compatibleLegacyNote",
            "The current version predates version following, so conversations already running on it stay on it. The next compatible save links up.",
          )
        : t(
            "agentVersioning.compatibleUnknownNote",
            "Conversations on a version that predates version following stay on it.",
          );

  return (
    <label
      className={cn(
        "flex cursor-pointer items-start gap-2 rounded-lg border p-3 text-sm",
        amber
          ? "border-amber-200 bg-white/60 dark:border-amber-800 dark:bg-amber-950/20"
          : "border-border/60 bg-muted/30",
        disabled && "cursor-not-allowed opacity-60",
        className,
      )}
    >
      <input
        type="checkbox"
        className={cn("mt-0.5 h-4 w-4 shrink-0", amber ? "accent-amber-600" : "accent-primary")}
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        aria-describedby={hintId}
        data-testid={testId}
      />
      <span className={amber ? "text-amber-800 dark:text-amber-300" : "text-muted-foreground"}>
        <span className={cn("block font-medium", amber ? "text-amber-900 dark:text-amber-200" : "text-foreground")}>
          {t(
            "agentVersioning.compatibleLabel",
            "Compatible with the previous version — running conversations may switch to it",
          )}
        </span>
        <span id={hintId} className="mt-0.5 block text-xs">
          {t(
            "agentVersioning.compatibleHint",
            "Only for changes that cannot break a conversation in progress, e.g. prompt wording or a different model. Leave unticked if properties, actions or workflow steps were renamed or removed.",
          )}
          {note && (
            <span className="mt-1 block" data-testid={`${testId}-note`}>
              {note}
            </span>
          )}
        </span>
      </span>
    </label>
  );
}
