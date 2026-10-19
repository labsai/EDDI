import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
// Configures the self-hosted Monaco instance before <Editor> can look for one.
// Side-effect import: without it @monaco-editor/react falls back to the jsDelivr CDN.
import "@/lib/monaco-setup";
import { DiffEditor } from "@monaco-editor/react";
import { RefreshCw, ChevronDown } from "lucide-react";
import { useTheme } from "@/components/layout/theme-provider";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

export interface VersionDiffDialogProps {
  open: boolean;
  onClose: () => void;
  /** Display name of the resource (e.g. "LLM", "Rules") */
  typeName: string;
  /** List of available versions with their data */
  versions: { version: number; lastModifiedOn?: number }[];
  /** Function to fetch a version's data — returns stringified JSON */
  fetchVersion: (version: number) => Promise<string>;
  /** Currently active version (pre-selected as "right" side) */
  currentVersion: number;
}

/**
 * Full-screen dialog showing a Monaco diff editor to compare two versions
 * of a resource side-by-side. User picks "left" (old) and "right" (new) versions.
 */
export function VersionDiffDialog({
  open,
  onClose,
  typeName,
  versions,
  fetchVersion,
  currentVersion,
}: VersionDiffDialogProps) {
  const { t } = useTranslation();
  const { theme } = useTheme();

  // Default: compare previous version (left) with current (right)
  const sortedVersions = [...versions].sort((a, b) => a.version - b.version);
  const currentIdx = sortedVersions.findIndex((v) => v.version === currentVersion);
  const defaultLeft = currentIdx > 0
    ? sortedVersions[currentIdx - 1]!.version
    : sortedVersions[0]?.version ?? 1;

  const [leftVersion, setLeftVersion] = useState(defaultLeft);
  const [rightVersion, setRightVersion] = useState(currentVersion);
  const [leftData, setLeftData] = useState<string | null>(null);
  const [rightData, setRightData] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The latest fetchVersion, without making it an effect dependency: callers
  // pass an inline arrow, and a new identity on every parent render would
  // refetch both versions each time.
  const fetchVersionRef = useRef(fetchVersion);
  useEffect(() => {
    fetchVersionRef.current = fetchVersion;
  }, [fetchVersion]);

  // Fetch both versions whenever the pair changes. This used to start the
  // fetch during render and let every response land: pick v1, then v2 before
  // v1 answered, and whichever finished last was shown — under v2's labels
  // when that was v1. Only the most recent request may write now.
  useEffect(() => {
    if (!open) return;
    let current = true;
    setLoading(true);
    setError(null);
    Promise.all([
      fetchVersionRef.current(leftVersion),
      fetchVersionRef.current(rightVersion),
    ])
      .then(([left, right]) => {
        if (!current) return;
        setLeftData(prettify(left));
        setRightData(prettify(right));
      })
      .catch((e: unknown) => {
        if (!current) return;
        // "" = no message of its own; the render shows a translated fallback.
        setError(e instanceof Error ? e.message : "");
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [open, leftVersion, rightVersion]);

  if (!open) return null;

  const monacoTheme = theme === "dark" ? "vs-dark" : "light";

  // The shared dialog supplies Escape, the focus trap and focus return, none of
  // which the hand-rolled overlay had despite claiming aria-modal.
  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={`${t("editor.compareVersions", "Compare Versions")} — ${typeName}`}
      testId="version-diff-dialog"
      maxWidth="max-w-[min(100rem,calc(100vw-2rem))]"
    >
      {/* A fixed height: the diff editor lays itself out to its container and
          collapses to nothing inside a content-sized box. */}
      <div className="flex h-[calc(100dvh-7rem)] flex-col">
        {/* Version selectors */}
        <div className="flex items-center gap-4 border-b border-border px-5 py-3 shrink-0 bg-muted/30">
          <VersionSelect
            label={t("editor.olderVersion", "Older")}
            versions={sortedVersions}
            value={leftVersion}
            onChange={setLeftVersion}
            side="left"
          />
          <span className="text-muted-foreground text-lg">↔</span>
          <VersionSelect
            label={t("editor.newerVersion", "Newer")}
            versions={sortedVersions}
            value={rightVersion}
            onChange={setRightVersion}
            side="right"
          />
          <button
            onClick={() => {
              setLeftVersion(rightVersion);
              setRightVersion(leftVersion);
            }}
            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary transition-colors"
            title={t("editor.swapVersions", "Swap versions")}
            aria-label={t("editor.swapVersions", "Swap versions")}
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>

        {/* Diff content */}
        <div className="flex-1 min-h-0">
          {loading && (
            <div className="flex items-center justify-center h-full">
              <RefreshCw className="h-6 w-6 animate-spin text-primary" />
            </div>
          )}

          {error !== null && (
            <div className="flex items-center justify-center h-full">
              <p className="text-sm text-destructive">
                {error || t("common.loadError", "Failed to load data")}
              </p>
            </div>
          )}

          {!loading && error === null && leftData !== null && rightData !== null && (
            <DiffEditor
              original={leftData}
              modified={rightData}
              language="json"
              theme={monacoTheme}
              options={{
                readOnly: true,
                renderSideBySide: true,
                minimap: { enabled: false },
                fontSize: 13,
                lineNumbers: "on",
                scrollBeyondLastLine: false,
                wordWrap: "on",
                renderOverviewRuler: false,
                automaticLayout: true,
              }}
            />
          )}
        </div>

        {/* Footer summary */}
        <div className="border-t border-border px-5 py-2 shrink-0 bg-muted/30">
          <p className="text-xs text-muted-foreground text-center">
            {t("editor.diffHint", "Additions shown in green, deletions in red. Left = older, Right = newer.")}
          </p>
        </div>
      </div>
    </AccessibleDialog>
  );
}

/* ─── Helpers ─── */

function prettify(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

function VersionSelect({
  label,
  versions,
  value,
  onChange,
  side,
}: {
  label: string;
  versions: { version: number; lastModifiedOn?: number }[];
  value: number;
  onChange: (v: number) => void;
  side: "left" | "right";
}) {
  return (
    <div className="flex items-center gap-2">
      <span className={`text-xs font-semibold uppercase tracking-wider ${
        side === "left" ? "text-rose-500" : "text-emerald-500"
      }`}>
        {label}
      </span>
      <div className="relative">
        <select
          value={value}
          onChange={(e) => onChange(Number(e.target.value))}
          className="appearance-none rounded-md border border-input bg-background pe-7 ps-3 py-1.5 text-sm font-medium text-foreground shadow-sm hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 cursor-pointer"
          data-testid={`diff-version-${side}`}
        >
          {versions.map((v) => (
            <option key={v.version} value={v.version}>
              v{v.version}
            </option>
          ))}
        </select>
        <ChevronDown className="pointer-events-none absolute inset-e-1.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
      </div>
    </div>
  );
}
