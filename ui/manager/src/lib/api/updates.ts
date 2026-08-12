/**
 * EDDI update check — "is a newer EDDI released than the one this Manager talks to?"
 *
 * ## One network call, to GitHub only
 *
 * EDDI ships from GitHub *and* Docker Hub, and the running deployment pulls
 * from Docker Hub — so the obvious design reads both, to catch the window where
 * a release is cut but its image has not landed and `docker compose pull` would
 * find nothing.
 *
 * That window does not exist. EDDI's own pipeline forbids it: in
 * `.github/workflows/ci.yml` the `release` job declares `needs: docker`, so the
 * image is pushed to Docker Hub *before* the GitHub release is created, every
 * time. A published release therefore implies a published image, and the tag is
 * the release version — which is also what the release body tells people to
 * pull. Reading Docker Hub could only ever confirm something already implied.
 *
 * So the Docker image shown alongside the release is **derived**, not fetched.
 * That is not a workaround: it is the accurate model of how EDDI is published,
 * and it costs one network call instead of two.
 *
 * It also sidesteps a wall worth recording, so nobody spends the afternoon
 * rediscovering it. Every first-party Docker endpoint is unreachable from a
 * browser — measured, not assumed: `hub.docker.com/v2`,
 * `registry.hub.docker.com/v2`, `index.docker.io/v1`, `auth.docker.io` and
 * `registry-1.docker.io` all answer a request carrying `Origin` while sending
 * **no** `Access-Control-Allow-Origin`, and Docker Hub rejects the preflight
 * with 405 (`allow: GET, HEAD`). A direct call is CORS-blocked in production
 * however it is written; anything that appears to work is a third party
 * relaying it, which this deliberately does not do. If a *live* Docker check is
 * ever genuinely needed, the only first-party route is a same-origin proxy on
 * the EDDI backend.
 *
 * ## Why this file does not use `ApiClient`
 *
 * `ApiClient` resolves against `window.location.origin` and injects the
 * Keycloak bearer token. Both are wrong here: the request goes to a third
 * party, and sending this deployment's access token to github.com would leak
 * it. This raw `fetch` deliberately sends **no** credentials — do not "fix" it
 * by spreading `api.getAuthHeader()` the way `AGENTS.md` requires for
 * same-origin raw fetches.
 */

const EDDI_REPO = "labsai/EDDI";
const EDDI_DOCKER_IMAGE = "labsai/eddi";
const EDDI_DOCKER_URL = `https://hub.docker.com/r/${EDDI_DOCKER_IMAGE}`;

/** The two links the card offers when it has nothing more specific to point at. */
export const EDDI_RELEASES_URL = `https://github.com/${EDDI_REPO}/releases`;
export const EDDI_DOCKER_TAGS_URL = `${EDDI_DOCKER_URL}/tags`;

const LATEST_RELEASE_API = `https://api.github.com/repos/${EDDI_REPO}/releases/latest`;

/** Deep-link to one tag on Docker Hub, so "is the image there?" is one click. */
export function dockerTagUrl(version: string): string {
  return `${EDDI_DOCKER_TAGS_URL}?name=${encodeURIComponent(version)}`;
}

/**
 * The image a given release is published as.
 *
 * Derived rather than looked up — see the file header: EDDI's CI pushes the
 * image before it creates the release, so the release version *is* the tag.
 *
 * Checked against reality, not just against the workflow file: of the 49
 * published releases, 48 have a matching tag in the registry. The one that does
 * not is `6.0.0-RC1`, a prerelease — and `releases/latest`, the only endpoint
 * this module calls, is defined as the newest **non-prerelease, non-draft**
 * release. So the derivation holds for every release this card can ever show.
 *
 * The link still goes to Docker Hub, so the claim is one click from being
 * checked rather than something the user has to take on trust.
 */
export function dockerImageFor(releaseVersion: string): DockerImage {
  return {
    version: releaseVersion,
    reference: `${EDDI_DOCKER_IMAGE}:${releaseVersion}`,
    url: dockerTagUrl(releaseVersion),
  };
}

