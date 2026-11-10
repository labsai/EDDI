import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Copy, Link2, Trash2 } from "lucide-react";
import { useCreateReportLink, useReportLinks, useRevokeReportLink } from "@/hooks/use-agent-health";
import { AccessibleDialog, Button, Skeleton, getErrorMessage } from "./deps";
import { EXPIRY_DAYS, REPORT_SECTIONS, allowedReportSections, sectionTitle } from "./format";

/**
 * Owner report links: a read-only, expiring page for people who never open the
 * Manager. Creating and revoking need EDIT (the panel offers this dialog to no
 * one else). The link is shown once, at creation; the list never has it again.
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
  const links = useReportLinks(agentId, open);
  const create = useCreateReportLink(agentId);
  const revoke = useRevokeReportLink(agentId);
  const [days, setDays] = useState<number>(30);
  const [sections, setSections] = useState<string[]>(() => allowedReportSections(canReadQuality).filter((s) => s !== "quality"));
  const [created, setCreated] = useState<string | null>(null);

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
    <AccessibleDialog open={open} onClose={onClose} title={t("health.reports.title", "Owner report links")} maxWidth="max-w-2xl" testId="health-reports-dialog">
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
            create.mutate(
              { expiresInDays: days, sections },
              {
                onSuccess: (link) => {
                  setCreated(link.url ?? null);
                },
              },
            );
          }}
        >
          <label className="flex items-center gap-2 text-xs">
            {t("health.reports.expires", "Expires after")}
            <select
              className="h-8 rounded-lg border border-input bg-background px-2 text-xs"
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
              data-testid="health-report-expiry"
            >
              {EXPIRY_DAYS.map((d) => (
                <option key={d} value={d}>{t("health.reports.days", { n: d, defaultValue: "{{n}} days" })}</option>
              ))}
            </select>
          </label>
          <fieldset>
            <legend className="mb-1 text-xs font-medium">{t("health.reports.sections", "Sections")}</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              {REPORT_SECTIONS.map((s) => {
                const disabled = s === "quality" && !canReadQuality;
                return (
                  <label key={s} className="flex items-center gap-1.5 text-xs" title={disabled ? t("health.reports.qualityDenied", "You may not read ratings, so you cannot share them.") : undefined}>
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
          {created && (
            <div className="flex items-center gap-2 rounded-lg bg-muted/60 p-2" data-testid="health-report-created">
              <code className="min-w-0 flex-1 truncate text-xs">{created}</code>
              <Button type="button" size="sm" variant="outline" onClick={() => void copy(created)}>
                <Copy aria-hidden="true" />
                {t("health.reports.copy", "Copy link")}
              </Button>
            </div>
          )}
          {created && <p className="text-xs text-muted-foreground">{t("health.reports.shownOnce", "Copy it now: the link is not shown again.")}</p>}
        </form>

        <div>
          <h3 className="mb-1 text-xs font-medium">{t("health.reports.existing", "Active links")}</h3>
          {links.isLoading && <Skeleton className="h-10 w-full" />}
          {links.isError && <p className="text-xs text-destructive" role="alert">{getErrorMessage(links.error)}</p>}
          {links.data && links.data.length === 0 && <p className="text-xs text-muted-foreground">{t("health.reports.none", "No links yet.")}</p>}
          <ul className="space-y-1" data-testid="health-report-list">
            {links.data?.map((l) => (
              <li key={l.id} className="flex flex-wrap items-center gap-2 rounded-lg border border-border px-2 py-1.5 text-xs">
                <span className="tabular-nums">{t("health.reports.expiresOn", { date: new Date(l.expiresAt).toLocaleDateString(), defaultValue: "Expires {{date}}" })}</span>
                <span className="min-w-0 flex-1 truncate text-muted-foreground">{l.sections.map((s) => sectionTitle(t, s)).join(", ")}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(l.id)}
                  aria-label={t("health.reports.revokeLabel", { date: new Date(l.expiresAt).toLocaleDateString(), defaultValue: "Revoke the link that expires {{date}}" })}
                >
                  <Trash2 aria-hidden="true" />
                  {t("health.reports.revoke", "Revoke")}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </AccessibleDialog>
  );
}
