import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Check, Pencil, X } from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";

export interface EditableTitleProps {
  /** The stored name; empty when none was ever given. */
  name?: string;
  /** Shown when there is no name. */
  fallback: string;
  description?: string;
  /** Whether the viewer may rename (EDIT level). Without it the title is plain. */
  canEdit: boolean;
  /** Persist the new name and description. Rejecting keeps the form open. */
  onSave: (value: { name: string; description: string }) => Promise<unknown>;
  /** Heading level classes, so each page keeps its own size. */
  titleClassName?: string;
  "data-testid"?: string;
}

/**
 * A page title that can be renamed in place — name and description.
 *
 * Agents, workflows and resources were named once, in their create dialog, and
 * could never be renamed afterwards.
 */
export function EditableTitle({
  name,
  fallback,
  description,
  canEdit,
  onSave,
  titleClassName,
  "data-testid": testId = "editable-title",
}: EditableTitleProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [draftName, setDraftName] = useState("");
  const [draftDescription, setDraftDescription] = useState("");
  const [saving, setSaving] = useState(false);
  const nameId = useId();
  const descriptionId = useId();

  function open() {
    setDraftName(name ?? "");
    setDraftDescription(description ?? "");
    setEditing(true);
  }

  async function save() {
    const trimmed = draftName.trim();
    if (!trimmed) return;
    setSaving(true);
    try {
      await onSave({ name: trimmed, description: draftDescription.trim() });
      setEditing(false);
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <form
        className="space-y-2"
        data-testid={`${testId}-form`}
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <div>
          <label htmlFor={nameId} className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("common.name", "Name")}
          </label>
          <input
            id={nameId}
            value={draftName}
            onChange={(e) => setDraftName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setEditing(false);
            }}
            autoFocus
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-lg font-semibold text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid={`${testId}-name`}
          />
        </div>
        <div>
          <label htmlFor={descriptionId} className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("common.description", "Description")}
          </label>
          <input
            id={descriptionId}
            value={draftDescription}
            onChange={(e) => setDraftDescription(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setEditing(false);
            }}
            className="w-full rounded-lg border border-input bg-background px-3 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid={`${testId}-description`}
          />
        </div>
        <div className="flex gap-2">
          <button
            type="submit"
            disabled={saving || !draftName.trim()}
            className="inline-flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-50"
            data-testid={`${testId}-save`}
          >
            <Check className="h-3.5 w-3.5" aria-hidden="true" />
            {t("common.save", "Save")}
          </button>
          <button
            type="button"
            onClick={() => setEditing(false)}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-lg border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary disabled:opacity-50"
            data-testid={`${testId}-cancel`}
          >
            <X className="h-3.5 w-3.5" aria-hidden="true" />
            {t("common.cancel", "Cancel")}
          </button>
        </div>
      </form>
    );
  }

  return (
    <div data-testid={testId}>
      <div className="flex items-center gap-2">
        <h1 className={cn("text-3xl font-bold text-foreground", titleClassName)}>{name || fallback}</h1>
        {canEdit && (
          <button
            type="button"
            onClick={open}
            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground"
            aria-label={t("common.rename", "Rename")}
            title={t("common.rename", "Rename")}
            data-testid={`${testId}-edit`}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
      </div>
      {description && (
        <p className="mt-0.5 text-sm text-muted-foreground" data-testid={`${testId}-description-text`}>
          {description}
        </p>
      )}
    </div>
  );
}
