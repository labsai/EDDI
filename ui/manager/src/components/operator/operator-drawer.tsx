import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, Link } from "react-router-dom";
import { Sparkles, X, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { OperatorChat } from "@/components/operator/operator-chat";
import { useOperatorConfig } from "@/hooks/use-operator";
import { useOperatorChat } from "@/hooks/use-operator-chat";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";
import { useCurrentScreenContext, toContextPayload } from "@/hooks/use-current-screen-context";
import { useApprovalStatus, usePendingApprovals } from "@/hooks/use-hitl";
import { useAuth } from "@/hooks/use-auth";
import { cn } from "@/lib/utils";

/**
 * Header launcher for the Platform Operator, rendered inside `TopBar` (Manager)
 * and `WorkforceTopbar` (all three Workforce viewport branches) — the one piece
 * of chrome both shells genuinely share.
 *
 * It used to be a `fixed` bottom-right FAB, mounted separately in `AppLayout`
 * and in each of `WorkforceLayout`'s three branches. That position was pure
 * collision avoidance rather than design: the bottom-right corner already holds
 * sonner's toast viewport (z-999999999, which covered the launcher outright
 * while any toast was up), `ChatDrawer`'s composer, `WorkforceBottomTabs`, and
 * `workforce-dashboard`'s own `MobileFab` — the last of which won the hit test
 * and navigated to `/workforce/new` when tapped. Each dodge bought a hardcoded
 * offset (`bottom-24`, `bottom-40`) that only held until the next thing landed
 * in that corner. The header has a real slot for a persistent control, so the
 * whole class of collision goes away rather than being re-measured.
 *
 * Reuses `useOperatorChat` and `useOperatorConfig` directly — the SAME
 * react-query cache and the SAME shared conversation store the full
 * `/manage/operator` page reads, not a second copy of either. A gated write
 * started here and a gated write approved there are the same pause.
 */
export function OperatorDrawer() {
  const { t } = useTranslation();
  const location = useLocation();
  const isOpen = useOperatorDrawerStore((s) => s.isOpen);
  const close = useOperatorDrawerStore((s) => s.close);
  const toggle = useOperatorDrawerStore((s) => s.toggle);

  // The operator config lives in the global variable store, which the backend
  // restricts to eddi-admin/eddi-editor. Unlike every other caller of this
  // hook — all of them admin screens someone navigated to deliberately — this
  // component is mounted on EVERY page of both shells, so an ungated read here
  // is a 403 on every navigation for every other role (`eddi-approver` above
  // all, whose whole job is the approvals inbox). Both roles are checked
  // because the backend allows either; `useHasRole` returns true for all roles
  // when auth is off, so a no-auth deployment is unaffected.
  // One `useAuth()` rather than two `useHasRole()` calls behind `||`: the
  // short-circuit makes the second a conditional hook call, which
  // react-hooks/rules-of-hooks rejects (and `npm run lint` is a CI step). It
  // survives at runtime today only because useHasRole bottoms out in
  // useContext, which claims no hook slot — one useMemo added inside it and
  // this crashes every page of both shells.
  const { method: authMethod, roles } = useAuth();
  const canReadOperatorConfig =
    authMethod === "none" || roles.includes("eddi-admin") || roles.includes("eddi-editor");
  const { data: config, isLoading: configLoading, isError: configError } = useOperatorConfig(canReadOperatorConfig);
  const chat = useOperatorChat(config);
  // Mirrors operator.tsx's own preference for approval-status's pauseReason
  // over the chat hook's derived one: the hook's is null on some pause paths
  // (a 409 arriving with no reason of its own), and approval-status is the
  // endpoint that actually carries it.
  const approvalStatus = useApprovalStatus(chat.conversationId ?? undefined, chat.isPaused);
  const screenContext = useCurrentScreenContext();

  useEffect(() => {
    // A shared store keeps `error` alive for the whole tab session now, not
    // just one mount — an hour-old failure from a different surface should
    // not be the first thing shown on open.
    if (isOpen) chat.clearError();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  /**
   * Escape closes, focus moves in on open and back to the launcher on close.
   *
   * The panel now follows the launcher in the DOM, so tab order already reaches
   * it — but the move-in is kept regardless: the panel opens beneath a header
   * that still has controls after it (theme, language, user menu), so without
   * it a keyboard user tabs through the rest of the header before reaching what
   * they just opened. Not a focus trap: this panel is non-modal by design — the
   * page behind it stays usable — so trapping would be wrong.
   * `WorkforceLayout`'s nav drawer IS modal and does trap; the difference is
   * deliberate.
   */
  const panelRef = useRef<HTMLDivElement>(null);
  const fabRef = useRef<HTMLButtonElement>(null);
  const wasOpen = useRef(false);

  useEffect(() => {
    if (!isOpen) {
      // Only restore focus on a real open→close transition, never on mount,
      // or every page load would steal focus to the launcher.
      if (wasOpen.current) fabRef.current?.focus();
      wasOpen.current = false;
      return;
    }
    wasOpen.current = true;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    window.addEventListener("keydown", onKeyDown);
    // rAF so the panel has been laid out before we look for something to focus.
    const raf = requestAnimationFrame(() => {
      const target = panelRef.current?.querySelector<HTMLElement>(
        'input, button, [href], select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      target?.focus();
    });
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      cancelAnimationFrame(raf);
    };
  }, [isOpen, close]);

  /**
   * Whether a decision is waiting on someone, from SERVER state rather than
   * this tab's chat store.
   *
   * `isPaused` is only ever set by a turn this tab streamed, so after a reload
   * — or when the pause was raised in another tab, or by a scheduled run — the
   * launcher would look idle while the operator sat blocked. The pending list
   * is the same query the sidebar badge and the approvals inbox read, so this
   * costs no extra request, and it is readable by every role that can act on
   * an approval.
   */
  const { data: pendingApprovals } = usePendingApprovals();
  const operatorHasPendingApproval =
    chat.isPaused ||
    (pendingApprovals ?? []).some(
      (item) =>
        (chat.conversationId && item.conversationId === chat.conversationId) ||
        (config?.agentId && item.agentId === config.agentId),
    );

  // Redundant with the page you're already on, and structurally the one
  // guard that keeps two OperatorChat instances from ever being interactive
  // at the same time in this tab.
  if (location.pathname.startsWith("/manage/operator")) return null;

  // No launcher at all rather than an "activate it" call-to-action nobody
  // without these roles could act on. `configError` covers the same ground
  // empirically: if the read failed anyway (roles mapped differently than this
  // check assumes), we cannot know whether the operator is even on, and
  // offering to set up a second one is the worst possible guess.
  if (!canReadOperatorConfig || configError) return null;

  const isActive = Boolean(config?.enabled && config?.agentId);

  return (
    <div className="relative">
      <button
        ref={fabRef}
        onClick={toggle}
        className={cn(
          "relative flex h-9 w-9 items-center justify-center rounded-lg transition-colors",
          isOpen
            ? "bg-secondary text-foreground"
            : "text-muted-foreground hover:bg-secondary hover:text-foreground",
        )}
        title={t("operator.drawer.title", "Platform Operator")}
        aria-label={
          operatorHasPendingApproval
            ? t("operator.drawer.titleAwaiting", "Platform Operator — a decision is waiting on you")
            : t("operator.drawer.title", "Platform Operator")
        }
        aria-expanded={isOpen}
        aria-controls={isOpen ? "operator-drawer-panel" : undefined}
        data-testid="operator-drawer-fab"
      >
        {/* The pause is silent otherwise: the conversation simply stops and
            waits, with nothing anywhere saying so. */}
        {operatorHasPendingApproval && !isOpen && (
          <span
            className="absolute end-1 top-1 h-2.5 w-2.5 rounded-full border-2 border-card bg-amber-500"
            data-testid="operator-drawer-pending-dot"
            aria-hidden="true"
          />
        )}
        <Sparkles className="h-4 w-4" aria-hidden="true" />
      </button>

      {isOpen && (
        <div
          ref={panelRef}
          id="operator-drawer-panel"
          // Anchored to the launcher rather than the viewport, so it follows
          // the header instead of needing its own hardcoded offsets. The
          // height is capped against the viewport because the Workforce mobile
          // branch has far less room below the header than the desktop one.
          className="absolute end-0 top-full z-50 mt-2 flex h-[32rem] max-h-[calc(100vh-6rem)] w-96 max-w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-xl border border-border bg-card shadow-xl"
          role="complementary"
          aria-label={t("operator.drawer.title", "Platform Operator")}
          data-testid="operator-drawer-panel"
        >
          <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
            <Sparkles className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
            <p className="flex-1 truncate text-sm font-semibold text-foreground">
              {t("operator.drawer.title", "Platform Operator")}
            </p>
            <button
              onClick={close}
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
              title={t("common.close", "Close")}
              aria-label={t("common.close", "Close")}
              data-testid="operator-drawer-close"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          <div className="flex flex-1 flex-col overflow-hidden">
            {configLoading ? (
              <div className="flex flex-1 items-center justify-center">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : isActive ? (
              <OperatorChat
                messages={chat.messages}
                events={chat.events}
                tracesByMessageId={chat.tracesByMessageId}
                isStreaming={chat.isStreaming}
                error={chat.error}
                onSend={(input, attachments) => chat.send(input, toContextPayload(screenContext), attachments)}
                onStop={chat.stop}
                onReset={chat.reset}
                conversationId={chat.conversationId}
                onEnsureConversation={chat.ensureConversation}
                isPaused={chat.isPaused}
                pauseReason={approvalStatus.data?.pauseReason ?? chat.pauseReason}
                isResolvingPause={chat.isResolvingPause}
                resolveError={chat.resolveError}
                pauseSurface="compact"
              />
            ) : (
              <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
                <Sparkles className="h-8 w-8 text-muted-foreground/40" />
                <p className="text-sm text-muted-foreground">
                  {t(
                    "operator.drawer.notActivated",
                    "Turn on the Platform Operator to chat with your deployment from anywhere.",
                  )}
                </p>
                <Button asChild size="sm">
                  <Link to="/manage/operator" data-testid="operator-drawer-activate-link">
                    {t("operator.drawer.activate", "Set up the Platform Operator")}
                  </Link>
                </Button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
