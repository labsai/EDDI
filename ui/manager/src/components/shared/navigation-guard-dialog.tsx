import { useContext, useEffect, useState } from "react";
import { UNSAFE_DataRouterContext, useBlocker } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import {
  anyGuardDirty,
  consumeNavigationBypass,
  saveAllDirtyGuards,
  useNavigationGuardStore,
} from "@/hooks/use-unsaved-changes-guard";

/**
 * The app's ONE router blocker. A router supports a single blocker and consults
 * only the most recently registered, so it lives here, once, and decides from the
 * store whether any `useUnsavedChangesGuard` is dirty.
 */
function NavigationBlocker() {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (consumeNavigationBypass()) return false;
    return anyGuardDirty() && currentLocation.pathname !== nextLocation.pathname;
  });

  useEffect(() => {
    if (blocker.state === "blocked") {
      const request = { proceed: () => blocker.proceed(), reset: () => blocker.reset() };
      useNavigationGuardStore.setState({ pending: request });
      return () => {
        if (useNavigationGuardStore.getState().pending === request) {
          useNavigationGuardStore.setState({ pending: null });
        }
      };
    }
  }, [blocker]);

  return null;
}

/**
 * The Stay / Discard prompt for an in-app navigation that a dirty
 * `useUnsavedChangesGuard` held back. Mounted once, inside the router, so a page
 * only has to call the hook. `useBlocker` exists only under a data router; under a
 * bare `MemoryRouter` (component tests) the blocker is simply absent.
 */
export function NavigationGuardDialog() {
  const { t } = useTranslation();
  const pending = useNavigationGuardStore((s) => s.pending);
  const hasDataRouter = useContext(UNSAFE_DataRouterContext) != null;
  const [saving, setSaving] = useState(false);
  // Read when the prompt opens: whether every dirty page can save from here.
  const save = pending ? saveAllDirtyGuards() : null;

  async function saveAndLeave() {
    if (!save || !pending) return;
    setSaving(true);
    try {
      if (await save()) pending.proceed();
    } catch {
      // The save reports its own failure; stay so nothing is lost.
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      {hasDataRouter && <NavigationBlocker />}
      <UnsavedChangesDialog
        open={pending !== null}
        onConfirm={() => pending?.proceed()}
        onCancel={() => pending?.reset()}
        cancelLabel={t("editor.stay", "Stay")}
        confirmLabel={t("editor.discardAndLeave", "Discard & Leave")}
        onSave={save ? () => void saveAndLeave() : undefined}
        isSaving={saving}
      />
    </>
  );
}
