import { useTranslation } from "react-i18next";
import { ShieldCheck } from "lucide-react";
import { guardrailActive, type ConversationMessage } from "@eddi/conversation";
import type { ChatMessage } from "../../lib/api/chat";

/**
 * The activity indicator's guardrail state (@eddi/conversation's
 * `ThinkingIndicator guardrail`, in the Manager's styling): a guardrail fired on
 * the reply still being written, so the wait that follows is it re-asking the
 * model or rewriting the answer. Renders nothing otherwise.
 */
export function GuardrailActivity({ messages, isProcessing }: { messages: ChatMessage[]; isProcessing: boolean }) {
  const { t } = useTranslation();
  if (!guardrailActive(messages as ConversationMessage[], isProcessing)) return null;
  return (
    <div
      className="flex items-center gap-2 px-4 py-1.5 text-xs text-muted-foreground"
      role="status"
      data-testid="guardrail-activity"
    >
      <ShieldCheck className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span className="italic">{t("conversation.indicator.guardrail")}</span>
    </div>
  );
}
