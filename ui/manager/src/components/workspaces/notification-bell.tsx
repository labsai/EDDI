import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Bell, CheckCheck, KeyRound, Share2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/lib/api-client";
import {
  getNotifications,
  getUnreadNotificationCount,
  markNotificationsRead,
  type WorkspaceNotification,
} from "@/lib/api/workspaces";
import { shareResource, type AccessLevel } from "@/lib/api/sharing";
import { userSubject } from "@/lib/spaces";
import { chatLinkFor, isAgentUri, managerRouteFor } from "@/lib/resource-links";
import { useSpaces } from "@/hooks/use-spaces";

const NOTIFICATION_KEYS = {
  count: ["workspaces", "notifications", "count"] as const,
  list: ["workspaces", "notifications", "list"] as const,
};

/**
 * Shares with the signed-in user, and access requests for what they own.
 *
 * <h3>Why this exists</h3> A share used to change a grant and nothing else: the
 * recipient found out only if the sharer told them some other way, and then had
 * to hunt for the resource. An access request had nowhere to go at all. Both now
 * land here, and an owner can grant a request in one click.
 *
 * Hidden while workspaces are not enforced — nothing is shared then, because
 * everybody already sees everything.
 */
export function NotificationBell() {
  const { enabled } = useSpaces();
  if (!enabled) return null;
  return <NotificationBellInner />;
}

