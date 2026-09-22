import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { DigestInteraction, DigestMember } from "@/hooks/use-discussion-digest";

interface InteractionMapProps {
  interactions: DigestInteraction[];
  members: DigestMember[];
  anonymous?: boolean;
  className?: string;
}

/**
 * Who addressed whom — the structure of the directional styles.
 *
 * PEER_REVIEW and DEVIL_ADVOCATE are *about* direction: a critique lands on
 * someone, a challenge is aimed at a position. `TranscriptEntry.targetAgentId`
 * carries that on every such turn, and without this band the dashboard could
 * say "Security spoke during Critique" but never "Security critiqued the
 * Architect" — the content of those styles rather than a detail of them.
 *
 * Grouped by speaker rather than drawn as a graph: a force-directed diagram of
 * five nodes is decoration, and at twenty it is unreadable. A list answers the
 * two real questions — who did this member take on, and who went unchallenged —
 * at any size, and needs no layout engine.
 *
 * Renders nothing when no turn carried a target, which is every broadcast-only
 * style, so callers can mount it unconditionally.
 */
export function InteractionMap({ interactions, members, anonymous, className }: InteractionMapProps) {
  const { t } = useTranslation();
  if (interactions.length === 0) return null;

  const nameOf = (agentId: string): string => {
    const index = members.findIndex((m) => m.agentId === agentId);
    if (anonymous) {
      return index >= 0
        ? t("groups.overview.anonymousMember", "Participant {{n}}", { n: index + 1 })
        : t("groups.overview.unknownMember", "Unknown");
    }
    return members[index]?.displayName ?? agentId;
  };

  // Grouped by speaker, speakers ordered by how much they engaged. `interactions`
  // arrives sorted heaviest-first, so first appearance is already that order.
  const bySpeaker: { fromAgentId: string; targets: DigestInteraction[] }[] = [];
  for (const edge of interactions) {
    const group = bySpeaker.find((g) => g.fromAgentId === edge.fromAgentId);
    if (group) group.targets.push(edge);
    else bySpeaker.push({ fromAgentId: edge.fromAgentId, targets: [edge] });
  }

  // Members nobody addressed. Worth naming: in a peer review that is a
  // contribution which drew no scrutiny, which is the thing a reviewer of the
  // review wants to find.
  const addressed = new Set(interactions.map((e) => e.toAgentId));
  const unaddressed = members.filter((m) => !addressed.has(m.agentId));

  return (
    <section
      className={cn("@container/interactions", className)}
      data-testid="overview-interactions"
      aria-label={t("groups.overview.interactionsLabel", "Who addressed whom")}
    >
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t("groups.overview.interactions", "Who addressed whom")}
      </h3>

      <ul className="space-y-1.5">
        {bySpeaker.map((group) => (
          <li
            key={group.fromAgentId}
            className="flex flex-wrap items-baseline gap-x-1.5 gap-y-1 rounded-lg border border-border bg-secondary/30 p-2 text-xs"
            data-testid={`overview-interaction-${group.fromAgentId}`}
          >
            <span className="font-medium text-foreground">{nameOf(group.fromAgentId)}</span>
            {/* Mirrored under RTL, the house idiom for a directional glyph —
                an arrow that keeps pointing right in Arabic reverses the
                sentence's meaning. */}
            <span className="inline-block text-muted-foreground rtl:-scale-x-100" aria-hidden="true">
              →
            </span>
            {group.targets.map((edge) => (
              <span
                key={edge.toAgentId}
                className="rounded-full bg-secondary px-2 py-0.5 text-secondary-foreground"
              >
                {nameOf(edge.toAgentId)}
                {edge.count > 1 && <span className="ms-1 text-muted-foreground">×{edge.count}</span>}
              </span>
            ))}
          </li>
        ))}
      </ul>

      {unaddressed.length > 0 && (
        <p className="mt-2 text-[11px] text-muted-foreground" data-testid="overview-unaddressed">
          {t("groups.overview.unaddressed", "Nobody addressed: {{names}}", {
            names: unaddressed.map((m) => nameOf(m.agentId)).join(", "),
          })}
        </p>
      )}
    </section>
  );
}
