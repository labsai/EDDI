import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Eye } from "lucide-react";
import { getAgentProfile } from "@/lib/api/agents";

/**
 * Tells the person chatting, before they type, that the agent's maintainers
 * may read the conversation — when the deployed version opted in.
 *
 * Renders nothing otherwise, and nothing when the profile cannot be read: a
 * missing notice must never be the reason a chat cannot start, and the backend
 * does not grant review access to a version that did not opt in, notice shown
 * or not.
 */
export function ReviewNotice({ agentId, environment }: { agentId: string | null; environment?: string }) {
  const { t } = useTranslation();
  const { data } = useQuery({
    queryKey: ["agents", "profile", agentId, environment ?? "production"],
    queryFn: () => getAgentProfile(agentId!, environment ?? "production"),
    enabled: !!agentId,
    retry: false,
    staleTime: 60_000,
  });
  if (!data?.reviewNotice) return null;
  return (
    <div
      className="flex items-start gap-2 border-b border-border bg-muted/40 px-4 py-2 text-xs text-muted-foreground"
      role="note"
      aria-label={t("chat.reviewNoticeLabel", "Conversation review")}
      data-testid="chat-review-notice"
    >
      <Eye className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span>{data.reviewNotice}</span>
    </div>
  );
}
