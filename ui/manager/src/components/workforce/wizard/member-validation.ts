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

/**
 * The provider/model/key that will actually be sent for a `new` advisor.
 *
 * A key is dropped when the effective provider takes none. The shared defaults
 * are inherited by every advisor, so an advisor switched to Ollama would
 * otherwise still carry the team's Anthropic key into its setup request —
 * where the backend vaults it and writes a reference into an LLM config that
 * has no use for it. The UI already hides the field in that case; this makes
 * the request match what the UI shows.
 */
export function effectiveLlm(member: MemberSlot, defaults: LlmDefaults): LlmDefaults {
  const provider = member.provider || defaults.provider;
  return {
    provider,
    model: member.model || defaults.model,
    apiKey: providerNeedsKey(provider) ? member.apiKey || defaults.apiKey : "",
  };
}

/**
 * Providers `AgentSetupService.isLocalLlmProvider` accepts without an API key:
 * ollama/jlama run locally, bedrock uses the AWS credential chain, oracle-genai
 * reads `~/.oci/config`.
 *
 * Deliberately NOT `LLM_PROVIDERS[].needsKey`. That flag decides whether the UI
 * *offers* a key field and disagrees with the backend on `gemini-vertex`, which
 * it marks keyless while `isLocalLlmProvider` does not — so trusting it would
 * let the wizard wave through the one request the server is certain to reject,
 * which is exactly the late failure this whole screen exists to prevent.
 */
const BACKEND_KEYLESS_PROVIDERS = new Set([
  "ollama",
  "jlama",
  "bedrock",
  "oracle-genai",
]);

/**
 * Whether the backend will demand an API key for this provider. Unknown and
 * blank providers count as needing one — blank resolves to anthropic on the
 * server, and the backend's own check is an allow-list, so anything it does not
 * recognise needs a key there too.
 */
export function providerNeedsKey(provider: string): boolean {
  const id = provider.trim().toLowerCase() || BACKEND_DEFAULT_PROVIDER;
  return !BACKEND_KEYLESS_PROVIDERS.has(id);
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
  // validate; it will be reused as is.
  if (member.createdAgentId) return null;
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
