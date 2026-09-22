import { useTranslation } from "react-i18next";
import { AlertTriangle, Dot, MessageSquare, Minus, X } from "lucide-react";
import type { TFunction } from "i18next";
import { cn } from "@/lib/utils";
import type { CellKind, DigestCell, DigestMember, DigestPhase } from "@/hooks/use-discussion-digest";

interface ParticipationMatrixProps {
  phases: DigestPhase[];
  members: DigestMember[];
  matrix: Record<string, DigestCell[]>;
  anonymous?: boolean;
  className?: string;
}

/**
 * Every turn of the discussion as one members × phases grid.
 *
 * This is the band that does the actual compression the dashboard exists for:
 * a seven-member, five-phase discussion is ~40 messages of prose and one 7×5
 * grid, and the grid answers "who has been quiet", "where did it break" and
 * "who dissented" without reading a word.
 *
 * The five cell kinds are deliberately distinguished rather than collapsed to
 * filled/empty — in particular `absent` (this phase never included that member,
 * e.g. a MODERATOR-only synthesis) versus `pending` (expected, hasn't spoken).
 * Drawing both as blank would tell a reader that a debate's PRO side had gone
 * silent during a CON-only phase.
 *
 * Colour is never the only channel: each kind also has its own glyph, and every
 * cell carries a text label for assistive technology.
 */
export function ParticipationMatrix({
  phases,
  members,
  matrix,
  anonymous,
  className,
}: ParticipationMatrixProps) {
  const { t } = useTranslation();
  if (phases.length === 0 || members.length === 0) return null;

  return (
    <section
      className={cn("@container/matrix", className)}
      data-testid="overview-matrix"
      aria-label={t("groups.overview.matrixLabel", "Contributions by member and phase")}
    >
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t("groups.overview.matrix", "Every turn at a glance")}
      </h3>

      <div
        role="table"
        className="grid items-center gap-1"
        style={{
          // minmax(0, …) on the phase columns is load-bearing: a grid column
          // defaults to min-width:auto, so one long phase name would size the
          // column to its own content and push the grid past its container.
          gridTemplateColumns: `minmax(3.5rem, 6rem) repeat(${phases.length}, minmax(0, 1fr))`,
        }}
      >
        <div role="row" className="contents">
          {/* The corner cell must occupy its grid track, so it cannot be
              `sr-only` — that is `position: absolute`, which takes the element
              OUT of grid flow. Every row then shifted one column left and each
              member's name landed where their last phase cell belonged.
              An empty corner is the conventional shape for a matrix anyway; the
              grid's accessible name lives on the section's aria-label. */}
          <span role="columnheader" aria-label={t("groups.overview.memberColumn", "Member")} />
          {phases.map((phase) => (
            <span
              key={phase.index}
              role="columnheader"
              title={phase.name}
              className={cn(
                "truncate text-center text-[10px]",
                phase.status === "active" ? "text-primary" : "text-muted-foreground",
              )}
            >
              {abbreviate(phase.name)}
            </span>
          ))}
        </div>

        {members.map((member, memberIndex) => {
          const name = anonymous
            ? t("groups.overview.anonymousMember", "Participant {{n}}", { n: memberIndex + 1 })
            : member.displayName;
          const cells = matrix[member.agentId] ?? [];
          return (
            <div role="row" className="contents" key={member.agentId}>
              <span
                role="rowheader"
                className="truncate text-[11px] text-muted-foreground"
                title={name}
              >
                {name}
              </span>
              {phases.map((phase, phaseIndex) => (
                <MatrixCell
                  key={phase.index}
                  cell={cells[phaseIndex] ?? { kind: "absent", entryCount: 0 }}
                  memberName={name}
                  phaseName={phase.name}
                />
              ))}
            </div>
          );
        })}
      </div>

      <Legend />
    </section>
  );
}

