import { useState, useEffect, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useCreateResource } from "@/hooks/use-resources";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

/** Backend pattern for snippet names: lowercase letters, digits, underscores only */
const SNIPPET_NAME_PATTERN = /^[a-z0-9_]+$/;

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
  const isSnippet = typeSlug === "snippets";

  const defaultName = useMemo(() => {
    if (isSnippet) {
      // Snippet names must match [a-z0-9_]+ — generate a compliant default
      const base = workflowName
        ? workflowName.toLowerCase().replace(/[^a-z0-9_]/g, "_").replace(/_+/g, "_").replace(/^_|_$/g, "")
        : "new_snippet";
      return base || "new_snippet";
    }
    return workflowName
      ? `${workflowName} — ${typeName}`
      : `${t("common.new", "New")} ${typeName}`;
  }, [isSnippet, workflowName, typeName, t]);

  const [name, setName] = useState(defaultName);
  const [description, setDescription] = useState("");

  // Client-side validation for snippet names
  const snippetNameError = useMemo(() => {
    if (!isSnippet) return "";
    const trimmed = name.trim();
    if (!trimmed) return t("resources.snippetNameRequired", "Snippet name is required.");
    if (!SNIPPET_NAME_PATTERN.test(trimmed)) {
      return t("resources.snippetNameInvalid", "Only lowercase letters (a-z), digits (0-9), and underscores (_) are allowed.");
    }
    return "";
  }, [isSnippet, name, t]);

  // Reset form fields when the dialog opens (handles stale default name)
  useEffect(() => {
    if (open) {
      setName(defaultName);
      setDescription("");
    }
  }, [open, defaultName]);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (snippetNameError) return;
      try {
        // For snippets, embed the name directly in the POST body so the
        // backend receives a valid PromptSnippet (name is required by
        // the [a-z0-9_]+ validation on the store).
        const body = isSnippet
          ? { name: name.trim(), description: description.trim() || undefined }
          : {};

        const result = await createMutation.mutateAsync({
          body,
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
    [createMutation, name, description, typeName, typeSlug, navigate, onClose, t, isSnippet, snippetNameError]
  );

  // AccessibleDialog, not a hand-rolled overlay: this one had no dialog role,
  // no accessible name, no focus trap and an unlabelled close button, so a
  // screen reader never learned a dialog had opened and Tab walked out of it
  // into the page underneath.
  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={t("resources.createTitle", {
        type: typeName,
        defaultValue: `Create ${typeName}`,
      })}
      testId="create-resource-dialog"
    >
      <div className="p-5">
        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-4">
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
              onChange={(e) => {
                if (isSnippet) {
                  // Auto-sanitize: lowercase + replace invalid chars with underscores
                  setName(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, "_"));
                } else {
                  setName(e.target.value);
                }
              }}
              placeholder={
                isSnippet
                  ? t("resources.snippetNamePlaceholder", "e.g. cautious_mode")
                  : t("resources.namePlaceholder", "My {{type}}", { type: typeName })
              }
              className={`w-full rounded-lg border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring ${
                snippetNameError ? "border-destructive focus:ring-destructive" : "border-input"
              }`}
              autoFocus
              data-testid="resource-name-input"
            />
            {snippetNameError && (
              <p className="mt-1 text-xs text-destructive">{snippetNameError}</p>
            )}
            {isSnippet && !snippetNameError && name.trim() && (
              <p className="mt-1 text-[11px] text-muted-foreground font-mono">
                {t("snippetEditor.usageHint", "Usage:")}{" "}
                <code className="rounded bg-primary/10 px-1 py-0.5 text-primary">
                  {"{{"}<wbr />snippets.{name.trim()}{"}}"}
                </code>
              </p>
            )}
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
                "Describe this {{type}} configuration...",
                { type: typeName.toLowerCase() }
              )}
              rows={3}
              className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
              data-testid="resource-description-input"
            />
          </div>

          {/* Error */}
          {createMutation.isError && (
            <p className="text-sm text-destructive" data-testid="create-resource-error">
              {getErrorMessage(createMutation.error)}
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
              disabled={createMutation.isPending || !!snippetNameError}
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
    </AccessibleDialog>
  );
}
