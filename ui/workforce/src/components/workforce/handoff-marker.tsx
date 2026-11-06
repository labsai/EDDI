import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowRightLeft } from "lucide-react";
import type { WorkforceHandoff } from "@eddi/app-kit/lib/workforce-handoffs";
import { handoffOriginHref, handoffTargetHref } from "@/lib/handoff-links";

const linkClass =
  "font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded";

/**
 * The marker in the chat a question was handed over FROM: where it went, the
 * start of the question, and a link to the new conversation. Recorded in this
 * browser only (`workforce-handoffs.ts`); the title says so.
 */
export function HandoffOriginMarker({ handoff }: { handoff: WorkforceHandoff }) {
  const { t } = useTranslation();
  const href = handoffTargetHref(handoff);
  return (
    <span
      className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
      title={t("Workforce.handoff.localOnly", "Recorded in this browser only")}
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
        <span className="italic">{t("Workforce.handoff.notStarted", "It did not start.")}</span>
      )}
    </span>
  );
}

/** The marker in the conversation a question was handed over TO: where it came from. */
export function HandoffTargetMarker({ handoff }: { handoff: WorkforceHandoff }) {
  const { t } = useTranslation();
  return (
    <span
      className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
      title={t("Workforce.handoff.localOnly", "Recorded in this browser only")}
      data-testid={`handoff-target-${handoff.id}`}
    >
      <ArrowRightLeft className="h-3 w-3 shrink-0" aria-hidden="true" />
      <span dir="auto">
        {t("Workforce.handoff.fromChat", "Handed over from your chat with {{name}}.", { name: handoff.from.agentName })}
      </span>
      <Link to={handoffOriginHref(handoff)} className={linkClass} data-testid={`handoff-back-${handoff.id}`}>
        {t("Workforce.handoff.backToChat", "Back to that chat")}
      </Link>
    </span>
  );
}
