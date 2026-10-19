/* ──────────────────────────────────────────────
   EDDI Chat — operator settings served to the widget
   The server sets `window.__EDDI_CHAT__` from /chat/__config__.js, a script
   chat.html loads before the widget (RestHtmlChatResource — the Manager reads
   /manage/__auth_config__.js the same way). Absent — `npm run dev` without a
   backend, a stand-alone bundle — every setting takes its safe default.
   ────────────────────────────────────────────── */

export interface EddiChatGlobal {
  /** `eddi.chat.url-token.enabled`: whether `?token=` is honoured. */
  urlTokenEnabled?: unknown;
}

declare global {
  interface Window {
    __EDDI_CHAT__?: EddiChatGlobal;
  }
}

/** The backend property that turns `?token=` on. Named in the console warning. */
export const URL_TOKEN_PROPERTY = "eddi.chat.url-token.enabled";

/**
 * Whether the operator allowed a bearer token in the widget URL. Strictly
 * `true`: a missing or malformed global is "off", never "on".
 */
export function isUrlTokenEnabled(win: Pick<Window, "__EDDI_CHAT__"> = window): boolean {
  return win.__EDDI_CHAT__?.urlTokenEnabled === true;
}

/**
 * The token from `?token=`, or null when there is none or the operator has not
 * enabled it. Off by default: a crafted link otherwise signs the widget in as
 * whoever minted the token (login CSRF), and the first request writes the
 * token into the access log before the widget has stripped it from the address
 * bar. The caller strips the parameter either way — a token the widget does
 * not use must not stay in the address bar either.
 */
export function readUrlToken(
  params: URLSearchParams,
  win: Pick<Window, "__EDDI_CHAT__"> = window,
): string | null {
  const token = params.get("token");
  if (!token) return null;
  if (isUrlTokenEnabled(win)) return token;
  console.warn(
    `[eddi-chat] ignoring ?token=: a bearer token in the URL is off unless ${URL_TOKEN_PROPERTY}=true ` +
      "is set on the EDDI server. Pass the token by postMessage (?tokenOrigin=) or ChatConfig.authToken instead.",
  );
  return null;
}
