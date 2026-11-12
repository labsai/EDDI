import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import type { UserEvent } from "@eddi/core/user-events";
import type { PendingApprovalSummary } from "@eddi/app-kit/lib/api/hitl";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import { useUserEvent } from "@eddi/app-kit/hooks/use-user-events";
import { approvalItems } from "@/lib/workforce-approvals";
import { isShowing, noticeFor } from "@/lib/workforce-notices";
import { shouldNotifyBrowser } from "@/hooks/use-browser-notices";

/**
 * Workforce's notices for what happened while the person was elsewhere (T2.3,
 * T2.8): a long job finished or failed, a team discussion ended or is waiting
 * for their turn, their paused conversation was decided. Mounted once by the
 * layout; renders nothing itself.
 *
 * - A toast (sonner, in the app's toaster: a polite live region, and it never
 *   takes focus), with "Open" to the conversation or team session.
 * - Nothing when that conversation is already on screen — the page shows it.
 * - Also a browser notification, only when the person opted in (the approvals
 *   menu) and the tab is in the background.
 */
export function WorkforceNotices() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { user, method } = useAuth();

  /** Whether a decided pause was one of the person's own, from the lists shown before it was decided. */
  function ownsApproval(event: UserEvent): boolean {
    const id = event.surface === "group" ? event.groupConversationId : event.conversationId;
    if (!id) return false;
    const cached = (prefix: string) =>
      queryClient
        .getQueriesData<PendingApprovalSummary[]>({ queryKey: [prefix] })
        .flatMap(([, data]) => data ?? []);
    const username = method === "none" ? null : (user?.username ?? null);
    return approvalItems(cached("pending-approvals"), cached("all-group-pending-approvals"), username).some(
      (item) => item.conversationId === id && item.mine,
    );
  }

  useUserEvent("*", (event) => {
    const notice = noticeFor(event, event.kind === "approval.decided" && ownsApproval(event));
    if (!notice || isShowing(location, notice.conversationId)) return;
    const message = t(`Workforce.notices.${notice.key}`);
    const href = notice.href;
    const options = {
      id: event.id,
      action: href ? { label: t("Workforce.notices.open", "Open"), onClick: () => navigate(href) } : undefined,
    };
    if (notice.tone === "error") toast.error(message, options);
    else toast(message, options);

    if (shouldNotifyBrowser()) {
      try {
        const browserNotice = new window.Notification(message, { tag: event.id });
        browserNotice.onclick = () => {
          window.focus();
          if (href) navigate(href);
          browserNotice.close();
        };
      } catch {
        // some browsers only notify from a service worker; the toast is there
      }
    }
  });

  return null;
}