function NotificationBellInner() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);

  const { data: unread = 0 } = useQuery({
    queryKey: NOTIFICATION_KEYS.count,
    queryFn: getUnreadNotificationCount,
    // A badge, not a chat: a minute is prompt enough, and polling pauses in a
    // background tab.
    refetchInterval: 60_000,
    retry: 1,
  });

  const { data: notifications, isLoading } = useQuery({
    queryKey: NOTIFICATION_KEYS.list,
    queryFn: () => getNotifications(false, 20),
    enabled: open,
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["workspaces", "notifications"] });
  };

  const markRead = useMutation({
    mutationFn: (ids?: string[]) => markNotificationsRead(ids),
    onSuccess: refresh,
    onError: (e) => toast.error(getErrorMessage(e)),
  });

  const grant = useMutation({
    mutationFn: async (n: WorkspaceNotification) => {
      const subject = userSubject(n.actor);
      if (!subject) throw new Error("No requester");
      await shareResource(n.resourceId, subject, n.level as AccessLevel);
      await markNotificationsRead([n.id]);
    },
    onSuccess: (_, n) => {
      toast.success(
        t("workspaces.notifications.granted", "Access granted to {{name}}", { name: n.actorLabel || n.actor })
      );
      refresh();
      void queryClient.invalidateQueries({ queryKey: ["shares", n.resourceId] });
    },
    onError: (e) => toast.error(getErrorMessage(e)),
  });

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger
        className="relative flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        aria-label={
          unread > 0
            ? t("workspaces.notifications.labelUnread", "Notifications — {{count}} unread", { count: unread })
            : t("workspaces.notifications.label", "Notifications")
        }
        data-testid="notification-bell"
      >
        <Bell className="h-4 w-4" aria-hidden="true" />
        {unread > 0 && (
          <span
            className="absolute -top-0.5 -end-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold text-primary-foreground"
            data-testid="notification-count"
          >
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-96 max-w-[calc(100vw-2rem)] p-0">
        <div className="flex items-center justify-between px-3 py-2">
          <DropdownMenuLabel className="p-0">{t("workspaces.notifications.label", "Notifications")}</DropdownMenuLabel>
          {unread > 0 && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => markRead.mutate(undefined)}
              disabled={markRead.isPending}
              data-testid="notifications-mark-all"
            >
              <CheckCheck className="h-4 w-4" aria-hidden="true" />
              {t("workspaces.notifications.markAll", "Mark all read")}
            </Button>
          )}
        </div>
        <DropdownMenuSeparator className="m-0" />
        <div className="max-h-[28rem] overflow-y-auto">
          {isLoading && <p className="px-3 py-6 text-center text-sm text-muted-foreground">{t("common.loading", "Loading…")}</p>}
          {!isLoading && (notifications ?? []).length === 0 && (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground" data-testid="notifications-empty">
              {t("workspaces.notifications.empty", "Nothing new. Shares with you and access requests appear here.")}
            </p>
          )}
          <ul className="divide-y divide-border">
            {(notifications ?? []).map((n) => (
              <NotificationItem
                key={n.id}
                notification={n}
                busy={grant.isPending || markRead.isPending}
                onGrant={() => grant.mutate(n)}
                onDismiss={() => markRead.mutate([n.id])}
                onNavigate={() => {
                  if (!n.readAt) markRead.mutate([n.id]);
                  setOpen(false);
                }}
              />
            ))}
          </ul>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function NotificationItem({
  notification: n,
  busy,
  onGrant,
  onDismiss,
  onNavigate,
}: {
  notification: WorkspaceNotification;
  busy: boolean;
  onGrant: () => void;
  onDismiss: () => void;
  onNavigate: () => void;
}) {
  const { t } = useTranslation();
  const who = n.actorLabel || n.actor;
  const what = n.resourceName || t("workspaces.notifications.unnamed", "an unnamed resource");
  const level = levelLabel(t, n.level);
  const unread = !n.readAt;
  const request = n.type === "ACCESS_REQUESTED";
  // Somebody given only USE cannot open the configuration page — send them to
  // the chat instead, which is what "shared so you can chat with it" means.
  const chatOnly = !request && n.level === "USE" && isAgentUri(n.resourceUri);
  const route = managerRouteFor(n.resourceUri, n.resourceId);

  return (
    <li className={cn("space-y-2 px-3 py-3", unread && "bg-primary/5")} data-testid={`notification-${n.id}`}>
      <div className="flex items-start gap-2">
        {request ? (
          <KeyRound className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        ) : (
          <Share2 className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        )}
        <div className="min-w-0 flex-1 space-y-1">
          <p className="text-sm">
            {request
              ? t("workspaces.notifications.requested", "{{who}} asks for “{{level}}” access to {{what}}", { who, what, level })
              : t("workspaces.notifications.shared", "{{who}} shared {{what}} with you — {{level}}", { who, what, level })}
          </p>
          {n.message && <p className="rounded-md bg-muted/50 px-2 py-1 text-xs text-muted-foreground">“{n.message}”</p>}
          <p className="text-xs text-muted-foreground">{new Date(n.createdAt).toLocaleString()}</p>
        </div>
      </div>
      <div className="flex flex-wrap gap-2 ps-6">
        {request && unread && (
          <Button size="sm" onClick={onGrant} disabled={busy} data-testid={`notification-grant-${n.id}`}>
            {t("workspaces.notifications.grant", "Grant")}
          </Button>
        )}
        {chatOnly ? (
          <Button size="sm" variant="outline" asChild>
            <a href={chatLinkFor(n.resourceId)} target="_blank" rel="noopener noreferrer" onClick={onNavigate}>
              {t("workspaces.notifications.openChat", "Open chat")}
            </a>
          </Button>
        ) : (
          route && (
            <Button size="sm" variant="outline" asChild>
              <Link to={route} onClick={onNavigate}>
                {t("workspaces.notifications.open", "Open")}
              </Link>
            </Button>
          )
        )}
        {unread && (
          <Button size="sm" variant="ghost" onClick={onDismiss} disabled={busy} data-testid={`notification-dismiss-${n.id}`}>
            {t("workspaces.notifications.dismiss", "Dismiss")}
          </Button>
        )}
      </div>
    </li>
  );
}

function levelLabel(t: (k: string, d: string) => string, level: string): string {
  switch (level) {
    case "USE":
      return t("workspaces.level.use", "Can chat");
    case "VIEW":
      return t("workspaces.level.view", "Can view");
    case "EDIT":
      return t("workspaces.level.edit", "Can edit");
    case "OWN":
      return t("workspaces.level.own", "Owner");
    default:
      return level;
  }
}
