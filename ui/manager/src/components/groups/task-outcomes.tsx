import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useTaskOutcomes } from "@/hooks/use-group-outcomes";
import type { OutcomeEvent } from "@/lib/api/group-outcomes";

/** One outcome as a single line: kind, reading, source and when. */
export function OutcomeLine({ event }: { event: OutcomeEvent }) {
  const reading = event.value !== null && event.value !== undefined ? ` (${event.metric ? `${event.metric} = ` : ""}${event.value}${event.unit ? ` ${event.unit}` : ""})` : "";
  return (
    <li className="text-[10px] text-muted-foreground" data-testid={`outcome-${event.id}`}>
      <span className="font-mono text-foreground">{event.kind}</span>
      {reading}
      {event.source && <span> · {event.source}</span>}
      <span> · {event.occurredAt.slice(0, 10)}</span>
      {event.note && <span className="block ps-3 italic">{event.note}</span>}
    </li>
  );
}

/**
 * The outcomes reported against one backlog task, loaded when the row is
 * expanded. Shown only for a group whose configuration enables outcomes.
 */
export function TaskOutcomes({ groupId, taskId }: { groupId: string; taskId: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const outcomes = useTaskOutcomes(groupId, taskId, open);
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <div className="mt-1" data-testid={`task-outcomes-${taskId}`}>
      <button
        type="button"
        className="inline-flex items-center gap-1 text-[10px] font-medium text-primary hover:underline"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        data-testid={`task-outcomes-toggle-${taskId}`}
      >
        <Chevron className="h-3 w-3" />
        {t("groupLearning.outcomes", "Outcomes")}
        {outcomes.data && ` (${outcomes.data.length})`}
      </button>
      {open && (
        <div className="mt-1 ps-4">
          {outcomes.isLoading ? (
            <p className="text-[10px] text-muted-foreground">{t("common.loading", "Loading…")}</p>
          ) : outcomes.isError ? (
            <p className="text-[10px] text-destructive">{t("common.error", "Something went wrong")}</p>
          ) : outcomes.data && outcomes.data.length > 0 ? (
            <ul className="space-y-0.5" data-testid={`task-outcomes-list-${taskId}`}>
              {outcomes.data.map((event) => (
                <OutcomeLine key={event.id} event={event} />
              ))}
            </ul>
          ) : (
            <p className="text-[10px] text-muted-foreground" data-testid={`task-outcomes-empty-${taskId}`}>
              {t("groupLearning.outcomesEmpty", "No outcomes reported yet.")}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
