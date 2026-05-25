import { useState, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  Layers,
  Search,
  RefreshCw,
  AlertCircle,
  Bot,
  Gauge,
  Tag,
  ExternalLink,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useDebounce } from "@/hooks/use-debounce";
import { useSkills, useCapabilitySearch, useSkillRegistry } from "@/hooks/use-capabilities";

const confidenceColors: Record<string, string> = {
  high: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
  medium: "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20",
  low: "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20",
};

export function CapabilitiesPage() {
  const { t } = useTranslation();
  const [searchSkill, setSearchSkill] = useState("");
  const [selectedSkill, setSelectedSkill] = useState("");
  const [strategy, setStrategy] = useState("highest_confidence");
  const [expandedSkill, setExpandedSkill] = useState<string | null>(null);

  const debouncedSearch = useDebounce(searchSkill.trim(), 400);
  const searchTerm = selectedSkill || debouncedSearch;

  const { data: allSkills, isLoading: skillsLoading, isError: skillsError, refetch: refetchSkills } = useSkills();
  const { data: matches, isLoading: searchLoading, isError: searchError, refetch: refetchSearch } = useCapabilitySearch(
    searchTerm,
    strategy,
  );
  const { registry, isLoading: registryLoading, isError: registryError, refetchMatches } = useSkillRegistry();

  const filteredSkills = useMemo(() => {
    if (!allSkills) return [];
    if (!searchSkill.trim()) return allSkills;
    const q = searchSkill.trim().toLowerCase();
    return allSkills.filter((s) => s.toLowerCase().includes(q));
  }, [allSkills, searchSkill]);

  // Filter registry rows by search too
  const filteredRegistry = useMemo(() => {
    if (!searchSkill.trim()) return registry;
    const q = searchSkill.trim().toLowerCase();
    return registry.filter((r) => r.skill.toLowerCase().includes(q));
  }, [registry, searchSkill]);

  return (
    <div className="space-y-6" data-testid="capabilities-page">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-violet-500/10">
            <Layers className="h-5 w-5 text-violet-500" />
          </div>
          {t("capabilities.title", "Capability Registry")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("capabilities.subtitle", "Discover agents by their declared skills and capabilities")}
        </p>
      </div>

      {/* Search controls */}
      <div className="flex flex-col gap-3 sm:flex-row">
        <div className="relative flex-1">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={searchSkill}
            onChange={(e) => {
              setSearchSkill(e.target.value);
              setSelectedSkill("");
            }}
            placeholder={t("capabilities.searchPlaceholder", "Search skills...")}
            className="h-10 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="capability-search"
          />
        </div>
        <select
          value={strategy}
          onChange={(e) => setStrategy(e.target.value)}
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          data-testid="capability-strategy"
        >
          <option value="highest_confidence">{t("capabilities.strategyHighest", "Highest Confidence")}</option>
          <option value="all">{t("capabilities.strategyAll", "All Matches")}</option>
        </select>
      </div>

      {/* ═══ Registry Overview Table ═══ */}
      <section>
        <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
          <Layers className="h-4 w-4 text-violet-500" />
          {t("capabilities.registryOverview", "Registry Overview")}
          {allSkills && (
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] text-primary">
              {allSkills.length} {t("capabilities.skills", "skills")}
            </span>
          )}
        </h2>

        {(skillsLoading || registryLoading) && (
          <div className="flex items-center justify-center py-12">
            <RefreshCw className="h-6 w-6 animate-spin text-primary" />
          </div>
        )}

        {skillsError && (
          <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-8">
            <AlertCircle className="h-8 w-8 text-destructive" />
            <p className="mt-2 text-sm text-destructive">{t("common.error")}</p>
            <button onClick={() => refetchSkills()} className="mt-2 text-xs text-primary hover:underline">{t("common.retry")}</button>
          </div>
        )}

        {!skillsError && registryError && (
          <div className="flex flex-col items-center justify-center rounded-xl border border-amber-500/30 bg-amber-500/5 py-4">
            <AlertCircle className="h-6 w-6 text-amber-500" />
            <p className="mt-1.5 text-xs text-amber-700 dark:text-amber-300">{t("capabilities.partialError", "Some skill queries failed")}</p>
            <button onClick={() => refetchMatches()} className="mt-1.5 text-xs text-primary hover:underline">{t("common.retry")}</button>
          </div>
        )}

        {!skillsLoading && !registryLoading && !skillsError && filteredRegistry.length > 0 && (
          <div className="overflow-hidden rounded-xl border border-border" data-testid="registry-table">
            {/* Table header */}
            <div className="grid grid-cols-[1fr_100px_120px] gap-2 border-b border-border bg-muted/50 px-4 py-2.5">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("capabilities.skillName", "Skill")}
              </span>
              <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground text-center">
                {t("capabilities.agentCount", "Agents")}
              </span>
              <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground text-center">
                {t("capabilities.confidence", "Confidence")}
              </span>
            </div>
            {/* Table rows */}
            {filteredRegistry.map((entry) => {
              const isExpanded = expandedSkill === entry.skill;
              const highCount = entry.matches.filter((m) => m.confidence === "high").length;
              const medCount = entry.matches.filter((m) => m.confidence === "medium").length;
              const lowCount = entry.matches.filter((m) => m.confidence === "low").length;

              return (
                <div key={entry.skill}>
                  <button
                    type="button"
                    onClick={() => setExpandedSkill(isExpanded ? null : entry.skill)}
                    className="grid w-full grid-cols-[1fr_100px_120px] gap-2 px-4 py-3 text-start transition-colors hover:bg-secondary/50"
                    data-testid={`registry-row-${entry.skill}`}
                  >
                    <div className="flex items-center gap-2">
                      {isExpanded
                        ? <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                        : <ChevronRight className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                      }
                      <span className="text-xs font-medium text-foreground">{entry.skill}</span>
                    </div>
                    <div className="flex items-center justify-center">
                      <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[11px] font-semibold text-primary">
                        {entry.matches.length}
                      </span>
                    </div>
                    <div className="flex items-center justify-center gap-1">
                      {highCount > 0 && (
                        <span className={cn("rounded-full border px-1.5 py-0.5 text-[9px] font-semibold", confidenceColors.high)}>
                          {highCount}H
                        </span>
                      )}
                      {medCount > 0 && (
                        <span className={cn("rounded-full border px-1.5 py-0.5 text-[9px] font-semibold", confidenceColors.medium)}>
                          {medCount}M
                        </span>
                      )}
                      {lowCount > 0 && (
                        <span className={cn("rounded-full border px-1.5 py-0.5 text-[9px] font-semibold", confidenceColors.low)}>
                          {lowCount}L
                        </span>
                      )}
                    </div>
                  </button>

                  {/* Expanded agent list */}
                  {isExpanded && (
                    <div className="border-t border-border bg-muted/30 px-4 py-3 space-y-2" data-testid={`registry-expanded-${entry.skill}`}>
                      {entry.matches.length === 0 ? (
                        <p className="text-xs text-muted-foreground">{t("capabilities.noMatches", "No agents found for this skill")}</p>
                      ) : (
                        entry.matches.map((match, mi) => (
                          <div key={`${match.agentId}-${mi}`} className="flex items-center gap-3 rounded-lg border border-border bg-card p-2.5">
                            <Bot className="h-4 w-4 text-primary shrink-0" />
                            <Link
                              to={`/manage/agentview/${match.agentId}`}
                              className="text-xs font-medium text-foreground hover:text-primary transition-colors flex items-center gap-1"
                            >
                              {match.agentId}
                              <ExternalLink className="h-3 w-3 opacity-40" />
                            </Link>
                            <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-semibold ms-auto", confidenceColors[match.confidence] ?? "bg-muted text-muted-foreground")}>
                              {match.confidence}
                            </span>
                            {match.attributes && Object.keys(match.attributes).length > 0 && (
                              <div className="flex flex-wrap gap-1">
                                {Object.entries(match.attributes).map(([k, v]) => (
                                  <span key={k} className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                                    {k}={v}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>
                        ))
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {!skillsLoading && !registryLoading && !skillsError && filteredRegistry.length === 0 && searchSkill.trim() && (
          <p className="text-sm text-muted-foreground py-4">{t("capabilities.noSkills", "No skills found")}</p>
        )}
      </section>

      {/* ═══ Skill Pills (quick select) ═══ */}
      <div>
        <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
          <Tag className="h-4 w-4 text-primary" />
          {t("capabilities.registeredSkills", "Registered Skills")}
          {allSkills && (
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] text-primary">
              {allSkills.length}
            </span>
          )}
        </h2>

        {!skillsLoading && !skillsError && allSkills && (
          <div className="flex flex-wrap gap-2" data-testid="skills-grid">
            {filteredSkills.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("capabilities.noSkills", "No skills found")}</p>
            ) : (
              filteredSkills.map((skill) => (
                <button
                  key={skill}
                  onClick={() => {
                    setSelectedSkill(skill);
                    setSearchSkill(skill);
                  }}
                  className={cn(
                    "rounded-full border px-3 py-1.5 text-xs font-medium transition-all",
                    selectedSkill === skill
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-border bg-card text-foreground hover:border-primary/30 hover:bg-primary/5",
                  )}
                  data-testid={`skill-${skill}`}
                >
                  {skill}
                </button>
              ))
            )}
          </div>
        )}
      </div>

      {/* ═══ Skill Search Results ═══ */}
      {searchTerm && (
        <div>
          <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
            <Bot className="h-4 w-4 text-primary" />
            {t("capabilities.matchingAgents", "Matching Agents")}
            {matches && (
              <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] text-primary">
                {matches.length}
              </span>
            )}
          </h2>

          {searchLoading && (
            <div className="flex items-center justify-center py-8">
              <RefreshCw className="h-6 w-6 animate-spin text-primary" />
            </div>
          )}

          {searchError && (
            <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-8">
              <AlertCircle className="h-8 w-8 text-destructive" />
              <p className="mt-2 text-sm text-destructive">{t("common.error")}</p>
              <button onClick={() => refetchSearch()} className="mt-2 text-xs text-primary hover:underline">{t("common.retry")}</button>
            </div>
          )}

          {!searchLoading && !searchError && matches && (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="capability-results">
              {matches.length === 0 ? (
                <p className="col-span-full text-center py-8 text-sm text-muted-foreground">
                  {t("capabilities.noMatches", "No agents found for this skill")}
                </p>
              ) : (
                matches.map((match, i) => (
                  <div
                    key={`${match.agentId}-${i}`}
                    className="rounded-xl border border-border bg-card p-4 space-y-3 transition-colors hover:border-primary/20"
                    data-testid={`match-${match.agentId}`}
                  >
                    <div className="flex items-center gap-2">
                      <Bot className="h-4 w-4 text-primary" />
                      <Link
                        to={`/manage/agentview/${match.agentId}`}
                        className="text-sm font-medium text-foreground hover:text-primary transition-colors truncate flex items-center gap-1"
                      >
                        {match.agentId}
                        <ExternalLink className="h-3 w-3 opacity-40" />
                      </Link>
                    </div>
                    <div className="flex items-center gap-2">
                      <Layers className="h-3.5 w-3.5 text-violet-500" />
                      <span className="text-xs text-foreground">{match.skill}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Gauge className="h-3.5 w-3.5 text-muted-foreground" />
                      <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-semibold", confidenceColors[match.confidence] ?? "bg-muted text-muted-foreground")}>
                        {match.confidence}
                      </span>
                    </div>
                    {match.attributes && Object.keys(match.attributes).length > 0 && (
                      <div className="flex flex-wrap gap-1">
                        {Object.entries(match.attributes).map(([k, v]) => (
                          <span key={k} className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                            {k}={v}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
