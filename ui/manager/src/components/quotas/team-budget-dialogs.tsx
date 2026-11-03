import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { getErrorMessage } from "@/lib/api-client";
import { formatUsd } from "@/lib/utils";
import { useSetCurrentMonthCap, useTransferTeamBudget } from "@/hooks/use-team-budget";
import { TEAM_BUDGET_MAX_REASON_LENGTH, newIdempotencyKey, parseUsd, type TeamBudgetSnapshot } from "@/lib/api/team-budget";

const FIELD = "w-full rounded-lg border border-input bg-background px-3 py-2 text-sm";

function teamLabel(s: TeamBudgetSnapshot): string {
  return s.groupName || s.groupId;
}

/** Admin: set this month's cap. The server refuses a cap below what is spent. */
export function AdjustBudgetDialog({ snapshot, onClose }: { snapshot: TeamBudgetSnapshot | null; onClose: () => void }) {
  const { t } = useTranslation();
  const mutation = useSetCurrentMonthCap();
  const [cap, setCap] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const capValue = parseUsd(cap);
  const valid = capValue !== null && capValue >= (snapshot?.spentUsd ?? 0) && reason.trim() !== "";

  function close() {
    setCap("");
    setReason("");
    setError(null);
    onClose();
  }

  function submit() {
    if (!snapshot || capValue === null) return;
    mutation.mutate(
      { groupId: snapshot.groupId, capUsd: capValue, reason: reason.trim() },
      {
        onSuccess: () => {
          toast.success(t("teamBudget.adjusted", "Cap updated"));
          close();
        },
        onError: (e) => setError(getErrorMessage(e)),
      },
    );
  }

  return (
    <AccessibleDialog open={snapshot !== null} onClose={close} title={t("teamBudget.adjustTitle", "Adjust this month's cap")} testId="team-budget-adjust-dialog">
      {snapshot && (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (valid) submit();
          }}
        >
          <p className="text-sm font-medium text-foreground">
            {teamLabel(snapshot)} · {snapshot.month}
          </p>
          <p className="text-xs text-muted-foreground">
            {t("teamBudget.adjustHelp", "The cap cannot go below what the team has already spent. Next month starts at the configured base again.")}
          </p>
          <p className="text-xs text-muted-foreground">
            {t("teamBudget.cap", "Cap")}: {formatUsd(snapshot.capUsd ?? 0)} · {t("teamBudget.spent", "Spent")}: {formatUsd(snapshot.spentUsd)}
          </p>
          <label className="block text-sm">
            <span className="mb-1 block font-medium">{t("teamBudget.capLabel", "New cap for this month (USD)")}</span>
            <input className={FIELD} inputMode="decimal" value={cap} onChange={(e) => setCap(e.target.value)} data-testid="team-budget-cap-input" autoFocus />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block font-medium">{t("teamBudget.reasonLabel", "Reason")}</span>
            <input
              className={FIELD}
              maxLength={TEAM_BUDGET_MAX_REASON_LENGTH}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              data-testid="team-budget-reason-input"
            />
            <span className="mt-1 block text-xs text-muted-foreground">{t("teamBudget.reasonHelp", "Required. Recorded in the audit ledger.")}</span>
          </label>
          {error && (
            <p role="alert" className="text-sm text-destructive" data-testid="team-budget-error">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={close}>
              {t("common.cancel", "Cancel")}
            </Button>
            <Button type="submit" disabled={!valid || mutation.isPending} data-testid="team-budget-adjust-save">
              {t("common.save", "Save")}
            </Button>
          </div>
        </form>
      )}
    </AccessibleDialog>
  );
}

/** Admin: move part of this month's cap from one team to another (idempotent per opening). */
export function TransferBudgetDialog({
  open,
  teams,
  initialFromId,
  onClose,
}: {
  open: boolean;
  teams: TeamBudgetSnapshot[];
  initialFromId?: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const mutation = useTransferTeamBudget();
  const [from, setFrom] = useState(initialFromId ?? "");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  // One key per opening: a retry after a timeout cannot move the money twice.
  const [key, setKey] = useState(() => newIdempotencyKey());
  const source = useMemo(() => teams.find((s) => s.groupId === from), [teams, from]);
  const amountValue = parseUsd(amount);
  const valid =
    from !== "" && to !== "" && from !== to && amountValue !== null && amountValue <= (source?.remainingUsd ?? 0) && reason.trim() !== "";

  function close() {
    setFrom(initialFromId ?? "");
    setTo("");
    setAmount("");
    setReason("");
    setError(null);
    setKey(newIdempotencyKey());
    onClose();
  }

  function submit() {
    if (amountValue === null) return;
    mutation.mutate(
      { request: { fromGroupId: from, toGroupId: to, amountUsd: amountValue, reason: reason.trim() }, idempotencyKey: key },
      {
        onSuccess: () => {
          toast.success(t("teamBudget.transferred", "Budget transferred"));
          close();
        },
        onError: (e) => setError(getErrorMessage(e)),
      },
    );
  }

  const options = (exclude: string) => (
    <>
      <option value="">{t("teamBudget.pickTeam", "Choose a team")}</option>
      {teams
        .filter((s) => s.groupId !== exclude)
        .map((s) => (
          <option key={s.groupId} value={s.groupId}>
            {teamLabel(s)}
          </option>
        ))}
    </>
  );

  return (
    <AccessibleDialog open={open} onClose={close} title={t("teamBudget.transferTitle", "Transfer budget between teams")} testId="team-budget-transfer-dialog">
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) submit();
        }}
      >
        <p className="text-xs text-muted-foreground">
          {t("teamBudget.transferHelp", "Moves part of this month's cap. The source team keeps at least what it has spent.")}
        </p>
        <label className="block text-sm">
          <span className="mb-1 block font-medium">{t("teamBudget.from", "From")}</span>
          <select className={FIELD} value={from} onChange={(e) => setFrom(e.target.value)} data-testid="team-budget-from">
            {options(to)}
          </select>
        </label>
        <label className="block text-sm">
          <span className="mb-1 block font-medium">{t("teamBudget.to", "To")}</span>
          <select className={FIELD} value={to} onChange={(e) => setTo(e.target.value)} data-testid="team-budget-to">
            {options(from)}
          </select>
        </label>
        <label className="block text-sm">
          <span className="mb-1 block font-medium">{t("teamBudget.amount", "Amount (USD)")}</span>
          <input className={FIELD} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} data-testid="team-budget-amount" />
          {source && (
            <span className="mt-1 block text-xs text-muted-foreground">
              {t("teamBudget.remaining", "Remaining")}: {formatUsd(source.remainingUsd ?? 0)}
            </span>
          )}
        </label>
        <label className="block text-sm">
          <span className="mb-1 block font-medium">{t("teamBudget.reasonLabel", "Reason")}</span>
          <input
            className={FIELD}
            maxLength={TEAM_BUDGET_MAX_REASON_LENGTH}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            data-testid="team-budget-transfer-reason"
          />
          <span className="mt-1 block text-xs text-muted-foreground">{t("teamBudget.reasonHelp", "Required. Recorded in the audit ledger.")}</span>
        </label>
        {error && (
          <p role="alert" className="text-sm text-destructive" data-testid="team-budget-error">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={close}>
            {t("common.cancel", "Cancel")}
          </Button>
          <Button type="submit" disabled={!valid || mutation.isPending} data-testid="team-budget-transfer-save">
            {t("teamBudget.transfer", "Transfer budget")}
          </Button>
        </div>
      </form>
    </AccessibleDialog>
  );
}