/** Give up rather than leave a spinner running against an unreachable host. */
const REQUEST_TIMEOUT_MS = 10_000;

// ─── Types ───────────────────────────────────────────────────────────────────

export interface EddiRelease {
  /** Tag with any leading `v` stripped, e.g. `6.2.0`. */
  version: string;
  /** Release title as shown on GitHub; falls back to the version. */
  name: string;
  /** Link to the release page (release notes). */
  url: string;
  /** ISO timestamp, or `null` when GitHub omitted it. */
  publishedAt: string | null;
  /** Release notes as authored — GitHub-flavoured markdown, possibly empty. */
  notes: string;
}

export interface DockerImage {
  /** Published tag, which for EDDI is the release version verbatim. */
  version: string;
  /** What you would actually pull, e.g. `labsai/eddi:6.2.0`. */
  reference: string;
  /** Deep link to that tag on Docker Hub. */
  url: string;
}

export type UpdateCheckErrorReason =
  /** GitHub's anonymous 60-requests-per-hour budget is spent for this IP. */
  | "rate-limited"
  /** The page's own Content-Security-Policy refused the request. */
  | "blocked-by-csp"
  /** No response at all — offline, DNS, or an egress firewall. */
  | "unreachable"
  /** A response we cannot use (non-2xx, or a body in an unexpected shape). */
  | "failed";

export class UpdateCheckError extends Error {
  readonly reason: UpdateCheckErrorReason;

  constructor(reason: UpdateCheckErrorReason, message: string) {
    super(message);
    this.name = "UpdateCheckError";
    this.reason = reason;
  }
}

/**
 * How the installed version relates to the latest release.
 *
 * `ahead` is a real state, not a bug: anyone running a SNAPSHOT or a locally
 * built image is legitimately in front of the newest tag, and telling them they
 * are "up to date" would hide that.
 */
export type UpdateStatus = "up-to-date" | "update-available" | "ahead" | "unknown";

interface ParsedVersion {
  core: number[];
  prerelease: string[];
}

// ─── Fetch ───────────────────────────────────────────────────────────────────

interface GitHubRelease {
  tag_name?: unknown;
  name?: unknown;
  html_url?: unknown;
  published_at?: unknown;
  body?: unknown;
}

/**
 * Notice the browser refusing this request under the page's *own* CSP.
 *
 * A request blocked by Content-Security-Policy rejects with exactly the same
 * `TypeError` as a dead network, so without this the card tells the operator to
 * check their proxy when nothing ever left the browser and no proxy was
 * involved. That is not a corner case here: EDDI serves the Manager with
 * `connect-src 'self'` (`application.properties`, the `csp-default` filter), so
 * on a stock deployment this is the *expected* outcome — the check works in
 * `npm run dev` (no CSP) and fails everywhere it actually ships.
 *
 * Matching on the blocked origin alone, not the directive: anything of ours the
 * browser blocks at this URL is a CSP problem the operator has to fix in the
 * header, whichever directive names it.
 */
function watchForCspBlock(url: string) {
  const origin = new URL(url).origin;
  let blocked = false;

  const onViolation = (event: SecurityPolicyViolationEvent) => {
    // `blockedURI` is the origin alone for most fetch violations, the full URL
    // in some browsers — so accept both, but only those two. A bare
    // `startsWith(origin)` would also swallow `https://api.github.com.example`,
    // an unrelated host whose violation would then explain away a genuine
    // GitHub outage as a CSP problem.
    if (event.blockedURI === origin || event.blockedURI?.startsWith(`${origin}/`)) {
      blocked = true;
    }
  };
  document.addEventListener("securitypolicyviolation", onViolation);

  return {
    stop: () => document.removeEventListener("securitypolicyviolation", onViolation),
    /**
     * The violation event and the fetch rejection are not ordered against each
     * other, so yield a macrotask before reading the flag rather than racing it.
     * Only ever awaited on the failure path.
     */
    wasBlocked: async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
      return blocked;
    },
  };
}

