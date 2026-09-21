import { useTranslation } from "react-i18next";

export interface VersionInfo {
  version: number;
  lastModifiedOn?: number;
}

export interface VersionPickerProps {
  /** Available versions, sorted newest-first */
  versions: VersionInfo[];
  /** Currently selected version */
  current: number;
  /** Called when user selects a different version */
  onChange: (version: number) => void;
  /** Disable interaction */
  disabled?: boolean;
}

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString();
}

/**
 * Version dropdown for navigating between resource versions.
 * Displays version numbers with relative timestamps.
 */
export function VersionPicker({
  versions,
  current,
  onChange,
  disabled = false,
}: VersionPickerProps) {
  const { t } = useTranslation();

  if (versions.length <= 1) {
    return (
      <span
        className="inline-flex items-center gap-1.5 rounded-md bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground"
        data-testid="version-badge"
      >
        v{current}
      </span>
    );
  }

  return (
    <select
      value={current}
      onChange={(e) => onChange(Number(e.target.value))}
      disabled={disabled}
      data-testid="version-picker"
      className="rounded-md border border-input bg-background px-2.5 py-1 text-xs font-medium text-foreground shadow-sm transition-colors hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:opacity-50"
      aria-label={t("editor.versionPicker", "Select version")}
    >
      {versions.map((v) => (
        <option key={v.version} value={v.version}>
          v{v.version}
          {v.lastModifiedOn ? ` — ${formatRelativeTime(v.lastModifiedOn)}` : ""}
        </option>
      ))}
    </select>
  );
}
