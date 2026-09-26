/**
 * Reading the version a save created out of its `Location` header.
 *
 * Every EDDI config store answers a PUT with a NEW version and names it in
 * `Location` (`…/{id}?version=N`). Whatever the page does next — a second save,
 * a delete, a deploy — must address that version: the backend refuses a write
 * to a version that is no longer current with a 409. A page that ignores the
 * header keeps the version it was opened with, shows the pre-save document on
 * the next refetch and fails its next action.
 */

/**
 * The version in a Location URI like `eddi://…?version=2`, or `null` when there
 * is none to read.
 *
 * Deliberately NOT `parseResourceUri`, which ends with
 * `parseInt(url.searchParams.get("version") || "1", 10)` and so cannot tell
 * "version 1" from "no version at all". Forty-odd call sites rely on that
 * forgiving behaviour, so it stays as it is; a save that writes the version it
 * reads into another document needs the strict reading and gets this one.
 */
export function parseVersionFromLocation(location: string | undefined | null): number | null {
  if (!location) {
    return null;
  }
  let raw: string | null;
  try {
    /*
     * Normalised exactly as `parseResourceUri` does it, and for the same two
     * reasons: `eddi://` is not a special scheme, and a Location header may be
     * a relative path with no origin at all — `new URL(location)` on its own
     * throws on the second, which would turn every relative Location into a
     * failed save.
     */
    const normalised = location.startsWith("eddi://")
      ? location.replace("eddi://", "http://")
      : location;
    raw = new URL(normalised, "http://dummy").searchParams.get("version");
  } catch {
    return null;
  }
  /*
   * The WHOLE value must be digits. A prefix match accepted `version=2.5` and
   * `version=2abc` as 2, so a cascade would have written a version into the
   * parent that the server never reported — the same class of silent wrong
   * reference this strict parser exists to prevent, one layer in.
   */
  if (raw === null || !/^\d+$/.test(raw)) {
    return null;
  }
  const version = Number(raw);
  return Number.isSafeInteger(version) ? version : null;
}

/**
 * The version a save reported, or a thrown error naming what could not be read.
 *
 * A cascade writes the version it just created into the PARENT document: the
 * resource version goes into the workflow, the workflow version into the agent.
 * Guessing wrong is not a display bug, it is a wrong reference written to the
 * database — and with the forgiving parser a missing `Location` header resolved
 * to version 1, so a save that lost its header would have quietly pointed the
 * parent at the very first revision while reporting success.
 *
 * Failing the save is the right answer: the user sees that it did not work and
 * retries, instead of finding out later that their agent runs an old config.
 */
export function requireVersionFromLocation(
  location: string | undefined | null,
  what: string,
): number {
  const version = parseVersionFromLocation(location);
  if (version === null) {
    throw new Error(
      `The ${what} was saved but the server did not report its new version, so the parent ` +
        `reference cannot be updated safely. Nothing further was written — please retry.`,
    );
  }
  return version;
}
