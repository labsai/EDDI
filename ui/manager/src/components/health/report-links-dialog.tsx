import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Copy, Link2, Trash2 } from "lucide-react";
import { useCreateReportLink, useReportLinks, useRevokeReportLink } from "./use-agent-health";
import { AccessibleDialog, Button, Skeleton, getErrorMessage } from "./deps";
import { EXPIRY_DAYS, REPORT_SECTIONS, allowedReportSections, formatDays, formatShortDate, sectionTitle, selectClass } from "./format";

/**
 * The dialog's whole state, in a component that exists only while the dialog is
 * open: closing it unmounts this, so a link shown once at creation is gone when
 * the dialog is reopened (the token is never shown twice).
 */
function ReportsBody({ agentId, canReadQuality }: { agentId: string; canReadQuality: boolean }) {
  const { t } = useTranslation();
  const links = useReportLinks(agentId);
  const create = useCreateReportLink(agentId);
  const revoke = useRevokeReportLink(agentId);
  const [days, setDays] = useState<number>(30);
  const [sections, setSections] = useState<string[]>(() => allowedReportSections(canReadQuality).filter((s) => s !== "quality"));
  const [created, setCreated] = useState<{ url: string | null } | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);

  const toggle = (s: string) => setSections((cur) => (cur.includes(s) ? cur.filter((c) => c !== s) : [...cur, s]));
  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      toast.success(t("health.reports.copied", "Link copied"));
    } catch {
      toast.error(t("health.reports.copyFailed", "Could not copy the link"));
    }
  };

  return (
    <div className="space-y-4 text-sm">
      <p className="text-muted-foreground">
        {t(
          "health.reports.intro",
          "A link shows this agent's health to someone without a Manager login. It is read-only, has no conversations or user data, and stops working when it expires or is revoked.",
        )}
      </p>

      <form
        className="space-y-3 rounded-lg border border-border p-3"
        onSubmit={(e) => {
          e.preventDefault();
          create.mutate({ expiresInDays: days, sections }, { onSuccess: (link) => setCreated({ url: link.url ?? null }) });
        }}
      >
        <label className="flex items-center gap-2 text-xs">
          {t("health.reports.expires", "Expires after")}
          <select className={selectClass} value={days} onChange={(e) => setDays(Number(e.target.value))} data-testid="health-report-expiry">
            {EXPIRY_DAYS.map((d) => (
              <option key={d} value={d}>{formatDays(d)}</option>
            ))}
          </select>
        </label>
        <fieldset>
          <legend className="mb-1 text-xs font-medium">{t("health.reports.sections", "Sections")}</legend>
          <div className="flex flex-wrap gap-x-4 gap-y-1">
            {REPORT_SECTIONS.map((s) => {
              const disabled = s === "quality" && !canReadQuality;
              return (
                <label key={s} className="flex items-center gap-1.5 text-xs">
                  <input
                    type="checkbox"
                    disabled={disabled}
                    checked={!disabled && sections.includes(s)}
                    onChange={() => toggle(s)}
                    data-testid={`health-report-section-${s}`}
                  />
                  {sectionTitle(t, s)}
                </label>
              );
            })}
          </div>
          {!canReadQuality && (
            <p className="mt-1 text-xs text-muted-foreground" data-testid="health-report-quality-note">
              {t("health.reports.qualityDenied", "You may not read ratings, so you cannot share them.")}
            </p>
          )}
        </fieldset>
        {create.isError && <p className="text-xs text-destructive" role="alert">{getErrorMessage(create.error)}</p>}
        <Button type="submit" size="sm" disabled={create.isPending || sections.length === 0} data-testid="health-report-create">
          <Link2 aria-hidden="true" />
          {t("health.reports.create", "Create link")}
        </Button>
        {created && created.url && (
          <>
            <div className="flex items-center gap-2 rounded-lg bg-muted/60 p-2" data-testid="health-report-created">
              <code className="min-w-0 flex-1 truncate text-xs">{created.url}</code>
              <Button type="button" size="sm" variant="outline" onClick={() => void copy(created.url!)}>
                <Copy aria-hidden="true" />
                {t("health.reports.copy", "Copy link")}
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">{t("health.reports.shownOnce", "Copy it now: the link is not shown again.")}</p>
          </>
        )}
        {created && !created.url && (
          <p className="text-xs text-destructive" role="alert" data-testid="health-report-no-url">
            {t("health.reports.noUrl", "The link was created, but the server did not return it. Revoke it below and create another.")}
          </p>
        )}
      </form>

      <div>
        <h3 className="mb-1 text-xs font-medium">{t("health.reports.existing", "Active links")}</h3>
        {links.isLoading && <Skeleton className="h-10 w-full" />}
        {links.isError && <p className="text-xs text-destructive" role="alert">{getErrorMessage(links.error)}</p>}
        {revoke.isError && <p className="text-xs text-destructive" role="alert" data-testid="health-report-revoke-error">{getErrorMessage(revoke.error)}</p>}
        {links.data && links.data.length === 0 && <p className="text-xs text-muted-foreground">{t("health.reports.none", "No links yet.")}</p>}
        <ul className="space-y-1" data-testid="health-report-list">
          {links.data?.map((l) => {
            const date = formatShortDate(l.expiresAt);
            return (
              <li key={l.id} className="flex flex-wrap items-center gap-2 rounded-lg border border-border px-2 py-1.5 text-xs">
                <span className="tabular-nums">{t("health.reports.expiresOn", { date, defaultValue: "Expires {{date}}" })}</span>
                <span className="min-w-0 flex-1 truncate text-muted-foreground">{l.sections.map((s) => sectionTitle(t, s)).join(", ")}</span>
                {confirming === l.id ? (
                  <>
                    <span className="text-destructive">{t("health.reports.revokeConfirm", "Revoke this link for good?")}</span>
                    <Button
                      type="button"
                      variant="destructive"
                      size="sm"
                      disabled={revoke.isPending}
                      onClick={() => revoke.mutate(l.id, { onSettled: () => setConfirming(null) })}
                      data-testid="health-report-revoke-confirm"
                    >
                      {t("health.reports.revokeYes", "Yes, revoke")}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" onClick={() => setConfirming(null)}>
                      {t("common.cancel", "Cancel")}
                    </Button>
                  </>
                ) : (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setConfirming(l.id)}
                    aria-label={t("health.reports.revokeLabel", { date, defaultValue: "Revoke the link that expires {{date}}" })}
                  >
                    <Trash2 aria-hidden="true" />
                    {t("health.reports.revoke", "Revoke")}
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

/**
 * Owner report links: a read-only, expiring page for people who never open the
 * Manager. Creating and revoking need EDIT (the panel offers this dialog to no
 * one else).
 */
export function ReportLinksDialog({
  open,
  onClose,
  agentId,
  canReadQuality,
}: {
  open: boolean;
  onClose: () => void;
  agentId: string;
  /** Derived from the panel: the quality section is not `restricted`. */
  canReadQuality: boolean;
}) {
  const { t } = useTranslation();
  return (
    <AccessibleDialog open={open} onClose={onClose} title={t("health.reports.title", "Owner report links")} maxWidth="max-w-2xl" testId="health-reports-dialog">
      <ReportsBody agentId={agentId} canReadQuality={canReadQuality} />
    </AccessibleDialog>
  );
}
