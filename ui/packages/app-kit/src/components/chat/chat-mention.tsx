import { useTranslation } from "react-i18next";
import { Bot, Users, X } from "lucide-react";
import { cn } from "../../lib/utils";
import { mentionOptionId, type ChatMentionCandidate } from "../../lib/chat-mention";

/** The popup listbox of a mention combobox. Picking never moves focus out of the composer. */
export function MentionListbox({
  id,
  options,
  activeIndex,
  onPick,
  onHover,
}: {
  id: string;
  options: readonly ChatMentionCandidate[];
  activeIndex: number;
  onPick: (option: ChatMentionCandidate) => void;
  onHover: (index: number) => void;
}) {
  const { t } = useTranslation();
  return (
    <ul
      id={id}
      role="listbox"
      aria-label={t("chat.mention.listLabel", "Hand over to")}
      className="absolute bottom-full start-0 z-20 mb-2 max-h-64 w-full max-w-sm overflow-y-auto rounded-xl border border-border bg-popover p-1 shadow-lg"
      data-testid="chat-mention-listbox"
    >
      {options.map((option, i) => {
        const Icon = option.kind === "team" ? Users : Bot;
        return (
          <li
            key={`${option.kind}-${option.id}`}
            id={mentionOptionId(id, i)}
            role="option"
            aria-selected={i === activeIndex}
            // Mouse down, not click: a click would blur the composer first.
            onMouseDown={(e) => {
              e.preventDefault();
              onPick(option);
            }}
            onMouseEnter={() => onHover(i)}
            className={cn(
              "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm",
              i === activeIndex ? "bg-muted text-foreground" : "text-muted-foreground",
            )}
            data-testid={`chat-mention-option-${option.kind}-${option.id}`}
          >
            <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate" dir="auto">
              {option.name}
            </span>
            <span className="shrink-0 text-[10px] uppercase tracking-wide">
              {option.kind === "team" ? t("chat.mention.team", "Team") : t("chat.mention.agent", "Agent")}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

/** The chosen hand-off target above the composer, with a way to take it back. */
export function MentionTargetChip({
  id,
  target,
  onRemove,
}: {
  id: string;
  target: ChatMentionCandidate;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const Icon = target.kind === "team" ? Users : Bot;
  return (
    <div className="mb-2 flex items-center gap-2 text-xs" data-testid="chat-mention-target">
      <span
        id={id}
        className="inline-flex min-w-0 items-center gap-1.5 rounded-full border border-primary/30 bg-primary/5 px-2.5 py-1 text-primary"
      >
        <Icon className="h-3 w-3 shrink-0" aria-hidden="true" />
        <span className="truncate" dir="auto">
          {target.kind === "team"
            ? t("chat.mention.handOffToTeam", "Hand over to the team {{name}}", { name: target.name })
            : t("chat.mention.handOffToAgent", "Hand over to {{name}}", { name: target.name })}
        </span>
      </span>
      <button
        type="button"
        onClick={onRemove}
        className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        aria-label={t("chat.mention.remove", "Don't hand over")}
        data-testid="chat-mention-remove"
      >
        <X className="h-3 w-3" aria-hidden="true" />
      </button>
    </div>
  );
}
