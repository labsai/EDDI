/**
 * Client-side mirror of EDDI's knowledge-base storage rules (`KnowledgeBaseStorage`
 * and `KnowledgeBaseStorageGuard`, EDDI 6.6+). The server is authoritative and
 * refuses all of this on save; mirroring it lets the editor say so while the
 * author is still typing, with the server's reasoning.
 */

/** `storeNamespace` of a knowledge base addressed by its id — every one created since 6.6. */
export const NAMESPACE_ID = "id";

/** Every default location EDDI derives starts with this; an explicit one may not. */
export const RESERVED_PREFIX = "eddi_kb";

const ID_PREFIX = "eddi_kbid_";
const MAX_PG_IDENTIFIER_LENGTH = 63;
const PG_QUALIFIED_IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_$]{0,62}(?:\.[A-Za-z_][A-Za-z0-9_$]{0,62})?$/;

/** The `storeParameters` key that names the physical location, or null (`in-memory`). */
export function locationParameter(storeType: string | undefined | null): string | null {
  switch (storeType) {
    case "pgvector":
      return "table";
    case "mongodb-atlas":
    case "qdrant":
    case "chroma":
      return "collectionName";
    case "elasticsearch":
      return "indexName";
    default:
      return null;
  }
}

/** Whether a name falls in EDDI's own namespace: any dot-separated part starts with `eddi_kb`, any case. */
export function isReservedLocation(name: string): boolean {
  return name
    .trim()
    .toLowerCase()
    .split(".")
    .some((part) => part.trim().startsWith(RESERVED_PREFIX));
}

export type LocationProblem = "reference" | "pgIdentifier" | "reserved";

/**
 * What the server would refuse about an explicit location, checked in the
 * server's order. `null` for a blank value — no explicit location means the
 * knowledge base's own default, which is always allowed.
 */
export function locationProblem(storeType: string | undefined | null, value: string | undefined | null): LocationProblem | null {
  if (!value || !value.trim()) return null;
  // A ${vars:…} reference resolves only when the store is built, and global
  // variables are editable by any editor — so it could be re-pointed at another
  // knowledge base's table, or at SQL, after every check had passed. Since 6.6 a
  // stored one stops ingesting and retrieving until it is saved written out.
  if (value.includes("${")) return "reference";
  if (storeType === "pgvector" && !PG_QUALIFIED_IDENTIFIER.test(value.trim())) return "pgIdentifier";
  if (isReservedLocation(value)) return "reserved";
  return null;
}

/** Whether the knowledge base is on the per-id layout. Absent (or `"name"`) is the 6.5 layout. */
export function usesIdNamespace(storeNamespace: string | undefined | null): boolean {
  return storeNamespace === NAMESPACE_ID;
}

/**
 * The default location of a knowledge base on the per-id layout —
 * `eddi_kbid_<id>`, lower-cased with anything but `[a-z0-9_]` replaced, and cut
 * to PostgreSQL's 63-character identifier limit for pgvector. `null` for a store
 * with no named location, or without an id yet.
 */
export function idLayoutLocation(storeType: string | undefined | null, ragId: string | undefined | null): string | null {
  if (!ragId || !locationParameter(storeType)) return null;
  const name = ID_PREFIX + ragId.toLowerCase().replace(/[^a-z0-9_]/g, "_");
  return storeType === "pgvector" ? name.slice(0, MAX_PG_IDENTIFIER_LENGTH) : name;
}
