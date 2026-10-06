import { useEffect, useState, useSyncExternalStore } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import {
  activeGuard,
  getPendingLeave,
  installPopstateGuard,
  noteHistoryIndex,
  proceedWith,
  setPendingLeave,
  subscribe,
} from "@/lib/unsaved-changes-registry";

// Before the router mounts, so this popstate listener runs ahead of its own.
installPopstateGuard();

/**
 * The in-app half of `useUnsavedChangesGuard`: while any page or sheet holds
 * unsaved edits, a click on an internal link and a back/forward step are held
 * and the user is asked — Keep editing, Discard changes, or Save & leave when
 * the page can save from here. Mounted once, inside the router.
 *
 * Links are caught in the capture phase on `document`, ahead of React's own
 * handlers, so it covers every `<Link>` — sidebar, breadcrumbs, cards — without
 * each one having to opt in.
 */
export function UnsavedChangesNavigationGuard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const pending = useSyncExternalStore(subscribe, getPendingLeave);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    noteHistoryIndex();
  }, [location]);

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const anchor = (e.target as Element | null)?.closest?.("a[href]");
      if (!(anchor instanceof HTMLAnchorElement)) return;
      if (anchor.target && anchor.target !== "_self") return;
      if (anchor.hasAttribute("download")) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.origin !== window.location.origin) return;
      // Staying on the same page (a hash link, the active nav item) leaves no edit behind.
      if (url.pathname === window.location.pathname && url.search === window.location.search) return;
      if (!activeGuard()) return;
      e.preventDefault();
      e.stopPropagation();
      const to = url.pathname + url.search + url.hash;
      setPendingLeave({ kind: "push", proceed: () => navigate(to) });
    }
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, [navigate]);

  const guard = pending ? activeGuard() : null;

  function leave() {
    const held = pending;
    setPendingLeave(null);
    if (held) proceedWith(held);
  }

  async function saveAndLeave() {
    if (!guard?.onSave) return;
    setSaving(true);
    try {
      if (await guard.onSave()) leave();
    } catch {
      // The save reports its own failure; stay so nothing is lost.
    } finally {
      setSaving(false);
    }
  }

  return (
    <UnsavedChangesDialog
      open={pending !== null}
      title={t("editor.leaveTitle", "Leave with unsaved changes?")}
      message={t(
        "editor.leaveMessage",
        "You have changes on this page that are not saved yet. Save them, discard them, or keep editing.",
      )}
      cancelLabel={t("editor.keepEditing", "Keep editing")}
      confirmLabel={t("editor.discardChanges", "Discard changes")}
      onCancel={() => setPendingLeave(null)}
      onConfirm={leave}
      onSave={guard?.onSave ? saveAndLeave : undefined}
      isSaving={saving}
    />
  );
}
