import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { useSkillRegistry } from "@/hooks/use-capabilities";
import { Skeleton } from "@/components/ui/skeleton";
import { Shield, AlertTriangle } from "lucide-react";

// ─── Component ───────────────────────────────────────────────────

function SkillCoverage() {
  const { t } = useTranslation();
  const { registry, isLoading } = useSkillRegistry();

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
        <Skeleton className="h-5 w-36 mb-4" />
        <div className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      </div>
    );
  }

  const totalSkills = registry.length;
  const coveredSkills = registry.filter(
    (s) => s.matches.length > 0,
  ).length;
  const uncoveredSkills = registry.filter(
    (s) => s.matches.length === 0,
  );
  const highConfidence = registry.filter((s) =>
    s.matches.some((m) => m.confidence === "high"),
  ).length;

  // Skill bars sorted by agent count (descending)
  const sortedSkills = [...registry].sort(
    (a, b) => b.matches.length - a.matches.length,
  );
  const maxAgents = Math.max(...sortedSkills.map((s) => s.matches.length), 1);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <div className="mb-4 flex items-center gap-2">
        <Shield className="h-4 w-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">
          {t("analyticsPage.skillCoverage", "Skill Coverage")}
        </h3>
      </div>

      {totalSkills === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t(
            "analyticsPage.noSkills",
            "No capabilities registered. Define skills on your agents to track coverage.",
          )}
        </p>
      ) : (
        <>
          {/* Summary stats */}
          <div className="grid grid-cols-3 gap-4 mb-5">
            <div>
              <p className="text-lg font-bold">{totalSkills}</p>
              <p className="text-xs text-muted-foreground">
                {t("analyticsPage.totalSkills", "Skills")}
              </p>
            </div>
            <div>
              <p className="text-lg font-bold">
                {totalSkills > 0
                  ? Math.round((coveredSkills / totalSkills) * 100)
                  : 0}
                %
              </p>
              <p className="text-xs text-muted-foreground">
                {t("analyticsPage.covered", "Covered")}
              </p>
            </div>
            <div>
              <p className="text-lg font-bold">{highConfidence}</p>
              <p className="text-xs text-muted-foreground">
                {t("analyticsPage.highConfidence", "High Confidence")}
              </p>
            </div>
          </div>

          {/* Skill bars */}
          <div className="space-y-2">
            {sortedSkills.slice(0, 8).map((entry) => {
              const pct =
                maxAgents > 0
                  ? Math.round((entry.matches.length / maxAgents) * 100)
                  : 0;
              const hasHighConf = entry.matches.some(
                (m) => m.confidence === "high",
              );
              return (
                <div key={entry.skill} className="flex items-center gap-3">
                  <span
                    className="w-28 shrink-0 truncate text-xs"
                    title={entry.skill}
                  >
                    {entry.skill}
                  </span>
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                    <div
                      className={cn(
                        "h-2 rounded-full transition-all duration-700",
                        entry.matches.length === 0
                          ? "bg-destructive/40"
                          : hasHighConf
                            ? "bg-primary"
                            : "bg-primary/40",
                      )}
                      style={{ width: `${Math.max(pct, 2)}%` }}
                    />
                  </div>
                  <span className="w-6 text-end text-xs tabular-nums text-muted-foreground">
                    {entry.matches.length}
                  </span>
                </div>
              );
            })}
          </div>

          {/* Uncovered skills warning */}
          {uncoveredSkills.length > 0 && (
            <div className="mt-4 flex items-start gap-2 rounded-lg bg-destructive/5 p-3">
              <AlertTriangle className="h-4 w-4 shrink-0 text-destructive mt-0.5" />
              <div>
                <p className="text-xs font-medium text-destructive">
                  {t(
                    "analyticsPage.uncoveredSkills",
                    "{{count}} uncovered skills",
                    { count: uncoveredSkills.length },
                  )}
                </p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {uncoveredSkills
                    .slice(0, 3)
                    .map((s) => s.skill)
                    .join(", ")}
                  {uncoveredSkills.length > 3 && ` +${uncoveredSkills.length - 3} more`}
                </p>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

export { SkillCoverage };
