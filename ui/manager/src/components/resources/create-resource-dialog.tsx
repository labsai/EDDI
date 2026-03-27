import { useState, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { X } from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useCreateResource } from "@/hooks/use-resources";

interface CreateResourceDialogProps {
  open: boolean;
  onClose: () => void;
  typeSlug: string;
  typeName: string;
  /** Optional workflow name to use as default name prefix */
  workflowName?: string;
}

export function CreateResourceDialog({
  open,
  onClose,
  typeSlug,
  typeName,
  workflowName,
}: CreateResourceDialogProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const createMutation = useCreateResource(typeSlug);

  const defaultName = workflowName
    ? `${workflowName} — ${typeName}`
    : `${t("common.new", "New")} ${typeName}`;

  const [name, setName] = useState(defaultName);
  const [description, setDescription] = useState("");

  // Reset form fields when the dialog opens (handles stale default name)
  useEffect(() => {
    if (open) {
      setName(defaultName);
      setDescription("");
    }
  }, [open, defaultName]);

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      try {
        const result = await createMutation.mutateAsync({
          body: {},
          name: name.trim() || undefined,
          description: description.trim() || undefined,
        });
        toast.success(
          t("resources.createSuccess", {
            type: typeName,
            defaultValue: `${typeName} created successfully`,
          })
        );
        onClose();
        // Navigate to the new resource detail page
        if (result.location) {
          const parts = result.location.split("/");
          const lastPart = parts[parts.length - 1] ?? "";
          const id = lastPart.split("?")[0];
          if (id) {
            navigate(`/manage/resources/${typeSlug}/${id}`);
          }
        }
      } catch (err) {
        toast.error(getErrorMessage(err));
      }
    },
    [createMutation, name, description, typeName, typeSlug, navigate, onClose, t]
  );

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
        data-testid="create-resource-backdrop"
      />

      {/* Dialog */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-md rounded-xl border bg-card p-6 shadow-2xl"
          onClick={(e) => e.stopPropagation()}
          data-testid="create-resource-dialog"
        >
          {/* Header */}
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-foreground">
              {t("resources.createTitle", {
                type: typeName,
                defaultValue: `Create ${typeName}`,
              })}
            </h2>
            <button
              onClick={onClose}
              className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
              data-testid="create-resource-close"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="mt-6 space-y-4">
            <div>
              <label
                htmlFor="resource-name"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("common.name", "Name")}
              </label>
              <input
                id="resource-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={`My ${typeName}`}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                autoFocus
                data-testid="resource-name-input"
              />
            </div>

            <div>
              <label
                htmlFor="resource-description"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("common.description", "Description")}
              </label>
              <textarea
                id="resource-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t(
                  "resources.descriptionPlaceholder",
                  `Describe this ${typeName.toLowerCase()} configuration...`
                )}
                rows={3}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
                data-testid="resource-description-input"
              />
            </div>

            {/* Error */}
            {createMutation.isError && (
              <p className="text-sm text-destructive" data-testid="create-resource-error">
                {t("common.error")}
              </p>
            )}

            {/* Actions */}
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={onClose}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-secondary transition-colors"
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                disabled={createMutation.isPending}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="create-resource-submit"
              >
                {createMutation.isPending
                  ? t("common.loading")
                  : t("common.create")}
              </button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
