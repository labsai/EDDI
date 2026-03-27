import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { DiffEditor } from "@monaco-editor/react";
import {
  X,
  GitCompareArrows,
  RefreshCw,
  ChevronDown,
} from "lucide-react";
import { useTheme } from "@/components/layout/theme-provider";

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

  // Fetch both versions
  const loadVersions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [left, right] = await Promise.all([
        fetchVersion(leftVersion),
        fetchVersion(rightVersion),
      ]);
      setLeftData(prettify(left));
      setRightData(prettify(right));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load versions");
    } finally {
      setLoading(false);
    }
  }, [leftVersion, rightVersion, fetchVersion]);

  // Load on first render and when versions change
  const [lastLoaded, setLastLoaded] = useState("");
  const loadKey = `${leftVersion}-${rightVersion}`;
  if (open && loadKey !== lastLoaded) {
    setLastLoaded(loadKey);
    loadVersions();
  }

  if (!open) return null;

  const monacoTheme = theme === "dark" ? "vs-dark" : "light";

  return (
    <div className="fixed inset-0 z-50 flex flex-col" data-testid="version-diff-dialog">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />

      {/* Dialog — full screen with padding */}
      <div
        className="relative z-10 flex flex-col m-4 rounded-xl border bg-card shadow-2xl overflow-hidden flex-1"
        role="dialog"
        aria-modal="true"
        aria-labelledby="diff-dialog-title"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-5 py-3 shrink-0">
          <div className="flex items-center gap-2">
            <GitCompareArrows className="h-5 w-5 text-primary" aria-hidden="true" />
            <h3 id="diff-dialog-title" className="text-lg font-semibold text-foreground">
              {t("editor.compareVersions", "Compare Versions")} — {typeName}
            </h3>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
            aria-label={t("common.close", "Close")}
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

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

          {error && (
            <div className="flex items-center justify-center h-full">
              <p className="text-sm text-destructive">{error}</p>
            </div>
          )}

          {!loading && !error && leftData !== null && rightData !== null && (
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
    </div>
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
