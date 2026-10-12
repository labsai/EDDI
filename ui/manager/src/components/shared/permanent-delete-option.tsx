import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface PermanentDeleteOptionProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** What a permanent delete removes beyond the soft delete — shown under the label. */
  consequence?: string;
  className?: string;
  "data-testid"?: string;
}

/**
 * The one place a delete dialog offers a HARD delete.
 *
 * Every EDDI store deletes softly unless `permanent=true` is sent: the document
 * is marked deleted and stays in the database until it is purged (Orphans →
 * include deleted). The Manager used to pick `permanent=true` silently for
 * groups and connections — the grey "Delete Group Only" button was the
 * unrecoverable one, and it also took the group's workspace with it, while the
 * red "Delete Group + All Agents" was the recoverable one. A hard delete is now
 * always an explicit, unchecked-by-default choice with its consequence spelled
 * out next to it.
 */
export function PermanentDeleteOption({
  checked,
  onChange,
  consequence,
  className,
  "data-testid": testId = "permanent-delete-checkbox",
}: PermanentDeleteOptionProps) {
  const { t } = useTranslation();
  return (
    <label
      className={cn(
        "flex items-start gap-2 rounded-lg border p-2 text-xs",
        checked ? "border-destructive/40 bg-destructive/5" : "border-border",
        className,
      )}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5"
        data-testid={testId}
      />
      <span className="space-y-0.5">
        <span className={cn("block font-medium", checked ? "text-destructive" : "text-foreground")}>
          {t("common.deletePermanently", "Delete permanently")}
        </span>
        <span className="block text-muted-foreground">
          {consequence ??
            t(
              "common.deletePermanentlyHint",
              "Cannot be undone. Without this, the item is only marked deleted and stays recoverable until it is purged.",
            )}
        </span>
      </span>
    </label>
  );
}
