import { useState } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowUpCircle,
  CheckCircle2,
  ChevronDown,
  Container,
  ExternalLink,
  AlertTriangle,
  Gift,
  Github,
  RefreshCw,
  Info,
  Server,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useUpdateCheck } from "@/hooks/use-update-check";
import { EDDI_DOCKER_TAGS_URL, EDDI_RELEASES_URL } from "@/lib/api/updates";
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
    image,
    imageStatus,
    isChecking,
    errorReason,
    imageLookupFailed,
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
        {/* The three versions that matter, each from its own source */}
        <div className="flex flex-wrap items-start gap-x-8 gap-y-3">
          <VersionCell icon={Server} label={t("updates.installed", "Installed")}>
            {installedVersionLoading ? (
              <Skeleton className="my-0.5 block h-4 w-20" />
            ) : (
              <span data-testid="update-installed-version">
                {knownInstalled ?? t("updates.unknownVersion", "Unknown")}
              </span>
            )}
          </VersionCell>

          <VersionCell
            icon={Github}
            label={t("updates.githubRelease", "GitHub release")}
            href={latest?.url}
            testId="update-latest-version"
          >
            {latest ? latest.version : "—"}
          </VersionCell>

          <VersionCell
            icon={Container}
            label={t("updates.dockerImage", "Docker image")}
            href={image?.url ?? (hasChecked ? EDDI_DOCKER_TAGS_URL : undefined)}
            testId="update-image-version"
          >
            {image ? image.version : "—"}
          </VersionCell>

          <Button
            variant="outline"
            size="sm"
            onClick={checkNow}
            disabled={isChecking}
            className="ms-auto mt-4"
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

        {/* The reason for reading both sources: a release whose image is not
            published yet cannot be pulled, however current the tag looks. */}
        {imageStatus === "pending" && latest && image && (
          <ResultRow tone="warning" icon={AlertTriangle} testId="update-image-pending">
            <span>
              {t(
                "updates.imagePending",
                "The {{version}} image is not on Docker Hub yet — a pull right now would still fetch {{available}}.",
                { version: latest.version, available: image.version },
              )}
            </span>
          </ResultRow>
        )}
        {imageLookupFailed && !errorReason && (
          <ResultRow tone="muted" icon={Info} testId="update-image-failed">
            <span>
              {t(
                "updates.imageLookupFailed",
                "The published Docker tag could not be read. Check the image tags directly.",
              )}
            </span>
          </ResultRow>
        )}

        {/* Release notes */}
        {latest && <ReleaseNotes release={latest} highlight={updateAvailable} />}

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
            <ExternalLinkText
              href={latest?.url ?? EDDI_RELEASES_URL}
              testId="update-release-notes-link"
            >
              {latest
                ? t("updates.releaseNotes", "Release notes")
                : t("updates.allReleases", "All releases")}
            </ExternalLinkText>
            {/* Brand name, deliberately not an i18n key: it is the same in
                every locale, and a key would read to the debt ratchet as ten
                untranslated strings. */}
            <ExternalLinkText
              href={image?.url ?? EDDI_DOCKER_TAGS_URL}
              testId="update-docker-hub-link"
            >
              Docker Hub
            </ExternalLinkText>
            <p className="text-xs text-muted-foreground">
              {t(
                "updates.privacyNote",
                "Checks contact api.github.com and img.shields.io directly from your browser. No deployment data is sent.",
              )}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/* ─── Release notes ───────────────────────────────────────────────────────── */

/**
 * The "what did I actually get" half of an update prompt.
 *
 * Collapsed by default — a version number is the answer to "should I update",
 * the notes are the answer to "why", and only one of those belongs on a
 * dashboard unprompted.
 */
function ReleaseNotes({
  release,
  highlight,
}: {
  release: { version: string; url: string; notes: string };
  highlight: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  return (
    <div
      className={cn(
        "rounded-lg border",
        highlight ? "border-primary/30 bg-primary/5" : "border-border bg-muted/40",
      )}
      data-testid="update-release-notes"
    >
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        className="flex w-full items-center gap-2.5 p-3 text-start text-sm"
        aria-expanded={open}
        aria-controls="update-release-notes-body"
        data-testid="update-release-notes-toggle"
      >
        <Gift
          className={cn("h-4 w-4 shrink-0", highlight ? "text-primary" : "text-muted-foreground")}
          aria-hidden="true"
        />
        <span className="font-medium">
          {t("updates.whatsNew", "What's new in {{version}}", { version: release.version })}
        </span>
        <ChevronDown
          className={cn(
            "ms-auto h-4 w-4 shrink-0 text-muted-foreground transition-transform",
            open && "rotate-180",
          )}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div id="update-release-notes-body" className="space-y-3 border-t border-border/60 p-3">
          {release.notes ? (
            /* Deliberately NO rehypeRaw: release notes are third-party text, so
               raw HTML stays escaped rather than being injected live. */
            <div className="prose prose-sm dark:prose-invert max-h-80 max-w-none overflow-y-auto break-words [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:text-xs [&_h1]:text-base [&_h2]:text-sm [&_h3]:text-sm [&_p:first-child]:mt-0 [&_p:last-child]:mb-0 [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{release.notes}</ReactMarkdown>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              {t("updates.noNotes", "This release has no notes.")}
            </p>
          )}
          <ExternalLinkText href={release.url} testId="update-full-notes-link">
            {t("updates.fullNotes", "Full release notes on GitHub")}
          </ExternalLinkText>
        </div>
      )}
    </div>
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

/* ─── Small pieces ────────────────────────────────────────────────────────── */

function VersionCell({
  icon: Icon,
  label,
  href,
  testId,
  children,
}: {
  icon: typeof Info;
  label: string;
  href?: string;
  testId?: string;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();

  return (
    <div>
      <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
        <Icon className="h-3 w-3" aria-hidden="true" />
        {label}
      </p>
      {/* A div, not a p: the loading state renders a Skeleton, which is a div. */}
      <div className="text-sm font-medium tabular-nums" data-testid={testId}>
        {href ? (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 hover:text-primary hover:underline"
          >
            {children}
            <ExternalLink className="h-3 w-3 opacity-50" aria-hidden="true" />
            <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
          </a>
        ) : (
          children
        )}
      </div>
    </div>
  );
}

function ExternalLinkText({
  href,
  testId,
  children,
}: {
  href: string;
  testId?: string;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-1.5 text-xs font-medium text-primary hover:underline"
      data-testid={testId}
    >
      {children}
      <ExternalLink className="h-3 w-3" aria-hidden="true" />
      <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
    </a>
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
  testId,
  children,
}: {
  tone: ResultTone;
  icon: typeof Info;
  testId?: string;
  children: React.ReactNode;
}) {
  const styles = TONE_STYLES[tone];
  return (
    <div
      className={cn("flex items-start gap-2.5 rounded-lg border p-3 text-sm", styles.wrapper)}
      data-testid={testId}
    >
      <Icon className={cn("mt-0.5 h-4 w-4 shrink-0", styles.icon)} aria-hidden="true" />
      <div className="min-w-0 text-muted-foreground">{children}</div>
    </div>
  );
}
