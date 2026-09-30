import { useTranslation } from "react-i18next";
import { ArrowRightLeft } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Marks the step on which a conversation moved to another version of its agent
 * (step data `agent:switch`) — the version that answered from here on differs
 * from the one before.
 */
export function AgentSwitchNotice({ from, to, className }: { from: number; to: number; className?: string }) {
  const { t } = useTranslation();
  return (
    <div
      role="note"
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md bg-sky-500/10 px-2 py-1 text-[11px] font-medium text-sky-700 dark:text-sky-300",
        className,
      )}
      data-testid="agent-switch-notice"
    >
      <ArrowRightLeft className="h-3 w-3" aria-hidden="true" />
      {t("agentVersioning.switchNotice", "Agent moved from v{{from}} to v{{to}}", { from, to })}
    </div>
  );
}
