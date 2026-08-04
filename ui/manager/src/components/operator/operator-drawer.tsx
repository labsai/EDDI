import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, Link } from "react-router-dom";
import { Sparkles, X, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { OperatorChat } from "@/components/operator/operator-chat";
import { useOperatorConfig } from "@/hooks/use-operator";
import { useOperatorChat } from "@/hooks/use-operator-chat";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";
import { useCurrentScreenContext, toContextPayload } from "@/hooks/use-current-screen-context";
import { useApprovalStatus } from "@/hooks/use-hitl";
import { useAuth } from "@/hooks/use-auth";
import { cn } from "@/lib/utils";

export interface OperatorDrawerProps {
  /**
   * The Workforce mobile layout's `WorkforceBottomTabs` is a `fixed bottom-0
   * h-16` (64px) bar — the same reason that layout's own `<main>` carries a
   * `pb-20` it does not use anywhere else. Without this, the launcher's
   * default `bottom-6` sits ~40px into that bar rather than above it.
   */
  clearsBottomTabBar?: boolean;
}

/**
 * Floating launcher for the Platform Operator, mounted once in `AppLayout`
 * and once in each of `WorkforceLayout`'s three viewport branches — a
 * self-positioned `fixed` panel sidesteps the fact that those four layouts
 * share no common chrome slot the way `ChatDrawer` shares `AppLayout`'s.
 *
 * Reuses `useOperatorChat` and `useOperatorConfig` directly — the SAME
 * react-query cache and the SAME shared conversation store the full
 * `/manage/operator` page reads, not a second copy of either. A gated write
 * started here and a gated write approved there are the same pause.
 */
export function OperatorDrawer({ clearsBottomTabBar = false }: OperatorDrawerProps) {
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
    <div
      className={cn(
        // z-30, not z-40: both shells render their mobile/tablet nav backdrop
        // at z-40 and this component AFTER them, so at equal z-index the later
        // DOM node wins and the launcher painted over an open nav overlay —
        // clickable, and in Workforce's tablet branch that overlay is
        // `aria-modal="true"`, so it opened a second panel on top of a modal.
        "fixed end-6 z-30 flex flex-col items-end gap-3",
        clearsBottomTabBar ? "bottom-20" : "bottom-6",
      )}
    >
      {isOpen && (
        <div
          className="flex h-[32rem] w-96 max-w-[calc(100vw-3rem)] flex-col overflow-hidden rounded-xl border border-border bg-card shadow-xl"
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
                onSend={(input) => chat.send(input, toContextPayload(screenContext))}
                onStop={chat.stop}
                onReset={chat.reset}
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

      <button
        onClick={toggle}
        className="flex h-14 w-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-transform hover:scale-105"
        title={t("operator.drawer.title", "Platform Operator")}
        aria-label={t("operator.drawer.title", "Platform Operator")}
        aria-expanded={isOpen}
        data-testid="operator-drawer-fab"
      >
        {isOpen ? <X className="h-5 w-5" aria-hidden="true" /> : <Sparkles className="h-5 w-5" aria-hidden="true" />}
      </button>
    </div>
  );
}
