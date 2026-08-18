import type { TFunction } from "i18next";
import { LLM_PROVIDERS, getProviderConfig } from "@/lib/api/agent-setup";
import type { MemberSlot } from "./team-builder";

/**
 * Client-side mirror of what `POST /administration/agents/setup` will refuse,
 * so the wizard can say so on the Team step instead of discovering it
 * mid-creation — after it has already provisioned the advisors ahead of the
 * failing one in the list.
 *
 * `AgentSetupService.setupAgent` rejects, in this order: a blank name, a blank
 * system prompt, and a missing API key for any provider that is not local
 * (`isLocalLlmProvider`: ollama, jlama, bedrock, oracle-genai). A blank
 * provider falls back to `DEFAULT_PROVIDER` — anthropic — which needs a key,
 * so "no provider, no key" is the common failure, not the safe default.
 *
 * Lives outside `team-builder.tsx` so that file keeps exporting components
 * only (react-refresh/only-export-components).
 */

/** Provider/model/key shared by every new advisor that does not set its own. */
export interface LlmDefaults {
  provider: string;
  model: string;
  apiKey: string;
}

/** Mirrors `AgentSetupService.DEFAULT_PROVIDER`. */
export const BACKEND_DEFAULT_PROVIDER = "anthropic";

export const INITIAL_LLM_DEFAULTS: LlmDefaults = {
  provider: BACKEND_DEFAULT_PROVIDER,
  model: getProviderConfig(BACKEND_DEFAULT_PROVIDER)?.defaultModel ?? "",
  apiKey: "",
};

/** The provider/model/key that will actually be sent for a `new` advisor. */
export function effectiveLlm(member: MemberSlot, defaults: LlmDefaults): LlmDefaults {
  return {
    provider: member.provider || defaults.provider,
    model: member.model || defaults.model,
    apiKey: member.apiKey || defaults.apiKey,
  };
}

/**
 * Whether the backend will demand an API key for this provider. Unknown or
 * blank providers count as needing one — blank resolves to anthropic on the
 * server, and an unlisted provider is far more likely a cloud one.
 */
export function providerNeedsKey(provider: string): boolean {
  if (!provider.trim()) return true;
  return getProviderConfig(provider)?.needsKey ?? true;
}

/**
 * Display name for a provider id. A blank id is what the backend resolves to
 * anthropic, so it is labelled as such rather than as an empty string.
 */
export function providerLabel(provider: string): string {
  const id = provider || BACKEND_DEFAULT_PROVIDER;
  return LLM_PROVIDERS.find((p) => p.id === id)?.name ?? id;
}

export type MemberIssue = "name" | "agent" | "prompt" | "apiKey";

/**
 * The first required field a member slot is still missing, or null when it is
 * complete. Ordered the way the backend validates, so fixing issues top-down
 * never surfaces one the server would have raised earlier.
 */
export function memberIssue(member: MemberSlot, defaults: LlmDefaults): MemberIssue | null {
  if (!member.displayName.trim()) return "name";
  if (member.mode === "existing") return member.agentId.trim() ? null : "agent";
  // Already provisioned by an earlier, partially failed attempt — nothing to
  // validate; it will be reused as-is.
  if (member.agentId) return null;
  if (!member.systemPrompt.trim()) return "prompt";
  const llm = effectiveLlm(member, defaults);
  if (providerNeedsKey(llm.provider) && !llm.apiKey.trim()) return "apiKey";
  return null;
}

/**
 * A usable first draft of a system prompt from what the wizard already knows
 * about the advisor. Templates apply it to every role they seed, so picking
 * "Advisory Board" no longer means writing five prompts before anything can be
 * created; a custom advisor can insert it with one click and edit from there.
 */
export function starterPrompt(displayName: string, role: string, t: TFunction): string {
  const name = displayName.trim();
  const roleText = role.trim();
  // Four shapes so a blank name or role never leaves a dangling comma
  // ("You are , the Finance voice…").
  if (name && roleText) {
    return t(
      "Workforce.wizard.starterPrompt",
      "You are {{name}}, the {{role}} voice on this team. Speak from that perspective: give clear, well-reasoned, actionable input, name the risks and trade-offs you see, and keep your contribution focused on what you know best.",
      { name, role: roleText },
    );
  }
  if (name) {
    return t(
      "Workforce.wizard.starterPromptNoRole",
      "You are {{name}}, a member of this team. Give clear, well-reasoned, actionable input, name the risks and trade-offs you see, and keep your contribution focused.",
      { name },
    );
  }
  if (roleText) {
    return t(
      "Workforce.wizard.starterPromptRoleOnly",
      "You are the {{role}} voice on this team. Speak from that perspective: give clear, well-reasoned, actionable input, name the risks and trade-offs you see, and keep your contribution focused on what you know best.",
      { role: roleText },
    );
  }
  return t(
    "Workforce.wizard.starterPromptBare",
    "You are a member of this team. Give clear, well-reasoned, actionable input, name the risks and trade-offs you see, and keep your contribution focused.",
  );
}
