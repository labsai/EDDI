import type { TFunction } from "i18next";
import type { LucideIcon } from "lucide-react";
import { RESOURCE_TYPE_ICONS as ICONS, UNKNOWN_RESOURCE_TYPE_ICON } from "../resource-type-icons";
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
  /** English fallback, kept so a missing translation still reads sensibly. */
  label: string;
  /** i18n key — these labels are rendered, so they must not ship as raw English. */
  labelKey: string;
  /** From the shared RESOURCE_TYPE_ICONS, so a step looks the same everywhere. */
  icon: LucideIcon;
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

/** Well-known extension type IDs and their display info */
export const EXTENSION_TYPE_INFO: Record<
  string,
  ExtensionTypeConfig
> = {
  "eddi://ai.labs.parser": { label: "Input Parser", labelKey: "extensionTypes.parser", icon: ICONS.parser, order: 1, color: "text-sky-400" },
  // "behavior" is the pre-v6 name of rules: the same step, so the same icon.
  "eddi://ai.labs.behavior": { label: "Behavior", labelKey: "extensionTypes.behavior", icon: ICONS.rules, order: 2, color: "text-pink-400" },
  "eddi://ai.labs.rules": { label: "Rules", labelKey: "extensionTypes.rules", icon: ICONS.rules, order: 3, color: "text-blue-400" },
  "eddi://ai.labs.property": { label: "Property Setter", labelKey: "extensionTypes.property", icon: ICONS.propertysetter, order: 4, color: "text-teal-400" },
  "eddi://ai.labs.apicalls": { label: "API Calls", labelKey: "extensionTypes.apicalls", icon: ICONS.apicalls, order: 5, color: "text-orange-400" },
  "eddi://ai.labs.httpcalls": { label: "API Calls (legacy)", labelKey: "extensionTypes.httpcallsLegacy", icon: ICONS.apicalls, order: 6, color: "text-orange-400" },
  "eddi://ai.labs.llm": { label: "LLM", labelKey: "extensionTypes.llm", icon: ICONS.llm, order: 7, color: "text-purple-400" },
  "eddi://ai.labs.output": { label: "Output", labelKey: "extensionTypes.output", icon: ICONS.output, order: 8, color: "text-emerald-400" },
  "eddi://ai.labs.templating": { label: "Templating", labelKey: "extensionTypes.templating", icon: ICONS.templating, order: 9, color: "text-cyan-400" },
  "eddi://ai.labs.output.template": { label: "Templating", labelKey: "extensionTypes.templating", icon: ICONS.templating, order: 10, color: "text-cyan-400" },
  "eddi://ai.labs.mcpcalls": { label: "MCP Calls", labelKey: "extensionTypes.mcpcalls", icon: ICONS.mcpcalls, order: 11, color: "text-rose-400" },
  "eddi://ai.labs.workflow": { label: "Workflow", labelKey: "extensionTypes.workflow", icon: ICONS.workflow, order: 12, color: "text-indigo-400" },
  "eddi://ai.labs.dictionary": { label: "Dictionary", labelKey: "extensionTypes.dictionary", icon: ICONS.dictionary, order: 13, color: "text-amber-400" },
  "eddi://ai.labs.rag": { label: "RAG", labelKey: "extensionTypes.rag", icon: ICONS.rag, order: 14, color: "text-purple-400" },
};

/**
 * A human-readable label for an extension type.
 *
 * Takes `t` rather than returning English: these labels reach the pipeline
 * builder and the add-extension dialog, so an untranslated one is raw English
 * on an Arabic or Japanese screen. The unknown-type fallback stays the raw
 * type string, which is a diagnostic rather than prose.
 */
export function getExtensionLabel(type: string, t: TFunction): string {
  const info = EXTENSION_TYPE_INFO[type];
  return info ? t(info.labelKey, info.label) : type;
}

/**
 * Resolve a Lucide icon component for an extension type, falling back to the
 * shared unknown-type icon.
 *
 * @param type - Full eddi:// extension type (e.g. "eddi://ai.labs.llm")
 */
export function getExtensionIcon(type: string): LucideIcon {
  return EXTENSION_TYPE_INFO[type]?.icon ?? UNKNOWN_RESOURCE_TYPE_ICON;
}

/**
 * A running task's type → the extension type it belongs to.
 *
 * SSE task events and audit entries carry a task's `getType()` — "langchain",
 * "behavior_rules", "httpCalls", … — not its `eddi://` extension id. Looked up
 * in EXTENSION_TYPE_INFO directly they always missed, so every row of the chat
 * activity panel, and most of the audit trail, fell back to the unknown-type
 * icon and grey. The keys below are the values the backend's lifecycle tasks
 * return from getType(); "behavior", "httpcalls" and "propertysetter" are the
 * spellings the audit page was keyed on and the mock data still uses.
 */
const TASK_TYPE_TO_EXTENSION: Record<string, string> = {
  expressions: "eddi://ai.labs.parser",
  behavior_rules: "eddi://ai.labs.rules",
  behavior: "eddi://ai.labs.rules",
  properties: "eddi://ai.labs.property",
  propertysetter: "eddi://ai.labs.property",
  httpCalls: "eddi://ai.labs.apicalls",
  httpcalls: "eddi://ai.labs.apicalls",
  mcpCalls: "eddi://ai.labs.mcpcalls",
  rag: "eddi://ai.labs.rag",
  langchain: "eddi://ai.labs.llm",
  output: "eddi://ai.labs.output",
};

/**
 * Normalise whatever names a task — its runtime type, its task id
 * ("ai.labs.llm") or its extension type — to the extension type, so the
 * EXTENSION_TYPE_INFO accessors can resolve it. Unknown input comes back as is.
 */
export function extensionTypeForTask(taskType: string): string {
  if (Object.prototype.hasOwnProperty.call(TASK_TYPE_TO_EXTENSION, taskType)) return TASK_TYPE_TO_EXTENSION[taskType]!;
  if (!taskType.startsWith("eddi://") && EXTENSION_TYPE_INFO[`eddi://${taskType}`]) {
    return `eddi://${taskType}`;
  }
  return taskType;
}

/** Get the display colour for an extension type */
export function getExtensionColor(type: string): string {
  return EXTENSION_TYPE_INFO[type]?.color ?? "text-gray-400";
}

/** Full display config for an extension type — label, icon component, and colour */
export function getExtensionTypeConfig(
  type: string,
): { label: string; icon: LucideIcon; color: string } {
  const info = EXTENSION_TYPE_INFO[type];
  if (info) {
    return { label: info.label, icon: getExtensionIcon(type), color: info.color };
  }
  return {
    label: type.split(".").pop() ?? type,
    icon: UNKNOWN_RESOURCE_TYPE_ICON,
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
  // Renamed step types appear under BOTH spellings: the backend registers each
  // one twice, stored workflows carry whichever was current when they were
  // saved, and a missing entry silently makes the step look store-less — the
  // pipeline builder then offers no config to attach.
  "eddi://ai.labs.rules": "rules",
  "eddi://ai.labs.behavior": "rules",
  "eddi://ai.labs.apicalls": "apicalls",
  "eddi://ai.labs.httpcalls": "apicalls",
  "eddi://ai.labs.llm": "llm",
  "eddi://ai.labs.output": "output",
  "eddi://ai.labs.property": "propertysetter",
  "eddi://ai.labs.mcpcalls": "mcpcalls",
  "eddi://ai.labs.rag": "rag",
  "eddi://ai.labs.snippet": "snippets",
  "eddi://ai.labs.snippets": "snippets",
  "eddi://ai.labs.parser": "parser",
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
