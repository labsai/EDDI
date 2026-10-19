/**
 * May the browser be sent to this identity-provider URL?
 *
 * The OAuth link flow ends in a full-page navigation to whatever
 * `authorizationUrl` the backend returned. That string is data from a
 * connection configuration an editor can write, so it is checked here rather
 * than trusted: a `javascript:` or `data:` URL passed to `location.assign` runs
 * script in the Manager's origin, and a plain-`http:` provider would carry the
 * authorization request in the clear.
 *
 * Allowed: `https:`, and `http:` only for a loopback host (local development
 * against a provider on the same machine). Embedded credentials are refused.
 */
const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

export function isSafeAuthorizationUrl(value: unknown): value is string {
  if (typeof value !== "string" || value.trim() === "") return false;
  let url: URL;
  try {
    // No base: a relative or scheme-less value is not an authorization URL.
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.username !== "" || url.password !== "") return false;
  if (url.protocol === "https:") return true;
  return url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
}
