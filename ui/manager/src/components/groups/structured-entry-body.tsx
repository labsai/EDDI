import { useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle, CheckCircle2, ListOrdered, User2, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { EntryBody } from "@/lib/group-entry-body";
import type { StructuredItem } from "./group-utils";
import { StructuredTurnCard } from "./structured-turn-card";

/**
 * The non-prose kinds of a group message, rendered identically on every
 * surface — see `readEntryBody`. Prose stays with each surface, which owns its
 * own collapse and truncation; everything here used to exist only inside the
 * Manager's `AgentResponseCard`, so the other two transcripts showed a task
 * plan as a ```json block.
 */
export function StructuredEntryBody({ body, className }: { body: EntryBody; className?: string }) {
  switch (body.kind) {
    case "payload":
      return <StructuredTurnCard payload={body.payload} className={className} />;
    case "items":
      return <StructuredItemsList items={body.items} verification={body.verification} className={className} />;
    case "failed":
      return <AgentFailedNotice className={className} />;
    case "abstained":
      return <AbstainedNotice className={className} />;
    default:
      return null;
  }
}

/** A member whose own conversation errored — a status, not an answer. */
export function AgentFailedNotice({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <div
      className={cn("flex items-center gap-1.5 text-sm italic text-muted-foreground", className)}
      data-testid="agent-failed-notice"
    >
      <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" aria-hidden="true" />
      {t("groups.agentFailedOutput", "This agent failed to produce a response.")}
    </div>
  );
}

/** A member who passed on the round — their turn is the decision not to add anything. */
function AbstainedNotice({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <p className={cn("text-sm italic text-muted-foreground", className)} data-testid="abstained-notice">
      {t("groups.abstainedBody", "Declined to add anything new this round.")}
    </p>
  );
}

/** A task plan (numbered, with assignee and priority) or a verification sheet (pass/fail). */
export function StructuredItemsList({
  items,
  verification,
  className,
}: {
  items: StructuredItem[];
  verification: boolean;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <div className={cn("space-y-2", className)} data-testid="structured-items">
      <div className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        {verification ? <CheckCircle2 className="h-3.5 w-3.5" /> : <ListOrdered className="h-3.5 w-3.5" />}
        {items.length} {items.length === 1 ? t("groups.item", "item") : t("groups.items", "items")}
      </div>
      {items.map((item, i) => {
        const hasVerdict = item.passed !== undefined;
        return (
          <div
            key={i}
            className={cn(
              "flex items-start gap-3 rounded-lg border px-3 py-2.5",
              hasVerdict && item.passed && "border-emerald-500/30 bg-emerald-500/5",
              hasVerdict && !item.passed && "border-destructive/30 bg-destructive/5",
              !hasVerdict && "border-border/50 bg-secondary/30",
            )}
            data-testid="structured-item"
          >
            {hasVerdict ? (
              item.passed ? (
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
              ) : (
                <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
              )
            ) : (
              <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-sky-500/20 text-[10px] font-bold text-sky-400">
                {i + 1}
              </span>
            )}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium leading-snug text-foreground">{item.subject}</p>
              {item.description && <ExpandableText text={item.description} className="mt-0.5" />}
              {item.feedback && <ExpandableText text={item.feedback} className="mt-0.5" />}
              {item.assignedTo && (
                <div className="mt-1.5 flex items-center gap-1">
                  <User2 className="h-3 w-3 text-muted-foreground" />
                  <span
                    className="max-w-[200px] truncate text-[10px] font-medium text-muted-foreground"
                    title={item.assignedTo}
                  >
                    {item.assignedTo}
                  </span>
                </div>
              )}
            </div>
            {item.priority != null && (
              <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
                {t("groups.priorityShort", "P{{priority}}", { priority: item.priority })}
              </Badge>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** Clamped text with a show more/less toggle for long content. */
function ExpandableText({ text, className }: { text: string; className?: string }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const isLong = text.length > 100;

  return (
    <div className={className}>
      <p className={cn("text-xs leading-relaxed text-muted-foreground", !expanded && isLong && "line-clamp-2")}>
        {text}
      </p>
      {isLong && (
        <Button
          type="button"
          variant="link"
          size="sm"
          onClick={() => setExpanded(!expanded)}
          className="mt-0.5 h-auto px-0 py-0 text-[10px] text-primary/70 hover:text-primary"
        >
          {expanded ? t("common.showLess", "Show less") : t("common.showMore", "Show more")}
        </Button>
      )}
    </div>
  );
}
