import { useCallback, useRef } from "react";
import { toast } from "sonner";
import { useUpdateAgent } from "./use-agents";
import { getErrorMessage } from "@/lib/api-client";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import type { Agent } from "@/lib/api/agents";

interface SectionSaveVars {
  /** Accepted for call-site symmetry with `useUpdateAgent`; the hook's own id is used. */
  id?: string;
  /** Accepted for call-site symmetry; the version is chosen by the hook (see below). */
  version?: number;
  /** The whole agent document as this render's section would write it. */
  agent: Agent;
}

interface SectionSaveOptions {
  onSuccess?: () => void;
  /** Replaces the default error toast. */
  onError?: (err: unknown) => void;
}

/** What the last save from this section produced, and from which version. */
interface LastSave {
  agentId: string;
  /** The version the page showed when the first save of this run was made. */
  base: number;
  /** The version the latest save created. */
  saved: number;
  agent: Agent;
}

/**
 * Save an agent-config section — the agent detail page's inline editors, each
 * of which writes the whole agent document from the `agent` and `version` its
 * page passed down.
 *
 * Every save creates a new agent version, and the backend refuses a write to a
 * version that is no longer current. The page learns the new version only when
 * its queries refetch, so an edit made in the meantime — a toggle clicked while
 * the first save was in flight, a debounced field committing a moment later —
 * went out against the superseded version, 409'd, and was dropped without a
 * word: most sections pass no error handler.
 *
 * So this hook:
 *  - runs saves one at a time, in order;
 *  - addresses each save to the version the previous one CREATED, for as long
 *    as the page still shows a version at or before it (a page that has moved
 *    to a newer version, or back to an older one, is followed instead);
 *  - applies the fields this render changed onto the document that save wrote,
 *    so the second edit does not quietly undo the first;
 *  - and reports a failure, instead of swallowing it.
 *
 * `mutate` has `useUpdateAgent`'s call shape so a section switches over by
 * changing one line.
 */
export function useAgentSectionSave(agentId: string, version: number, agent: Agent) {
  const updateAgent = useUpdateAgent();
  const { mutateAsync } = updateAgent;
  const lastSave = useRef<LastSave | null>(null);
  const queue = useRef<Promise<void>>(Promise.resolve());

  const mutate = useCallback(
    (vars: SectionSaveVars, options?: SectionSaveOptions) => {
      const next = vars.agent;
      const run = async () => {
        const last = lastSave.current;
        const chained =
          last !== null &&
          last.agentId === agentId &&
          version >= last.base &&
          version <= last.saved;
        const targetVersion = chained ? last.saved : version;
        const document = chained ? applyChangedFields(last.agent, agent, next) : next;
        try {
          const result = await mutateAsync({ id: agentId, version: targetVersion, agent: document });
          const created = parseVersionFromLocation(result?.location);
          lastSave.current =
            created === null
              ? null
              : { agentId, base: chained ? last.base : version, saved: created, agent: document };
          options?.onSuccess?.();
        } catch (err) {
          if (options?.onError) options.onError(err);
          else toast.error(getErrorMessage(err));
        }
      };
      queue.current = queue.current.then(run, run);
    },
    [agentId, version, agent, mutateAsync],
  );

  return { mutate, isPending: updateAgent.isPending };
}

/**
 * `base` with every top-level field that `next` changed relative to `rendered`
 * — the document the section built its edit from. Sections replace whole
 * top-level blocks (`capabilities`, `hitlConfig`, …), so a changed field is one
 * whose reference differs; a field `next` dropped is dropped here too.
 */
function applyChangedFields(base: Agent, rendered: Agent, next: Agent): Agent {
  const result: Record<string, unknown> = { ...base };
  const nextFields = next as Record<string, unknown>;
  const renderedFields = rendered as Record<string, unknown>;
  const keys = new Set([...Object.keys(renderedFields), ...Object.keys(nextFields)]);
  for (const key of keys) {
    if (nextFields[key] === renderedFields[key]) continue;
    if (key in nextFields) result[key] = nextFields[key];
    else delete result[key];
  }
  return result as Agent;
}
