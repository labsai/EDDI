import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowRightLeft } from "lucide-react";
import type { WorkforceHandoff } from "@eddi/app-kit/lib/workforce-handoffs";
import { handoffOriginHref, handoffTargetHref } from "@/lib/handoff-links";

const linkClass =
  "font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded";

/**
 * "Recorded in this browser only" (`workforce-handoffs.ts`), as visible text:
 * a title is unreachable by touch, keyboard and most screen readers, and this
 * is what tells the person the marker will be missing on another device.
 */
function LocalOnlyNote() {
  const { t } = useTranslation();
  return (
    <span className="text-[10px] text-muted-foreground/80" data-testid="handoff-local-only">
      ({t("Workforce.handoff.localOnly", "Recorded in this browser only")})
    </span>
  );
}

/**
 * The marker in the chat a question was handed over FROM: where it went, the
 * start of the question, and a link to the new conversation once the question
 * arrived there (until then, "Not linked yet": still being sent, refused, or
 * started in another tab whose link this browser never heard of).
 */
export function HandoffOriginMarker({ handoff }: { handoff: WorkforceHandoff }) {
  const { t } = useTranslation();
  const href = handoffTargetHref(handoff);
  return (
    <span
      className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
      data-testid={`handoff-origin-${handoff.id}`}
    >
      <ArrowRightLeft className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span dir="auto">
        {handoff.to.kind === "team"
          ? t("Workforce.handoff.toTeam", "You handed “{{question}}” over to the team {{name}}.", {
              question: handoff.question,
              name: handoff.to.name,
            })
          : t("Workforce.handoff.toAgent", "You handed “{{question}}” over to {{name}}.", {
              question: handoff.question,
              name: handoff.to.name,
            })}
      </span>
      {href ? (
        <Link to={href} className={linkClass} data-testid={`handoff-open-${handoff.id}`}>
          {handoff.to.kind === "team"
            ? t("Workforce.handoff.openSession", "Open the team session")
            : t("Workforce.handoff.openChat", "Open that chat")}
        </Link>
      ) : (
        <span className="italic">{t("Workforce.handoff.notLinked", "Not linked yet.")}</span>
      )}
      <LocalOnlyNote />
    </span>
  );
}

/** The marker in the conversation a question was handed over TO: where it came from. */
export function HandoffTargetMarker({ handoff }: { handoff: WorkforceHandoff }) {
  const { t } = useTranslation();
  return (
    <span
      className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
      data-testid={`handoff-target-${handoff.id}`}
    >
      <ArrowRightLeft className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span dir="auto">
        {t("Workforce.handoff.fromChat", "Handed over from your chat with {{name}}.", { name: handoff.from.agentName })}
      </span>
      <Link to={handoffOriginHref(handoff)} className={linkClass} data-testid={`handoff-back-${handoff.id}`}>
        {t("Workforce.handoff.backToChat", "Back to that chat")}
      </Link>
      <LocalOnlyNote />
    </span>
  );
}
