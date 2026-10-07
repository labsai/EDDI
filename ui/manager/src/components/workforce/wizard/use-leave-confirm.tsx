import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";

/**
 * Unsaved-work protection for a wizard.
 *
 * Reload and tab close go through the shared `useUnsavedChangesGuard`. The
 * wizard's own exits (Back, Cancel, "Back to groups") ask first through a
 * dialog, and once the user has confirmed — or the wizard finished — the guard
 * is released before navigating, so a router-level block never asks a second
 * time for the same departure.
 *
 * `leaveNow` skips the question: use it after a successful create.
 */
export function useLeaveConfirm(isDirty: boolean): {
  requestLeave: (to: string) => void;
  leaveNow: (to: string) => void;
  dialog: ReactNode;
} {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [pendingTo, setPendingTo] = useState<string | null>(null);
  const [leavingTo, setLeavingTo] = useState<string | null>(null);

  useUnsavedChangesGuard(isDirty && leavingTo === null);

  // Navigate from an effect so the released guard is already in force.
  useEffect(() => {
    if (leavingTo !== null) navigate(leavingTo);
  }, [leavingTo, navigate]);

  const requestLeave = useCallback(
    (to: string) => {
      if (isDirty) setPendingTo(to);
      else navigate(to);
    },
    [isDirty, navigate],
  );

  const leaveNow = useCallback((to: string) => setLeavingTo(to), []);

  const dialog = (
    <UnsavedChangesDialog
      open={pendingTo !== null}
      onCancel={() => setPendingTo(null)}
      onConfirm={() => {
        setLeavingTo(pendingTo);
        setPendingTo(null);
      }}
      title={t("wizardLeave.title", "Leave without creating?")}
      message={t(
        "wizardLeave.message",
        "Your team setup, including any prompts and API keys you entered, has not been created yet and will be lost.",
      )}
    />
  );

  return { requestLeave, leaveNow, dialog };
}
