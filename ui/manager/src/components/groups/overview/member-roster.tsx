import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Quote, Sparkles, UserX } from "lucide-react";
import { cn, formatUsd } from "@/lib/utils";
import type { DigestMember } from "@/hooks/use-discussion-digest";

interface MemberRosterProps {
  members: DigestMember[];
  /** Hide identities (DELPHI). See `rosterIsAnonymous`. */
  anonymous?: boolean;
  className?: string;
}

/**
 * "Who thinks what" — one card per member: their current one-line position,
 * how many turns they have taken, what they have cost, and whether they are
 * speaking, dissenting or broken.
 *
 * This is the band that answers the question a long transcript buries. Whether
 * the stance is the member's own words or an LLM paraphrase is shown, not
 * flattened: the two are different kinds of claim, and rendering a paraphrase
 * in a way that reads as a quote would put words in an agent's mouth.
 */
/**
 * How many stance cards to show before collapsing.
 *
 * A standing team can have twenty members, and twenty stance cards are the wall
 * of text this whole view exists to replace. Eight fills the widest grid
 * (three columns) without dominating the page.
 */
const ROSTER_VISIBLE = 8;

export function MemberRoster({ members, anonymous, className }: MemberRosterProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  // Ties the toggle to the list it controls, as the task board's toggle does.
  const listId = useId();
  if (members.length === 0) return null;

  const hidden = Math.max(0, members.length - ROSTER_VISIBLE);
  const shown = expanded || hidden === 0 ? members : members.slice(0, ROSTER_VISIBLE);

  return (
    <section
      className={cn("@container/roster", className)}
      data-testid="overview-roster"
      aria-label={t("groups.overview.rosterLabel", "Member positions")}
    >
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t("groups.overview.roster", "Who thinks what")}
      </h3>
      <div id={listId} className="grid grid-cols-1 gap-2 @[26rem]/roster:grid-cols-2 @[52rem]/roster:grid-cols-3">
        {shown.map((member, index) => (
          <MemberCard key={member.agentId} member={member} index={index} anonymous={anonymous} />
        ))}
      </div>
      {hidden > 0 && (
        <button
          type="button"
          onClick={() => setExpanded((e) => !e)}
          aria-expanded={expanded}
          aria-controls={listId}
          className="mt-2 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
          data-testid="overview-roster-toggle"
        >
          {expanded
            ? t("groups.overview.rosterShowFewer", "Show fewer")
            : t("groups.overview.rosterShowAll", "Show all {{count}} members", {
                count: members.length,
              })}
        </button>
      )}
    </section>
  );
}

function MemberCard({
  member,
  index,
  anonymous,
}: {
  member: DigestMember;
  index: number;
  anonymous?: boolean;
}) {
  const { t } = useTranslation();

  const name = anonymous
    ? t("groups.overview.anonymousMember", "Participant {{n}}", { n: index + 1 })
    : member.displayName;

  const body = (
    <>
      <div className="mb-1.5 flex items-center gap-2">
        <span
          aria-hidden="true"
          className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-secondary text-[10px] font-semibold text-secondary-foreground"
        >
          {initials(name)}
        </span>
        <span className="truncate text-sm font-medium text-foreground" title={name}>
          {name}
        </span>
        <StatusChip member={member} />
      </div>

      {member.stance ? (
        <p className="line-clamp-3 text-xs leading-relaxed text-muted-foreground">
          {member.stanceIsQuote ? (
            <Quote
              className="me-1 inline h-3 w-3 align-[-1px] text-muted-foreground"
              aria-label={t("groups.overview.stanceQuoted", "Their own words")}
            />
          ) : (
            <Sparkles
              className="me-1 inline h-3 w-3 align-[-1px] text-violet-600 dark:text-violet-400"
              aria-label={t("groups.overview.stanceSummarised", "Summarised by a model")}
            />
          )}
          {member.stance}
        </p>
      ) : (
        <p className="text-xs italic text-muted-foreground">
          {/* Two different facts, and conflating them misreports the member.
              A moderator's only contribution is a SYNTHESIS — it summarises
              everyone else rather than stating a position of its own — so it
              has no stance while plainly having spoken, and "has not spoken
              yet" next to its turn count is simply false. */}
          {member.turnCount > 0
            ? t("groups.overview.noStanceOfOwn", "No position of their own")
            : t("groups.overview.noStanceYet", "Has not spoken yet")}
        </p>
      )}

      <div className="mt-2 flex items-center gap-3 text-[11px] text-muted-foreground">
        <span>
          {t("groups.overview.turnCount", "{{count}} turn", {
            count: member.turnCount,
            defaultValue_other: "{{count}} turns",
          })}
        </span>
        {member.cost !== null && <span className="font-mono">{formatUsd(member.cost)}</span>}
      </div>
    </>
  );

  return (
    <div
      className="rounded-lg border border-border bg-secondary/30 p-2.5 text-start"
      data-testid={`overview-member-${member.agentId}`}
    >
      {body}
    </div>
  );
}

function StatusChip({ member }: { member: DigestMember }) {
  const { t } = useTranslation();

  if (member.status === "speaking") {
    return (
      <span className="ms-auto flex shrink-0 items-center gap-1 text-[11px] text-primary">
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
        {t("groups.overview.statusSpeaking", "speaking")}
      </span>
    );
  }
  if (member.status === "awaiting") {
    return (
      <span className="ms-auto shrink-0 text-[11px] text-amber-600 dark:text-amber-400">
        {t("groups.overview.statusAwaiting", "your turn")}
      </span>
    );
  }
  if (member.status === "failed") {
    return (
      <span className="ms-auto flex shrink-0 items-center gap-1 text-[11px] text-destructive">
        <UserX className="h-3 w-3" aria-hidden="true" />
        {t("groups.overview.statusFailed", "failed")}
      </span>
    );
  }
  if (member.status === "dissented") {
    return (
      <span className="ms-auto flex shrink-0 items-center gap-1 text-[11px] text-amber-600 dark:text-amber-400">
        <AlertTriangle className="h-3 w-3" aria-hidden="true" />
        {t("groups.overview.statusDissented", "dissent")}
      </span>
    );
  }
  return null;
}

/**
 * Up to two initials from a display name.
 *
 * Uses `Array.from` rather than `split("")` so a name whose first character is
 * outside the BMP (an emoji, some CJK extensions) yields that whole character
 * instead of half a surrogate pair, which renders as a replacement glyph.
 */
function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const firstWord = words[0];
  const lastWord = words[words.length - 1];
  if (!firstWord || !lastWord) return "?";
  const first = Array.from(firstWord)[0] ?? "";
  if (words.length === 1) return first.toUpperCase();
  const second = Array.from(lastWord)[0] ?? "";
  return (first + second).toUpperCase();
}
