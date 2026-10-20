import { parseResourceUri } from "@/lib/api/agents";
import { RESOURCE_TYPES } from "@/lib/api/resources";

/**
 * What kind of tool a chip is. Resource kinds are the Manager's resource slugs, so
 * a chip can link to that resource's editor.
 */
export type ToolKind = "apicalls" | "mcpcalls" | "resource" | "builtin" | "feature";

/** The workflow features an LLM task can expose as tools, translated by the panel. */
export type ToolFeature = "httpCalls" | "mcpCalls" | "memory";

/** One chip in the agent details panel's "Active Tools" section. */
export interface ActiveTool {
  /** Stable identity: the full resource URI for a tool resource. */
  key: string;
  kind: ToolKind;
  /** What the chip shows: a descriptor name, a short id, or a built-in's name. Empty for a feature. */
  label: string;
  /** The tooltip: the full reference, so nothing is lost by the short label. */
  title: string;
  /** The resource editor this chip opens, for a resource of a known type. */
  href?: string;
  /** The name is still being read: render a placeholder rather than a short id that flickers. */
  loading?: boolean;
  /** For a feature chip: which feature, for the panel to translate. */
  feature?: ToolFeature;
}

/** The tools of one kind, in the order the panel shows the groups. */
export interface ToolGroup {
  kind: ToolKind;
  tools: ActiveTool[];
}

export interface ActiveToolSources {
  /** The LLM task's `tools`: resource URIs, possibly repeated. */
  toolUris?: string[] | null;
  /** The descriptor name of a tool URI, once it has been read. */
  nameOf: (uri: string) => string | undefined;
  /** Whether the descriptor of a tool URI is still being read. */
  isLoading?: (uri: string) => boolean;
  builtIns?: string[] | null;
  httpCallTools?: boolean;
  mcpCallTools?: boolean;
  memoryTools?: boolean;
}

const GROUP_ORDER: ToolKind[] = ["apicalls", "mcpcalls", "resource", "builtin", "feature"];

/** A resource id short enough for a chip: `6ad75f…a7b2`. */
export function shortToolId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
}

/** The distinct, non-empty tool resource URIs, in their configured order. */
export function distinctToolUris(toolUris?: string[] | null): string[] {
  return Array.from(new Set((toolUris ?? []).map((uri) => uri?.trim()).filter((uri): uri is string => !!uri)));
}

/** The resource slug a tool URI belongs to (`apicalls`, `mcpcalls`, …), or null. */
export function toolResourceSlug(uri: string): string | null {
  const match = /^eddi:\/\/([^/]+)\//.exec(uri);
  if (!match) return null;
  return RESOURCE_TYPES.find((type) => type.extension === match[1])?.slug ?? null;
}

/**
 * The "Active Tools" groups. Tool resources are named from their descriptors (a raw
 * `eddi://ai.labs.apicalls/…?version=N` URI is unreadable and overflowed the card),
 * listed once each, link to their editor, and fall back to a short id when no name
 * can be read. Empty groups are left out.
 */
export function buildToolGroups(sources: ActiveToolSources): ToolGroup[] {
  const byKind = new Map<ToolKind, ActiveTool[]>(GROUP_ORDER.map((kind) => [kind, []]));
  const seen = new Set<string>();
  const add = (tool: ActiveTool) => {
    if (seen.has(tool.key)) return;
    seen.add(tool.key);
    byKind.get(tool.kind)!.push(tool);
  };

  for (const uri of distinctToolUris(sources.toolUris)) {
    const { id, version } = parseResourceUri(uri);
    const slug = toolResourceSlug(uri);
    const name = sources.nameOf(uri)?.trim();
    add({
      key: uri,
      kind: slug === "apicalls" || slug === "mcpcalls" ? slug : "resource",
      label: name || shortToolId(id),
      title: uri,
      href: slug ? `/manage/resources/${slug}/${encodeURIComponent(id)}?version=${version}` : undefined,
      loading: !name && (sources.isLoading?.(uri) ?? false),
    });
  }
  for (const builtIn of sources.builtIns ?? []) {
    const name = builtIn?.trim();
    if (name) add({ key: `builtin:${name}`, kind: "builtin", label: name, title: name });
  }
  const features: [boolean | undefined, ToolFeature][] = [
    [sources.httpCallTools, "httpCalls"],
    [sources.mcpCallTools, "mcpCalls"],
    [sources.memoryTools, "memory"],
  ];
  for (const [enabled, feature] of features) {
    if (enabled) add({ key: `feature:${feature}`, kind: "feature", label: "", title: "", feature });
  }

  return GROUP_ORDER.map((kind) => ({ kind, tools: byKind.get(kind)! })).filter((group) => group.tools.length > 0);
}

/**
 * The groups as shown while collapsed: the first `max` tools across all groups,
 * keeping group order, plus how many were left out. Nothing is cut (and the
 * caller shows no toggle) when the total fits.
 */
export function collapseToolGroups(groups: ToolGroup[], max: number): { groups: ToolGroup[]; hidden: number } {
  const total = groups.reduce((sum, group) => sum + group.tools.length, 0);
  if (total <= max) return { groups, hidden: 0 };
  let budget = max;
  const visible: ToolGroup[] = [];
  for (const group of groups) {
    if (budget <= 0) break;
    visible.push({ kind: group.kind, tools: group.tools.slice(0, budget) });
    budget -= Math.min(budget, group.tools.length);
  }
  return { groups: visible, hidden: total - max };
}
