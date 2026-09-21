/**
 * Leave the application entirely, by full page navigation.
 *
 * One function rather than `window.location.assign` at the call site, for two
 * reasons that pull the same way.
 *
 * **It is a seam.** `window.location` is non-configurable under jsdom 26, so a
 * test that wants to assert "we sent the browser to the provider" has to
 * redefine a global property the platform declares immutable — which works
 * today only because Vitest builds its own window, and stops working the moment
 * that changes. Mocking one module has no such dependency.
 *
 * **It is a marker.** A full navigation discards everything on the page:
 * unsaved drafts, in-flight requests, scroll position. Naming the act makes the
 * places that do it greppable, which matters because the OAuth link flow *must*
 * use one (the nonce cookie binding the flow to this browser is
 * `SameSite=Lax`, which admits a top-level GET return and nothing else) while
 * everything else in the app should be using the router instead.
 */
export function navigateAway(url: string): void {
  window.location.assign(url);
}
