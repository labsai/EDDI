import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  ShieldAlert,
  Download,
  Trash2,
  AlertTriangle,
  FileSearch,
  CheckCircle2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { useDeleteUserData, useExportUserData } from "@/hooks/use-gdpr";
import type { GdprDeletionResult } from "@/lib/api/gdpr";

export function GdprPage() {
  const { t } = useTranslation();
  const [userId, setUserId] = useState("");
  const [showConfirm, setShowConfirm] = useState(false);
  const [result, setResult] = useState<GdprDeletionResult | null>(null);

  const deleteMutation = useDeleteUserData();
  const exportMutation = useExportUserData();

  const handleExport = useCallback(() => {
    if (!userId.trim()) return;
    exportMutation.mutate(userId.trim(), {
      onSuccess: (data) => {
        // Download as JSON file
        const blob = new Blob([JSON.stringify(data, null, 2)], {
          type: "application/json",
        });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `user-data-${userId.trim()}.json`;
        a.click();
        URL.revokeObjectURL(url);
        toast.success(
          t("gdpr.exportSuccess", "User data exported successfully"),
        );
      },
      onError: (error) => {
        toast.error(error.message);
      },
    });
  }, [userId, exportMutation, t]);

  const handleDelete = useCallback(() => {
    if (!userId.trim()) return;
    deleteMutation.mutate(userId.trim(), {
      onSuccess: (data) => {
        setResult(data);
        setShowConfirm(false);
        toast.success(
          t("gdpr.deleteSuccess", "User data deleted successfully"),
        );
      },
      onError: (error) => {
        setShowConfirm(false);
        toast.error(error.message);
      },
    });
  }, [userId, deleteMutation, t]);

  return (
    <div className="space-y-8" data-testid="gdpr-page">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-red-500/10">
            <ShieldAlert className="h-5 w-5 text-red-500" />
          </div>
          {t("gdpr.title", "Privacy & Compliance")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t(
            "gdpr.subtitle",
            "GDPR-compliant user data management — erasure (Art. 17) and portability (Art. 15/20)",
          )}
        </p>
      </div>

      {/* Info Banner */}
      <div className="rounded-xl border border-amber-500/20 bg-amber-500/5 p-4 space-y-2">
        <div className="flex items-start gap-2">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
          <div className="space-y-1 text-xs text-foreground">
            <p className="font-semibold">
              {t("gdpr.legalNotice", "Data Protection Notice")}
            </p>
            <p className="text-muted-foreground">
              {t(
                "gdpr.legalDesc",
                "Data export (Art. 15/20) provides a JSON file containing all stored data for a user. Deletion (Art. 17) permanently removes all memories and conversation data, pseudonymizes audit entries, and cannot be undone.",
              )}
            </p>
          </div>
        </div>
      </div>

      {/* Actions */}
      <div className="rounded-xl border border-border bg-card p-6 space-y-5">
        <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
          <FileSearch className="h-4 w-4 text-primary" />
          {t("gdpr.userLookup", "User Lookup")}
        </h2>

        <div className="flex items-end gap-3">
          <div className="flex-1">
            <label
              htmlFor="gdpr-user-id"
              className="mb-1 block text-xs font-medium text-muted-foreground"
            >
              {t("gdpr.userId", "User ID")}
            </label>
            <input
              id="gdpr-user-id"
              type="text"
              value={userId}
              onChange={(e) => {
                setUserId(e.target.value);
                setResult(null);
              }}
              placeholder={t("gdpr.userIdPlaceholder", "Enter user ID...")}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="gdpr-user-id"
            />
          </div>

          <Button
            variant="secondary"
            onClick={handleExport}
            disabled={!userId.trim() || exportMutation.isPending}
            className="gap-2"
            data-testid="gdpr-export-btn"
          >
            <Download className="h-4 w-4" />
            {exportMutation.isPending
              ? t("gdpr.exporting", "Exporting...")
              : t("gdpr.export", "Export Data")}
          </Button>

          <Button
            variant="destructive"
            onClick={() => setShowConfirm(true)}
            disabled={!userId.trim() || deleteMutation.isPending}
            className="gap-2"
            data-testid="gdpr-delete-btn"
          >
            <Trash2 className="h-4 w-4" />
            {deleteMutation.isPending
              ? t("gdpr.deleting", "Deleting...")
              : t("gdpr.delete", "Delete All Data")}
          </Button>
        </div>
      </div>

      {/* Results */}
      {result && (
        <div
          className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 p-6 space-y-4"
          data-testid="gdpr-results"
        >
          <h3 className="flex items-center gap-2 text-sm font-semibold text-emerald-600 dark:text-emerald-400">
            <CheckCircle2 className="h-4 w-4" />
            {t("gdpr.resultsTitle", "Erasure Complete")}
          </h3>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <ResultCard
              label={t("gdpr.memoriesDeleted", "Memories Deleted")}
              value={result.memoriesDeleted}
            />
            <ResultCard
              label={t("gdpr.conversationsDeleted", "Conversations Deleted")}
              value={result.conversationsDeleted}
            />
            <ResultCard
              label={t("gdpr.auditPseudonymized", "Audit Pseudonymized")}
              value={result.auditEntriesPseudonymized}
            />
            <ResultCard
              label={t("gdpr.logsPseudonymized", "Logs Pseudonymized")}
              value={result.logEntriesPseudonymized}
            />
          </div>
        </div>
      )}

      {/* Confirm Dialog */}
      <AlertDialog
        open={showConfirm}
        onOpenChange={setShowConfirm}
        title={t("gdpr.confirmTitle", "Confirm Data Deletion")}
        description={t(
          "gdpr.confirmDesc",
          'This will permanently delete ALL data for user "{{userId}}". This action cannot be undone.',
          { userId: userId.trim() },
        )}
        confirmLabel={t("gdpr.confirmDelete", "Yes, Delete All Data")}
        variant="destructive"
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}

function ResultCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-border bg-background p-3 text-center">
      <p className="text-2xl font-bold text-foreground">{value}</p>
      <p className="mt-0.5 text-[10px] text-muted-foreground">{label}</p>
    </div>
  );
}
