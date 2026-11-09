import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { getErrorMessage } from "@/lib/api-client";
import {
  useApproveSpendControlChange,
  usePendingSpendControlChanges,
  useRejectSpendControlChange,
} from "@/hooks/use-spend-control";
import type { SpendControlChange, SpendControlKind } from "@/lib/api/spend-control";

interface PendingSpendChangesProps {
  /** Which control's changes this page shows. */
  kind: SpendControlKind;
  /** Only an eddi-admin may read or decide them; the panel renders nothing otherwise. */
  isAdmin: boolean;
}

type Pending = { change: SpendControlChange; action: "approve" | "reject" };

/**
 * Changes an administrator's MCP client proposed that would loosen spending —
 * a lower or removed price, a looser unpriced policy, a higher or disabled
 * quota (docs/spend-control-changes.md). Nothing of them is applied until an
 * eddi-admin approves here; approving applies the proposed document with the
 * approver as actor, and only while the control is still what it was computed
 * against (otherwise the backend answers STALE and applies nothing).
 */
export function PendingSpendChanges({ kind, isAdmin }: PendingSpendChangesProps) {
  const { t } = useTranslation();
  const { data } = usePendingSpendControlChanges(isAdmin);
  const approve = useApproveSpendControlChange();
  const reject = useRejectSpendControlChange();
  const [pending, setPending] = useState<Pending | null>(null);

  const changes = (data ?? []).filter((c) => c.kind === kind);
  if (!isAdmin || changes.length === 0) return null;

  const decide = () => {
    if (!pending) return;
    const mutation = pending.action === "approve" ? approve : reject;
    mutation.mutate(
      { id: pending.change.id },
      {
        onSuccess: () => {
          toast.success(
            pending.action === "approve"
              ? t("spendControl.approved", "Change approved and applied")
              : t("spendControl.rejected", "Change rejected; nothing was applied"),
          );
          setPending(null);
        },
        onError: (error) => {
          toast.error(getErrorMessage(error));
          setPending(null);
        },
      },
    );
  };

  return (
    <section className="rounded-xl border border-warning/40 bg-warning/5 p-4" data-testid={`spend-changes-${kind}`}>
      <div className="flex items-center gap-2">
        <ShieldAlert className="h-4 w-4 text-warning" />
        <h2 className="text-sm font-semibold text-foreground">
          {t("spendControl.title", "Pending changes")} ({changes.length})
        </h2>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {t(
          "spendControl.description",
          "Proposed over MCP and not applied: each would loosen spending. Approving applies it under your name.",
        )}
      </p>
      <ul className="mt-3 space-y-3">
        {changes.map((change) => (
          <li key={change.id} className="rounded-lg border border-border bg-card p-3" data-testid={`spend-change-${change.id}`}>
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              {change.tenantId && <Badge variant="outline">{change.tenantId}</Badge>}
              <span>
                {t("spendControl.proposedBy", "Proposed by {{who}} on {{when}}", {
                  who: change.proposedBy,
                  when: new Date(change.proposedAtMillis).toLocaleString(),
                })}
              </span>
            </div>
            <p className="mt-2 text-sm text-foreground" data-testid={`spend-change-reason-${change.id}`}>
              {change.reason}
            </p>
            <div className="mt-2 text-xs">
              <div className="font-medium text-destructive">{t("spendControl.loosening", "Loosens spending")}</div>
              <ul className="mt-1 list-disc space-y-0.5 ps-5 text-destructive" data-testid={`spend-change-loosening-${change.id}`}>
                {change.loosening.map((line) => (
                  <li key={line} className="break-words">{line}</li>
                ))}
              </ul>
              {change.changes.length > change.loosening.length && (
                <>
                  <div className="mt-2 font-medium text-muted-foreground">{t("spendControl.allChanges", "All changes")}</div>
                  <ul className="mt-1 list-disc space-y-0.5 ps-5 text-muted-foreground">
                    {change.changes.map((line) => (
                      <li key={line} className="break-words">{line}</li>
                    ))}
                  </ul>
                </>
              )}
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="warning"
                onClick={() => setPending({ change, action: "approve" })}
                data-testid={`spend-change-approve-${change.id}`}
              >
                {t("spendControl.approve", "Approve")}
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setPending({ change, action: "reject" })}
                data-testid={`spend-change-reject-${change.id}`}
              >
                {t("spendControl.reject", "Reject")}
              </Button>
            </div>
          </li>
        ))}
      </ul>

      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => !open && setPending(null)}
        variant={pending?.action === "approve" ? "warning" : "destructive"}
        title={
          pending?.action === "approve"
            ? t("spendControl.approveTitle", "Approve this change?")
            : t("spendControl.rejectTitle", "Reject this change?")
        }
        description={
          pending?.action === "approve"
            ? t(
                "spendControl.approveDesc",
                "It is applied now with you as the actor and loosens spending: {{loosening}}",
                { loosening: pending.change.loosening.join("; ") },
              )
            : t("spendControl.rejectDesc", "Nothing is applied. The proposer can file it again.")
        }
        confirmLabel={pending?.action === "approve" ? t("spendControl.approve", "Approve") : t("spendControl.reject", "Reject")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={decide}
        isPending={approve.isPending || reject.isPending}
      />
    </section>
  );
}
