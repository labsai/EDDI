import { useTranslation } from "react-i18next";
import { ChevronRight } from "lucide-react";
import type { AgentStat } from "@/hooks/use-workforce-analytics";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";

interface AgentLeaderboardProps {
  agents: AgentStat[];
  onAgentClick?: (agentId: string) => void;
}

function AgentLeaderboard({ agents, onAgentClick }: AgentLeaderboardProps) {
  const { t } = useTranslation();
  const top = agents.slice(0, 10);
  const maxContributions = Math.max(...top.map((a) => a.contributions), 1);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.agentLeaderboard", "Agent Leaderboard")}
      </h3>

      {top.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("analyticsPage.noAgents", "No agent activity yet.")}
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">
              {t("analyticsPage.agentLeaderboard", "Agent Leaderboard")}
            </caption>
            <thead>
              <tr className="border-b border-border text-xs text-muted-foreground">
                <th className="pb-2 pe-2 text-start font-medium">#</th>
                <th className="pb-2 pe-3 text-start font-medium">
                  {t("analyticsPage.agentName", "Agent")}
                </th>
                <th className="pb-2 pe-3 text-end font-medium">
                  {t("analyticsPage.sessions", "Sessions")}
                </th>
                <th className="pb-2 pe-3 text-start font-medium">
                  {t("analyticsPage.contributions", "Contributions")}
                </th>
                <th className="pb-2 text-end font-medium">
                  {t("analyticsPage.errors", "Errors")}
                </th>
                <th className="pb-2 w-6">
                  <span className="sr-only">{t("analyticsPage.actions", "Actions")}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {top.map((agent, idx) => {
                const pct = Math.round(
                  (agent.contributions / maxContributions) * 100,
                );
                return (
                  <tr
                    key={agent.agentId}
                    className="cursor-pointer border-b border-border/50 transition-colors last:border-0 hover:bg-muted/50"
                    onClick={() => onAgentClick?.(agent.agentId)}
                  >
                    <td className="py-2.5 pe-2 text-muted-foreground">
                      {idx + 1}
                    </td>
                    <td className="py-2.5 pe-3">
                      <div className="flex items-center gap-2">
                        <AdvisorAvatar
                          name={agent.displayName}
                          agentId={agent.agentId}
                          size="sm"
                        />
                        <span className="truncate font-medium">
                          {agent.displayName}
                        </span>
                      </div>
                    </td>
                    <td className="py-2.5 pe-3 text-end tabular-nums">
                      {agent.sessions}
                    </td>
                    <td className="py-2.5 pe-3">
                      <div className="flex items-center gap-2">
                        <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                          <div
                            className="bg-primary/30 h-2 rounded-full transition-all duration-700"
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                        <span className="w-8 text-end text-xs tabular-nums text-muted-foreground">
                          {agent.contributions}
                        </span>
                      </div>
                    </td>
                    <td className="py-2.5 text-end tabular-nums">
                      {agent.errors}
                    </td>
                    <td className="py-2.5 ps-2">
                      <ChevronRight className="h-4 w-4 text-muted-foreground" />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export { AgentLeaderboard };
export type { AgentLeaderboardProps };
