import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { KeyRound, MessageSquare, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { getErrorMessage } from "@/lib/api-client";
import { getAgentProfile } from "@/lib/api/agents";
import { requestAccess, type AccessLevel } from "@/lib/api/sharing";
import { Link } from "react-router-dom";
import { managerChatPath } from "@/lib/resource-links";

type RequestableLevel = Exclude<AccessLevel, "OWN">;

interface RequestAccessPanelProps {
  resourceId: string;
  /** For an agent: check whether the caller may already chat with it. */
  isAgent?: boolean;
}

/**
 * What a page shows instead of a bare error when the caller is not allowed to
 * open the resource — with a way to ask for access.
 *
 * <h3>Why not just an error</h3> A link to an agent is the most common way work
 * gets shared, and until now following one without access showed "Something went
 * wrong" and a Retry button that could never succeed. The honest answer is "you
 * do not have access", and the useful one is "ask for it".
 *
 * <h3>Careful wording</h3> The server never says whether the resource exists or
 * who owns it — a request for an id that matches nothing is answered exactly like
 * a delivered one — so neither does this: it says the request was sent, not that
 * a named person was told.
 */
export function RequestAccessPanel({ resourceId, isAgent }: RequestAccessPanelProps) {
  const { t } = useTranslation();
  const [message, setMessage] = useState("");
  const [sent, setSent] = useState(false);

  // An agent shared at "Can chat" cannot be opened here, and that is by design.
  // Finding out lets the page say so and offer the chat, instead of implying the
  // user has no access at all.
  const { data: profile } = useQuery({
    queryKey: ["agents", "profile", resourceId],
    queryFn: () => getAgentProfile(resourceId),
    enabled: !!isAgent,
    retry: false,
  });
  const canChat = !!profile;
  const [chosenLevel, setLevel] = useState<RequestableLevel>("USE");
  // Asking to chat with an agent you can already chat with is not a request;
  // the profile arrives after the first render, so this is derived, not stored.
  const level: RequestableLevel = canChat && chosenLevel === "USE" ? "VIEW" : chosenLevel;

  const request = useMutation({
    mutationFn: () => requestAccess(resourceId, level, message),
    onSuccess: (outcome) => {
      if (outcome === "ALREADY_HAS_ACCESS") {
        toast.success(t("workspaces.request.alreadyHas", "You already have that access — reload the page."));
        return;
      }
      setSent(true);
    },
    onError: (e) => toast.error(getErrorMessage(e)),
  });

  const levels: { value: RequestableLevel; label: string }[] = [
    { value: "USE", label: t("workspaces.level.use", "Can chat") },
    { value: "VIEW", label: t("workspaces.level.view", "Can view") },
    { value: "EDIT", label: t("workspaces.level.edit", "Can edit") },
  ];

  return (
    <div className="mx-auto max-w-lg space-y-4 rounded-xl border border-border bg-card p-6" data-testid="request-access-panel">
      <div className="flex items-center gap-3">
        <KeyRound className="h-8 w-8 text-primary" aria-hidden="true" />
        <div>
          <h2 className="text-lg font-semibold">
            {canChat
              ? t("workspaces.request.chatOnlyTitle", "You can chat with this agent")
              : t("workspaces.request.title", "You don't have access to this")}
          </h2>
          <p className="text-sm text-muted-foreground">
            {canChat
              ? t("workspaces.request.chatOnly", "It was shared with you for chatting, so its configuration stays private.")
              : t("workspaces.request.subtitle", "It may be private, or shared with other people only. You can ask for access.")}
          </p>
        </div>
      </div>

      {canChat && (
        <Button variant="outline" asChild>
          <Link to={managerChatPath(resourceId, profile?.name)} data-testid="request-access-open-chat">
            <MessageSquare className="h-4 w-4" aria-hidden="true" />
            {t("workspaces.notifications.openChat", "Open chat")}
          </Link>
        </Button>
      )}

      {sent ? (
        <p className="rounded-md border border-primary/30 bg-primary/5 p-3 text-sm" role="status" data-testid="request-access-sent">
          {t(
            "workspaces.request.sent",
            "Request sent. If this exists and has an owner, they will see it in their notifications — and once they grant it, you will too."
          )}
        </p>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-col gap-2 sm:flex-row">
            <select
              value={level}
              onChange={(e) => setLevel(e.target.value as RequestableLevel)}
              aria-label={t("workspaces.share.levelLabel", "Access level")}
              className="h-10 rounded-lg border border-border bg-background px-2 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              data-testid="request-access-level"
            >
              {levels
                .filter((option) => !canChat || option.value !== "USE")
                .map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
            </select>
            <Input
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              maxLength={500}
              placeholder={t("workspaces.request.messagePlaceholder", "Why do you need it? (optional)")}
              aria-label={t("workspaces.request.message", "Message to the owner")}
              data-testid="request-access-message"
            />
          </div>
          <Button onClick={() => request.mutate()} disabled={request.isPending} data-testid="request-access-submit">
            <Send className="h-4 w-4" aria-hidden="true" />
            {t("workspaces.request.action", "Request access")}
          </Button>
        </div>
      )}
    </div>
  );
}
