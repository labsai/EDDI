import { parseResourceUri } from "@/lib/api/agents";
import { RESOURCE_TYPES } from "@/lib/api/resources";
import type { WorkflowConfiguration } from "@/lib/api/workflows";

/**
 * The step types the backend turns into LLM tools. Matched EXACTLY, as the
 * backend does (`WorkflowTraversal.discoverConfigs` compares the type string):
 * `HttpCallToolsProvider` looks for `eddi://ai.labs.httpcalls` and
 * `McpToolsProvider` for `eddi://ai.labs.mcpcalls`. A step typed with the
 * `eddi://ai.labs.apicalls` alias still runs in the pipeline but is not offered
 * to the LLM, so it is deliberately not listed here.
 */
export const HTTP_TOOLS_STEP_TYPE = "eddi://ai.labs.httpcalls";
export const MCP_TOOLS_STEP_TYPE = "eddi://ai.labs.mcpcalls";

/** One tool set the LLM receives: an apicalls or mcpcalls resource a workflow step points at. */
export interface ToolResourceRef {
  uri: string;
  id: string;
  version: number;
  kind: "http" | "mcp";
  /** `RESOURCE_TYPES` slug (`apicalls`, `mcpcalls`), or null if the store is unknown. */
  slug: string | null;
}

/**
 * The store segment is authoritative (`/apicallstore/`): a URI naming a store
 * this UI does not know gets no slug, so it is never linked to the wrong editor.
 * The `ai.labs.*` host is the fallback only for a URI with no store segment.
 */
function slugFor(uri: string): string | null {
  const store = /\/([a-z]+store)\//i.exec(uri)?.[1]?.toLowerCase();
  if (store) return RESOURCE_TYPES.find((rt) => rt.store === store)?.slug ?? null;
  const extension = /^eddi:\/\/(ai\.labs\.[a-z]+)/i.exec(uri)?.[1]?.toLowerCase();
  return extension ? (RESOURCE_TYPES.find((rt) => rt.extension === extension)?.slug ?? null) : null;
}

/**
 * The tool sets an LLM task actually receives, read from the agent's workflows
 * the way the backend reads them.
 *
 * NOT from the task's `tools` list: the backend never resolves those entries
 * (`LlmConfiguration.Task#tools` — "a non-empty list only switches the task into
 * agent mode"). HTTP and MCP tools are discovered from every workflow of the
 * agent, gated by `enableHttpCallTools` / `enableMcpCallTools`, both of which
 * default to on — so only an explicit `false` turns a category off.
 *
 * Duplicates (the same resource id and version in two steps) count once.
 */
export function collectWorkflowToolRefs(
  workflows: readonly (WorkflowConfiguration | undefined)[],
  options: { http: boolean; mcp: boolean },
): ToolResourceRef[] {
  const seen = new Set<string>();
  const refs: ToolResourceRef[] = [];
  for (const workflow of workflows) {
    for (const step of workflow?.workflowSteps ?? []) {
      const kind =
        step.type === HTTP_TOOLS_STEP_TYPE && options.http
          ? "http"
          : step.type === MCP_TOOLS_STEP_TYPE && options.mcp
            ? "mcp"
            : null;
      if (!kind) continue;
      const rawUri = step.config?.uri;
      const uri = typeof rawUri === "string" ? rawUri.trim() : "";
      if (!uri) continue;
      const { id, version } = parseResourceUri(uri);
      const key = `${id}@${version}`;
      if (seen.has(key)) continue;
      seen.add(key);
      refs.push({ uri, id, version, kind, slug: slugFor(uri) });
    }
  }
  return refs;
}
