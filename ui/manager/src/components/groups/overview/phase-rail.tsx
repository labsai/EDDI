import { useTranslation } from "react-i18next";
import { Check, CircleDot, Lock, ShieldQuestion } from "lucide-react";
import { cn } from "@/lib/utils";
import type { DigestPhase } from "@/hooks/use-discussion-digest";

interface PhaseRailProps {
  phases: DigestPhase[];
  /** Called with a phase index when the reader wants to read that phase's turns. */
  onSelectPhase?: (phaseIndex: number) => void;
  className?: string;
}

/**
 * The discussion's spine: every phase, what it is, how far it got, and which
 * members have spoken in it.
 *
 * Laid out as a responsive grid rather than a horizontally scrolled strip. A
 * strip reads better on a wide screen, but it hides the later phases behind a
 * scroll the reader has no reason to suspect — and "what happens after this"
 * is half of what a rail is for. Wrapping keeps every phase on screen at every
 * width.
 *
 * Sized by container query, not viewport: this renders inside a transcript
 * column that the group config panel can squeeze to roughly half the window.
 * Viewport breakpoints would promote it to five columns while it was 300px
 * wide, which is the bug `task-board.tsx` records hitting.
 */
export function PhaseRail({ phases, onSelectPhase, className }: PhaseRailProps) {
  const { t } = useTranslation();
  if (phases.length === 0) return null;

  return (
    <section
      className={cn("@container/rail", className)}
      data-testid="overview-phase-rail"
      aria-label={t("groups.overview.phasesLabel", "Discussion phases")}
    >
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t("groups.overview.phases", "Phases")}
      </h3>
      <ol className="grid grid-cols-2 gap-2 @[30rem]/rail:grid-cols-3 @[46rem]/rail:grid-cols-5">
        {phases.map((phase) => (
          <PhaseCard key={phase.index} phase={phase} onSelect={onSelectPhase} />
        ))}
      </ol>
    </section>
  );
}

function PhaseCard({
  phase,
  onSelect,
}: {
  phase: DigestPhase;
  onSelect?: (phaseIndex: number) => void;
}) {
  const { t } = useTranslation();
  const interactive = !!onSelect && phase.entryCount > 0;

  const statusLabel =
    phase.status === "done"
      ? t("groups.overview.phaseDone", "Done")
      : phase.status === "active"
        ? t("groups.overview.phaseActive", "Now")
        : t("groups.overview.phaseUpcoming", "Upcoming");

  const StatusIcon = phase.status === "done" ? Check : phase.status === "active" ? CircleDot : Lock;

  const body = (
    <>
      <div className="mb-1.5 flex items-center gap-1.5">
        <StatusIcon
          className={cn(
            "h-3.5 w-3.5 shrink-0",
            phase.status === "done" && "text-emerald-600 dark:text-emerald-400",
            phase.status === "active" && "animate-pulse text-primary",
            phase.status === "pending" && "text-muted-foreground",
          )}
          aria-hidden="true"
        />
        <span
          className={cn(
            "text-[11px] font-medium",
            phase.status === "active" ? "text-primary" : "text-muted-foreground",
          )}
        >
          {statusLabel}
        </span>
        {phase.requiresApproval && (
          <ShieldQuestion
            className="h-3.5 w-3.5 shrink-0 text-amber-600 dark:text-amber-400"
            aria-label={t("groups.overview.phaseNeedsApproval", "Needs human approval")}
          />
        )}
      </div>

      <p
        className={cn(
          "truncate text-xs font-medium",
          phase.status === "pending" ? "text-muted-foreground" : "text-foreground",
        )}
        title={phase.name}
      >
        {phase.name}
      </p>

      <SpeakerDots phase={phase} />

      {phase.convergence && (
        <p
          className="mt-1.5 truncate text-[11px] text-violet-600 dark:text-violet-400"
          title={phase.convergence.reason}
        >
          {phase.convergence.agreementScore === null
            ? t("groups.overview.convergenceNoScore", "Agreement not scored")
            : t("groups.overview.convergenceScore", "Agreement {{pct}}%", {
                pct: Math.round(phase.convergence.agreementScore * 100),
              })}
        </p>
      )}
    </>
  );

  const shell = cn(
    "rounded-lg border p-2.5 text-start",
    phase.status === "active" ? "border-primary/40 bg-primary/5" : "border-border bg-secondary/30",
  );

  return (
    <li>
      {interactive ? (
        <button
          type="button"
          onClick={() => onSelect?.(phase.index)}
          className={cn(shell, "w-full transition-colors hover:border-primary/60 hover:bg-primary/10")}
          data-testid={`overview-phase-${phase.index}`}
        >
          {body}
        </button>
      ) : (
        <div className={shell} data-testid={`overview-phase-${phase.index}`}>
          {body}
        </div>
      )}
    </li>
  );
}

/**
 * One dot per member that has spoken, plus hollow dots for those still
 * expected — a glance-level "3 of 5 have had their turn".
 *
 * Capped at eight rendered dots with a "+n" overflow: a standing team can have
 * twenty members, and twenty dots wrap into a block that reads as texture
 * rather than as a count.
 */
function SpeakerDots({ phase }: { phase: DigestPhase }) {
  const { t } = useTranslation();
  const spoken = phase.spokenBy.length;
  if (spoken === 0 && phase.status !== "active") return null;

  const MAX_DOTS = 8;
  const shown = Math.min(spoken, MAX_DOTS);
  const overflow = spoken - shown;

  return (
    <div
      className="mt-2 flex flex-wrap items-center gap-1"
      title={t("groups.overview.spokenCount", "{{count}} contribution so far", {
        count: phase.entryCount,
        defaultValue_other: "{{count}} contributions so far",
      })}
    >
      {Array.from({ length: shown }, (_, i) => (
        <span
          key={i}
          className={cn(
            "h-1.5 w-1.5 rounded-full",
            phase.status === "active" ? "bg-primary" : "bg-emerald-600 dark:bg-emerald-400",
          )}
        />
      ))}
      {overflow > 0 && <span className="text-[10px] text-muted-foreground">+{overflow}</span>}
      {spoken === 0 && phase.status === "active" && (
        <span className="text-[10px] text-muted-foreground">
          {t("groups.overview.phaseStarting", "starting…")}
        </span>
      )}
    </div>
  );
}