const CELL_STYLES: Record<CellKind, string> = {
  spoke: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400",
  dissent: "bg-amber-500/20 text-amber-700 dark:text-amber-400",
  failed: "bg-destructive/15 text-destructive",
  abstained: "bg-secondary text-muted-foreground",
  pending: "border border-dashed border-primary/50",
  // Expected and never arrived — distinguishable from `absent` at a glance,
  // because "the run dropped this member's turn" is worth noticing.
  silent: "border border-dotted border-border-strong bg-secondary/40 text-muted-foreground",
  absent: "bg-secondary/40",
};

function cellLabel(kind: CellKind, t: TFunction): string {
  switch (kind) {
    case "spoke":
      return t("groups.overview.cellSpoke", "spoke");
    case "dissent":
      return t("groups.overview.cellDissent", "dissented");
    case "failed":
      return t("groups.overview.cellFailed", "failed");
    case "abstained":
      return t("groups.overview.cellAbstained", "abstained");
    case "pending":
      return t("groups.overview.cellPending", "pending");
    case "silent":
      return t("groups.overview.cellSilent", "expected, said nothing");
    case "absent":
      return t("groups.overview.cellAbsent", "not in this phase");
  }
}

function CellGlyph({ kind }: { kind: CellKind }) {
  switch (kind) {
    case "spoke":
      return <MessageSquare className="h-3 w-3" aria-hidden="true" />;
    case "dissent":
      return <AlertTriangle className="h-3 w-3" aria-hidden="true" />;
    case "failed":
      return <X className="h-3 w-3" aria-hidden="true" />;
    case "abstained":
      return <Minus className="h-3 w-3" aria-hidden="true" />;
    case "silent":
      return <Dot className="h-3 w-3" aria-hidden="true" />;
    default:
      return null;
  }
}

function MatrixCell({
  cell,
  memberName,
  phaseName,
}: {
  cell: DigestCell;
  memberName: string;
  phaseName: string;
}) {
  const { t } = useTranslation();
  const label = t("groups.overview.cellSummary", "{{member}}, {{phase}}: {{state}}", {
    member: memberName,
    phase: phaseName,
    state: cellLabel(cell.kind, t),
  });

  return (
    <span
      role="cell"
      className={cn("flex h-6 items-center justify-center rounded", CELL_STYLES[cell.kind])}
      title={label}
      aria-label={label}
    >
      <CellGlyph kind={cell.kind} />
    </span>
  );
}

function Legend() {
  const { t } = useTranslation();
  const kinds: CellKind[] = ["spoke", "dissent", "failed", "abstained", "pending", "silent", "absent"];
  return (
    <ul className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-muted-foreground">
      {kinds.map((kind) => (
        <li key={kind} className="flex items-center gap-1">
          <span className={cn("flex h-3 w-3 items-center justify-center rounded", CELL_STYLES[kind])}>
            <CellGlyph kind={kind} />
          </span>
          {cellLabel(kind, t)}
        </li>
      ))}
    </ul>
  );
}

/**
 * Shortens a phase name for a column header that is often under 60px wide.
 *
 * First word, plus any trailing parenthetical. The parenthetical is what makes
 * this usable for DEBATE, whose phases are "Opening Arguments (Pro)" and
 * "Opening Arguments (Con)" — on the first word alone both headers read
 * "Opening", and an abbreviation that collides is worse than none.
 *
 * The full name stays on the header's `title` and inside every cell's
 * accessible label, so nothing depends on the abbreviation being unambiguous.
 */
function abbreviate(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length <= 8) return trimmed;

  const qualifier = /\(([^)]{1,6})\)\s*$/.exec(trimmed)?.[1];
  const firstWord = (trimmed.split(/\s+/)[0] ?? trimmed).replace(/[(),]/g, "");
  const head = firstWord.length <= 8 ? firstWord : `${firstWord.slice(0, 7)}…`;
  return qualifier ? `${head} (${qualifier})` : head;
}
