import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { Plus, Wand2 } from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

interface CreateOrWizardDialogProps {
  open: boolean;
  onClose: () => void;
  type: "agent" | "group";
  wizardPath: string;
  /** Called when user picks "Quick Create" — parent should close this dialog and open the create form */
  onQuickCreate: () => void;
}

/**
 * Unified creation dialog that offers two paths:
 * 1. Quick Create — parent opens its own create form
 * 2. Guided Wizard — navigates to the wizard page
 */
export function CreateOrWizardDialog({
  open,
  onClose,
  type,
  wizardPath,
  onQuickCreate,
}: CreateOrWizardDialogProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const handleWizard = () => {
    onClose();
    navigate(wizardPath);
  };

  const typeLabel =
    type === "agent"
      ? t("common.agent", "Agent")
      : t("common.group", "Group");

  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={t("createOrWizard.title", { type: typeLabel, defaultValue: `New ${typeLabel}` })}
      testId="create-or-wizard-dialog"
      maxWidth="max-w-2xl"
    >
      <div className="p-6 sm:p-8">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 sm:gap-6">
          {/* Quick Create card */}
          <button
            onClick={onQuickCreate}
            className="group flex flex-col items-start gap-4 rounded-xl border-2 border-border bg-card p-6 text-start transition-all hover:-translate-y-1 hover:border-primary/60 hover:shadow-xl"
            data-testid="choice-quick-create"
          >
            <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary/20">
              <Plus className="h-6 w-6" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-foreground">
                {t("createOrWizard.quickCreate", "Quick Create")}
              </h3>
              <p className="mt-1 text-sm text-muted-foreground leading-relaxed">
                {t(
                  "createOrWizard.quickCreateDesc",
                  "Set a name and description, configure details later."
                )}
              </p>
            </div>
          </button>

          {/* Guided Wizard card */}
          <button
            onClick={handleWizard}
            className="group flex flex-col items-start gap-4 rounded-xl border-2 border-border bg-card p-6 text-start transition-all hover:-translate-y-1 hover:border-amber-500/60 hover:shadow-xl"
            data-testid="choice-wizard"
          >
            <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-amber-500/10 text-amber-500 transition-colors group-hover:bg-amber-500/20">
              <Wand2 className="h-6 w-6" />
            </div>
            <div>
              <h3 className="text-base font-semibold text-foreground">
                {t("createOrWizard.guidedSetup", "Guided Setup")}
              </h3>
              <p className="mt-1 text-sm text-muted-foreground leading-relaxed">
                {t(
                  "createOrWizard.guidedSetupDesc",
                  "Step-by-step wizard with LLM provider, prompts, and deployment."
                )}
              </p>
            </div>
          </button>
        </div>
      </div>
    </AccessibleDialog>
  );
}
