import { useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────

interface ContextCardProps {
  boardName: string;
  question: string;
  response: string;
  className?: string;
}

// ─── Icons ───────────────────────────────────────────────────────

function ChevronIcon({ expanded }: { expanded: boolean }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={cn(
        "h-4 w-4 shrink-0 transition-transform duration-200",
        expanded && "rotate-180",
      )}
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

// ─── Component ───────────────────────────────────────────────────

function ContextCard({ boardName, question, response, className }: ContextCardProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  // Don't render if there's no context to show
  if (!question && !response) return null;

  return (
    <div
      className={cn(
        "rounded-xl border",
        "bg-primary/10 border-primary/30",
        className,
      )}
    >
      {/* Header — always visible */}
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className={cn(
          "flex w-full items-center justify-between px-4 py-3",
          "text-start text-sm font-medium",
          "text-primary",
          "hover:bg-primary/10",
          "rounded-xl transition-colors",
        )}
        aria-expanded={expanded}
        aria-label={t("Workforce.thread.toggleContext", "Toggle context")}
      >
        <span>
          📌{" "}
          {t("Workforce.thread.fromSession", "From {{boardName}} session", {
            boardName,
          })}
        </span>
        <ChevronIcon expanded={expanded} />
      </button>

      {/* Body — collapsible via CSS grid-rows trick */}
      <div
        className={cn(
          "grid transition-[grid-template-rows] duration-300 ease-in-out",
          expanded ? "grid-rows-[1fr]" : "grid-rows-[0fr]",
        )}
      >
        <div className="overflow-hidden">
          <div className="space-y-3 px-4 pb-4">
            {/* Question */}
            {question && (
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {t("Workforce.thread.questionLabel", "Question")}
                </p>
                <p className="mt-1 text-sm text-foreground/80">
                  {question}
                </p>
              </div>
            )}

            {/* Response */}
            {response && (
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {t("Workforce.thread.responseLabel", "Response")}
                </p>
                <p className="mt-1 max-h-32 overflow-y-auto text-sm text-foreground/80">
                  {response}
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export { ContextCard };
export type { ContextCardProps };
