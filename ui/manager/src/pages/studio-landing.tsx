import { useMemo } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAgentDescriptors, parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { Bot, Sparkles, ArrowRight, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

export function StudioLandingPage() {
  const { t } = useTranslation();

  const { data: descriptors, isLoading } = useQuery({
    queryKey: ["studio", "agents"],
    queryFn: () => getAgentDescriptors(100, 0, ""),
    staleTime: 60_000,
  });

  // Deduplicate by name, keep latest version
  const agents = useMemo(() => {
    if (!descriptors) return [];
    const grouped = new Map<string, AgentDescriptor & { id: string; version: number }>();
    for (const agent of descriptors) {
      const { id, version } = parseResourceUri(agent.resource);
      const existing = grouped.get(agent.name);
      if (!existing || version > existing.version) {
        grouped.set(agent.name, { ...agent, id, version });
      }
    }
    return Array.from(grouped.values());
  }, [descriptors]);

  return (
    <div className="space-y-6" data-testid="studio-landing">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary/10">
          <Sparkles className="h-6 w-6 text-primary" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            {t("studio.title", "Agent Studio")}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t("studio.landingDescription", "Select an agent to open the visual pipeline editor, debugger, and live chat")}
          </p>
        </div>
      </div>

      {/* Agent grid */}
      {isLoading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : agents.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-xl border bg-card py-16">
          <Bot className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-3 text-sm text-muted-foreground">
            {t("studio.noAgents", "No agents found — create one from the Agents page")}
          </p>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {agents.map((agent) => (
            <Link
              key={agent.id}
              to={`/manage/studio/${agent.id}`}
              className={cn(
                "group flex items-center gap-3 rounded-xl border bg-card p-4 transition-all",
                "hover:border-primary/40 hover:shadow-md hover:shadow-primary/5",
              )}
              data-testid={`studio-agent-${agent.id}`}
            >
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 group-hover:bg-primary/15 transition-colors">
                <Bot className="h-5 w-5 text-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-foreground truncate">
                  {agent.name}
                </p>
                {agent.description && (
                  <p className="text-xs text-muted-foreground truncate">
                    {agent.description}
                  </p>
                )}
                <p className="text-[10px] text-muted-foreground/60 mt-0.5">
                  v{agent.version}
                </p>
              </div>
              <ArrowRight className="h-4 w-4 shrink-0 text-muted-foreground/30 group-hover:text-primary transition-colors" />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
