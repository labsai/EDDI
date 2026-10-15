import { createContext, useContext } from "react";

/**
 * The agent the resource on screen is being edited FOR, when the page knows it.
 *
 * Extension editors are rendered through `EDITOR_MAP` with only the resource's
 * own data, and a resource (an LLM config, an MCP-calls config…) can be shared
 * by several agents — so the editor itself cannot know which agent it belongs
 * to. The pages that do know (the resource page reached from an agent, Agent
 * Studio, the agent page) provide it here, and an editor reads it to tell its
 * secret pickers which agent a restricted vault key must be granted to.
 *
 * `undefined` everywhere else; the pickers then only badge restricted keys.
 */
export const OwningAgentContext = createContext<string | undefined>(undefined);

/** The owning agent's id, or `undefined` when the page does not know it. */
export function useOwningAgentId(): string | undefined {
  return useContext(OwningAgentContext);
}