/**
 * The one outbound request this module makes, with its two contracts named:
 * it carries no credentials, and it gives up rather than hanging.
 *
 * A named function for a single caller because the no-credentials part is the
 * security-relevant bit — `git grep getWithoutCredentials` should keep finding
 * every off-origin call the Manager makes.
 */
async function getWithoutCredentials(url: string, accept: string): Promise<Response> {
  // `AbortSignal.timeout` is not reliably present in the jsdom test env, so the
  // timeout is wired by hand.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const csp = watchForCspBlock(url);

  try {
    return await fetch(url, {
      method: "GET",
      // No `Authorization` — see the file header.
      headers: { Accept: accept },
      signal: controller.signal,
      credentials: "omit",
      // `credentials: "omit"` drops cookies and auth, but not the referrer: the
      // browser default (`strict-origin-when-cross-origin`) would still hand
      // GitHub this deployment's origin. For a self-hosted instance that
      // hostname *is* deployment data, and the card promises none is sent.
      referrerPolicy: "no-referrer",
    });
  } catch {
    if (await csp.wasBlocked()) {
      throw new UpdateCheckError(
        "blocked-by-csp",
        `The page's Content-Security-Policy blocked the request to ${new URL(url).host}`,
      );
    }
    throw new UpdateCheckError("unreachable", `Could not reach ${new URL(url).host}`);
  } finally {
    clearTimeout(timer);
    csp.stop();
  }
}

/**
 * Fetch the latest published EDDI release from GitHub.
 *
 * Never called unless the operator asked for it — either by pressing the check
 * button or by opting into the per-reload check.
 *
 * @throws {UpdateCheckError} on any outcome that is not a usable release.
 */
export async function fetchLatestEddiRelease(): Promise<EddiRelease> {
  const res = await getWithoutCredentials(LATEST_RELEASE_API, "application/vnd.github+json");

  if (!res.ok) {
    // GitHub answers an exhausted anonymous budget with 403 (legacy) or 429,
    // both carrying `x-ratelimit-remaining: 0`. Worth separating: it is the one
    // failure that fixes itself, and the advice ("try again later") differs.
    const remaining = res.headers.get("x-ratelimit-remaining");
    if ((res.status === 403 || res.status === 429) && remaining === "0") {
      throw new UpdateCheckError("rate-limited", "GitHub API rate limit reached");
    }
    throw new UpdateCheckError("failed", `GitHub API responded ${res.status}`);
  }

  let body: GitHubRelease;
  try {
    body = (await res.json()) as GitHubRelease;
  } catch {
    throw new UpdateCheckError("failed", "GitHub API returned a malformed response");
  }

  const tag = typeof body.tag_name === "string" ? body.tag_name.trim() : "";
  if (!tag) {
    throw new UpdateCheckError("failed", "GitHub API returned a release without a tag");
  }

  const version = normalizeVersion(tag);
  return {
    version,
    name: typeof body.name === "string" && body.name.trim() ? body.name.trim() : version,
    url: typeof body.html_url === "string" && body.html_url ? body.html_url : EDDI_RELEASES_URL,
    publishedAt: typeof body.published_at === "string" ? body.published_at : null,
    notes: typeof body.body === "string" ? body.body.trim() : "",
  };
}

// ─── Release notes ───────────────────────────────────────────────────────────

/**
 * How much of a release body to show inline, in characters.
 *
 * EDDI's notes run to ~23 KB. Pouring that into a nested scroll box on a
 * dashboard traps the page scroll for dozens of screens to say something a
 * link says better, so the card shows the opening — which is where these notes
 * put their summary — and hands off to GitHub for the rest.
 */
const RELEASE_NOTES_PREVIEW_CHARS = 1500;

export interface ReleaseNotesPreview {
  markdown: string;
  truncated: boolean;
}

/**
 * Cut release notes down to a preview without leaving broken markdown behind.
 *
 * Cuts on a blank line so a table or list is never sliced mid-row, and backs
 * off past an unclosed code fence — a body ending inside ``` would otherwise
 * swallow the rest of the panel into one code block.
 */
