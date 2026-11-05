import { parseResourceUri } from "@/lib/api/agents";
import { RESOURCE_TYPES } from "@/lib/api/resources";

/**
 * One configuration resource an LLM task names in `tools` — an `eddi://` URI
 * such as `eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=1`.
 *
 * The URI is what the backend needs and nothing a person can read: an agent
 * with sixteen API-call tools rendered as sixteen copies of the same 60-char
 * prefix. The parts a UI needs are the id and version (to resolve the
 * descriptor's name), and the resource slug (for an icon and a link).
 */
export interface ToolResourceRef {
  uri: string;
  id: string;
  version: number;
  /** `RESOURCE_TYPES` slug (`apicalls`, `mcpcalls`, …), or null if the store is unknown. */
  slug: string | null;
}

/**
 * The store segment is authoritative (`/apicallstore/`); the `ai.labs.*` host is
 * the fallback for a URI written without one.
 */
function slugFor(uri: string): string | null {
  const store = /\/([a-z]+store)\//i.exec(uri)?.[1]?.toLowerCase();
  const byStore = store ? RESOURCE_TYPES.find((rt) => rt.store === store) : undefined;
  if (byStore) return byStore.slug;
  const extension = /^eddi:\/\/(ai\.labs\.[a-z]+)/i.exec(uri)?.[1]?.toLowerCase();
  return extension ? (RESOURCE_TYPES.find((rt) => rt.extension === extension)?.slug ?? null) : null;
}

/**
 * Parse an LLM task's `tools` list into resource references, dropping blanks
 * and duplicates (the same id and version listed twice is one tool source).
 */
export function parseToolResourceRefs(tools: readonly string[] | undefined): ToolResourceRef[] {
  if (!tools?.length) return [];
  const seen = new Set<string>();
  const refs: ToolResourceRef[] = [];
  for (const raw of tools) {
    const uri = raw?.trim();
    if (!uri) continue;
    const { id, version } = parseResourceUri(uri);
    const key = `${id}@${version}`;
    if (seen.has(key)) continue;
    seen.add(key);
    refs.push({ uri, id, version, slug: slugFor(uri) });
  }
  return refs;
}
