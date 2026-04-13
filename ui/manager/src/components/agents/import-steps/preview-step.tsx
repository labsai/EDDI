import { Fragment } from "react";
import { useTranslation } from "react-i18next";
import {
  FileArchive,
  ArrowLeft,
  ArrowUp,
  ArrowDown,
  Check,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
import { ActionBadge } from "@/components/shared/action-badge";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import type { ImportPreview } from "@/lib/api/backup";

type Strategy = "create" | "merge" | "upgrade" | "sync";

interface PreviewStepProps {
  preview: ImportPreview;
  strategy: Strategy;
  selected: Set<string>;
  expandedDiff: string | null;
  workflowOrder: string[];
  isLoading: boolean;
  error: string | null;
  onToggleResource: (sourceId: string) => void;
  onToggleAll: () => void;
  onExpandDiff: (sourceId: string | null) => void;
  onMoveWorkflow: (id: string, dir: -1 | 1) => void;
  onBack: () => void;
  onExecute: () => void;
}

export function PreviewStep({
  preview,
  strategy,
  selected,
  expandedDiff,
  workflowOrder,
  isLoading,
  error,
  onToggleResource,
  onToggleAll,
  onExpandDiff,
  onMoveWorkflow,
  onBack,
  onExecute,
}: PreviewStepProps) {
  const { t } = useTranslation();
  const showMatch = strategy === "upgrade" || strategy === "sync";

  return (
    <div className="flex-1 flex flex-col space-y-4 min-h-0">
      <div className="flex items-center gap-2">
        <FileArchive className="h-5 w-5 text-primary shrink-0" />
        <p className="text-sm font-semibold text-foreground">
          {preview.sourceAgentName || preview.sourceAgentId || "Agent"}
          {preview.targetAgentName && (
            <span className="text-muted-foreground font-normal">
              {" → "}
              {preview.targetAgentName}
            </span>
          )}
        </p>
      </div>

      {/* Resource table */}
      <div className="flex-1 overflow-auto rounded-lg border min-h-0">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-secondary/80 backdrop-blur-sm">
            <tr>
              <th className="px-3 py-2 text-start w-8">
                <input
                  type="checkbox"
                  checked={selected.size === preview.resources.length}
                  onChange={onToggleAll}
                  className="accent-primary"
                />
              </th>
              <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.resource", "Resource")}
              </th>
              <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.type", "Type")}
              </th>
              {showMatch && (
                <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                  {t("importDialog.match", "Match")}
                </th>
              )}
              <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.action", "Action")}
              </th>
              {showMatch && (
                <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase w-8" />
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {preview.resources.map((r) => (
              <PreviewRow
                key={r.sourceId}
                resource={r}
                checked={selected.has(r.sourceId)}
                onToggle={() => onToggleResource(r.sourceId)}
                showMatch={showMatch}
                showDiff={showMatch}
                expanded={expandedDiff === r.sourceId}
                onExpand={() =>
                  onExpandDiff(expandedDiff === r.sourceId ? null : r.sourceId)
                }
                workflowOrder={workflowOrder}
                onMoveWorkflow={onMoveWorkflow}
              />
            ))}
          </tbody>
        </table>
      </div>

      {/* Summary */}
      <div className="flex gap-3 text-xs text-muted-foreground">
        <span>
          {preview.resources.filter((r) => r.action === "CREATE").length}{" "}
          {t("importDialog.new", "new")}
        </span>
        <span>
          {preview.resources.filter((r) => r.action === "UPDATE").length}{" "}
          {t("importDialog.updated", "updated")}
        </span>
        <span>
          {preview.resources.filter((r) => r.action === "SKIP").length}{" "}
          {t("importDialog.unchanged", "unchanged")}
        </span>
        <span>
          {selected.size} {t("importDialog.selected", "selected")}
        </span>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <div className="flex justify-between pt-2">
        <Button
          variant="ghost"
          onClick={onBack}
          disabled={isLoading}
        >
          <ArrowLeft className="h-4 w-4" />
          {t("common.back", "Back")}
        </Button>
        <Button
          onClick={onExecute}
          disabled={isLoading || selected.size === 0}
          data-testid="import-confirm-merge"
        >
          {strategy === "sync"
            ? t("importDialog.syncNow", "Sync Now")
            : strategy === "upgrade"
              ? t("importDialog.upgradeNow", "Upgrade Now")
              : t("importDialog.mergeNow", "Import Selected")}
          <Check className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

/* ─── Helper sub-components ─── */

function MatchBadge({ strategy }: { strategy: string | null }) {
  if (!strategy) return null;
  const labels: Record<string, string> = {
    position: "pos",
    type: "type",
    name: "name",
    originId: "ID",
  };
  return (
    <span className="inline-flex rounded-full bg-secondary px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
      {labels[strategy] || strategy}
    </span>
  );
}

function PreviewRow({
  resource,
  checked,
  onToggle,
  showMatch,
  showDiff,
  expanded,
  onExpand,
  workflowOrder,
  onMoveWorkflow,
}: {
  resource: ImportPreview["resources"][0];
  checked: boolean;
  onToggle: () => void;
  showMatch: boolean;
  showDiff: boolean;
  expanded: boolean;
  onExpand: () => void;
  workflowOrder: string[];
  onMoveWorkflow: (id: string, dir: -1 | 1) => void;
}) {
  const hasDiff = resource.action === "UPDATE" && (resource.sourceContent || resource.targetContent);
  const isCreateWorkflow = resource.resourceType === "workflow" && resource.action === "CREATE";
  const wfIdx = workflowOrder.indexOf(resource.sourceId);

  return (
    <Fragment>
      <tr className={`transition-colors ${checked ? "bg-primary/5" : ""}`}>
        <td className="px-3 py-2">
          <input
            type="checkbox"
            checked={checked}
            onChange={onToggle}
            className="accent-primary"
          />
        </td>
        <td className="px-3 py-2 font-medium text-foreground">
          <div className="flex items-center gap-1.5">
            {resource.name || resource.sourceId.substring(0, 12)}
            {isCreateWorkflow && wfIdx >= 0 && (
              <span className="flex items-center gap-0.5 ms-1">
                <button
                  onClick={() => onMoveWorkflow(resource.sourceId, -1)}
                  disabled={wfIdx === 0}
                  className="rounded p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30"
                  title="Move up"
                >
                  <ArrowUp className="h-3 w-3" />
                </button>
                <button
                  onClick={() => onMoveWorkflow(resource.sourceId, 1)}
                  disabled={wfIdx === workflowOrder.length - 1}
                  className="rounded p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30"
                  title="Move down"
                >
                  <ArrowDown className="h-3 w-3" />
                </button>
              </span>
            )}
          </div>
        </td>
        <td className="px-3 py-2">
          <ResourceTypeBadge type={resource.resourceType} />
        </td>
        {showMatch && (
          <td className="px-3 py-2">
            <MatchBadge strategy={resource.matchStrategy} />
          </td>
        )}
        <td className="px-3 py-2">
          <ActionBadge action={resource.action} />
        </td>
        {showDiff && (
          <td className="px-3 py-2">
            {hasDiff && (
              <button
                onClick={onExpand}
                className="rounded p-0.5 text-muted-foreground hover:text-foreground"
              >
              {expanded ? (
                <ChevronDown className="h-4 w-4" />
              ) : (
                <ChevronRight className="h-4 w-4" />
              )}
              </button>
            )}
          </td>
        )}
      </tr>
      {expanded && hasDiff && (
        <tr>
          <td colSpan={showMatch ? 6 : 5} className="px-3 py-2">
            <ResourceDiffViewer
              sourceContent={resource.sourceContent}
              targetContent={resource.targetContent}
            />
          </td>
        </tr>
      )}
    </Fragment>
  );
}
