import { memo, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Eye } from "lucide-react";
import { useUpdateAgent } from "@/hooks/use-agents";
import { getAgentUsage, type Agent } from "@/lib/api/agents";
import { EditorSection } from "./editor-section";

/**
 * Whether this agent's maintainers may read other people's conversations with
 * it — and how much it is used, which they may always see.
 *
 * <h3>Framed as a decision about other people</h3> A conversation belongs to
 * the person who had it. The switch is off unless someone turns it on, the
 * notice the chat shows is editable here so it can say who reads and why, and
 * the copy is explicit that only conversations on the new version are covered.
 */
export const ConversationReviewSection = memo(function ConversationReviewSection({
  agent,
  agentId,
  version,
}: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();
  const review = agent.conversationReview ?? {};
  const [notice, setNotice] = useState(review.notice ?? "");

  useEffect(() => {
    setNotice(agent.conversationReview?.notice ?? "");
  }, [agent.conversationReview?.notice]);

  const { data: usage } = useQuery({
    queryKey: ["agents", "usage", agentId],
    queryFn: () => getAgentUsage(agentId),
    retry: false,
    staleTime: 60_000,
  });

  function patch(updates: { enabled?: boolean; notice?: string | null }) {
    updateAgent.mutate({
      id: agentId,
      version,
      agent: { ...agent, conversationReview: { ...review, ...updates } },
    });
  }

  return (
    <EditorSection
      label={t("agentDetail.review.title", "Usage & conversation review")}
      icon={Eye}
      accent="text-sky-500"
      variant="card"
      defaultOpen={review.enabled ?? false}
    >
      <div className="space-y-4" data-testid="conversation-review-section">
        {usage && (
          <dl className="grid grid-cols-3 gap-3 text-center" data-testid="agent-usage">
            <div className="rounded-lg border border-border p-2">
              <dt className="text-[10px] text-muted-foreground">{t("agentDetail.review.total", "Conversations")}</dt>
              <dd className="text-lg font-semibold">{usage.total}</dd>
            </div>
            <div className="rounded-lg border border-border p-2">
              <dt className="text-[10px] text-muted-foreground">{t("agentDetail.review.active", "Active now")}</dt>
              <dd className="text-lg font-semibold">{usage.active}</dd>
            </div>
            <div className="rounded-lg border border-border p-2">
              <dt className="text-[10px] text-muted-foreground">{t("agentDetail.review.users", "People")}</dt>
              <dd className="text-lg font-semibold">{usage.distinctUsers}</dd>
            </div>
          </dl>
        )}

        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "agentDetail.review.description",
            "Conversations are private to the person who had them. Turn this on to let everyone who can edit this agent read its conversations — to see what people ask and improve it. The chat tells people before they type."
          )}
        </p>

        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={review.enabled ?? false}
            onChange={() => patch({ enabled: !(review.enabled ?? false) })}
            disabled={updateAgent.isPending}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="conversation-review-enabled"
          />
          {t("agentDetail.review.enable", "Let this agent's maintainers read its conversations")}
        </label>

        {review.enabled && (
          <div className="space-y-2 ps-5">
            <label htmlFor="review-notice" className="block text-[10px] text-muted-foreground">
              {t("agentDetail.review.notice", "What the chat tells people (leave empty for the standard wording)")}
            </label>
            <textarea
              id="review-notice"
              value={notice}
              onChange={(e) => setNotice(e.target.value)}
              onBlur={() => {
                const next = notice.trim() || null;
                if (next !== (review.notice ?? null)) patch({ notice: next });
              }}
              rows={2}
              maxLength={300}
              className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              placeholder={t(
                "agentDetail.review.noticePlaceholder",
                "Conversations with this agent may be read by the people who maintain it, to improve it."
              )}
              data-testid="conversation-review-notice"
            />
            <p className="text-[10px] text-muted-foreground">
              {t(
                "agentDetail.review.versionNote",
                "Covers conversations on the version this change creates and later ones — never what was said before. Slack, Teams and API clients cannot show the notice; if people reach this agent there, say it in the greeting."
              )}
            </p>
          </div>
        )}
      </div>
    </EditorSection>
  );
});
