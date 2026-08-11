import { useTranslation } from "react-i18next";
import {
  ArrowUpCircle,
  CheckCircle2,
  ExternalLink,
  AlertTriangle,
  RefreshCw,
  Info,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useUpdateCheck } from "@/hooks/use-update-check";
import { EDDI_RELEASES_URL } from "@/lib/api/updates";
import { cn } from "@/lib/utils";

/** The exact commands EDDI's own README documents for an upgrade. */
const CLI_UPDATE_COMMAND = "eddi update";
const MANUAL_UPDATE_COMMANDS = [
  "cd ~/.eddi",
  "docker compose --env-file .env -f docker-compose.yml pull",
  "docker compose --env-file .env -f docker-compose.yml up -d",
].join("\n");

/**
 * Opt-in "is there a newer EDDI?" section.
 *
 * Sits at the bottom of the dashboard because it is a maintenance concern, not
 * a daily one. Nothing here talks to the network until the operator presses the
 * button or ticks the checkbox.
 */
export function UpdateCheckCard() {
  const { t, i18n } = useTranslation();
  const {
    autoCheck,
    setAutoCheck,
    installedVersion,
    installedVersionLoading,
    latest,
    status,
    isChecking,
    errorReason,
    hasChecked,
    checkNow,
  } = useUpdateCheck();

  const updateAvailable = status === "update-available";
  const knownInstalled =
    !!installedVersion && installedVersion !== "Unknown" ? installedVersion : null;

  return (
    <Card
      id="updates"
      data-testid="update-check-card"
      className={cn("scroll-mt-6", updateAvailable && "border-primary/40")}
    >
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ArrowUpCircle className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          {t("updates.title", "EDDI Updates")}
        </CardTitle>
        <p className="text-sm text-muted-foreground">
          {t(
            "updates.description",
            "Check whether a newer EDDI release is available. Nothing is sent until you ask.",
          )}
        </p>
      </CardHeader>

      <CardContent className="space-y-4 pt-4">
        {/* Versions + trigger */}
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("updates.installed", "Installed")}
            </p>
            <p className="text-sm font-medium tabular-nums" data-testid="update-installed-version">
              {installedVersionLoading ? (
                <Skeleton className="my-0.5 block h-4 w-20" />
              ) : (
                (knownInstalled ?? t("updates.unknownVersion", "Unknown"))
              )}
            </p>
          </div>

          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("updates.latestRelease", "Latest release")}
            </p>
            <p className="text-sm font-medium tabular-nums" data-testid="update-latest-version">
              {latest ? latest.version : "—"}
            </p>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={checkNow}
            disabled={isChecking}
            className="ms-auto"
            data-testid="update-check-now"
          >
            <RefreshCw className={cn("h-4 w-4", isChecking && "animate-spin")} aria-hidden="true" />
            {isChecking ? t("updates.checking", "Checking…") : t("updates.checkNow", "Check now")}
          </Button>
        </div>

        {/* Result */}
        <div data-testid="update-check-result" aria-live="polite">
          {errorReason ? (
            <ResultRow tone="warning" icon={AlertTriangle}>
              <span>
                {errorReason === "rate-limited"
                  ? t(
                      "updates.errorRateLimited",
                      "GitHub's rate limit for unauthenticated requests was reached. Try again later, or open the releases page.",
                    )
                  : errorReason === "unreachable"
                    ? t(
                        "updates.errorUnreachable",
                        "Could not reach api.github.com. Check your network or any outbound proxy.",
                      )
                    : t(
                        "updates.errorFailed",
                        "The update check failed. Open the releases page to check manually.",
                      )}
              </span>
            </ResultRow>
          ) : !hasChecked ? (
            <p className="text-sm text-muted-foreground">
              {t("updates.neverChecked", "No check run yet.")}
            </p>
          ) : status === "update-available" && latest ? (
            <ResultRow tone="accent" icon={ArrowUpCircle}>
              <div className="space-y-1">
                <p className="font-medium text-foreground">
                  {t("updates.updateAvailable", "EDDI {{version}} is available", {
                    version: latest.version,
                  })}
                </p>
                <p>
                  {knownInstalled
                    ? t("updates.updateAvailableDetail", "You are running {{installed}}.", {
                        installed: knownInstalled,
                      })
                    : null}{" "}
                  {latest.publishedAt
                    ? t("updates.publishedOn", "Published {{date}}", {
                        date: new Date(latest.publishedAt).toLocaleDateString(i18n.language),
                      })
                    : null}
                </p>
              </div>
            </ResultRow>
          ) : status === "up-to-date" && latest ? (
            <ResultRow tone="success" icon={CheckCircle2}>
              <span>
                {t("updates.upToDate", "You are running the latest release ({{version}}).", {
                  version: latest.version,
                })}
              </span>
            </ResultRow>
          ) : status === "ahead" && latest && knownInstalled ? (
            <ResultRow tone="muted" icon={Info}>
              <span>
                {t(
                  "updates.ahead",
                  "You are running {{installed}}, which is newer than the latest release ({{version}}).",
                  { installed: knownInstalled, version: latest.version },
                )}
              </span>
            </ResultRow>
          ) : latest ? (
            <ResultRow tone="muted" icon={Info}>
              <span>
                {t(
                  "updates.unknownInstalled",
                  "The latest release is {{version}}. The installed version could not be determined, so there is nothing to compare it against.",
                  { version: latest.version },
                )}
              </span>
            </ResultRow>
          ) : null}
        </div>

        {/* Update instructions — only once there is something to update to */}
        {updateAvailable && <UpdateInstructions />}

        {/* Preference + links */}
        <div className="space-y-3 border-t border-border pt-4">
          <label className="flex items-start gap-2.5 text-sm">
            <input
              type="checkbox"
              checked={autoCheck}
              onChange={(e) => setAutoCheck(e.target.checked)}
              className="mt-0.5 h-4 w-4 shrink-0 rounded border-input accent-primary"
              data-testid="update-auto-check"
            />
            <span>
              <span className="font-medium">
                {t("updates.autoCheck", "Check automatically on every page load")}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t(
                  "updates.autoCheckHint",
                  "Runs one check per browser reload and shows a banner when a newer release exists.",
                )}
              </span>
            </span>
          </label>

          <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
            <a
              href={latest?.url ?? EDDI_RELEASES_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 text-xs font-medium text-primary hover:underline"
              data-testid="update-release-notes-link"
            >
              {latest
                ? t("updates.releaseNotes", "Release notes")
                : t("updates.allReleases", "All releases")}
              <ExternalLink className="h-3 w-3" aria-hidden="true" />
              <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
            </a>
            <p className="text-xs text-muted-foreground">
              {t(
                "updates.privacyNote",
                "Checks contact api.github.com directly from your browser. No deployment data is sent.",
              )}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ─── Update instructions ─────────────────────────────────────────────────── */

/**
 * The upgrade path is a backend/host operation — nothing the Manager can do
 * from the browser — so this is documentation, kept in step with EDDI's README.
 */
function UpdateInstructions() {
  const { t } = useTranslation();

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/40 p-4" data-testid="update-instructions">
      <p className="text-sm font-medium">{t("updates.howToUpdate", "How to update")}</p>

      <div className="space-y-1.5">
        <p className="text-xs text-muted-foreground">
          {t("updates.howToUpdateCli", "With the eddi CLI (created by the installer):")}
        </p>
        <CommandBlock command={CLI_UPDATE_COMMAND} />
      </div>

      <div className="space-y-1.5">
        <p className="text-xs text-muted-foreground">
          {t(
            "updates.howToUpdateManual",
            "Without the CLI, from your install directory (~/.eddi by default):",
          )}
        </p>
        <CommandBlock command={MANUAL_UPDATE_COMMANDS} />
      </div>

      <p className="text-xs text-muted-foreground">
        {t(
          "updates.howToUpdateNote",
          "Adjust the -f flags to match your setup — add -f docker-compose.auth.yml if you run Keycloak.",
        )}
      </p>
    </div>
  );
}

