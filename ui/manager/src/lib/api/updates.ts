/**
 * EDDI update check — "is a newer EDDI released than the one this Manager talks to?"
 *
 * ## Why GitHub releases and not Docker Hub
 *
 * EDDI ships from both, and the running deployment actually *pulls* from Docker
 * Hub (`labsai/eddi`), so Docker Hub looks like the more honest source. It is
 * not the more useful one:
 *
 * - A GitHub release carries a version, a publish date and release notes. A
 *   Docker Hub tag carries a string. Telling someone "6.3.0 is out" without
 *   being able to link what changed is a notification with nothing behind it.
 * - `hub.docker.com/v2/repositories/labsai/eddi/tags` returns every tag —
 *   `latest`, `dev-*`, per-arch and per-digest entries — so picking "the newest
 *   version" means filtering noise and hoping the filter still holds later.
 *   `releases/latest` already means exactly that.
 * - GitHub's REST API documents CORS support for anonymous browser requests.
 *   Docker Hub's does not, so it can start failing from the browser without
 *   anything on our side changing.
 *
 * The one thing Docker Hub would tell us that GitHub cannot is whether the
 * image for a fresh release has finished publishing. That window is short and
 * the cost of being early is a `docker compose pull` that finds nothing new —
 * not worth a second network dependency and a second way to fail.
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

export const EDDI_REPO = "labsai/EDDI";
export const EDDI_RELEASES_URL = `https://github.com/${EDDI_REPO}/releases`;
export const EDDI_REPO_URL = `https://github.com/${EDDI_REPO}`;

const LATEST_RELEASE_API = `https://api.github.com/repos/${EDDI_REPO}/releases/latest`;

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
}

export type UpdateCheckErrorReason =
  /** GitHub's anonymous 60-requests-per-hour budget is spent for this IP. */
  | "rate-limited"
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
  // `AbortSignal.timeout` is not reliably present in the jsdom test env, so the
  // timeout is wired by hand.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  let res: Response;
  try {
    res = await fetch(LATEST_RELEASE_API, {
      method: "GET",
      // No `Authorization` — see the file header.
      headers: {
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      signal: controller.signal,
      credentials: "omit",
    });
  } catch {
    throw new UpdateCheckError("unreachable", "Could not reach api.github.com");
  } finally {
    clearTimeout(timer);
  }

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
  };
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
