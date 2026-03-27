import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useCreateAgent } from "@/hooks/use-agents";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

interface CreateAgentDialogProps {
  open: boolean;
  onClose: () => void;
}

export function CreateAgentDialog({ open, onClose }: CreateAgentDialogProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const createAgent = useCreateAgent();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      const result = await createAgent.mutateAsync({
        agent: { workflows: [], channels: [] },
        name,
        description,
      });
      toast.success(t("agents.createSuccess", "Agent created successfully"));
      setName("");
      setDescription("");
      onClose();
      // Navigate to the new agent detail page
      if (result.location) {
        const url = new URL(result.location, "http://dummy");
        const parts = url.pathname.split("/").filter(Boolean);
        const id = parts[parts.length - 1];
        if (id) navigate(`/manage/agents/${id}`);
      }
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  }

  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={t("agents.createTitle", "Create New Agent")}
      testId="create-agent-dialog"
    >
      <form onSubmit={handleSubmit} className="space-y-4 p-5">
        <div>
          <label
            htmlFor="agent-name"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("agents.name", "Name")}
          </label>
          <input
            id="agent-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("agents.namePlaceholder", "My Agent")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            autoFocus
          />
        </div>

        <div>
          <label
            htmlFor="agent-description"
            className="mb-1.5 block text-sm font-medium text-foreground"
          >
            {t("agents.description", "Description")}
          </label>
          <textarea
            id="agent-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t(
              "agents.descriptionPlaceholder",
              "Describe what this agent does..."
            )}
            rows={3}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
          />
        </div>

        {/* Error */}
        {createAgent.isError && (
          <p className="text-sm text-destructive" role="alert">
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
            disabled={createAgent.isPending}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {createAgent.isPending
              ? t("common.loading")
              : t("common.create")}
          </button>
        </div>
      </form>
    </AccessibleDialog>
  );
}
