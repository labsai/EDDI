import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { X, Plus, Loader2 } from "lucide-react";
import { useCreateResource } from "@/hooks/use-resources";
import { cn } from "@/lib/utils";

interface CreateResourceDialogProps {
  open: boolean;
  onClose: () => void;
  typeSlug: string;
  typeName: string;
}

export function CreateResourceDialog({
  open,
  onClose,
  typeSlug,
  typeName,
}: CreateResourceDialogProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const createMutation = useCreateResource(typeSlug);

  if (!open) return null;

  function handleCreate() {
    createMutation.mutate(
      {},
      {
        onSuccess: (data) => {
          // Parse ID from Location header
          const parts = data.location.split("/");
          const lastPart = parts[parts.length - 1] ?? "";
          const id = lastPart.split("?")[0];
          onClose();
          if (id) {
            navigate(`/manage/resources/${typeSlug}/${id}`);
          }
        },
      }
    );
  }

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />

      {/* Dialog */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className={cn(
            "w-full max-w-md rounded-2xl border bg-card p-6 shadow-2xl",
            "animate-in fade-in-0 zoom-in-95"
          )}
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-semibold text-foreground">
              {t("resources.createTitle", {
                type: typeName,
                defaultValue: `Create ${typeName}`,
              })}
            </h2>
            <button
              onClick={onClose}
              className="rounded-lg p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Info */}
          <p className="mt-3 text-sm text-muted-foreground">
            {t("resources.createDescription", {
              type: typeName,
              defaultValue: `Create an empty ${typeName} configuration. You can edit it after creation.`,
            })}
          </p>

          {/* Actions */}
          <div className="mt-6 flex justify-end gap-3">
            <button
              onClick={onClose}
              className="rounded-lg border border-input px-4 py-2.5 text-sm font-medium text-foreground transition-colors hover:bg-secondary"
            >
              {t("common.cancel")}
            </button>
            <button
              onClick={handleCreate}
              disabled={createMutation.isPending}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98] disabled:opacity-50"
            >
              {createMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Plus className="h-4 w-4" />
              )}
              {t("common.create")}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
