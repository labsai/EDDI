import { useState } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { FileStack, ChevronDown, ChevronUp, History } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { SharedArtifact } from "@/lib/api/groups";

interface ArtifactsPanelProps {
  artifacts: SharedArtifact[];
  className?: string;
}

const TYPE_ICON: Record<SharedArtifact["type"], string> = {
  TEXT: "📄",
  MARKDOWN: "📝",
  JSON: "🧾",
};

/**
 * The discussion's shared artifacts (I17, blackboard-lite) — read-only:
 * there is no REST endpoint for a human to write an artifact, only the
 * member-facing tools can. Only ever populated by the single-conversation
 * fetch (`getGroupConversation`), never list/discuss/followup/continue/close —
 * see `GroupConversation.artifacts`'s own doc comment.
 */
export function ArtifactsPanel({ artifacts, className }: ArtifactsPanelProps) {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);

  if (artifacts.length === 0) return null;

  return (
    <div className={cn("rounded-xl border border-sky-500/30 bg-sky-500/5 p-4", className)} data-testid="artifacts-panel">
      <h3 className="mb-3 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <FileStack className="h-4 w-4 text-sky-600 dark:text-sky-400" aria-hidden="true" />
        {t("groups.artifactsTitle", "Shared Artifacts ({{count}})", { count: artifacts.length })}
      </h3>
      <div className="space-y-1.5">
        {artifacts.map((artifact) => {
          const isExpanded = expandedId === artifact.id;
          // The backend always sends `history`, but a panel is not worth a
          // white screen if a future/older shape omits it — same reasoning as
          // `entryTypeInfo` for transcript entry types.
          const revisionCount = artifact.history?.length ?? 0;
          return (
            <div
              key={artifact.id}
              className="overflow-hidden rounded-lg border border-border bg-background/60"
              data-testid={`artifact-${artifact.id}`}
            >
              <button
                type="button"
                onClick={() => setExpandedId(isExpanded ? null : artifact.id)}
                className="flex w-full items-center gap-2 p-2.5 text-start hover:bg-secondary/30 transition-colors"
                aria-expanded={isExpanded}
                data-testid={`artifact-toggle-${artifact.id}`}
              >
                <span aria-hidden="true">{TYPE_ICON[artifact.type] ?? "📄"}</span>
                <span className="min-w-0 flex-1 truncate text-xs font-medium text-foreground" title={artifact.name}>
                  {artifact.name}
                </span>
                <Badge
                  variant={artifact.status === "FINAL" ? "success" : "secondary"}
                  className="shrink-0 text-[9px] px-1.5 py-0"
                >
                  {artifact.status === "FINAL"
                    ? t("groups.artifactFinal", "Final")
                    : t("groups.artifactDraft", "Draft")}
                </Badge>
                <span className="shrink-0 font-mono text-[10px] text-muted-foreground">v{artifact.version}</span>
                {revisionCount > 0 && (
                  <span
                    className="flex shrink-0 items-center gap-0.5 text-[10px] text-muted-foreground"
                    title={t("groups.artifactRevisions", {
                      defaultValue: "{{count}} prior revision",
                      defaultValue_other: "{{count}} prior revisions",
                      count: revisionCount,
                    })}
                  >
                    <History className="h-2.5 w-2.5" aria-hidden="true" />
                    {revisionCount}
                  </span>
                )}
                {isExpanded ? (
                  <ChevronUp className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                ) : (
                  <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                )}
              </button>

              {isExpanded && (
                <div className="border-t border-border p-2.5" data-testid={`artifact-content-${artifact.id}`}>
                  {artifact.lastEditorAgentId && (
                    <p className="mb-1.5 text-[10px] text-muted-foreground">
                      {t("groups.artifactLastEdited", "Last edited by {{agent}}", { agent: artifact.lastEditorAgentId })}
                    </p>
                  )}
                  {artifact.type === "JSON" ? (
                    // Pretty-printed in a plain <pre> rather than a Monaco
                    // instance: this is a read-only view, so a full editor buys
                    // nothing and costs a heavyweight dependency on the
                    // transcript path — which every group discussion renders.
                    <pre
                      className="max-h-72 overflow-auto rounded-md bg-background p-2.5 font-mono text-[11px] leading-relaxed text-foreground"
                      data-testid={`artifact-json-${artifact.id}`}
                    >
                      {formatJsonSafely(artifact.content)}
                    </pre>
                  ) : artifact.type === "MARKDOWN" ? (
                    <div className="prose prose-sm dark:prose-invert max-w-none rounded-md bg-background p-2.5 text-xs">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{artifact.content}</ReactMarkdown>
                    </div>
                  ) : (
                    <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-md bg-background p-2.5 text-xs text-foreground">
                      {artifact.content}
                    </pre>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** A stale/corrupt artifact must still render — never let a JSON.parse failure blank the panel. */
function formatJsonSafely(content: string): string {
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}
