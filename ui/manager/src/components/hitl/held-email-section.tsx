import { useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Mail, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@eddi/ui";
import { AccessibleDialog } from "@eddi/app-kit/components/ui/accessible-dialog";
import { getErrorMessage } from "@eddi/app-kit/lib/api-client";
import { useAllHeldEmail, useDecideHeldEmail, useHeldEmailPreview } from "@/hooks/use-channels";
import type { HeldEmailDecision, HeldEmailItem } from "@/lib/api/channels";

interface Selected {
  integrationId: string;
  integrationName: string | null;
  item: HeldEmailItem;
}

/**
 * Held mail (email channel v3 §7): mail an email integration would have
 * refused — a sender not on the allowlist, or one that did not authenticate —
 * kept for a person to decide. Its own kind on the approvals page, next to the
 * pauses: one queue for the human.
 */
export function HeldEmailSection() {
  const { t } = useTranslation();
  const { queues } = useAllHeldEmail();
  const [selected, setSelected] = useState<Selected | null>(null);
  const rows = queues.flatMap((q) => q.items.map((item) => ({ integrationId: q.integrationId, integrationName: q.integrationName, item })));
  if (rows.length === 0) return null;

  return (
    <section className="space-y-3" data-testid="held-email-section">
      <h2 className="flex items-center gap-2 text-lg font-semibold text-foreground">
        <Mail className="h-5 w-5 text-primary" aria-hidden="true" />
        {t("heldEmail.title", "Held mail")}
        <span className="rounded-full bg-warn-500/10 px-2 py-0.5 text-xs font-medium text-warn-600">{rows.length}</span>
      </h2>
      <p className="text-sm text-muted-foreground">
        {t(
          "heldEmail.subtitle",
          "Mail an email integration would have dropped — the sender is not on its allowlist, or did not authenticate. Decide whether it is processed at all.",
        )}
      </p>
      <div className="rounded-xl border bg-card shadow-sm overflow-x-auto">
        <table className="w-full text-sm" data-testid="held-email-table">
          <thead>
            <tr className="border-b bg-muted/30">
              <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("heldEmail.integration", "Integration")}</th>
              <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("heldEmail.sender", "Sender")}</th>
              <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("heldEmail.reason", "Held because")}</th>
              <th className="px-4 py-3 text-start font-medium text-muted-foreground">{t("heldEmail.expires", "Dropped on")}</th>
              <th className="px-4 py-3 text-end font-medium text-muted-foreground">{t("common.actions", "Actions")}</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.map((row) => (
              <tr key={row.item.rowId} data-testid={`held-email-row-${row.item.rowId}`}>
                <td className="px-4 py-3">{row.integrationName ?? row.integrationId}</td>
                <td className="px-4 py-3">
                  <span className="font-mono text-xs">{row.item.sender ?? "—"}</span>
                  {row.item.runsAnonymously && (
                    <span className="ms-2 inline-flex items-center gap-1 text-xs text-warn-600">
                      <ShieldAlert className="h-3.5 w-3.5" aria-hidden="true" />
                      {t("heldEmail.unauthenticated", "not authenticated")}
                    </span>
                  )}
                </td>
                <td className="px-4 py-3 text-xs">{row.item.heldFor.map((reason) => reasonLabel(reason, t)).join(", ")}</td>
                <td className="px-4 py-3 text-xs">{row.item.expiresAt ? new Date(row.item.expiresAt).toLocaleString() : "—"}</td>
                <td className="px-4 py-3 text-end">
                  <Button size="sm" variant="outline" onClick={() => setSelected(row)} data-testid={`held-email-review-${row.item.rowId}`}>
                    {t("heldEmail.review", "Review")}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {selected && <HeldEmailDecisionDialog selected={selected} onClose={() => setSelected(null)} />}
    </section>
  );
}

function reasonLabel(reason: string, t: ReturnType<typeof useTranslation>["t"]): string {
  switch (reason) {
    case "not_allowlisted":
      return t("heldEmail.reasonNotAllowlisted", "not on the allowlist");
    case "unauthenticated":
      return t("heldEmail.reasonUnauthenticated", "sender not authenticated");
    default:
      return reason;
  }
}

/**
 * The decision dialog: a live preview of the mail (re-read from the mailbox,
 * never stored), what each decision does, and the three decisions. "Run and
 * allow" is offered only for an aligned DKIM pass; for any other mail the
 * dialog says, before the decision, that a release runs it anonymously with
 * read-only tools and sends nothing.
 */
