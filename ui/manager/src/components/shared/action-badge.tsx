import { useTranslation } from "react-i18next";
import { Plus, RefreshCw, Check, AlertTriangle } from "lucide-react";

/** Colored badge for diff actions — shared by import/export/sync UIs */
export function ActionBadge({ action }: { action: string }) {
  const { t } = useTranslation();

  switch (action) {
    case "CREATE":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-500">
          <Plus className="h-3 w-3" /> {t("sync.actionCreate", "New")}
        </span>
      );
    case "UPDATE":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-blue-500">
          <RefreshCw className="h-3 w-3" /> {t("sync.actionUpdate", "Update")}
        </span>
      );
    case "SKIP":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground">
          <Check className="h-3 w-3" /> {t("sync.actionSkip", "Up to date")}
        </span>
      );
    case "CONFLICT":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-destructive">
          <AlertTriangle className="h-3 w-3" /> {t("sync.actionConflict", "Conflict")}
        </span>
      );
    default:
      return <span className="text-xs text-muted-foreground">{action}</span>;
  }
}
