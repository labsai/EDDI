import { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

export function ShortcutsDialog() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const handler = () => setOpen(true);
    window.addEventListener("boardroom:show-shortcuts", handler);
    return () => window.removeEventListener("boardroom:show-shortcuts", handler);
  }, []);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open]);

  if (!open) return null;

  const shortcuts = [
    {
      key: "N",
      label: t("boardroom.shortcuts.newBoard", "New Boardroom"),
      context: t("boardroom.shortcuts.fromDashboard", "From dashboard"),
    },
    {
      key: "?",
      label: t("boardroom.shortcuts.showHelp", "Show keyboard shortcuts"),
    },
    {
      key: "Esc",
      label: t("boardroom.shortcuts.closePanel", "Close panel / dialog"),
    },
  ];

  return (
    <>
      <div
        className="fixed inset-0 z-50 bg-black/40 backdrop-blur-sm"
        onClick={() => setOpen(false)}
        aria-hidden="true"
      />
      <div className="fixed inset-0 z-50 flex items-center justify-center pointer-events-none">
        <div
          role="dialog"
          aria-modal="true"
          aria-label={t("boardroom.shortcuts.title", "Keyboard Shortcuts")}
          className={cn(
            "pointer-events-auto w-full max-w-sm",
            "bg-card rounded-xl shadow-2xl border border-slate-200 dark:border-slate-700",
            "animate-[br-message-in_200ms_ease-out]"
          )}
        >
          <div className="flex items-center justify-between ps-5 pe-5 pt-4 pb-3 border-b border-slate-200 dark:border-slate-700">
            <h2 className="text-lg font-semibold">
              {t("boardroom.shortcuts.title", "Keyboard Shortcuts")}
            </h2>
            <button
              onClick={() => setOpen(false)}
              className="text-muted-foreground hover:text-foreground transition-colors p-1 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
              aria-label={t("boardroom.board.close", "Close")}
            >
              ✕
            </button>
          </div>
          <div className="ps-5 pe-5 pt-3 pb-5 space-y-3">
            {shortcuts.map((s) => (
              <div key={s.key} className="flex items-center justify-between">
                <div className="flex flex-col">
                  <span className="text-sm font-medium">{s.label}</span>
                  {s.context && (
                    <span className="text-xs text-muted-foreground">
                      {s.context}
                    </span>
                  )}
                </div>
                <kbd
                  className={cn(
                    "inline-flex items-center justify-center min-w-[2rem] h-7",
                    "ps-2 pe-2 rounded-md border border-slate-300 dark:border-slate-600",
                    "bg-slate-50 dark:bg-slate-800",
                    "text-xs font-mono font-semibold text-slate-600 dark:text-slate-300"
                  )}
                >
                  {s.key}
                </kbd>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}
