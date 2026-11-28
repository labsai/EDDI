import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle, Plus, Undo2, X } from "lucide-react";
import { Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { getErrorMessage, isApiError } from "@eddi/app-kit/lib/api-client";
import { useEmailSenders, useUpdateEmailSenders } from "@/hooks/use-channels";
import { toastChannelWarnings } from "@/lib/email-channel-messages";
import type { EmailSenderChange } from "@/lib/api/channels";

type ListName = "allow" | "block";

/** `user@domain`, `*@domain` or `*@*.domain` — the backend checks it again. */
const SENDER_PATTERN = /^(\*@(\*\.)?[^\s@*]+\.[^\s@*]+|[^\s@*]+@[^\s@*]+\.[^\s@*]+)$/;

function normalise(entry: string): string {
  return entry.trim().toLowerCase();
}

interface Pending {
  add: Record<ListName, string[]>;
  remove: Record<ListName, string[]>;
}

const NOTHING_PENDING: Pending = { add: { allow: [], block: [] }, remove: { allow: [], block: [] } };

/**
 * The sender allowlist and blocklist of a saved email integration, edited one
 * entry at a time (email plan v3 §5.1): every applied change is a new version
 * of the integration, written by the backend with the version this view read,
 * and recorded in the audit ledger with its reason.
 *
 * Disabled while the page holds unsaved changes: applying would move the
 * integration to a new version under a draft that still carries the old lists.
 */
export function EmailSenderListsEditor({
  integrationId,
  disabled = false,
  onChanged,
}: {
  integrationId: string;
  /** The page has unsaved changes; applying is not offered. */
  disabled?: boolean;
  /** Called with the new version after a change was stored. */
  onChanged: (version: number) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const { data, isLoading, isError, error, refetch } = useEmailSenders(integrationId);
  const mutation = useUpdateEmailSenders();
  const [pending, setPending] = useState<Pending>(NOTHING_PENDING);
  const [drafts, setDrafts] = useState<Record<ListName, string>>({ allow: "", block: "" });
  const [reason, setReason] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);

  if (isError && !data) {
    // The inline sender fields are not shown beside this editor, so a failed load
    // must offer a way back rather than a spinner that never ends.
    return (
      <div className="space-y-2" role="alert" data-testid="email-senders-error">
        <p className="text-xs text-destructive">{getErrorMessage(error)}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()} data-testid="email-senders-retry">
          {t("common.retry", "Retry")}
        </Button>
      </div>
    );
  }
  if (isLoading || !data) {
    return <p className="text-xs text-muted-foreground">{t("emailSenders.loading", "Loading the sender lists…")}</p>;
  }

  const current: Record<ListName, string[]> = { allow: data.allow, block: data.block };
  const hasPending = (["allow", "block"] as const).some((l) => pending.add[l].length > 0 || pending.remove[l].length > 0);
  const remainingAllow = current.allow.filter((e) => !pending.remove.allow.includes(e)).length + pending.add.allow.length;
  const emptiesAllowlist = current.allow.length > 0 && remainingAllow === 0;

  const addEntry = (list: ListName) => {
    const entry = normalise(drafts[list]);
    if (!entry) return;
    if (!SENDER_PATTERN.test(entry)) {
      toast.error(t("emailSenders.invalidEntry", "Use user@domain, *@domain or *@*.domain."));
      return;
    }
    const other: ListName = list === "allow" ? "block" : "allow";
    if (current[other].includes(entry) && !pending.remove[other].includes(entry)) {
      toast.error(t("emailSenders.onBothLists", "That sender is on the other list. Remove it there first."));
      return;
    }
    setPending((p) => {
      if (p.remove[list].includes(entry)) {
        return { ...p, remove: { ...p.remove, [list]: p.remove[list].filter((e) => e !== entry) } };
      }
      if (current[list].includes(entry) || p.add[list].includes(entry)) return p;
      return { ...p, add: { ...p.add, [list]: [...p.add[list], entry] } };
    });
    setDrafts((d) => ({ ...d, [list]: "" }));
  };

  const toggleRemove = (list: ListName, entry: string) => {
    setPending((p) => {
      if (p.add[list].includes(entry)) {
        return { ...p, add: { ...p.add, [list]: p.add[list].filter((e) => e !== entry) } };
      }
      const removing = p.remove[list].includes(entry);
      return {
        ...p,
        remove: { ...p.remove, [list]: removing ? p.remove[list].filter((e) => e !== entry) : [...p.remove[list], entry] },
      };
    });
  };

  const reset = () => {
    setPending(NOTHING_PENDING);
    setReason("");
    setConfirmOpen(false);
  };

  const apply = async () => {
    const change: EmailSenderChange = {
      expectedVersion: data.version,
      add: pending.add,
      remove: pending.remove,
      reason: reason.trim() || undefined,
      confirmOpen: emptiesAllowlist ? confirmOpen : undefined,
    };
    try {
      const result = await mutation.mutateAsync({ id: integrationId, change });
      reset();
      if (result.changed) {
        toast.success(t("emailSenders.saved", { version: result.version, defaultValue: "Sender lists saved as version {{version}}." }));
        toastChannelWarnings(result.warnings ?? [], t);
        onChanged(result.version);
      } else {
        toast.info(t("emailSenders.nothingChanged", "Nothing changed: those entries were already in that state."));
      }
    } catch (err) {
      if (isApiError(err) && err.status === 409) {
        toast.error(t("emailSenders.conflict", "Someone changed this integration meanwhile. The lists were reloaded; check them and apply again."));
        await refetch();
        return;
      }
      toast.error(getErrorMessage(err));
    }
  };

  const renderList = (list: ListName) => {
    const label = list === "allow" ? t("emailChannel.senderAllowlist", "Allowed senders") : t("emailChannel.senderBlocklist", "Blocked senders");
    const entries = [...current[list], ...pending.add[list]];
    return (
      <div className="space-y-2" data-testid={`email-senders-${list}`}>
        <span id={`${uid}-${list}-label`} className="text-xs font-medium">{label}</span>
        <ul aria-labelledby={`${uid}-${list}-label`} className="flex flex-wrap gap-1.5">
          {entries.length === 0 && (
            <li className="text-[10px] text-muted-foreground">
              {list === "allow"
                ? t("emailSenders.emptyAllowlist", "None — every sender is allowed (subject to sender authentication).")
                : t("emailSenders.emptyBlocklist", "None.")}
            </li>
          )}
          {entries.map((entry) => {
            const added = pending.add[list].includes(entry);
            const removed = pending.remove[list].includes(entry);
            return (
              <li
                key={entry}
                data-testid={`email-sender-chip-${list}`}
                className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs ${
                  added ? "border-primary/60 bg-primary/10" : removed ? "border-destructive/50 line-through opacity-70" : "border-border"
                }`}
              >
                <span>{entry}</span>
                <button
                  type="button"
                  className="rounded-full p-0.5 hover:bg-muted disabled:opacity-50"
                  disabled={disabled}
                  aria-label={
                    removed
                      ? t("emailSenders.keep", { entry, defaultValue: "Keep {{entry}}" })
                      : t("emailSenders.remove", { entry, defaultValue: "Remove {{entry}}" })
                  }
                  onClick={() => toggleRemove(list, entry)}
                >
                  {removed ? <Undo2 className="h-3 w-3" /> : <X className="h-3 w-3" />}
                </button>
              </li>
            );
          })}
        </ul>
        <div className="flex gap-2">
          <Input
            id={`${uid}-${list}-new`}
            data-testid={`email-senders-${list}-input`}
            aria-label={t("emailSenders.addLabel", { list: label, defaultValue: "Add to {{list}}" })}
            value={drafts[list]}
            disabled={disabled}
            placeholder={list === "allow" ? "alice@partner.com" : "*@*.spam.example"}
            onChange={(e) => setDrafts((d) => ({ ...d, [list]: e.target.value }))}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addEntry(list);
              }
            }}
          />
          <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => addEntry(list)} data-testid={`email-senders-${list}-add`}>
            <Plus className="h-3.5 w-3.5" />
            {t("emailSenders.add", "Add")}
          </Button>
        </div>
      </div>
    );
  };

  return (
    <div className="space-y-3 sm:col-span-2" data-testid="email-sender-lists">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {renderList("allow")}
        {renderList("block")}
      </div>
      <p className="text-[10px] text-muted-foreground">
        {t(
          "emailSenders.hint",
          "Prefer exact addresses. Each applied change is a new version of this integration and is recorded in the audit log with its reason. A conversation that an email started can never change these lists.",
        )}
      </p>
      {disabled && (
        <p className="text-[10px] text-muted-foreground" data-testid="email-senders-disabled">
          {t("emailSenders.saveFirst", "Save or discard your other changes first; the sender lists are applied on their own.")}
        </p>
      )}
      {hasPending && (
        <div className="space-y-2 rounded-lg border border-border/60 p-3">
          <label htmlFor={`${uid}-reason`} className="text-xs font-medium">{t("emailSenders.reason", "Why (for the audit log)")}</label>
          <Input
            id={`${uid}-reason`}
            data-testid="email-senders-reason"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
            placeholder={t("emailSenders.reasonPlaceholder", "e.g. new supplier, contract 2026-114")}
          />
          {emptiesAllowlist && (
            <label className="flex items-start gap-2 text-xs" data-testid="email-senders-confirm-open">
              <input type="checkbox" checked={confirmOpen} onChange={(e) => setConfirmOpen(e.target.checked)} />
              <span className="flex items-start gap-1">
                <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-warning" />
                {t("emailSenders.confirmOpen", "I understand: with an empty allowlist every sender can make the agent run.")}
              </span>
            </label>
          )}
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              data-testid="email-senders-apply"
              disabled={disabled || mutation.isPending || (emptiesAllowlist && !confirmOpen)}
              onClick={apply}
            >
              {t("emailSenders.apply", "Apply sender changes")}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={reset} data-testid="email-senders-discard">
              {t("emailSenders.discard", "Discard")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
