import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import type { SharedArtifact } from "@eddi/core/groups";
import { ArtifactVersions } from "@eddi/conversation/team";

/**
 * The board's shared-artifacts panel (T2.6), loaded when first opened: the
 * session's artifacts, one opened, its versions. The board owns the slide-over
 * (scrim, Escape, returning focus to the trigger); this is its content, and it
 * takes focus itself once loaded, since the board's own focus pass ran before
 * the chunk arrived.
 */
export function ArtifactsDrawer({
  artifacts,
  memberDisplayNames,
  onClose,
}: {
  artifacts: readonly SharedArtifact[];
  memberDisplayNames?: Record<string, string> | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  return (
    <div className="flex h-full flex-col" data-testid="artifacts-drawer">
      <div className="flex items-center justify-between border-b border-border p-3">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("Workforce.board.artifactsTitle", "Shared artifacts")}
        </h3>
        <button
          ref={closeRef}
          type="button"
          onClick={onClose}
          className="rounded p-1 text-muted-foreground transition-colors hover:bg-secondary/50 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={t("Workforce.board.artifactsClose", "Close shared artifacts")}
          data-testid="artifacts-drawer-close"
        >
          <X className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>
      <p className="border-b border-border px-3 py-2 text-[11px] text-muted-foreground">
        {t(
          "Workforce.board.artifactsHint",
          "The team's members write these with their tools. You can read every version the server still keeps.",
        )}
      </p>
      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        <ArtifactVersions artifacts={artifacts} memberDisplayNames={memberDisplayNames} />
      </div>
    </div>
  );
}
