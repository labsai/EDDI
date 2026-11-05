/**
 * Where a `${connection:name}` may sit in an agent config, and under which
 * header — the backend's build-time rules, mirrored so an editor can say so
 * while the value is still on screen.
 *
 * The rules (EDDI `docs/connections.md`, "Where a reference may appear"):
 *
 *  - allowed only as the WHOLE value of an httpcall header, an mcpcalls
 *    `apiKey` or an A2A `apiKey`;
 *  - the header must be named what the connection's `staticAuth.headerName`
 *    says (OAuth connections send `Authorization`);
 *  - refused in an httpcall path, query or body, and in every language-model,
 *    embedding and vector-store parameter.
 *
 * Pure, like `connection-validation.ts`: codes and names come out, the
 * components translate them.
 */

import type { ConnectionConfiguration } from "./api/connections";
import { parseConnectionReference } from "./secret-reference";

/** The part of a connection document the header rule reads. */
export type HeaderBearingConnection = Pick<ConnectionConfiguration, "authType" | "staticAuth">;

/**
 * The header a connection's credential travels in.
 *
 * STATIC and BASIC carry it in `staticAuth.headerName`, which the backend
 * defaults to `Authorization`; an OAuth connection contributes a bearer token
 * and always uses `Authorization`.
 */
export function connectionHeaderName(config: HeaderBearingConnection): string {
  if (config.authType === "STATIC" || config.authType === "BASIC") {
    return config.staticAuth?.headerName?.trim() || "Authorization";
  }
  return "Authorization";
}

/** HTTP header names are case-insensitive, so the comparison is too. */
export function headerNameMismatch(headerName: string, expected: string): boolean {
  return headerName.trim().toLowerCase() !== expected.trim().toLowerCase();
}

/**
 * The header name a referenced connection requires when the header carrying
 * it is named something else — or null when the value is not a canonical
 * reference, the connection is unknown to the lookup, or the names agree.
 *
 * Unknown means silent, not wrong: the lookup is fed from a list only an
 * editor or administrator can fetch, and a viewer without it should not be
 * told their header is misnamed on no evidence.
 */
export function expectedHeaderFor(
  headerName: string,
  value: string,
  lookup: (name: string) => HeaderBearingConnection | undefined,
): string | null {
  const reference = parseConnectionReference(value);
  if (!reference) return null;
  const config = lookup(reference.name);
  if (!config) return null;
  const expected = connectionHeaderName(config);
  return headerNameMismatch(headerName, expected) ? expected : null;
}
