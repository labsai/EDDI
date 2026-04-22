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
  { label: string; icon: string; order: number }
> = {
  "ai.labs.parser": { label: "Input Parser", icon: "FileText", order: 1 },
  "ai.labs.rules": { label: "Rules", icon: "GitBranch", order: 2 },
  "ai.labs.property": { label: "Property Setter", icon: "Settings", order: 3 },
  "ai.labs.httpcalls": { label: "HTTP Calls", icon: "Globe", order: 4 },
  "ai.labs.apicalls": { label: "HTTP Calls", icon: "Globe", order: 4 },
  "ai.labs.llm": { label: "LLM", icon: "Brain", order: 5 },
  "ai.labs.output": { label: "Output", icon: "MessageSquareText", order: 6 },
  "ai.labs.templating": { label: "Templating", icon: "FileCode", order: 7 },
  "ai.labs.output.template": { label: "Templating", icon: "FileCode", order: 7 },
  "ai.labs.mcpcalls": { label: "MCP Calls", icon: "Plug", order: 8 },
};

/** Get a human-readable label for an extension type */
export function getExtensionLabel(type: string): string {
  return EXTENSION_TYPE_INFO[type]?.label ?? type;
}

/**
 * Map from EDDI extension type to the resource slug used in RESOURCE_TYPES.
 * Extension types without a standalone resource store (e.g. templating)
 * are not included — they use embedded config or have no separate store.
 */
export const EXTENSION_TO_RESOURCE_SLUG: Record<string, string> = {
  "ai.labs.dictionary": "dictionary",
  "ai.labs.rules": "rules",
  "ai.labs.httpcalls": "apicalls",
  "ai.labs.apicalls": "apicalls",
  "ai.labs.llm": "llm",
  "ai.labs.output": "output",
  "ai.labs.property": "propertysetter",
  "ai.labs.mcpcalls": "mcpcalls",
  "ai.labs.rag": "rag",
  "ai.labs.snippet": "snippets",
  "ai.labs.snippets": "snippets",
};

/** Check if an extension type has a standalone resource config store */
export function hasResourceStore(extensionType: string): boolean {
  return extensionType in EXTENSION_TO_RESOURCE_SLUG;
}

/** Get the resource slug for an extension type (undefined if no store) */
export function getResourceSlugForExtension(extensionType: string): string | undefined {
  return EXTENSION_TO_RESOURCE_SLUG[extensionType];
}
