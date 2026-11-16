import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueries } from "@tanstack/react-query";
import { ArrowUpRight, ChevronDown, ChevronUp, Globe, Plug, Wrench, Database, Boxes } from "lucide-react";
import { getDescriptor } from "@/lib/api/descriptors";
import { collectWorkflowToolRefs, type ToolResourceRef } from "@/lib/agent-tool-refs";
import type { WorkflowConfiguration } from "@/lib/api/workflows";
import type { LlmTask } from "@/components/editors/llm/types";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** How many resource rows show before "Show all". */
const COLLAPSED_ROWS = 4;

interface ActiveToolsListProps {
  task: LlmTask | undefined;
  /** Every workflow of the agent, in order; an entry is undefined while it loads. */
  workflows: readonly (WorkflowConfiguration | undefined)[];
  /** True while any of the agent's workflows is still loading. */
  workflowsLoading?: boolean;
  enableMemoryTools?: boolean;
}

function ResourceIcon({ kind }: { kind: ToolResourceRef["kind"] }) {
  const Icon = kind === "mcp" ? Plug : Globe;
  return <Icon className="h-3.5 w-3.5 shrink-0 text-sky-400" aria-hidden />;
}

/**
 * The tools the agent's LLM actually receives, as the backend assembles them:
 *
 * - HTTP and MCP tool sets from the `eddi://ai.labs.httpcalls` and
 *   `eddi://ai.labs.mcpcalls` steps of every workflow of the agent, unless the
 *   task switches the category off (`enableHttpCallTools` /
 *   `enableMcpCallTools` default to on). Each is shown by its descriptor name and
 *   links to its editor. The task's own `tools` list is deliberately ignored —
 *   the backend never resolves it.
 * - Built-in tools, only when `enableBuiltInTools` is on: the whitelist, or
 *   "all" without one.
 * - Memory tools, from the agent.
 *
 * Capabilities are not tools and have their own card in the details panel.
 */
export function ActiveToolsList({ task, workflows, workflowsLoading, enableMemoryTools }: ActiveToolsListProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const httpOn = task?.enableHttpCallTools !== false;
  const mcpOn = task?.enableMcpCallTools !== false;
  const refs = useMemo(
    () => collectWorkflowToolRefs(workflows, { http: httpOn, mcp: mcpOn }),
    [workflows, httpOn, mcpOn],
  );

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

  // The backend ignores the whitelist unless built-ins are switched on.
  const builtInsOn = task?.enableBuiltInTools === true;
  const builtIns = builtInsOn ? (task?.builtInToolsWhitelist?.filter(Boolean) ?? []) : [];
  const allBuiltIns = builtInsOn && builtIns.length === 0;

  const switches: { key: string; label: string; icon: typeof Globe }[] = [];
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

      {total === 0 && workflowsLoading && (
        <div className="space-y-1.5" data-testid="active-tools-loading">
          <Skeleton className="h-3 w-full" />
          <Skeleton className="h-3 w-2/3" />
        </div>
      )}

      {total === 0 && !workflowsLoading && (
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
                  <ResourceIcon kind={ref.kind} />
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
                    <Link to={`/manage/resources/${ref.slug}/${ref.id}?version=${ref.version}`} className={cn(rowClass, "hover:bg-muted/50 transition-colors")}>
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
