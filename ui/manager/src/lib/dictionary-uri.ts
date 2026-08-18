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
 * Split a dictionary URI into its id and version, or into no id at all.
 *
 * Never throws — the URI can have been typed by hand in the picker's manual
 * field, and a half-written one must render as itself rather than break the
 * editor displaying it.
 *
 * But "does not throw" is not "accepts anything": `new URL` resolves free text
 * and every other store's URI just as happily, and taking the last segment of
 * those yields an id-shaped string that is not an id. Callers turn that into a
 * `/manage/resources/dictionary/<id>` link to nothing, a descriptor request
 * that cannot resolve, and a duplicate-detection key that matches the wrong
 * row. Only a `<store>/<plural>/<id>` tail is an id here; anything else has
 * none, and the caller falls back to showing the raw URI.
 */
export function parseDictionaryUri(uri: string): { id: string; version: number | null } {
  try {
    const normalised = uri.startsWith("eddi://") ? uri.replace("eddi://", "http://") : uri;
    const url = new URL(normalised, "http://dummy");
    const segments = url.pathname.split("/").filter(Boolean);
    const [store, plural, id] = segments.slice(-3);
    if (store !== DICTIONARY.store || plural !== DICTIONARY.plural || !id) {
      return { id: "", version: null };
    }
    const rawVersion = url.searchParams.get("version");
    const version = rawVersion === null ? null : Number.parseInt(rawVersion, 10);
    return { id, version: version !== null && Number.isFinite(version) ? version : null };
  } catch {
    return { id: "", version: null };
  }
}
