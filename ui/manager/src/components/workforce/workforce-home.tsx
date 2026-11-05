import { useId, useMemo, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowUp, MessageSquare, Users } from "lucide-react";
import { Button } from "@eddi/ui";
import { cn, getInitials } from "@/lib/utils";
import { isImeComposing } from "@/lib/ime";
import { useDeployedAgents } from "@/hooks/use-chat";
import { useCatalogAgents } from "@/hooks/use-catalog";
import { useWorkforceSessions } from "@/hooks/use-workforce-sessions";
import { useAgentReviewProfile } from "@/hooks/use-agent-review-profile";
import { ReviewNotice } from "@/components/chat/review-notice";
import {
  catalogDisplayName,
  safeAccentColor,
  safeAvatarUrl,
  type CatalogAgent,
} from "@/lib/api/catalog";
import { HOME_RECENT_COUNT, sessionLabel } from "@/lib/workforce-sessions";
import { workforceChatStart } from "@/lib/workforce-chat-start";

/**
 * Workforce Home (T1.4): a composer first, the agent's starters, the recent
 * conversations — the catalog follows below it (`EndUserCatalog`).
 *
 * Sending (or a starter) opens the chat with the text handed over in router
 * state, never in the URL: a link can preselect an agent, but only the person
 * typing here starts a conversation (the chat's deep-link rule).
 */

interface PickerAgent {
  id: string;
  name: string;
}

