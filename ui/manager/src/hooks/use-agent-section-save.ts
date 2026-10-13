import { useCallback, useSyncExternalStore } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useTranslation } from "react-i18next";
import { showSavedNotLiveToast } from "@/lib/save-not-live-toast";
import { useUpdateAgent } from "./use-agents";
import { getErrorMessage, isApiError } from "@/lib/api-client";
import { getAgent, type Agent } from "@/lib/api/agents";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import { agentKeys } from "@/lib/query-keys";

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

/** What the last save to an agent produced, and from which version. */
interface LastSave {
  /** The version the page showed when the first save of this run was made. */
  base: number;
  /** The version the latest save created. */
  saved: number;
  agent: Agent;
}

/**
 * Save state for ONE agent, shared by every section that edits it.
 *
 * It lives at module scope, keyed by agent id, rather than in each section's
 * hook instance: the detail page renders eight-odd sections, and with a queue
 * per section a toggle in one while another's save was in flight (or before the
 * page had refetched) went to the superseded version and 409'd.
 */
interface AgentSaveState {
  lastSave: LastSave | null;
  queue: Promise<void>;
  /** Saves queued or in flight. */
  pending: number;
  listeners: Set<() => void>;
}

const states = new Map<string, AgentSaveState>();

function stateFor(agentId: string): AgentSaveState {
  let state = states.get(agentId);
  if (!state) {
    state = { lastSave: null, queue: Promise.resolve(), pending: 0, listeners: new Set() };
    states.set(agentId, state);
  }
  return state;
}

function setPending(state: AgentSaveState, delta: number) {
  state.pending += delta;
  state.listeners.forEach((listener) => listener());
}

/**
 * Save an agent-config section — the agent detail page's inline editors, each
 * of which writes the whole agent document from the `agent` and `version` its
 * page passed down.
 *
 * Every save creates a new agent version, and the backend refuses a write to a
 * version that is no longer current. The page learns the new version only when
 * its queries refetch, so an edit made in the meantime — in this section or any
 * other, a toggle clicked while a save was in flight, a debounced field
 * committing a moment later — went out against the superseded version, 409'd,
 * and was dropped without a word: most sections pass no error handler.
 *
 * So, per agent and across every section:
 *  - saves run one at a time, in order;
 *  - each is addressed to the version the previous one CREATED, for as long as
 *    the page still shows a version at or before it (a page that has moved to a
 *    newer version, or back to an older one, is followed instead);
 *  - the fields this render changed are applied onto the document that version
 *    actually holds — the last save's, or the cached copy of the page's version
 *    when the `agent` on screen is a placeholder from another one — so an edit
 *    never quietly undoes another;
 *  - a failure is reported, not swallowed, and a conflict (409) also drops the
 *    chain and refetches, so the page moves onto the version that superseded it;
 *  - `isPending` covers queued saves as well as the one in flight.
 *
 * `mutate` has `useUpdateAgent`'s call shape so a section switches over by
 * changing one line.
 */
export function useAgentSectionSave(agentId: string, version: number, agent: Agent) {
  const queryClient = useQueryClient();
  const { t } = useTranslation();
  const { mutateAsync } = useUpdateAgent();

  const subscribe = useCallback(
    (listener: () => void) => {
      const state = stateFor(agentId);
      state.listeners.add(listener);
      return () => {
        state.listeners.delete(listener);
      };
    },
    [agentId],
  );
  const isPending = useSyncExternalStore(subscribe, () => stateFor(agentId).pending > 0);

  const mutate = useCallback(
    (vars: SectionSaveVars, options?: SectionSaveOptions) => {
      const next = vars.agent;
      const state = stateFor(agentId);
      const run = async () => {
        try {
          const last = state.lastSave;
          const chained = last !== null && version >= last.base && version <= last.saved;
          const targetVersion = chained ? last.saved : version;
          const base = chained ? last.agent : await documentAt(version);
          const document = base === agent ? next : applyChangedFields(base, agent, next);
          const result = await mutateAsync({ id: agentId, version: targetVersion, agent: document });
          const created = parseVersionFromLocation(result?.location);
          state.lastSave =
            created === null
              ? null
              : { base: chained ? last.base : version, saved: created, agent: document };
          // Every section save writes a whole new agent version, and nothing on
          // screen said so: say which one, and that it is not what is serving.
          showSavedNotLiveToast({ t, queryClient, agentId, newAgentVersion: created });
          options?.onSuccess?.();
        } catch (err) {
          if (isApiError(err) && err.status === 409) {
            // The version this save addressed is no longer current: another
            // write created a newer one. The chain is dead, and until the page
            // learns the newer version every save repeats the 409 — a failed
            // save refetches nothing by itself (`useUpdateAgent` invalidates
            // only on success). So drop the chain and refetch; a page following
            // the latest version moves onto it and the next save goes there,
            // merged onto the document that version actually holds.
            //
            // Only on a conflict. After any other failure (a 500, a dropped
            // connection) the version the last save created is still current
            // and the page may not have caught up to it yet; dropping the chain
            // then would send the next edit to the page's older version and
            // turn one failed save into two.
            state.lastSave = null;
            void queryClient.invalidateQueries({ queryKey: agentKeys.all });
          }
          if (options?.onError) options.onError(err);
          else toast.error(getErrorMessage(err));
        } finally {
          setPending(state, -1);
        }
      };
      setPending(state, +1);
      state.queue = state.queue.then(run, run);

      /**
       * The document stored under `v`: the cached copy — normally the very
       * object the section rendered from — or, when the section is showing a
       * placeholder from another version, a fresh read. A failed read falls
       * back to the rendered document, which is what was sent before.
       */
      async function documentAt(v: number): Promise<Agent> {
        try {
          return await queryClient.ensureQueryData({
            queryKey: [...agentKeys.all, agentId, v],
            queryFn: () => getAgent(agentId, v),
          });
        } catch {
          return agent;
        }
      }
    },
    [agentId, version, agent, mutateAsync, queryClient, t],
  );

  return { mutate, isPending };
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
