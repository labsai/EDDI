import { useTranslation } from "react-i18next";
import { Gavel, MessageSquareWarning, Scale } from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { DecisionRecord, DecisionType } from "@/lib/api/groups";

/**
 * The structured conclusion of a discussion (EDDI Wave 0, F3), rendered next to
 * the prose synthesis.
 *
 * A discussion's only conclusion used to be `synthesizedAnswer`, which is always
 * prose — so "who won the debate" was something a reader had to infer from
 * English. `decision` is the machine-readable answer, and this card is its
 * display: the outcome sentence, the winning side, the per-side tally, and the
 * minority report of everyone who disagreed.
 */

interface DecisionRecordCardProps {
  decision: DecisionRecord;
  className?: string;
}

const TYPE_ICON: Record<DecisionType, typeof Gavel> = {
  VERDICT: Gavel,
  VOTE: Scale,
  AGREEMENT: Scale,
  AWARD: Gavel,
  NONE: MessageSquareWarning,
};

export function DecisionRecordCard({ decision, className }: DecisionRecordCardProps) {
  const { t } = useTranslation();
  const Icon = TYPE_ICON[decision.type] ?? MessageSquareWarning;
  const tally = normalizeTally(decision.tally);
  const dissents = decision.dissents ?? [];
  const unparsed = decision.type === "NONE" && !!decision.raw?.trim();

  return (
    <div
      className={cn(
        "rounded-xl border p-4",
        unparsed
          ? "border-amber-500/30 bg-amber-500/5"
          : "border-primary/30 bg-primary/5",
        className,
      )}
      data-testid="decision-record"
    >
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Icon className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        <h3 className="text-sm font-semibold text-foreground">
          {t(`groups.decisionType.${decision.type}`, DEFAULT_TYPE_LABELS[decision.type] ?? decision.type)}
        </h3>
        {decision.winner ? (
          <Badge variant="success" data-testid="decision-winner">
            {t("groups.decisionWinner", "Winner: {{winner}}", { winner: decision.winner })}
          </Badge>
        ) : (
          decision.type === "VERDICT" && (
            <Badge variant="secondary" data-testid="decision-tie">
              {t("groups.decisionTie", "Tie")}
            </Badge>
          )
        )}
        {decision.method && (
          <span className="ms-auto font-mono text-[10px] text-muted-foreground" title={decision.method}>
            {decision.method}
          </span>
        )}
      </div>

      {decision.outcome && (
        <p className="text-sm text-foreground" data-testid="decision-outcome">
          {decision.outcome}
        </p>
      )}

      {decision.decidedAtPhase && (
        <p className="mt-1 text-[11px] text-muted-foreground">
          {t("groups.decisionDecidedAt", "Decided in {{phase}}", { phase: decision.decidedAtPhase })}
        </p>
      )}

      {tally.length > 0 && (
        <dl className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3" data-testid="decision-tally">
          {tally.map(([key, value]) => (
            <div key={key} className="rounded-lg border border-border bg-background/60 px-2.5 py-1.5">
              <dt className="truncate text-[10px] uppercase tracking-wider text-muted-foreground" title={key}>
                {key}
              </dt>
              <dd className="text-sm font-semibold tabular-nums text-foreground">{value}</dd>
            </div>
          ))}
        </dl>
      )}

      {unparsed && (
        <p className="mt-3 rounded-lg border border-amber-500/30 bg-background/60 p-2 text-[11px] text-muted-foreground">
          {t(
            "groups.decisionUnparsed",
            "The judge's answer could not be read as a structured verdict, so it is kept verbatim below and the conclusion stands as prose.",
          )}
        </p>
      )}
      {unparsed && (
        <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-background/60 p-2 text-[11px] text-muted-foreground">
          {decision.raw}
        </pre>
      )}

      {dissents.length > 0 && (
        <div className="mt-3 border-t border-border pt-3">
          <h4 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            {/* `total`, not `count` — i18next treats `count` as a pluralization
                trigger, which would demand a plural form per category in every
                locale (six in Arabic) for a parenthesised numeral. */}
            {t("groups.minorityReport", "Minority report ({{total}})", { total: dissents.length })}
          </h4>
          <ul className="space-y-2">
            {dissents.map((d, idx) => (
              <li key={`${d.agentId}-${idx}`} className="flex gap-2">
                <div
                  className={cn(
                    "mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[9px] font-bold text-white",
                    hashColor(d.agentId || String(idx)),
                  )}
                  aria-hidden="true"
                >
                  {getInitials(d.displayName || "?")}
                </div>
                <div className="min-w-0">
                  <p className="text-xs font-medium text-foreground">{d.displayName || d.agentId}</p>
                  <p className="text-xs text-muted-foreground">{d.position}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

const DEFAULT_TYPE_LABELS: Record<DecisionType, string> = {
  VERDICT: "Verdict",
  VOTE: "Vote",
  AGREEMENT: "Agreement",
  AWARD: "Award",
  NONE: "No structured decision",
};

/**
 * `tally` is `Map<String, Object>` on the backend — its shape is defined by
 * whichever feature produced the decision (side→score for a verdict,
 * option→weight for a vote), so it is rendered generically. Numbers are shown at
 * a sane precision; anything else is stringified rather than dropped, because a
 * tally entry this build does not understand is still evidence.
 */
function normalizeTally(tally: Record<string, unknown> | null | undefined): [string, string][] {
  if (!tally || typeof tally !== "object") return [];
  return Object.entries(tally).map(([key, value]) => {
    if (typeof value === "number" && Number.isFinite(value)) {
      return [key, Number.isInteger(value) ? String(value) : value.toFixed(2)];
    }
    if (value == null) return [key, "—"];
    if (typeof value === "object") return [key, JSON.stringify(value)];
    return [key, String(value)];
  });
}
