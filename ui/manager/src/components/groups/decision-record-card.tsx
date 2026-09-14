import { useState } from "react";
import { useTranslation } from "react-i18next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ChevronDown, ChevronUp, Gavel, MessageSquareWarning, Scale } from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { DecisionRecord, DecisionType } from "@/lib/api/groups";
import { describeDecision } from "@/lib/group-decision";
import { formatMarkdownText } from "./group-utils";

/**
 * The structured conclusion of a discussion (EDDI Wave 0, F3), rendered next to
 * the prose synthesis.
 *
 * A discussion's only conclusion used to be `synthesizedAnswer`, which is always
 * prose — so "who won the debate" was something a reader had to infer from
 * English. `decision` is the machine-readable answer, and this card is its
 * display: the outcome, the winning side, the per-side tally, and the minority
 * report of everyone who disagreed. What each engine puts in `outcome` and
 * `tally` differs, and `describeDecision` owns reading it.
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

/** An outcome body longer than this starts collapsed. */
const BODY_COLLAPSE_CHARS = 400;

/** Tally labels up to this length ("PRO", "Ship it") fit the compact grid. */
const SHORT_LABEL_CHARS = 20;

export function DecisionRecordCard({ decision, className }: DecisionRecordCardProps) {
  const { t } = useTranslation();
  const [bodyExpanded, setBodyExpanded] = useState(false);
  const Icon = TYPE_ICON[decision.type] ?? MessageSquareWarning;
  const view = describeDecision(decision);
  const dissents = decision.dissents ?? [];
  const unparsed = decision.type === "NONE" && !!decision.raw?.trim();
  const bodyCollapsible = !!view.body && view.body.length > BODY_COLLAPSE_CHARS;
  const scoresAreSentences = view.scores.some(([label]) => label.length > SHORT_LABEL_CHARS);

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
        {view.showWinner ? (
          <Badge variant="success" data-testid="decision-winner">
            {t("groups.decisionWinner", "Winner: {{winner}}", { winner: decision.winner })}
          </Badge>
        ) : (
          view.showTie && (
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

      {view.headline && (
        <p className="text-sm text-foreground" data-testid="decision-outcome">
          {view.headline}
        </p>
      )}

      {view.body && (
        <div data-testid="decision-outcome">
          <div
            className={cn(
              "prose prose-sm dark:prose-invert max-w-none overflow-hidden text-foreground",
              bodyCollapsible && !bodyExpanded && "max-h-40",
            )}
          >
            {/* No rehypeRaw: an arbitrator's ruling is model output, so raw HTML stays escaped. */}
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatMarkdownText(view.body)}</ReactMarkdown>
          </div>
          {bodyCollapsible && (
            <Button
              type="button"
              variant="link"
              size="sm"
              onClick={() => setBodyExpanded((v) => !v)}
              className="mt-1 h-auto gap-1 px-0 py-0 text-xs hover:text-primary/80 [&_svg]:h-3 [&_svg]:w-3"
            >
              {bodyExpanded ? (
                <>
                  <ChevronUp className="h-3 w-3" />
                  {t("common.showLess", "Show less")}
                </>
              ) : (
                <>
                  <ChevronDown className="h-3 w-3" />
                  {t("common.showMore", "Show more")}
                </>
              )}
            </Button>
          )}
        </div>
      )}

      {decision.decidedAtPhase && (
        <p className="mt-1 text-[11px] text-muted-foreground">
          {t("groups.decisionDecidedAt", "Decided in {{phase}}", { phase: decision.decidedAtPhase })}
        </p>
      )}

      {view.scores.length > 0 && scoresAreSentences && (
        // A ballot's options are whatever the chair distilled — often whole
        // sentences, which the grid below uppercased and truncated to nothing.
        <dl className="mt-3 space-y-1.5" data-testid="decision-tally">
          {view.scores.map(([key, value]) => (
            <div
              key={key}
              className={cn(
                "flex items-start justify-between gap-3 rounded-lg border bg-background/60 px-2.5 py-1.5",
                key === decision.winner ? "border-primary/40" : "border-border",
              )}
            >
              <dt className="min-w-0 text-xs text-foreground">{key}</dt>
              <dd className="shrink-0 text-sm font-semibold tabular-nums text-foreground">{value}</dd>
            </div>
          ))}
        </dl>
      )}

      {view.scores.length > 0 && !scoresAreSentences && (
        <dl className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3" data-testid="decision-tally">
          {view.scores.map(([key, value]) => (
            <div key={key} className="rounded-lg border border-border bg-background/60 px-2.5 py-1.5">
              <dt className="truncate text-[10px] uppercase tracking-wider text-muted-foreground" title={key}>
                {key}
              </dt>
              <dd className="text-sm font-semibold tabular-nums text-foreground">{value}</dd>
            </div>
          ))}
        </dl>
      )}

      {view.ballots && (
        <p className="mt-2 text-[11px] text-muted-foreground" data-testid="decision-ballots">
          {t("groups.decisionBallots", "{{valid}} of {{participants}} ballots valid", view.ballots)}
        </p>
      )}

      {view.terms && (
        <div className="mt-3 rounded-lg border border-border bg-background/60 p-2.5" data-testid="decision-terms">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            {t("groups.decisionTerms", "Agreed terms")}
          </p>
          {/* Terms are a party's own words, markdown and all. No rehypeRaw: untrusted. */}
          <div className="prose prose-sm dark:prose-invert mt-0.5 max-w-none text-foreground">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatMarkdownText(view.terms)}</ReactMarkdown>
          </div>
        </div>
      )}

      {view.concessions.length > 0 && (
        <ul className="mt-2 space-y-1" data-testid="decision-concessions">
          {view.concessions.map((concession, idx) => (
            <li
              key={`${concession.gaveUp}-${idx}`}
              className="rounded-lg border border-border bg-background/60 p-2 text-xs"
            >
              <span className="text-foreground">{concession.gaveUp}</span>
              {/* Flipped with the writing direction — U+2192 is not bidi-mirrored. */}
              <span className="mx-1.5 inline-block text-muted-foreground rtl:-scale-x-100" aria-hidden="true">
                →
              </span>
              <span className="text-muted-foreground">
                {t("groups.payload.inReturnFor", "in return for {{received}}", {
                  received: concession.inReturnFor,
                })}
              </span>
            </li>
          ))}
        </ul>
      )}

      {view.details && (
        <div
          className="prose prose-sm dark:prose-invert mt-3 max-w-none text-muted-foreground"
          data-testid="decision-details"
        >
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{view.details}</ReactMarkdown>
        </div>
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
