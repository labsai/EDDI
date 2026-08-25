import { useEffect, useMemo, useState } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Link2,
  Link2Off,
  Loader2,
  PlugZap,
  RefreshCw,
  ShieldOff,
  PowerOff,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { GrantStatusBadge } from "@/components/connections/connection-badges";
import {
  useMyConnections,
  useAuthorizeConnection,
  useDisconnectConnection,
} from "@/hooks/use-connections";
import { navigateAway } from "@/lib/navigate-away";
import {
  CONNECTIONS_DISABLED,
  CONNECTIONS_FORBIDDEN,
  type LinkedAccount,
} from "@/lib/api/connections";
import type { TFunction } from "i18next";

/** A connection this viewer could link but has not. Only an admin can enumerate these. */
export interface ConnectableConnection {
  name: string;
  description?: string | null;
}

interface LinkedAccountsPanelProps {
  /**
   * Per-user connections available to link.
   *
   * Optional because there is **no per-user endpoint that lists them**:
   * `/connections/mine` returns grants that already exist, and enumerating
   * connections needs `eddi-admin`. So a non-admin sees what they have linked
   * and nothing else, which is the honest rendering of the API rather than a
   * gap to paper over — the first link is normally started from wherever the
   * agent asked for it.
   */
  connectable?: ConnectableConnection[];
  /** Rendered without the surrounding card chrome, for a page that supplies its own. */
  bare?: boolean;
}

/**
 * "My linked accounts" — the per-user half of connections.
 *
 * Deliberately usable by anyone signed in, not just an `eddi-admin`: linking
 * your own account is not an administrative act, and the backend agrees (these
 * routes are `@Authenticated`, not role-gated). It is embedded on the admin page
 * as well, because `ConnectionsConfig.defaultReturnTo()` sends people there.
 *
 * The two "this is off" states are states, not errors. A deployment with
 * `eddi.connections.enabled=false` is not broken and a deployment without OIDC
 * is not broken either; each gets a panel that says which switch is involved.
 */
