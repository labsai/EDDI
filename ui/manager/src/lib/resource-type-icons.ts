import type { LucideIcon } from "lucide-react";
import {
  BookA,
  Braces,
  Brain,
  GitBranch,
  Globe,
  Library,
  MessageSquareText,
  Package,
  Puzzle,
  ScanText,
  ServerCog,
  Tags,
  Workflow,
} from "lucide-react";

/**
 * The one icon for each kind of resource, wherever it appears — the Resources
 * grid, a resource list or detail page, a workflow's pipeline, the chat activity
 * feed, the audit trail.
 *
 * This used to be six maps (two string tables, three component-local lookups and
 * the audit page's own table). They drifted: the card grid knew six of the ten
 * types and drew RAG, MCP, snippets and parsers with the Rules icon; the pipeline
 * drew parser, behavior, workflow, dictionary and RAG as the same page glyph; the
 * detail page aliased RAG to the dictionary's book. Every surface now resolves
 * through here, and `resource-type-icons.test.ts` fails if two kinds ever share
 * an icon again.
 *
 * Choices that are not obvious:
 * - `dictionary` is `BookA`, not `BookOpen` — the open book is the sidebar's
 *   Documentation link.
 * - `mcpcalls` is `ServerCog`, not `Plug` — the plug is Connections.
 * - `propertysetter` is `Tags`: it attaches named values to the conversation.
 *   A gear read as "settings".
 * - `rag` is `Library`, the conventional knowledge-base mark.
 */
export const RESOURCE_TYPE_ICONS = {
  rules: GitBranch,
  apicalls: Globe,
  output: MessageSquareText,
  dictionary: BookA,
  llm: Brain,
  propertysetter: Tags,
  mcpcalls: ServerCog,
  rag: Library,
  snippets: Puzzle,
  parser: ScanText,
  workflow: Workflow,
  templating: Braces,
} as const satisfies Record<string, LucideIcon>;

export type ResourceIconKind = keyof typeof RESOURCE_TYPE_ICONS;

/** For a type this build does not know — e.g. a newer backend's extension. */
export const UNKNOWN_RESOURCE_TYPE_ICON: LucideIcon = Package;

/** The icon for a resource slug (`"rules"`, `"llm"`, …), or the unknown-type icon. */
export function getResourceTypeIcon(slug: string | undefined | null): LucideIcon {
  // An own-property check, not `in`: "constructor" must not resolve to Object's.
  if (slug && Object.prototype.hasOwnProperty.call(RESOURCE_TYPE_ICONS, slug)) {
    return RESOURCE_TYPE_ICONS[slug as ResourceIconKind];
  }
  return UNKNOWN_RESOURCE_TYPE_ICON;
}
