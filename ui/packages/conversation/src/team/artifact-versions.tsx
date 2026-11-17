import { useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronLeft, FileStack } from "lucide-react";
import { Badge, cn } from "@eddi/ui";
import type { SharedArtifact } from "@eddi/core/groups";
import { ArtifactContent } from "./artifact-content";
import { artifactVersions, type ArtifactVersionView } from "./artifact-version-list";

/**
 * A team session's shared artifacts with their versions (T2.6): the list, one
 * artifact opened, and a switch between its versions.
 *
 * Read-only, like everything about artifacts on the human side: members write
 * them through their tools, and no endpoint lets a person edit one. The
 * versions come from the artifact itself — `content`/`version` is the current
 * one, `history` the superseded ones the server keeps (at most ten, oldest
 * dropped: `ARTIFACT_HISTORY_CAP`), each with its content, editor and time. So
 * switching needs no request, and a version older than the history keeps is
 * gone for good, which the panel says.
 */
export function ArtifactVersions({
  artifacts,
  memberDisplayNames,
  className,
}: {
  artifacts: readonly SharedArtifact[];
  /** Agent id → display name, to say who edited a version. */
  memberDisplayNames?: Record<string, string> | null;
  className?: string;
}) {
  const { t, i18n } = useTranslation();
  const [openId, setOpenId] = useState<string | null>(artifacts.length === 1 ? artifacts[0]!.id : null);
  const open = artifacts.find((a) => a.id === openId) ?? null;

  if (artifacts.length === 0) {
    return (
      <p className={cn("p-4 text-sm text-muted-foreground", className)} data-testid="artifact-versions-empty">
        {t("groups.artifactPanel.empty", "No shared artifacts in this session yet.")}
      </p>
    );
  }

  if (open) {
    return (
      <div className={className}>
        {artifacts.length > 1 && (
          <button
            type="button"
            onClick={() => setOpenId(null)}
            className="mb-2 inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid="artifact-versions-back"
          >
            <ChevronLeft className="h-3.5 w-3.5 rtl:rotate-180" aria-hidden="true" />
            {t("groups.artifactPanel.allArtifacts", "All artifacts")}
          </button>
        )}
        {/* Keyed: another artifact starts on its own current version. */}
        <ArtifactDetail
          key={open.id}
          artifact={open}
          memberDisplayNames={memberDisplayNames}
          language={i18n.language}
        />
      </div>
    );
  }

  return (
    <ul className={cn("flex flex-col gap-1.5", className)} data-testid="artifact-versions-list">
      {artifacts.map((artifact) => (
        <li key={artifact.id}>
          <button
            type="button"
            onClick={() => setOpenId(artifact.id)}
            className="flex w-full items-center gap-2 rounded-lg border border-border bg-background/60 p-2.5 text-start transition-colors hover:bg-secondary/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid={`artifact-open-${artifact.id}`}
          >
            <FileStack className="h-4 w-4 shrink-0 text-sky-600 dark:text-sky-400" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground" dir="auto">
              {artifact.name}
            </span>
            <StatusBadge status={artifact.status} />
            <span className="shrink-0 font-mono text-[10px] text-muted-foreground">
              {t("groups.artifactPanel.versionShort", "v{{version}}", { version: artifact.version })}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function StatusBadge({ status }: { status: SharedArtifact["status"] }) {
  const { t } = useTranslation();
  return (
    <Badge variant={status === "FINAL" ? "success" : "secondary"} className="shrink-0 px-1.5 py-0 text-[10px]">
      {status === "FINAL" ? t("groups.artifactFinal", "Final") : t("groups.artifactDraft", "Draft")}
    </Badge>
  );
}

function ArtifactDetail({
  artifact,
  memberDisplayNames,
  language,
}: {
  artifact: SharedArtifact;
  memberDisplayNames?: Record<string, string> | null;
  language: string;
}) {
  const { t } = useTranslation();
  const selectId = useId();
  const versions = useMemo(() => artifactVersions(artifact), [artifact]);
  const [picked, setPicked] = useState<number | null>(null);
  // A version the artifact no longer has (the history dropped it) falls back to the current one.
  const shown: ArtifactVersionView = versions.find((v) => v.version === picked) ?? versions[0]!;
  const current = versions[0]!;
  const isCurrent = shown.version === current.version;

  const editorName = (id: string | null) => (id ? (memberDisplayNames?.[id] ?? id) : null);
  const formatAt = (iso: string | null) => {
    if (!iso) return null;
    const ms = new Date(iso).getTime();
    if (Number.isNaN(ms)) return null;
    return new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "short" }).format(new Date(ms));
  };
  const versionLabel = (v: ArtifactVersionView) => {
    const at = formatAt(v.at);
    const editor = editorName(v.editorAgentId);
    const base =
      v.version === current.version
        ? t("groups.artifactPanel.versionCurrent", "v{{version}} (current)", { version: v.version })
        : t("groups.artifactPanel.versionShort", "v{{version}}", { version: v.version });
    return [base, editor, at].filter(Boolean).join(" · ");
  };
  const shownEditor = editorName(shown.editorAgentId);
  const shownAt = formatAt(shown.at);

  return (
    <article data-testid={`artifact-detail-${artifact.id}`}>
      <header className="mb-2 flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-semibold text-foreground" dir="auto">
          {artifact.name}
        </h3>
        <StatusBadge status={artifact.status} />
      </header>

      <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
        <label htmlFor={selectId} className="text-muted-foreground">
          {t("groups.artifactPanel.version", "Version")}
        </label>
        <select
          id={selectId}
          value={shown.version}
          onChange={(e) => setPicked(Number(e.target.value))}
          className="min-w-0 max-w-full rounded-md border border-input bg-background px-2 py-1 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          data-testid="artifact-version-select"
        >
          {versions.map((v) => (
            <option key={v.version} value={v.version}>
              {versionLabel(v)}
            </option>
          ))}
        </select>
      </div>

      {(shownEditor || shownAt) && (
        <p className="mb-2 text-[11px] text-muted-foreground" data-testid="artifact-version-meta">
          {shownEditor && shownAt
            ? t("groups.artifactPanel.editedByAt", "Written by {{editor}} on {{at}}", { editor: shownEditor, at: shownAt })
            : shownEditor
              ? t("groups.artifactLastEdited", "Last edited by {{agent}}", { agent: shownEditor })
              : shownAt}
        </p>
      )}

      {!isCurrent && (
        <div
          className="mb-2 flex flex-wrap items-center gap-2 rounded-md border border-warn-500/30 bg-warn-500/5 px-2.5 py-1.5 text-xs text-muted-foreground"
          data-testid="artifact-older-version"
        >
          <span className="flex-1">
            {t("groups.artifactPanel.olderVersion", "This is an earlier version (v{{shown}}). The current one is v{{current}}.", {
              shown: shown.version,
              current: current.version,
            })}
          </span>
          <button
            type="button"
            onClick={() => setPicked(null)}
            className="font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid="artifact-show-current"
          >
            {t("groups.artifactPanel.showCurrent", "Show the current version")}
          </button>
        </div>
      )}

      <ArtifactContent type={artifact.type} content={shown.content} testId="artifact-version-content" />

      {versions.length > 1 && versions[versions.length - 1]!.version > 1 && (
        <p className="mt-2 text-[11px] text-muted-foreground" data-testid="artifact-history-trimmed">
          {t(
            "groups.artifactPanel.historyTrimmed",
            "Versions before v{{oldest}} are no longer kept.",
            { oldest: versions[versions.length - 1]!.version },
          )}
        </p>
      )}
    </article>
  );
}