export function LinkedAccountsPanel({
  connectable,
  bare = false,
}: LinkedAccountsPanelProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const { data: accounts, isLoading, isError, error, refetch } = useMyConnections();
  const authorize = useAuthorizeConnection();
  const disconnect = useDisconnectConnection();

  const [unlinkTarget, setUnlinkTarget] = useState<string | null>(null);
  /** Which connection the browser is on its way to a provider for. */
  const [leavingFor, setLeavingFor] = useState<string | null>(null);
  /** A healthy account whose Reconnect needs confirming before the page leaves. */
  const [reconnectTarget, setReconnectTarget] = useState<string | null>(null);
  /**
   * One authorize at a time, for every row type.
   *
   * The connectable rows already locked globally while the dashed Connect rows
   * locked only themselves, so a slow authorize left every Reconnect live. Two
   * in-flight flows both call `window.location.assign`, and the last one to
   * resolve wins — which need not be the one the spinner is on.
   */
  const linkInFlight = leavingFor !== null;

  /**
   * Come back from the provider with the buttons live again.
   *
   * `leavingFor` is deliberately not cleared after `navigateAway` — while the
   * page really is leaving, the lock and the spinner should hold. But Back from
   * the provider's consent screen restores this page from the back/forward
   * cache with its JS state intact, so the flag survives a navigation that
   * never completed: every Connect and Reconnect stays disabled and one row
   * spins indefinitely, until a manual reload. `pageshow` with `persisted` is
   * the only signal that distinguishes a restore from a fresh load.
   */
  useEffect(() => {
    const onPageShow = (e: PageTransitionEvent) => {
      if (e.persisted) setLeavingFor(null);
    };
    window.addEventListener("pageshow", onPageShow);
    return () => window.removeEventListener("pageshow", onPageShow);
  }, []);

  const code = (error as { code?: string } | null)?.code;
  /**
   * A failure state only replaces the list when there is no list to show.
   *
   * React Query keeps the last good `data` when a *background* refetch fails,
   * so an ungated branch turns one blip — a lapsed token, a moment offline, or
   * the refetch that any `["connections"]` invalidation triggers — into
   * "linking is switched off" over a screen that was working. Both of these are
   * definitive statements about the deployment; neither is true of a transient
   * error, and both were being rendered on top of live data.
   */
  const showFailureState = isError && (accounts ?? []).length === 0;

  /**
   * Connections that can be linked but are not.
   *
   * Compared by the connection NAME, which is what a grant is filed under —
   * matching on the descriptor's display name would offer "Connect" beside an
   * account that is already linked.
   */
  const notYetLinked = useMemo(() => {
    if (!connectable) return [];
    const linked = new Set((accounts ?? []).map((a) => a.connection));
    return connectable.filter((c) => !linked.has(c.name));
  }, [connectable, accounts]);

  /**
   * Send the browser to the provider.
   *
   * A **top-level navigation**, not a popup and not an iframe: the nonce cookie
   * that binds this flow to this browser is `SameSite=Lax`, which admits a
   * top-level GET return and nothing else. The page is leaving, so this is
   * offered only from surfaces with nothing unsaved on them — never from the
   * connection editor, where it would silently discard a draft.
   */
  const startLinking = async (name: string) => {
    if (linkInFlight) return;
    setLeavingFor(name);
    try {
      const { authorizationUrl } = await authorize.mutateAsync({
        name,
        // Same-origin relative path. The backend validates it and falls back to
        // its own default page rather than refusing, so a rejected value costs
        // the user their place on the page but never the link itself.
        returnTo: location.pathname,
      });
      navigateAway(authorizationUrl);
    } catch (err) {
      setLeavingFor(null);
      toast.error(
        connectionErrorMessage(
          t,
          err,
          t("connections.linkStartFailed", "Could not start linking."),
        ),
      );
    }
  };

  const confirmUnlink = async () => {
    if (!unlinkTarget) return;
    try {
      await disconnect.mutateAsync({ name: unlinkTarget });
      toast.success(
        t("connections.unlinked", {
          name: unlinkTarget,
          defaultValue: '"{{name}}" is no longer linked to your account.',
        }),
      );
    } catch (err) {
      toast.error(
        connectionErrorMessage(
          t,
          err,
          t("connections.unlinkFailed", "Could not unlink that account."),
        ),
      );
    } finally {
      setUnlinkTarget(null);
    }
  };

  const body = (() => {
    if (isLoading) {
      return (
        <div className="space-y-2" data-testid="linked-accounts-loading">
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-card p-4">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="mt-2 h-3 w-24" />
            </div>
          ))}
        </div>
      );
    }

    // Not an outage. `eddi.connections.enabled` defaults to false — a surface
    // that stores refresh tokens is one an operator turns on deliberately — and
    // a backend older than the feature answers the same way, which from here is
    // the same fact.
    if (showFailureState && code === CONNECTIONS_DISABLED) {
      return (
        <div
          className="rounded-xl border border-border bg-muted/30 p-5"
          data-testid="connections-disabled"
        >
          <div className="flex items-start gap-3">
            <PowerOff className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">
                {t("connections.disabledTitle", "Account linking is switched off")}
              </p>
              <p className="text-sm text-muted-foreground">
                {t(
                  "connections.disabledBody",
                  "This deployment does not store per-user credentials. An administrator turns it on with eddi.connections.enabled=true.",
                )}
              </p>
            </div>
          </div>
        </div>
      );
    }

    // Authorization is off, or nobody is signed in. Linking mints a credential,
    // and a credential minted for a self-asserted identity belongs to whoever
    // asked for it — so the backend refuses rather than guessing.
    if (showFailureState && code === CONNECTIONS_FORBIDDEN) {
      return (
        <div
          className="rounded-xl border border-border bg-muted/30 p-5"
          data-testid="connections-no-identity"
        >
          <div className="flex items-start gap-3">
            <ShieldOff className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">
                {t("connections.noIdentityTitle", "Sign in to link an account")}
              </p>
              <p className="text-sm text-muted-foreground">
                {t(
                  "connections.noIdentityBody",
                  "A linked account belongs to a specific person, so it needs a verified sign-in. This deployment is not asking anyone to sign in.",
                )}
              </p>
            </div>
          </div>
        </div>
      );
    }

    if (showFailureState) {
      return (
        <ErrorState
          message={t("connections.loadMineFailed", "Could not load your linked accounts")}
          onRetry={() => void refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      );
    }

    const linked = accounts ?? [];
    if (linked.length === 0 && notYetLinked.length === 0) {
      return (
        <EmptyState
          icon={Link2}
          title={t("connections.noneLinked", "No linked accounts")}
          description={t(
            "connections.noneLinkedDesc",
            "When an agent needs to act as you in another service, you will be asked to connect it. Accounts you connect appear here, and you can unlink them at any time.",
          )}
        />
      );
    }

    return (
      <div className="space-y-2">
        {linked.map((account) => (
          <LinkedAccountRow
            key={account.connection}
            account={account}
            locale={i18n.language}
            busy={leavingFor === account.connection}
            disabled={linkInFlight}
            onReconnect={() => {
              // A healthy account has nothing to fix, and Reconnect sits one
              // gap from Unlink — so the click that throws the whole tab out to
              // a provider's consent screen asks first. A broken one goes
              // straight through: reconnecting IS the remedy there.
              if (account.status === "ACTIVE" || account.status === "EXPIRED") {
                setReconnectTarget(account.connection);
                return;
              }
              void startLinking(account.connection);
            }}
            onUnlink={() => setUnlinkTarget(account.connection)}
          />
        ))}

        {notYetLinked.map((connection) => (
          <div
            key={connection.name}
            data-testid={`connectable-${connection.name}`}
            className="flex flex-wrap items-center gap-3 rounded-xl border border-dashed border-border bg-card/50 p-4"
          >
            <PlugZap className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="truncate font-medium text-foreground">{connection.name}</p>
              {connection.description && (
                <p className="truncate text-xs text-muted-foreground">
                  {connection.description}
                </p>
              )}
            </div>
            <Button
              size="sm"
              onClick={() => void startLinking(connection.name)}
              disabled={linkInFlight}
              data-testid={`connect-${connection.name}`}
            >
              {leavingFor === connection.name ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
              ) : (
                <Link2 className="h-3.5 w-3.5" aria-hidden="true" />
              )}
              {leavingFor === connection.name
                ? t("connections.redirecting", "Taking you there…")
                : t("connections.connect", "Connect")}
            </Button>
          </div>
        ))}
      </div>
    );
  })();

  const content = (
    <>
      {body}
      <AlertDialog
        open={reconnectTarget !== null}
        onOpenChange={() => setReconnectTarget(null)}
        variant="warning"
        title={t("connections.confirmReconnect", "Connect this account again?")}
        description={t(
          "connections.confirmReconnectDesc",
          "This account is working, so there is nothing to repair. Continuing takes you out of EDDI to the provider's sign-in page and replaces the access you granted before.",
        )}
        onConfirm={() => {
          const name = reconnectTarget;
          setReconnectTarget(null);
          if (name) void startLinking(name);
        }}
        confirmLabel={t("connections.reconnect", "Reconnect")}
        cancelLabel={t("common.cancel", "Cancel")}
      />

      <AlertDialog
        open={unlinkTarget !== null}
        onOpenChange={() => setUnlinkTarget(null)}
        title={t("connections.confirmUnlink", "Unlink this account?")}
        description={t(
          "connections.confirmUnlinkDesc",
          "EDDI will forget the credentials you granted and can no longer act as you in that service. You can connect again whenever you like.",
        )}
        // Warning, not destructive: nothing is lost that cannot be granted
        // again, and dressing a reversible act in red teaches people to click
        // through red.
        variant="warning"
        onConfirm={() => void confirmUnlink()}
        confirmLabel={t("connections.unlink", "Unlink")}
        cancelLabel={t("common.cancel", "Cancel")}
        isPending={disconnect.isPending}
      />
    </>
  );

  if (bare) return content;

  return (
    <section className="space-y-3" data-testid="linked-accounts-panel">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-semibold text-foreground">
          <Link2 className="h-5 w-5 text-primary" aria-hidden="true" />
          {t("connections.mineTitle", "Your linked accounts")}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {t(
            "connections.mineSubtitle",
            "Services you have allowed EDDI to use on your behalf. Nobody else can use them, and EDDI never shows you the credentials.",
          )}
        </p>
      </div>
      {content}
    </section>
  );
}