function CommandBlock({ command }: { command: string }) {
  return (
    <pre className="overflow-x-auto rounded-md bg-background p-2.5 text-xs" dir="ltr">
      <code className="font-mono text-foreground">{command}</code>
    </pre>
  );
}

/* ─── Result row ──────────────────────────────────────────────────────────── */

type ResultTone = "accent" | "success" | "warning" | "muted";

const TONE_STYLES: Record<ResultTone, { wrapper: string; icon: string }> = {
  accent: { wrapper: "border-primary/30 bg-primary/5", icon: "text-primary" },
  success: {
    wrapper: "border-emerald-500/30 bg-emerald-500/5",
    icon: "text-emerald-600 dark:text-emerald-400",
  },
  warning: {
    wrapper: "border-amber-500/30 bg-amber-500/5",
    icon: "text-amber-600 dark:text-amber-400",
  },
  muted: { wrapper: "border-border bg-muted/40", icon: "text-muted-foreground" },
};

function ResultRow({
  tone,
  icon: Icon,
  children,
}: {
  tone: ResultTone;
  icon: typeof Info;
  children: React.ReactNode;
}) {
  const styles = TONE_STYLES[tone];
  return (
    <div className={cn("flex items-start gap-2.5 rounded-lg border p-3 text-sm", styles.wrapper)}>
      <Icon className={cn("mt-0.5 h-4 w-4 shrink-0", styles.icon)} aria-hidden="true" />
      <div className="min-w-0 text-muted-foreground">{children}</div>
    </div>
  );
}