/** The composer with its agent picker and the picked agent's starters. */
export function HomeComposer() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const pickerId = useId();
  const { data: deployed } = useDeployedAgents();
  const { data: catalog } = useCatalogAgents();
  const { sessions } = useWorkforceSessions();
  const [text, setText] = useState("");
  const [picked, setPicked] = useState<string | null>(null);

  const appearanceById = useMemo(
    () => new Map<string, CatalogAgent>((catalog ?? []).map((a) => [a.agentId, a])),
    [catalog],
  );
  const agents: PickerAgent[] = useMemo(
    () =>
      (deployed ?? []).map((a) => {
        const entry = appearanceById.get(a.id);
        return { id: a.id, name: entry ? catalogDisplayName(entry) : a.name || a.id };
      }),
    [deployed, appearanceById],
  );

  // The default: the agent of the most recent chat, else the first listed.
  const defaultAgentId = useMemo(() => {
    const recent = sessions.find((s) => s.kind === "chat" && agents.some((a) => a.id === s.ownerId));
    return recent?.ownerId ?? agents[0]?.id ?? null;
  }, [sessions, agents]);
  const agentId = picked && agents.some((a) => a.id === picked) ? picked : defaultAgentId;
  const agent = agents.find((a) => a.id === agentId) ?? null;
  const starters = (agentId ? appearanceById.get(agentId)?.appearance?.starters : undefined) ?? [];

  // The chat sends what is typed here without showing its own notice first, so
  // the agent's review notice is shown here, and nothing is sent before the
  // lookup has answered (the same rule the chat keeps).
  const { pending: profilePending } = useAgentReviewProfile(agentId);

  const start = (message: string) => {
    const trimmed = message.trim();
    if (!agentId || !trimmed || profilePending) return;
    const { to, state } = workforceChatStart(agentId, trimmed);
    navigate(to, { state });
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    start(text);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !isImeComposing(e)) {
      e.preventDefault();
      start(text);
    }
  };

  if (agents.length === 0) return null;

  return (
    <section aria-label={t("Workforce.home.composerLabel", "Start a conversation")} data-testid="home-composer">
      <form
        onSubmit={onSubmit}
        className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm focus-within:ring-2 focus-within:ring-ring"
      >
        <ReviewNotice agentId={agentId} />
        <div className="p-3">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKeyDown}
          rows={2}
          dir="auto"
          placeholder={t("Workforce.home.placeholder", "Ask {{agent}}…", { agent: agent?.name ?? "" })}
          aria-label={t("Workforce.home.messageLabel", "Your message")}
          className="w-full resize-none bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
          data-testid="home-composer-input"
        />
        <div className="mt-2 flex items-center gap-2">
          <label htmlFor={pickerId} className="sr-only">
            {t("Workforce.home.agentLabel", "Agent")}
          </label>
          <select
            id={pickerId}
            value={agentId ?? ""}
            onChange={(e) => setPicked(e.target.value)}
            className="min-w-0 max-w-[60%] truncate rounded-lg border border-input bg-background ps-2 pe-2 py-1.5 text-xs text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid="home-agent-picker"
          >
            {agents.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
          <div className="flex-1" />
          <Button
            type="submit"
            size="icon"
            disabled={!text.trim() || !agentId || profilePending}
            aria-label={t("Workforce.home.send", "Send")}
            data-testid="home-composer-send"
          >
            <ArrowUp className="h-4 w-4" />
          </Button>
        </div>
        </div>
      </form>

      {starters.length > 0 && (
        <ul className="mt-3 flex flex-wrap gap-2" aria-label={t("Workforce.home.starters", "Suggestions")} data-testid="home-starters">
          {starters.map((starter, i) => (
            <li key={`${i}-${starter}`}>
              <button
                type="button"
                onClick={() => start(starter)}
                className="rounded-full border border-border bg-card ps-3 pe-3 py-1.5 text-start text-xs text-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                dir="auto"
                data-testid={`home-starter-${i}`}
              >
                {starter}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The last few chats and team sessions, from the sidebar's data. */
export function RecentSessions() {
  const { t, i18n } = useTranslation();
  const { sessions } = useWorkforceSessions();
  const recent = sessions.slice(0, HOME_RECENT_COUNT);
  if (recent.length === 0) return null;
  const formatDate = (ms: number) =>
    new Intl.DateTimeFormat(i18n.language, { month: "short", day: "numeric" }).format(new Date(ms));

  return (
    <section aria-label={t("Workforce.home.recent", "Recent")} data-testid="home-recent">
      <h2 className="mb-2 text-sm font-semibold text-foreground">{t("Workforce.home.recent", "Recent")}</h2>
      <ul className="flex flex-col divide-y divide-border rounded-xl border border-border bg-card">
        {recent.map((session) => {
          const Icon = session.kind === "team" ? Users : MessageSquare;
          return (
            <li key={`${session.kind}-${session.id}`}>
              <Link
                to={session.href}
                className="flex items-center gap-3 ps-3 pe-3 py-2 text-sm hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                data-testid={`home-recent-${session.kind}-${session.id}`}
              >
                <Icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="min-w-0 flex-1 truncate" dir="auto">
                  {sessionLabel(session, formatDate)}
                </span>
                <span className="shrink-0 text-xs text-muted-foreground">{session.ownerName}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** An agent's avatar: its `appearance.avatar`, else initials on its accent colour. */
export function AgentAvatar({ agent, className }: { agent: CatalogAgent; className?: string }) {
  const [failed, setFailed] = useState(false);
  const name = catalogDisplayName(agent);
  const avatar = safeAvatarUrl(agent.appearance?.avatar);
  const accent = safeAccentColor(agent.appearance?.accentColor);
  if (avatar && !failed) {
    return (
      <img
        src={avatar}
        alt=""
        onError={() => setFailed(true)}
        className={cn("h-9 w-9 rounded-full object-cover", className)}
        data-testid={`agent-avatar-img-${agent.agentId}`}
      />
    );
  }
  return (
    <span
      className={cn(
        "flex h-9 w-9 items-center justify-center rounded-full text-xs font-semibold",
        !accent && "bg-muted text-muted-foreground",
        className,
      )}
      style={accent ? { backgroundColor: accent, color: "#fff" } : undefined}
      aria-hidden
    >
      {getInitials(name)}
    </span>
  );
}