export function previewReleaseNotes(
  notes: string,
  budget = RELEASE_NOTES_PREVIEW_CHARS,
): ReleaseNotesPreview {
  const trimmed = notes.trim();
  if (trimmed.length <= budget) return { markdown: trimmed, truncated: false };

  const paragraphBreak = trimmed.lastIndexOf("\n\n", budget);
  // A body with no blank line before the budget still has to be cut somewhere.
  let cut = paragraphBreak > 0 ? trimmed.slice(0, paragraphBreak) : trimmed.slice(0, budget);

  if (countFences(cut) % 2 !== 0) {
    const lastFence = cut.lastIndexOf("```");
    cut = cut.slice(0, lastFence).trimEnd();
  }

  return { markdown: `${cut.trimEnd()}\n\n…`, truncated: true };
}

function countFences(text: string): number {
  return text.split("```").length - 1;
}

// ─── Version comparison ──────────────────────────────────────────────────────

/** Strip a leading `v`, as GitHub tags carry it in some repos and not others. */
export function normalizeVersion(raw: string): string {
  return raw.trim().replace(/^v/i, "");
}

/**
 * Parse a semver-ish version into comparable parts, or `null` if it is not one.
 *
 * Deliberately lenient about the shapes EDDI actually produces: `6.2`, `6.2.0`,
 * `6.2.0-RC1`, `6.3.0-SNAPSHOT`, `6.2.0+build.7`. Build metadata is discarded
 * because semver says it never affects precedence.
 */
export function parseVersion(raw: string): ParsedVersion | null {
  const cleaned = normalizeVersion(raw);
  const match = /^(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(cleaned);
  const core = match?.[1];
  if (!core) return null;

  const prerelease = match[2];
  return {
    core: core.split(".").map(Number),
    prerelease: prerelease ? prerelease.split(".") : [],
  };
}

/**
 * Semver precedence: negative if `a < b`, positive if `a > b`, `0` if equal.
 * `null` when either side is not a version we can compare.
 */
export function compareVersions(a: string, b: string): number | null {
  const left = parseVersion(a);
  const right = parseVersion(b);
  if (!left || !right) return null;

  const length = Math.max(left.core.length, right.core.length);
  for (let i = 0; i < length; i++) {
    const diff = (left.core[i] ?? 0) - (right.core[i] ?? 0);
    if (diff !== 0) return diff;
  }

  // A release outranks any prerelease of the same core version, so
  // 6.3.0 > 6.3.0-RC1 and 6.3.0-SNAPSHOT is still behind 6.3.0.
  if (left.prerelease.length === 0 && right.prerelease.length === 0) return 0;
  if (left.prerelease.length === 0) return 1;
  if (right.prerelease.length === 0) return -1;

  return comparePrerelease(left.prerelease, right.prerelease);
}

function comparePrerelease(left: string[], right: string[]): number {
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i++) {
    const l = left[i];
    const r = right[i];
    // Fewer identifiers wins when everything before matched: RC.1 < RC.1.2
    if (l === undefined) return -1;
    if (r === undefined) return 1;
    if (l === r) continue;

    const lNumeric = /^\d+$/.test(l);
    const rNumeric = /^\d+$/.test(r);
    if (lNumeric && rNumeric) return Number(l) - Number(r);
    // Numeric identifiers always rank below alphanumeric ones.
    if (lNumeric) return -1;
    if (rNumeric) return 1;
    return l < r ? -1 : 1;
  }
  return 0;
}

/**
 * Classify the installed version against the latest release.
 *
 * Returns `unknown` whenever either side cannot be parsed — including the
 * literal `"Unknown"` the version endpoint falls back to. An unparseable
 * version must never be reported as up to date; silence is the safe answer.
 */
export function getUpdateStatus(
  installed: string | undefined | null,
  latest: string | undefined | null,
): UpdateStatus {
  if (!installed || !latest) return "unknown";
  const diff = compareVersions(installed, latest);
  if (diff === null) return "unknown";
  if (diff < 0) return "update-available";
  if (diff > 0) return "ahead";
  return "up-to-date";
}