/**
 * A `ConnectionsError` as a sentence the reader's locale actually speaks.
 *
 * `ConnectionsError.message` is a hardcoded English fallback — the API layer has
 * no React context and cannot call `t()`, which is precisely why it carries a
 * `code` instead. Toasting `err.message` threw that away and put English in
 * front of every non-English user, on the same screen whose body renders the
 * identical fact translated. That is the regression the code mechanism exists
 * to prevent, so the codes are resolved here rather than at each call site.
 */
function connectionErrorMessage(
  t: TFunction,
  error: unknown,
  fallback: string,
): string {
  const code = (error as { code?: string } | null)?.code;
  if (code === CONNECTIONS_DISABLED) {
    return t("connections.disabledTitle", "Account linking is switched off");
  }
  if (code === CONNECTIONS_FORBIDDEN) {
    return t("connections.noIdentityTitle", "Sign in to link an account");
  }
  // Anything else carries the backend's own message, which is written for the
  // person who has to act on it — a deleted connection, a store that is down.
  return error instanceof Error && error.message ? error.message : fallback;
}

/* ─── One linked account ───────────────────────────────────────── */

function LinkedAccountRow({
  account,
  locale,
  busy,
  disabled,
  onReconnect,
  onUnlink,
}: {
  account: LinkedAccount;
  locale: string;
  busy: boolean;
  /** Another flow is already on its way to a provider. */
  disabled: boolean;
  onReconnect: () => void;
  onUnlink: () => void;
}) {
  const { t } = useTranslation();
  // REVOKED and REFRESH_FAILED cannot produce a token, so reconnecting is the
  // fix and gets the emphasis. EXPIRED deliberately does not: an expired access
  // token refreshes itself on the next call, and a prominent "Reconnect" there
  // would cost the user a consent screen to fix nothing.
  const needsAttention =
    account.status === "REFRESH_FAILED" || account.status === "REVOKED";
  const expiresAt = formatInstant(account.expiresAt, locale);

  return (
    <div
      data-testid={`linked-account-${account.connection}`}
      className={`flex flex-wrap items-center gap-3 rounded-xl border bg-card p-4 ${
        needsAttention ? "border-destructive/40" : "border-border"
      }`}
    >
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate font-medium text-foreground">
            {account.connection}
          </span>
          <GrantStatusBadge status={account.status} />
        </div>
        <p
          className="mt-1 text-xs text-muted-foreground"
          // The raw expiry is real information an operator occasionally wants,
          // and a terrible headline: an access token that expires in forty
          // minutes and renews itself reads as a countdown to a problem.
          title={
            expiresAt
              ? t("connections.detail.expiresAt", {
                  date: expiresAt,
                  defaultValue: "Access token valid until {{date}}",
                })
              : undefined
          }
        >
          {statusDetail(t, account, locale)}
        </p>
        {account.scopes && account.scopes.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">
            {account.scopes.map((scope) => (
              <Badge key={scope} variant="secondary" className="font-mono text-[10px]">
                {scope}
              </Badge>
            ))}
          </div>
        )}
      </div>
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          variant={needsAttention ? "primary" : "outline"}
          onClick={onReconnect}
          disabled={busy || disabled}
          data-testid={`reconnect-${account.connection}`}
        >
          {busy ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
          ) : (
            <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
          )}
          {t("connections.reconnect", "Reconnect")}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          className="text-destructive hover:text-destructive"
          onClick={onUnlink}
          data-testid={`unlink-${account.connection}`}
        >
          <Link2Off className="h-3.5 w-3.5" aria-hidden="true" />
          {t("connections.unlink", "Unlink")}
        </Button>
      </div>
    </div>
  );
}

