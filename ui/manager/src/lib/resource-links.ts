import { RESOURCE_TYPES } from "@/lib/api/resources";

/**
 * Where in the Manager a resource lives, from its `eddi://` URI.
 *
 * Used where a resource is named without its type being known up front — a
 * notification says "Alice shared X with you", and X can be an agent, a
 * workflow, a group or any configuration resource.
 *
 * @returns a route, or null when the URI is missing or of a type the Manager
 *   has no page for
 */
export function managerRouteFor(resourceUri: string | null | undefined, resourceId: string): string | null {
  if (!resourceUri) return null;
  const match = /^eddi:\/\/[^/]+\/([^/]+)\//.exec(resourceUri);
  const store = match?.[1];
  if (!store) return null;
  const id = encodeURIComponent(resourceId);
  switch (store) {
    case "agentstore":
      return `/manage/agentview/${id}`;
    case "workflowstore":
      return `/manage/workflowview/${id}`;
    case "groupstore":
      return `/manage/groups/${id}`;
    default: {
      const type = RESOURCE_TYPES.find((t) => t.store === store);
      return type ? `/manage/resources/${type.slug}/${id}` : null;
    }
  }
}

/** Whether a resource URI names an agent — the one kind of resource people chat with. */
export function isAgentUri(resourceUri: string | null | undefined): boolean {
  return !!resourceUri && /^eddi:\/\/[^/]+\/agentstore\//.test(resourceUri);
}

/** The Chat UI address for an agent. */
export function chatLinkFor(agentId: string, origin = window.location.origin): string {
  return `${origin}/chat/production/${encodeURIComponent(agentId)}`;
}
