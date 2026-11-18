import { useTranslation } from "react-i18next";
import { AlertDialog } from "@eddi/app-kit/components/ui/alert-dialog";
import { ConflictDetailsPanel } from "@eddi/app-kit/components/shared/conflict-details-panel";
import type { ConflictDetails } from "@eddi/app-kit/lib/conflict-details";

/**
 * Confirmation for deleting an agent. It names the agent. When the server refuses
 * the delete with a 409 the dialog stays open and shows the server's own message
 * and whatever lists it carries; the only thing it offers is "Reload" (read the
 * current version again) — a retry of the same request would get the same answer.
 */
export function DeleteAgentDialog({
  open,
  onOpenChange,
  agentName,
  conflict,
  isPending,
  onConfirm,
  onReload,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  agentName: string;
  /** What the server said when it refused (409), if it did. */
  conflict: ConflictDetails | null;
  isPending: boolean;
  onConfirm: () => void;
  /** Re-read the agent's current version and close the dialog. */
  onReload: () => void;
}) {
  const { t } = useTranslation();
  return (
    <AlertDialog
      open={open}
      onOpenChange={onOpenChange}
      title={t("agents.confirmDeleteNamed", "Delete {{name}}?", { name: agentName })}
      description={t(
        "agents.confirmDeleteDescription",
        "This action cannot be undone. The agent and all its data will be permanently removed.",
      )}
      confirmLabel={t("common.delete")}
      cancelLabel={t("common.cancel")}
      onConfirm={onConfirm}
      isPending={isPending}
    >
      {conflict && (
        <div className="space-y-3" data-testid="delete-agent-conflict">
          <ConflictDetailsPanel details={conflict} testId="delete-agent-conflict-details" />
          <button
            type="button"
            onClick={onReload}
            disabled={isPending}
            className="rounded-lg border border-input px-3 py-1.5 text-sm font-medium text-foreground hover:bg-secondary disabled:opacity-50"
            data-testid="delete-agent-reload"
          >
            {t("agents.deleteReload", "Reload the current version")}
          </button>
        </div>
      )}
    </AlertDialog>
  );
}