export function HeldEmailDecisionDialog({ selected, onClose }: { selected: Selected; onClose: () => void }) {
  const { t } = useTranslation();
  const { integrationId, item } = selected;
  const preview = useHeldEmailPreview(integrationId, item.rowId);
  const decide = useDecideHeldEmail();
  const [note, setNote] = useState("");

  const run = (decision: HeldEmailDecision) => {
    decide.mutate(
      { id: integrationId, rowId: item.rowId, decision, note },
      {
        onSuccess: () => {
          toast.success(
            decision === "discard"
              ? t("heldEmail.discarded", "Discarded.")
              : decision === "runAndAllow"
                ? t("heldEmail.releasedAllowed", "Released, and the sender is on the allowlist now. It runs at the next poll.")
                : t("heldEmail.released", "Released. It runs at the next poll."),
          );
          onClose();
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  };

  return (
    <AccessibleDialog open onClose={onClose} title={t("heldEmail.dialogTitle", "Held mail")} testId="held-email-dialog" maxWidth="max-w-2xl">
      <div className="space-y-4 p-5 text-sm">
        <div className="space-y-1">
          <p>
            <span className="text-muted-foreground">{t("heldEmail.from", "From")}: </span>
            <span className="font-mono">{item.sender ?? "—"}</span>{" "}
            <span className="text-xs text-muted-foreground">({item.authVerdict ?? "—"})</span>
          </p>
          <p className="text-xs text-muted-foreground">{item.heldFor.map((reason) => reasonLabel(reason, t)).join(", ")}</p>
        </div>
        {preview.isLoading ? (
          <p className="text-muted-foreground">{t("heldEmail.loadingPreview", "Reading the mail from the mailbox…")}</p>
        ) : preview.isError ? (
          <p className="text-destructive" data-testid="held-email-preview-error">
            {t("heldEmail.previewUnavailable", "The mail could not be read from the mailbox:")} {getErrorMessage(preview.error)}
          </p>
        ) : preview.data ? (
          <div className="space-y-2 rounded-lg border bg-muted/20 p-3" data-testid="held-email-preview">
            <p className="font-medium">{preview.data.subject ?? t("heldEmail.noSubject", "(no subject)")}</p>
            {preview.data.attachments.length > 0 && (
              <p className="text-xs text-muted-foreground">
                {t("heldEmail.attachments", "Attachments")}: {preview.data.attachments.join(", ")}
              </p>
            )}
            <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words text-xs">{preview.data.text}</pre>
            {preview.data.textTruncated && <p className="text-xs text-muted-foreground">{t("heldEmail.truncated", "Only the start of the text is shown.")}</p>}
          </div>
        ) : null}
        <p className="text-xs text-muted-foreground">
          {t("heldEmail.untrusted", "The mail is untrusted input. Releasing it means \"process it\", not \"do what it asks\": the target's approval policy still applies.")}
        </p>
        {item.runsAnonymously && (
          <p className="flex items-start gap-2 rounded-lg border border-warn-500/40 bg-warn-500/5 p-2 text-xs" data-testid="held-email-anonymous-warning">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warn-500" aria-hidden="true" />
            {t(
              "heldEmail.anonymousWarning",
              "This sender did not authenticate, so the From may be forged. If you run it, it runs anonymously, may use only the target's read-only tools, and nothing is ever sent to anyone because of it. It cannot be allowed.",
            )}
          </p>
        )}
        <label className="block space-y-1">
          <span className="text-xs font-medium">{t("heldEmail.note", "Note (for the audit log)")}</span>
          <input
            className="h-9 w-full rounded-lg border border-border bg-background px-3 text-sm"
            value={note}
            maxLength={500}
            onChange={(e) => setNote(e.target.value)}
            data-testid="held-email-note"
          />
        </label>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" onClick={() => run("discard")} disabled={decide.isPending} data-testid="held-email-discard">
            {t("heldEmail.discard", "Discard")}
          </Button>
          <Button variant="outline" onClick={() => run("runOnce")} disabled={decide.isPending} data-testid="held-email-run-once">
            {t("heldEmail.runOnce", "Run once")}
          </Button>
          {item.runAndAllowOffered && (
            <Button onClick={() => run("runAndAllow")} disabled={decide.isPending} data-testid="held-email-run-and-allow">
              {t("heldEmail.runAndAllow", "Run and allow this sender")}
            </Button>
          )}
        </div>
      </div>
    </AccessibleDialog>
  );
}
