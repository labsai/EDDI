import revisionFile from "./operator-revision.json";
import {
  buildToolApprovals,
  endpointsForScope,
  type OperatorScope,
} from "./tool-scopes";
import {
  defaultOperatorPromptBody,
  safetyPreambleForScope,
} from "./system-prompt";
import { toVaultRef } from "./vault-ref";
import { getProviderConfig } from "@/lib/api/agent-setup";
import { isBaseUrlRequired, isProvisionableBySetup } from "@/lib/model-suggestions";
import type { OperatorConfig } from "@/lib/api/operator";

/**
 * The operator's provisioning revision — the answer to "is the operator running
 * on this deployment the one this Manager would build today?"
 *
 * Everything the Manager bakes into the operator agent at activation (the
 * safety preamble, the default instructions, the endpoint allow-list, the
 * approval gate) is a SNAPSHOT: it is written onto the agent and never changes
 * again by itself. A Manager release that teaches the operator something new
 * therefore reached nobody who had already activated one — the knowledge-base
 * section added on 2026-09-17 was invisible to every operator activated before
 * it, and so was every model added to the catalogue since.
 *
 * The number lives in `operator-revision.json` so that BOTH halves of EDDI can
 * read it: this module, and the backend's `OperatorRevisionCheck`, which gets the
 * file on its classpath from the Maven build and logs a startup warning when the
 * deployed operator is older. One file, no second copy to forget.
 *
 * The fingerprint is what keeps the number honest. `operator-revision.test.ts`
 * hashes {@link operatorProvisioningSource} and fails when it no longer matches
 * the stored fingerprint — so changing the prompt, the allow-list or the gate
 * without bumping the revision fails the build instead of silently shipping a
 * change no existing operator will ever receive.
 */
export const OPERATOR_REVISION: number = revisionFile.revision;

/** Stored fingerprint of the revision above — see `operator-revision.test.ts`. */
export const OPERATOR_REVISION_FINGERPRINT: string = revisionFile.fingerprint;

const SCOPES: readonly OperatorScope[] = ["read_only", "read_write"];

/**
 * The canonical text the revision fingerprint is computed over: everything
 * provisioning derives from Manager code rather than from the admin's choices.
 *
 * Deliberately excluded: the model, provider, credential, environment, base URL
 * and auth mode (admin choices, carried across an upgrade unchanged) and the
 * tool-iteration budget (a backend-ceiling constant, not operator knowledge).
 */
export function operatorProvisioningSource(): string {
  return JSON.stringify({
    gate: buildToolApprovals(),
    scopes: SCOPES.map((scope) => ({
      scope,
      endpoints: endpointsForScope(scope),
      preamble: safetyPreambleForScope(scope),
      defaultBody: defaultOperatorPromptBody(scope),
    })),
  });
}

/**
 * What an upgrade would do to the operator's instructions (the editable body).
 *
 * - `default` — the admin never edited them; the upgrade installs the new
 *   default. Nothing of theirs is lost.
 * - `customized` — they did; the upgrade keeps their text unless they choose the
 *   new default.
 * - `unknown` — a config written before this was tracked, whose text differs
 *   from today's default. It is either an older default or the admin's own
 *   edit, and nothing stored can tell the two apart, so the admin chooses.
 */
export type InstructionsState = "default" | "customized" | "unknown";

/** Why an upgrade cannot run in one click and needs the full form instead. */
export type UpgradeBlocker = "provider" | "credential" | "llmBaseUrl";

export interface OperatorUpgradeAssessment {
  /** True when the deployed operator predates this Manager's revision. */
  needed: boolean;
  /** 0 for a config written before revisions were recorded. */
  provisionedRevision: number;
  currentRevision: number;
  /** Tools the upgrade adds. `null` when the old grant was not recorded. */
  addedEndpoints: string[] | null;
  /** Tools the upgrade removes. `null` when the old grant was not recorded. */
  removedEndpoints: string[] | null;
  instructions: InstructionsState;
  /** Set when the one-click upgrade cannot reproduce the current settings. */
  blocker: UpgradeBlocker | null;
}

/** Whether a stored config is running an operator that could be upgraded at all. */
function isLiveOperator(config: OperatorConfig | null | undefined): config is OperatorConfig {
  return Boolean(config?.enabled && config.agentId);
}

