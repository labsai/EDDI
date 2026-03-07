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
  "ai.labs.parser": { label: "Parser", icon: "FileText", order: 1 },
  "ai.labs.behavior": { label: "Behavior Rules", icon: "GitBranch", order: 2 },
  "ai.labs.property": { label: "Property Setter", icon: "Settings", order: 3 },
  "ai.labs.httpcalls": { label: "HTTP Calls", icon: "Globe", order: 4 },
  "ai.labs.langchain": { label: "LangChain", icon: "Brain", order: 5 },
  "ai.labs.output": { label: "Output", icon: "MessageSquareText", order: 6 },
  "ai.labs.output.template": { label: "Output Template", icon: "FileCode", order: 7 },
};

/** Get a human-readable label for an extension type */
export function getExtensionLabel(type: string): string {
  return EXTENSION_TYPE_INFO[type]?.label ?? type;
}
