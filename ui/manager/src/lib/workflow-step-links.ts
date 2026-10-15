/**
 * Links from a workflow step to the resource it references, and the matching
 * "does the saved workflow already contain this step" check.
 *
 * Kept apart from `pipeline-builder.tsx` (a component file) so the workflow page
 * can build the same link after it has saved a step.
 */

const STORE_TO_SLUG: Record<string, string> = {
  rulestore: "rules",
  apicallstore: "apicalls",
  llmstore: "llm",
  outputstore: "output",
  dictionarystore: "dictionary",
  propertysetterstore: "propertysetter",
  mcpcallsstore: "mcpcalls",
  ragstore: "rag",
  snippetstore: "snippets",
  parserstore: "parser",
};

/** Parse an eddi:// URI to extract the resource type slug and ID */
export function parseExtensionUri(uri: string): { slug: string; id: string } | null {
  try {
    const normalised = uri.startsWith("eddi://") ? uri.replace("eddi://", "http://") : uri;
    const url = new URL(normalised, "http://dummy");
    const segments = url.pathname.split("/").filter(Boolean);
    // e.g. /rulestore/rulesets/abc123 → slug=rules, id=abc123
    if (segments.length >= 3) {
      const storeName = segments[0];
      const resourceId = segments[2];
      if (storeName && resourceId) {
        return { slug: STORE_TO_SLUG[storeName] ?? storeName, id: resourceId };
      }
    }
  } catch {
    // ignore
  }
  return null;
}

/** The version a config URI names, or 1 when it names none. */
export function extensionUriVersion(uri: string): number {
  const match = uri.match(/[?&]version=(\d+)/);
  return match ? parseInt(match[1]!, 10) : 1;
}

export interface StepLinkContext {
  workflowId?: string;
  workflowVersion?: number;
  agentId?: string;
  agentVer?: string;
}

/**
 * The resource editor URL for a step's config URI. It carries the cascade
 * context (workflow, agent) and the version the step references, so the editor
 * opens what the pipeline shows rather than whatever is newest.
 */
export function buildStepResourceLink(configUri: string, ctx: StepLinkContext): string | null {
  const parsed = parseExtensionUri(configUri);
  if (!parsed) return null;
  let path = `/manage/resources/${parsed.slug}/${parsed.id}`;
  const params = new URLSearchParams();
  params.set("version", String(extensionUriVersion(configUri)));
  if (ctx.workflowId && ctx.workflowVersion) {
    params.set("wfId", ctx.workflowId);
    params.set("wfVer", String(ctx.workflowVersion));
  }
  if (ctx.agentId) params.set("agentId", ctx.agentId);
  if (ctx.agentVer) params.set("agentVer", ctx.agentVer);
  const qs = params.toString();
  if (qs) path += `?${qs}`;
  return path;
}

/**
 * Whether `uri` is referenced by any of the SAVED workflow's step URIs, at any
 * version. A step that is not cannot be edited through the cascade: the saved
 * workflow does not reference the resource, so the save is refused with
 * `workflowMissingResource` after the resource was written.
 */
export function isStepInSavedWorkflow(uri: string, savedUris: readonly string[]): boolean {
  const wanted = parseExtensionUri(uri);
  if (!wanted) return false;
  return savedUris.some((u) => {
    const p = parseExtensionUri(u);
    return p !== null && p.slug === wanted.slug && p.id === wanted.id;
  });
}
