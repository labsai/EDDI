import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { toast } from "sonner";
import {
  isConnectionErrorCode,
  type ConnectionErrorCode,
} from "@/lib/api/connections";
import { MINE_KEY } from "@/hooks/use-connections";

/**
 * What the browser came back from the provider carrying.
 *
 * `null` is the ordinary case — the page was opened directly rather than
 * returned to.
 */
export type LinkOutcome =
  | { kind: "connected"; connection: string }
  | { kind: "error"; code: ConnectionErrorCode | "unknown" }
  | null;

/**
 * Read the outcome of an account-linking round trip, announce it, and clear it.
 *
 * The backend's callback finishes with a 303 to the page that started the flow,
 * carrying `?connected=<name>` or `?error=<code>`. Three things have to happen
 * on arrival and all three are easy to leave out:
 *
 *  1. **Say what happened.** The user has just been through a provider consent
 *     screen and is looking at an ordinary page; without a toast, a refusal is
 *     completely silent.
 *  2. **Strip the parameter.** Otherwise a refresh — or a bookmark, or the back
 *     button — re-announces an outcome that is no longer happening.
 *  3. **Refetch the grants.** The list was fetched before the round trip and
 *     still says "not connected" for the account that was just linked.
 *
 * Guarded against React's double-invoked effects: the parameter is only handled
 * once per distinct value, so a development build does not toast twice.
 */
export function useConnectionLinkResult(): LinkOutcome {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const [outcome, setOutcome] = useState<LinkOutcome>(null);
  const handled = useRef<string | null>(null);

  const connected = searchParams.get("connected");
  const error = searchParams.get("error");

  useEffect(() => {
    if (connected === null && error === null) return;

    const token = connected !== null ? `connected:${connected}` : `error:${error}`;
    if (handled.current === token) return;
    handled.current = token;

    if (connected !== null) {
      setOutcome({ kind: "connected", connection: connected });

      // The list on screen predates the round trip. Status, scopes and expiry
      // all come from the grant that was just written.
      //
      // `type: "all"` because the refetch is not only a refresh, it is the
      // check: `?connected=<name>` is a plain URL parameter, so anyone can hand
      // the user a link that congratulates them on a grant that was never
      // created. Confirming the name against the refreshed list costs nothing
      // here and makes the success claim mean something.
      //
      // Silence rather than a denial when the list comes back without it: the
      // user did not do anything, so there is nothing to report. And when there
      // is no list to check against — the query never ran, or the refetch
      // failed — the toast still fires, because an unverifiable success is
      // still far more likely to be a real one.
      void queryClient
        .refetchQueries({ queryKey: MINE_KEY, type: "all" })
        .then(() => {
          const state = queryClient.getQueryState(MINE_KEY);
          const accounts = state?.data as { connection: string }[] | undefined;
          const checkable = state?.status === "success" && accounts !== undefined;
          if (checkable && !accounts.some((a) => a.connection === connected)) return;

          toast.success(
            t("connections.linked", {
              name: connected,
              defaultValue: 'Connected — "{{name}}" is now linked to your account.',
            }),
          );
        });
    } else {
      const code = isConnectionErrorCode(error) ? error : "unknown";
      setOutcome({ kind: "error", code });
      toast.error(linkErrorMessage(t, code));
    }

    // Strip only the two parameters this owns; anything else on the URL
    // (a version, a tab) belongs to the page and must survive.
    const next = new URLSearchParams(searchParams);
    next.delete("connected");
    next.delete("error");
    setSearchParams(next, { replace: true });
  }, [connected, error, searchParams, setSearchParams, queryClient, t]);

  return outcome;
}

/**
 * One sentence per outcome code — the Manager owns every word of it.
 *
 * The provider's own `error_description` is never forwarded by the backend (it
 * is attacker-influenceable text on its way to a browser, and is deliberately
 * not even bound on the callback), so there is nothing to append and no
 * "technical details" to reveal. Each message therefore has to be complete on
 * its own and say what to do next.
 *
 * `invalid_state` covers four backend conditions — unknown, expired,
 * already-used, and a callback that reached a different browser — because the
 * backend answers all four identically on purpose: telling them apart is a
 * state-guessing oracle. One message that is true of all four, rather than four
 * guesses at which one it was.
 */
function linkErrorMessage(
  t: TFunction,
  code: ConnectionErrorCode | "unknown",
): string {
  switch (code) {
    case "invalid_state":
      return t(
        "connections.error.invalidState",
        "That linking attempt expired or was already used. Start again from this page, in this browser.",
      );
    case "authorization_declined":
      return t(
        "connections.error.declined",
        "The request was declined, so nothing was linked. You can try again whenever you like.",
      );
    case "missing_code":
      return t(
        "connections.error.missingCode",
        "The provider did not send back an authorization code. Nothing was linked — please start again.",
      );
    case "connection_removed":
      return t(
        "connections.error.connectionRemoved",
        "That connection no longer exists — an administrator removed it while you were linking.",
      );
    case "exchange_failed":
      return t(
        "connections.error.exchangeFailed",
        "The provider would not complete the link. Try again; if it keeps failing, an administrator needs to check this connection's settings.",
      );
    default:
      return t(
        "connections.error.unknown",
        "Linking did not complete. Nothing was changed — please start again.",
      );
  }
}
