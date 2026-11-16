import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AlertCircle, RefreshCw } from "lucide-react";
import { Button } from "@eddi/ui";
import { Skeleton } from "@/components/ui/skeleton";
import { useCatalogAgents, useCatalogGroups } from "@/hooks/use-catalog";
import { usePinnedGroups } from "@/hooks/use-pinned-groups";
import { catalogDisplayName, catalogMembers, safeAccentColor } from "@/lib/api/catalog";
import { AgentAvatar, HomeComposer, RecentSessions } from "./workforce-home";
import { AgentWorkforceCard } from "./agent-workforce-card";
import { WorkforceCard } from "./workforce-card";
import { CatalogEmptyState } from "./catalog-empty-state";

/**
 * The Workforce home of an end user (`eddi-user` without `eddi-editor` /
 * `eddi-admin`): the agents and teams the catalog lists for them. Reads only
 * `/catalog/*`, so nothing on it can answer 403.
 */
export function EndUserCatalog() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const agents = useCatalogAgents();
  const groups = useCatalogGroups();
  const { togglePin, isPinned } = usePinnedGroups();

  if (agents.isLoading || groups.isLoading) {
    return (
      <div className="flex-1 min-h-0 overflow-y-auto p-5 md:p-8" data-testid="catalog-loading">
        <Skeleton className="h-5 w-40 mb-4" />
        <Skeleton className="h-36 rounded-xl" />
      </div>
    );
  }

  if (agents.isError || groups.isError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 py-24" data-testid="catalog-error">
        <AlertCircle className="h-12 w-12 text-destructive/60" />
        <p className="text-sm text-muted-foreground text-center max-w-xs">
          {t("Workforce.dashboard.loadError", "Couldn't load your workspace. Please try again.")}
        </p>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            void agents.refetch();
            void groups.refetch();
          }}
        >
          <RefreshCw className="h-4 w-4" />
          {t("Workforce.dashboard.retry", "Retry")}
        </Button>
      </div>
    );
  }

  const agentList = agents.data ?? [];
  const groupList = groups.data ?? [];

  return (
    <div className="flex-1 overflow-auto p-5 md:p-8 space-y-8" data-testid="workforce-catalog">
      {agentList.length === 0 && groupList.length === 0 ? (
        <CatalogEmptyState canAuthor={false} />
      ) : (
        <>
          {/* Home (T1.4): a composer first, then the recent conversations,
              then everything the person may use. */}
          <div className="mx-auto w-full max-w-2xl space-y-6">
            <HomeComposer />
            <RecentSessions />
          </div>

          {agentList.length > 0 && (
            <section aria-label={t("Workforce.catalog.agents", "Agents")}>
              <h2 className="text-lg font-semibold text-foreground mb-3">
                {t("Workforce.catalog.agents", "Agents")}
              </h2>
              <div className="grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-2 pb-2">
                {agentList.map((agent) => (
                  <div key={agent.agentId} data-testid={`catalog-agent-${agent.agentId}`}>
                    <AgentWorkforceCard
                      name={catalogDisplayName(agent) || t("workforce.unnamed", "Unnamed Agent")}
                      agentId={agent.agentId}
                      description={agent.description}
                      avatar={agent.appearance?.avatar || agent.appearance?.accentColor ? <AgentAvatar agent={agent} /> : undefined}
                      accentColor={safeAccentColor(agent.appearance?.accentColor)}
                      onClick={() =>
                        navigate(
                          `/workforce/chat?agentId=${encodeURIComponent(agent.agentId)}&agentName=${encodeURIComponent(agent.name || agent.agentId)}`,
                        )
                      }
                    />
                  </div>
                ))}
              </div>
            </section>
          )}

          {groupList.length > 0 && (
            <section aria-label={t("Workforce.catalog.teams", "Teams")}>
              <h2 className="text-lg font-semibold text-foreground mb-3">
                {t("Workforce.catalog.teams", "Teams")}
              </h2>
              <div className="@container/br-dash">
                <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
                  {groupList.map((group) => (
                    <div key={group.groupId} className="group relative" data-testid={`catalog-group-${group.groupId}`}>
                      <WorkforceCard
                        id={group.groupId}
                        name={group.name ?? ""}
                        description={group.description ?? undefined}
                        style={group.style ?? undefined}
                        members={catalogMembers(group)}
                        version={group.version}
                        viewMode="grid"
                        isPinned={isPinned(group.groupId)}
                        onTogglePin={() => togglePin(group.groupId)}
                        canManage={false}
                      />
                    </div>
                  ))}
                </div>
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}

/**
 * For an author's dashboard: how many agents and teams end users are offered,
 * and — when that is none — how to list one. Without it an editor has no way to
 * see from Workforce what the people they build for actually see.
 */
export function CatalogPreview() {
  const { t } = useTranslation();
  const agents = useCatalogAgents();
  const groups = useCatalogGroups();
  if (agents.isLoading || groups.isLoading || agents.isError || groups.isError) return null;
  const agentCount = agents.data?.length ?? 0;
  const groupCount = groups.data?.length ?? 0;

  return (
    <section aria-label={t("Workforce.catalog.previewTitle", "What end users see")} data-testid="catalog-preview">
      <h2 className="text-lg font-semibold text-foreground mb-1">
        {t("Workforce.catalog.previewTitle", "What end users see")}
      </h2>
      {agentCount === 0 && groupCount === 0 ? (
        <CatalogEmptyState canAuthor />
      ) : (
        <p className="text-sm text-muted-foreground" data-testid="catalog-preview-counts">
          {t("Workforce.catalog.previewCounts", "{{agents}} listed agents and {{teams}} listed teams are offered to the people allowed to use them.", {
            agents: agentCount,
            teams: groupCount,
          })}
        </p>
      )}
    </section>
  );
}
