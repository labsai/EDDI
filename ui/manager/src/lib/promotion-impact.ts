import type { ImportPreview } from "@/lib/api/backup";

/**
 * Whether the preview writes a new version over an agent that already exists —
 * the only case where "compatible with the previous version" means anything. A
 * brand-new agent has no previous version and nothing running on it.
 */
export function writesOverExistingAgent(preview: ImportPreview): boolean {
  return (
    !!preview.targetAgentId ||
    preview.resources.some((r) => r.resourceType === "agent" && r.action !== "CREATE")
  );
}
