import { Brain, FileCode, FileText, GitBranch, Globe, MessageSquareText, Plug, Puzzle, Settings } from "lucide-react";
import { api } from "../api-client";

/** Matches EDDI backend ExtensionDescriptor.ConfigValue */
export interface ExtensionConfigValue {
  displayName: string;
  fieldType: "INT" | "DOUBLE" | "STRING" | "BOOLEAN" | "ARRAY" | "URI";
  isOptional: boolean;
  defaultValue: unknown;
}

/** Matches EDDI backend ExtensionDescriptor */
export interface ExtensionDescriptor {
  type: string;
  displayName: string;
  configs: Record<string, ExtensionConfigValue>;
  extensions: Record<string, ExtensionDescriptor[]>;
}

export interface ExtensionTypeConfig {
  label: string;
  icon: string;
  order: number;
  // Color is used in the orphans.tsx
  color: string
}

/**
 * Fetch all available extension types from the EDDI extension store.
 * GET /extensionstore/extensions
 */
export function getExtensionTypes(
  filter = ""
): Promise<ExtensionDescriptor[]> {
  const params = new URLSearchParams();
  if (filter) params.set("filter", filter);
  const qs = params.toString();
  return api.get<ExtensionDescriptor[]>(
    `/extensionstore/extensions${qs ? `?${qs}` : ""}`
  );
}

const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
  Plug,
};

/** Well-known extension type IDs and their display info */
export const EXTENSION_TYPE_INFO: Record<
  string,
  ExtensionTypeConfig
> = {
  "eddi://ai.labs.parser": { label: "Input Parser", icon: "FileText", order: 1, color: "text-sky-400" },
  "eddi://ai.labs.behavior": { label: "Behavior", icon: "FileText", order: 2, color: "text-pink-400" },
  "eddi://ai.labs.rules": { label: "Rules", icon: "GitBranch", order: 3, color: "text-blue-400" },
  "eddi://ai.labs.property": { label: "Property Setter", icon: "Settings", order: 4, color: "text-teal-400" },
  "eddi://ai.labs.httpcalls": { label: "HTTP Calls", icon: "Globe", order: 5, color: "text-orange-400" },
  "eddi://ai.labs.apicalls": { label: "HTTP Calls", icon: "Globe", order: 6, color: "text-orange-400" },
  "eddi://ai.labs.llm": { label: "LLM", icon: "Brain", order: 7, color: "text-purple-400" },
  "eddi://ai.labs.output": { label: "Output", icon: "MessageSquareText", order: 8, color: "text-emerald-400" },
  "eddi://ai.labs.templating": { label: "Templating", icon: "FileCode", order: 9, color: "text-cyan-400" },
  "eddi://ai.labs.output.template": { label: "Templating", icon: "FileCode", order: 10, color: "text-cyan-400" },
  "eddi://ai.labs.mcpcalls": { label: "MCP Calls", icon: "Plug", order: 11, color: "text-rose-400" },
  "eddi://ai.labs.workflow": { label: "Workflow", icon: "FileText", order: 12, color: "text-indigo-400" },
  "eddi://ai.labs.dictionary": { label: "Dictionary", icon: "FileText", order: 13, color: "text-amber-400" },
  "eddi://ai.labs.rag": { label: "RAG", icon: "FileText", order: 14, color: "text-purple-400" },
};

/** Get a human-readable label for an extension type */
export function getExtensionLabel(type: string): string {
  return EXTENSION_TYPE_INFO[type]?.label ?? type;
}

/**
 * Resolve a Lucide icon component for an extension type.
 * Looks up the type in EXTENSION_TYPE_INFO to find the icon key,
 * then maps it through iconMap. Returns the generic Puzzle icon as fallback.
 *
 * @param type - Full eddi:// extension type (e.g. "eddi://ai.labs.llm")
 */
export function getExtensionIcon(type: string): React.ComponentType<{ className?: string }> {
  const info = EXTENSION_TYPE_INFO[type];
  if (info && iconMap[info.icon]) return iconMap[info.icon]!;
  return Puzzle;
}

/** Get the display colour for an extension type */
export function getExtensionColor(type: string): string {
  return EXTENSION_TYPE_INFO[type]?.color ?? "text-gray-400";
}

/** Full display config for an extension type — label, icon component, and colour */
export function getExtensionTypeConfig(
  type: string,
): { label: string; icon: React.ComponentType<{ className?: string }>; color: string } {
  const info = EXTENSION_TYPE_INFO[type];
  if (info) {
    return { label: info.label, icon: getExtensionIcon(type), color: info.color };
  }
  return {
    label: type.split(".").pop() ?? type,
    icon: Puzzle,
    color: "text-gray-400",
  };
}

/**
 * Map from EDDI extension type to the resource slug used in RESOURCE_TYPES.
 * Extension types without a standalone resource store (e.g. templating)
 * are not included — they use embedded config or have no separate store.
 */
export const EXTENSION_TO_RESOURCE_SLUG: Record<string, string> = {
  "eddi://ai.labs.dictionary": "dictionary",
  "eddi://ai.labs.rules": "rules",
  "eddi://ai.labs.httpcalls": "apicalls",
  "eddi://ai.labs.apicalls": "apicalls",
  "eddi://ai.labs.llm": "llm",
  "eddi://ai.labs.output": "output",
  "eddi://ai.labs.property": "propertysetter",
  "eddi://ai.labs.mcpcalls": "mcpcalls",
  "eddi://ai.labs.rag": "rag",
  "eddi://ai.labs.snippet": "snippets",
  "eddi://ai.labs.snippets": "snippets",
};

/** Check if an extension type has a standalone resource config store */
export function hasResourceStore(extensionType: string): boolean {
  return extensionType in EXTENSION_TO_RESOURCE_SLUG;
}

/** Get the resource slug for an extension type (undefined if no store) */
export function getResourceSlugForExtension(extensionType: string): string | undefined {
  return EXTENSION_TO_RESOURCE_SLUG[extensionType];
}

/** Get the sort order for an extension type (99 for unknown types) */
export function getExtensionSortOrder(type: string): number {
  return EXTENSION_TYPE_INFO[type]?.order ?? 99;
}

/** Sort extension descriptors by their pipeline order */
export function sortExtensionTypes<T extends { type: string }>(types: T[]): T[] {
  return [...types].sort((a, b) => getExtensionSortOrder(a.type) - getExtensionSortOrder(b.type));
}