/**
 * The sentence under the status chip.
 *
 * Says what is true *now* rather than restating the chip: when it was
 * connected, and — for the states where it matters — what happens next without
 * the user doing anything.
 */
function statusDetail(
  t: ReturnType<typeof useTranslation>["t"],
  account: LinkedAccount,
  locale: string,
): string {
  switch (account.status) {
    case "REFRESH_FAILED":
      return t(
        "connections.detail.refreshFailed",
        "The service stopped accepting the stored credentials. Reconnect to grant access again.",
      );
    case "REVOKED":
      return t(
        "connections.detail.revoked",
        "This access was revoked. Reconnect if you still want EDDI to use it.",
      );
    case "EXPIRED":
      return t(
        "connections.detail.expired",
        "The access token has expired and will be renewed automatically on the next use — nothing for you to do.",
      );
    case "ACTIVE": {
      const connected = formatInstant(account.connectedAt, locale);
      if (connected) {
        return t("connections.detail.connectedOn", {
          date: connected,
          defaultValue: "Connected on {{date}}.",
        });
      }
      return t("connections.detail.active", "Working normally.");
    }
  }
}

/**
 * One formatter per locale, for the app's lifetime.
 *
 * `Intl.DateTimeFormat` construction resolves locale data and is by far the
 * most expensive thing in a row; building one per call meant three per row per
 * render, and the panel re-renders on every busy-state change.
 */
const dateFormatters = new Map<string, Intl.DateTimeFormat>();

function dateFormatter(locale: string): Intl.DateTimeFormat {
  let formatter = dateFormatters.get(locale);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
    });
    dateFormatters.set(locale, formatter);
  }
  return formatter;
}

/** An ISO instant as a local date, or null when there is nothing to show. */
function formatInstant(value: string | null, locale: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return dateFormatter(locale).format(date);
}
