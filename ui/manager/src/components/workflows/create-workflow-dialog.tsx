import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useCreateWorkflow } from "@/hooks/use-workflows";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

interface CreateWorkflowDialogProps {
  open: boolean;
  onClose: () => void;
}

export function CreateWorkflowDialog({ open, onClose }: CreateWorkflowDialogProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const createMutation = useCreateWorkflow();

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      const result = await createMutation.mutateAsync({
        config: { workflowSteps: [] },
        name,
        description,
      });
      toast.success(t("packages.createSuccess", "Workflow created successfully"));
      setName("");
      setDescription("");
      onClose();
      // Navigate to the new workflow detail page
      if (result.location) {
        const url = new URL(result.location, "http://dummy");
        const parts = url.pathname.split("/").filter(Boolean);
        const id = parts[parts.length - 1];
        if (id) navigate(`/manage/workflowview/${id}`);
      }
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  }

  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={t("packages.createTitle", "Create New Workflow")}
      testId="create-workflow-dialog"
    >
      <form onSubmit={handleSubmit} className="space-y-4 p-5">
        <div>
          <label
            htmlFor="workflow-name"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("packages.name", "Name")}
          </label>
          <input
            id="workflow-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("packages.namePlaceholder", "My Workflow")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            autoFocus
          />
        </div>

        <div>
          <label
            htmlFor="workflow-description"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("packages.description", "Description")}
          </label>
          <textarea
            id="workflow-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t(
              "packages.descriptionPlaceholder",
              "Describe what this workflow does..."
            )}
            rows={3}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
          >
            {t("common.cancel")}
          </button>
          <button
            type="submit"
            disabled={createMutation.isPending}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {createMutation.isPending
              ? t("common.loading")
              : t("common.create")}
          </button>
        </div>
      </form>
    </AccessibleDialog>
  );
}
