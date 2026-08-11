/**
 * EDDI update check — "is a newer EDDI released than the one this Manager talks to?"
 *
 * ## Two sources, because they answer different questions
 *
 * EDDI ships from GitHub *and* Docker Hub, and they can disagree for a while:
 *
 * - **GitHub releases** say what has been *released* — with a version, a date
 *   and the notes that make the number mean something.
 * - **Docker Hub** says what can actually be *pulled*. This is the one the
 *   running deployment consumes, and for the minutes-to-hours after a release
 *   is cut, `docker compose pull` can still find nothing.
 *
 * Reporting them separately lets the card say "6.3.0 is out, but its image has
 * not landed yet" instead of sending someone to run a pull that does nothing.
 *
 * ## Why Docker Hub is read through shields.io
 *
 * Every first-party Docker endpoint is unreachable from a browser — verified,
 * not assumed: `hub.docker.com/v2`, `auth.docker.io` and `registry-1.docker.io`
 * all answer 200 to a request carrying `Origin` while sending no
 * `Access-Control-Allow-Origin`, and Docker Hub rejects the preflight with 405
 * (`allow: GET, HEAD`). So a direct call fails CORS in production no matter how
 * it is written. `img.shields.io` sends `access-control-allow-origin: *`, is
 * built to be called from pages, and its `docker/v` endpoint also does the
 * filtering we would otherwise have to guess at: the repo carries ~123 tags,
 * almost all CI builds like `6.2.0-b980`, and `?sort=semver` reduces that to
 * the real `6.2.0`.
 *
 * The cost is a third party in the path. It is a fixed, public query that says
 * nothing about this deployment, the whole check is opt-in, and a failure
 * degrades to the plain Docker Hub link rather than breaking the card — but if
 * that trade stops being acceptable, the fix is a same-origin proxy on the EDDI
 * backend, and only `fetchLatestDockerImage` has to change.
 *
 * ## Why this file does not use `ApiClient`
 *
 * `ApiClient` resolves against `window.location.origin` and injects the
 * Keycloak bearer token. Both are wrong here: the requests go to third parties,
 * and sending this deployment's access token to them would leak it. These raw
 * `fetch` calls deliberately send **no** credentials — do not "fix" them by
 * spreading `api.getAuthHeader()` the way `AGENTS.md` requires for same-origin
 * raw fetches.
 */

export const EDDI_REPO = "labsai/EDDI";
export const EDDI_RELEASES_URL = `https://github.com/${EDDI_REPO}/releases`;
export const EDDI_REPO_URL = `https://github.com/${EDDI_REPO}`;

export const EDDI_DOCKER_IMAGE = "labsai/eddi";
export const EDDI_DOCKER_URL = `https://hub.docker.com/r/${EDDI_DOCKER_IMAGE}`;
export const EDDI_DOCKER_TAGS_URL = `${EDDI_DOCKER_URL}/tags`;

const LATEST_RELEASE_API = `https://api.github.com/repos/${EDDI_REPO}/releases/latest`;
const LATEST_DOCKER_TAG_API = `https://img.shields.io/docker/v/${EDDI_DOCKER_IMAGE}.json?sort=semver`;

/** Deep-link to one tag on Docker Hub, so "is the image there?" is one click. */
export function dockerTagUrl(version: string): string {
  return `${EDDI_DOCKER_TAGS_URL}?name=${encodeURIComponent(version)}`;
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
  /** Highest published semver tag, e.g. `6.2.0`. */
  version: string;
  /** What you would actually pull, e.g. `labsai/eddi:6.2.0`. */
  reference: string;
  /** Deep link to that tag on Docker Hub. */
  url: string;
}

/**
 * Whether the image for the latest release can actually be pulled yet.
 *
 * `pending` is the state worth having a second source for: the release exists,
 * the image does not, and a `docker compose pull` right now is a no-op.
 */
export type ImageStatus = "ready" | "pending" | "unknown";

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
  body?: unknown;
}

/** Shared timeout + no-credentials plumbing for both third-party lookups. */
async function getWithoutCredentials(url: string, accept: string): Promise<Response> {
  // `AbortSignal.timeout` is not reliably present in the jsdom test env, so the
  // timeout is wired by hand.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    return await fetch(url, {
      method: "GET",
      // No `Authorization` — see the file header.
      headers: { Accept: accept },
      signal: controller.signal,
      credentials: "omit",
    });
  } catch {
    throw new UpdateCheckError("unreachable", `Could not reach ${new URL(url).host}`);
  } finally {
    clearTimeout(timer);
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

/**
 * Fetch the highest semver tag published for the `labsai/eddi` image.
 *
 * Answers "can this actually be pulled yet?", which the GitHub release cannot.
 * Read through shields.io because Docker's own endpoints are CORS-blocked from
 * a browser — see the file header for the measurement behind that claim.
 *
 * @throws {UpdateCheckError} on any outcome that is not a usable version.
 */
export async function fetchLatestDockerImage(): Promise<DockerImage> {
  const res = await getWithoutCredentials(LATEST_DOCKER_TAG_API, "application/json");

  if (!res.ok) {
    throw new UpdateCheckError("failed", `Docker tag lookup responded ${res.status}`);
  }

  let body: { value?: unknown; message?: unknown };
  try {
    body = (await res.json()) as { value?: unknown; message?: unknown };
  } catch {
    throw new UpdateCheckError("failed", "Docker tag lookup returned a malformed response");
  }

  // shields.io reports its own failures in-band as a 200 with a prose message
  // ("repo not found", "inaccessible"), so the version has to be validated
  // rather than trusted — otherwise the card would print an error as a tag.
  const raw = typeof body.value === "string" ? body.value : body.message;
  const candidate = typeof raw === "string" ? normalizeVersion(raw) : "";
  if (!parseVersion(candidate)) {
    throw new UpdateCheckError("failed", "Docker tag lookup returned no usable version");
  }

  return {
    version: candidate,
    reference: `${EDDI_DOCKER_IMAGE}:${candidate}`,
    url: dockerTagUrl(candidate),
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

/**
 * Has the image for the released version been published yet?
 *
 * `unknown` whenever either lookup is missing or unparseable — the point of
 * this check is to stop someone running a pull that cannot succeed, and a guess
 * would do the opposite.
 */
export function getImageStatus(
  releaseVersion: string | undefined | null,
  imageVersion: string | undefined | null,
): ImageStatus {
  if (!releaseVersion || !imageVersion) return "unknown";
  const diff = compareVersions(imageVersion, releaseVersion);
  if (diff === null) return "unknown";
  return diff >= 0 ? "ready" : "pending";
}
