import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueries } from "@tanstack/react-query";
import { ArrowUpRight, ChevronDown, ChevronUp, Globe, Plug, Wrench, Database, Boxes } from "lucide-react";
import { getDescriptor } from "@/lib/api/descriptors";
import { parseToolResourceRefs } from "@/lib/agent-tool-refs";
import type { LlmTask } from "@/components/editors/llm/types";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** How many resource rows show before "Show all". */
const COLLAPSED_ROWS = 4;

interface ActiveToolsListProps {
  task: LlmTask | undefined;
  enableMemoryTools?: boolean;
}

function ResourceIcon({ slug }: { slug: string | null }) {
  const Icon = slug === "apicalls" ? Globe : slug === "mcpcalls" ? Plug : Wrench;
  return <Icon className="h-3.5 w-3.5 shrink-0 text-sky-400" aria-hidden />;
}

/**
 * The agent's tools, grouped by where they come from and readable at a glance:
 * tool resources by their descriptor name (linked to their editor), built-ins
 * as short chips, and the "whole category" switches as their own chips.
 *
 * Capabilities are not tools and have their own card in the details panel, so
 * they are not repeated here.
 */
export function ActiveToolsList({ task, enableMemoryTools }: ActiveToolsListProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const refs = useMemo(() => parseToolResourceRefs(task?.tools), [task?.tools]);

  // One descriptor read per tool resource; react-query caches them by id, and a
  // failed read falls back to the id — an unresolved name is not a broken tool.
  const descriptorQueries = useQueries({
    queries: refs.map(({ id, version }) => ({
      queryKey: ["descriptor", id, version],
      queryFn: () => getDescriptor(id, version),
      enabled: Boolean(id),
      staleTime: 5 * 60_000,
      retry: false,
    })),
  });

  const builtIns = task?.builtInToolsWhitelist?.filter(Boolean) ?? [];
  const allBuiltIns = !!task?.enableBuiltInTools && builtIns.length === 0;

  const switches: { key: string; label: string; icon: typeof Globe }[] = [];
  if (task?.enableHttpCallTools)
    switches.push({ key: "http", label: t("Workforce.agentEditor.toolsWorkflowHttp", "Workflow HTTP calls"), icon: Globe });
  if (task?.enableMcpCallTools)
    switches.push({ key: "mcp", label: t("Workforce.agentEditor.toolsWorkflowMcp", "Workflow MCP tools"), icon: Plug });
  if (enableMemoryTools)
    switches.push({ key: "memory", label: t("Workforce.agentEditor.toolsMemory", "Memory"), icon: Database });

  const total = refs.length + builtIns.length + (allBuiltIns ? 1 : 0) + switches.length;
  const visibleRefs = expanded ? refs : refs.slice(0, COLLAPSED_ROWS);
  const hiddenCount = refs.length - visibleRefs.length;

  return (
    <div className="space-y-2 pt-2 border-t border-border/40 min-w-0" data-testid="active-tools">
      <div className="flex items-center justify-between">
        <span className="text-muted-foreground flex items-center gap-1 text-[11px]">
          <Wrench className="h-3 w-3 text-sky-400" />
          {t("Workforce.agentEditor.activeTools", "Active Tools")}
        </span>
        {total > 0 && (
          <span
            className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-1.5 py-0.5"
            data-testid="active-tools-count"
          >
            {total}
          </span>
        )}
      </div>

      {total === 0 && (
        <span className="text-[11px] text-muted-foreground italic" data-testid="active-tools-empty">
          {t("Workforce.agentEditor.noTools", "None configured")}
        </span>
      )}

      {refs.length > 0 && (
        <div className="space-y-1">
          <p className="text-[10px] uppercase tracking-wider text-muted-foreground/80">
            {t("Workforce.agentEditor.toolsResources", "Connected tool sets")}
          </p>
          <ul className="rounded-md border border-border/50 divide-y divide-border/40 overflow-hidden">
            {visibleRefs.map((ref, idx) => {
              const query = descriptorQueries[idx];
              const name = query?.data?.name?.trim() || null;
              const label = name ?? `${ref.id.slice(0, 10)}…`;
              const content = (
                <>
                  <ResourceIcon slug={ref.slug} />
                  {query?.isLoading ? (
                    <Skeleton className="h-3 flex-1" />
                  ) : (
                    <span className={cn("flex-1 min-w-0 truncate", !name && "font-mono text-muted-foreground")}>
                      {label}
                    </span>
                  )}
                  <span className="shrink-0 font-mono text-[10px] text-muted-foreground">v{ref.version}</span>
                  {ref.slug && (
                    <ArrowUpRight className="h-3 w-3 shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" aria-hidden />
                  )}
                </>
              );
              const rowClass = "group flex items-center gap-2 px-2 py-1.5 text-[11px] text-foreground min-w-0";
              return (
                <li key={`${ref.id}@${ref.version}`} title={name ? `${name}\n${ref.uri}` : ref.uri} data-testid={`active-tool-${ref.id}`}>
                  {ref.slug ? (
                    <Link to={`/manage/resources/${ref.slug}/${ref.id}`} className={cn(rowClass, "hover:bg-muted/50 transition-colors")}>
                      {content}
                    </Link>
                  ) : (
                    <div className={rowClass}>{content}</div>
                  )}
                </li>
              );
            })}
          </ul>
          {refs.length > COLLAPSED_ROWS && (
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              className="flex items-center gap-1 text-[10px] text-primary hover:underline font-medium"
              data-testid="active-tools-toggle"
            >
              {expanded ? (
                <>
                  <ChevronUp className="h-3 w-3" /> {t("Workforce.agentEditor.toolsShowFewer", "Show fewer")}
                </>
              ) : (
                <>
                  <ChevronDown className="h-3 w-3" />{" "}
                  {t("Workforce.agentEditor.toolsShowMore", "Show {{hidden}} more", { hidden: hiddenCount })}
                </>
              )}
            </button>
          )}
        </div>
      )}

      {(builtIns.length > 0 || allBuiltIns) && (
        <div className="space-y-1">
          <p className="text-[10px] uppercase tracking-wider text-muted-foreground/80">
            {t("Workforce.agentEditor.toolsBuiltIn", "Built-in")}
          </p>
          <div className="flex flex-wrap gap-1" data-testid="active-tools-builtin">
            {allBuiltIns ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-border/50 bg-muted/40 px-2 py-0.5 text-[10px] text-foreground">
                <Boxes className="h-3 w-3 text-sky-400" aria-hidden />
                {t("Workforce.agentEditor.toolsAllBuiltIn", "All built-in tools")}
              </span>
            ) : (
              builtIns.map((tool) => (
                <span
                  key={tool}
                  className="max-w-full truncate rounded-full border border-border/50 bg-muted/40 px-2 py-0.5 font-mono text-[10px] text-foreground"
                  title={tool}
                >
                  {tool}
                </span>
              ))
            )}
          </div>
        </div>
      )}

      {switches.length > 0 && (
        <div className="flex flex-wrap gap-1" data-testid="active-tools-switches">
          {switches.map(({ key, label, icon: Icon }) => (
            <span
              key={key}
              className="inline-flex items-center gap-1 rounded-full border border-sky-500/20 bg-sky-500/10 px-2 py-0.5 text-[10px] text-sky-400"
            >
              <Icon className="h-3 w-3" aria-hidden />
              {label}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
