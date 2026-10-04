import { useTranslation } from "react-i18next";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { useNavigationGuardStore } from "@/hooks/use-unsaved-changes-guard";

/**
 * The Stay / Discard prompt for an in-app navigation that `useUnsavedChangesGuard`
 * held back. Mounted once, inside the router, so a page only has to call the hook.
 */
export function NavigationGuardDialog() {
  const { t } = useTranslation();
  const pending = useNavigationGuardStore((s) => s.pending);

  return (
    <UnsavedChangesDialog
      open={pending !== null}
      onConfirm={() => pending?.proceed()}
      onCancel={() => pending?.reset()}
      cancelLabel={t("editor.stay", "Stay")}
      confirmLabel={t("editor.discardAndLeave", "Discard & Leave")}
    />
  );
}