export function instructionsState(config: OperatorConfig): InstructionsState {
  if (config.promptBodyIsDefault === true) return "default";
  if (config.promptBodyIsDefault === false) return "customized";
  // Legacy config: equality with today's default is the only evidence there is.
  return config.promptBody === defaultOperatorPromptBody(config.scope) ? "default" : "unknown";
}

/**
 * Why the stored config is not enough to rebuild this operator without asking.
 *
 * The form holds two things an older config may not have: a plaintext model
 * key (never stored, by design — only a vault key NAME is) and a local model
 * server's base URL (`llmBaseUrl`, stored since named providers arrived). An
 * operator activated with either missing needs the form once.
 */
export function upgradeBlocker(config: OperatorConfig): UpgradeBlocker | null {
  if (!isProvisionableBySetup(config.provider)) return "provider";
  const needsKey = getProviderConfig(config.provider)?.needsKey ?? true;
  if (needsKey && !config.credentialKey) return "credential";
  if (isBaseUrlRequired(config.provider) && !config.llmBaseUrl?.trim()) return "llmBaseUrl";
  return null;
}

/**
 * Compare a stored operator config with what this Manager would provision.
 *
 * Returns `null` when there is no live operator to assess — an inactive or
 * paused one is upgraded by activating it again, which always provisions the
 * current revision.
 */
export function assessOperatorUpgrade(
  config: OperatorConfig | null | undefined,
): OperatorUpgradeAssessment | null {
  if (!isLiveOperator(config)) return null;
  const provisionedRevision = config.provisionedRevision ?? 0;
  const current = endpointsForScope(config.scope);
  const previous = config.provisionedEndpoints ?? null;
  return {
    needed: provisionedRevision < OPERATOR_REVISION,
    provisionedRevision,
    currentRevision: OPERATOR_REVISION,
    addedEndpoints: previous ? current.filter((e) => !previous.includes(e)) : null,
    removedEndpoints: previous ? previous.filter((e) => !current.includes(e)) : null,
    instructions: instructionsState(config),
    blocker: upgradeBlocker(config),
  };
}

/** What the admin chose to do with the instructions during an upgrade. */
export type InstructionsChoice = "use-new-default" | "keep-current";

export interface UpgradeRequest {
  config: OperatorConfig;
  apiKey: string;
  baseUrl?: string;
}

/**
 * The activation request that upgrades this operator in one click: every admin
 * choice carried over, only the Manager-derived parts rebuilt.
 *
 * An upgrade is a REPLACEMENT, exactly like Reconfigure — `setup-api` only ever
 * creates, so the new operator gets a new agent id, passes every activation
 * check (spec, gate read-back, gate dry-run, background probes), and only then
 * retires the old one. That is deliberate: it reuses the one path that already
 * proves a new operator safe, rather than a second path that edits a live one.
 *
 * `null` when {@link upgradeBlocker} says the stored config cannot reproduce the
 * settings; the caller then opens the full form.
 */
export function buildUpgradeRequest(
  config: OperatorConfig,
  choice: InstructionsChoice,
): UpgradeRequest | null {
  if (upgradeBlocker(config)) return null;
  const promptBody =
    choice === "use-new-default" ? defaultOperatorPromptBody(config.scope) : config.promptBody;
  return {
    config: { ...config, promptBody },
    apiKey: config.credentialKey ? toVaultRef(config.credentialKey) : "",
    baseUrl: config.llmBaseUrl?.trim() || undefined,
  };
}

let loggedOutdated = false;

/**
 * One console line per page load when the operator is out of date — for the
 * admin who reads the browser console before any banner. Mirrors the backend's
 * startup warning. Exported for tests, which reset it.
 */
export function logOutdatedOnce(assessment: OperatorUpgradeAssessment | null): void {
  if (!assessment?.needed || loggedOutdated) return;
  loggedOutdated = true;
  console.warn(
    `[operator] The Platform Operator was provisioned at revision ${assessment.provisionedRevision}; ` +
      `this Manager ships revision ${assessment.currentRevision}. Its instructions and tools are out of date — ` +
      "open /manage/operator and choose Upgrade.",
  );
}

/** Test hook: re-arm {@link logOutdatedOnce}. */
export function resetOutdatedLogForTests(): void {
  loggedOutdated = false;
}
