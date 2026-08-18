import { getResourceType } from "@/lib/api/resources";

/**
 * The `eddi://` URI helpers for regular-dictionary references.
 *
 * Kept out of both the parser editor and the picker dialog because they have
 * to agree exactly: the dialog writes these strings and the editor reads them
 * back to resolve a name. Built from `RESOURCE_TYPES` rather than a literal,
 * so a store or plural rename cannot leave one of the two behind.
 */
const DICTIONARY = getResourceType("dictionary")!;

/** `eddi://ai.labs.dictionary/dictionarystore/dictionaries/<id>?version=<v>` */
export function buildDictionaryUri(id: string, version: number): string {
  return `eddi://${DICTIONARY.extension}/${DICTIONARY.store}/${DICTIONARY.plural}/${id}?version=${version}`;
}

/** The resource id in a dictionary URI, or `""` when it carries none. */
export function dictionaryIdFromUri(uri: string): string {
  return parseDictionaryUri(uri).id;
}

/**
 * Split a dictionary URI into its id and version.
 *
 * Tolerant on purpose — the URI can also have been typed by hand through the
 * picker's manual field, and a half-written one must render as itself rather
 * than crash the editor that displays it.
 */
export function parseDictionaryUri(uri: string): { id: string; version: number | null } {
  try {
    const normalised = uri.startsWith("eddi://") ? uri.replace("eddi://", "http://") : uri;
    const url = new URL(normalised, "http://dummy");
    const segments = url.pathname.split("/").filter(Boolean);
    const id = segments[segments.length - 1] ?? "";
    const rawVersion = url.searchParams.get("version");
    const version = rawVersion === null ? null : Number.parseInt(rawVersion, 10);
    return { id, version: version !== null && Number.isFinite(version) ? version : null };
  } catch {
    return { id: "", version: null };
  }
}
